package com.donghua.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.donghua.tv.data.Api
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shared network + disk-cache layer for the video player.
 *
 * The TV boxes this app targets are low powered (2 GB RAM, quad core), so the
 * pipeline is tuned to be cheap on RAM while being very resilient on the network:
 *
 *  - OkHttp instead of Media3's HttpURLConnection based source. HLS pulls many
 *    small segment requests; OkHttp reuses one connection (HTTP/2 multiplexing)
 *    instead of reconnecting constantly, which removes most of the micro-stalls.
 *  - A bounded SimpleCache so already buffered segments are served from disk.
 *    A dropped WiFi packet no longer stops the picture, and re-preparing an
 *    episode is instant.
 *  - Generous but size-capped buffering (see PlayerActivity) so a short burst of
 *    jitter is absorbed instead of surfacing as a freeze.
 */
@OptIn(UnstableApi::class)
object PlaybackEngine {

    private const val TAG = "DonghuaPlayback"

    /** Referer used when the caller did not provide one. */
    val DEFAULT_REFERER: String = Api.BASE + "/"

    /** Wire-level diagnostics, used by the player to explain stalls. */
    internal val streamLog: StreamLogger by lazy { StreamLogger() }

    /**
     * Long lived client. One instance for the whole process so the connection
     * pool and HTTP/2 sessions survive episode changes. Shared with the
     * background prefetcher.
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Generous, because these CDNs are slow rather than flaky. A 10 s
            // segment was measured arriving in 12.4 s when fetched serially, which
            // sits uncomfortably close to the old 18 s read timeout: a perfectly
            // normal segment could trip it and be treated as a dead connection.
            // Timing out a request that is still arriving just wastes the bytes
            // already spent and forces a re-fetch, which is worse than waiting.
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // streaming: no whole call cap
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 12
                    maxRequestsPerHost = 10
                }
            )
            .addInterceptor(streamLog)
            .build()
    }

    /**
 * Hard cap on how long a single prefetched segment may take, in total.
 *
 * Deliberately separate from the read timeout, and this is the fix for a stall
 * that proved impossible to recover from.
 *
 * A read timeout only fires when a connection goes *quiet*. A CDN dribbling out
 * a segment a few bytes at a time never goes quiet, so the read timeout never
 * fires either, and the download simply never finishes. The prefetcher has
 * three threads; once all three are stuck on trickling connections the whole
 * prefetcher is dead, and because nothing ever threw, nothing was ever logged -
 * it just silently stopped advancing.
 *
 * Observed exactly that: an episode fetched its first 73 segments normally and
 * then stopped dead, with `failed=0` throughout. The prefetch window drained
 * from 25 segments to 12, the player had to fetch everything itself over a
 * connection capped at real-time, and one stall turned into twenty-two.
 *
 * A whole-call timeout bounds this regardless of how the bytes are trickling.
 * Sixty seconds is generous: a normal segment here takes about eleven.
 */
private const val PREFETCH_CALL_TIMEOUT_SECONDS = 60L

/**
     * Client used only by the background prefetcher.
     *
     * Separate from [httpClient] on purpose. The player streams a long segment
     * and is entitled to take as long as it takes; the prefetcher has three
     * threads and must be able to abandon a bad segment and move on. Sharing one
     * client would mean imposing the prefetcher's deadline on playback.
     */
val prefetchClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .callTimeout(PREFETCH_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Segment cache size.
     *
     * This has to be larger than one play window or the prefetcher and the
     * player end up evicting each other's data. Measured on the TV box: a segment
     * is ~5.3 MB for 10 s of 720p, so the old 240 s look-ahead alone wanted
     * ~128 MB against a 96 MB cache. The window could never fit, the LRU threw
     * away segments the player had not watched yet, and the stall that produced
     * was not random jitter but the window evicting the very segment about to be
     * decoded.
     *
     * 160 MB holds the whole working set - prefetch window plus the ~40 MB the
     * player keeps in memory - with room to spare. It is not raised further
     * because this box has only ~450 MB free on /data and was already 90% full.
     *
     * Still a cache in the app-private cache dir, so the system may reclaim it
     * under pressure without breaking anything.
     */
    private const val MAX_CACHE_BYTES = 160L * 1024L * 1024L

    /**
     * How much of the cache the prefetcher is allowed to aim at.
     *
     * Deliberately well under the whole cache, so the segments the player is
     * actively reading always have somewhere to live and are never evicted by
     * the prefetcher racing ahead of it. At the measured 0.53 MB/s this works
     * out to roughly three minutes of look-ahead.
     */
    val PREFETCH_BUDGET_BYTES: Long = 96L * 1024L * 1024L

    /**
     * Bytes buffered before a segment is committed to the cache. Segments are
     * only a few MB, so a small value means each one lands on disk as it
     * finishes and a later seek or restart reads it locally.
     */
    private const val SEGMENT_FRAGMENT_BYTES = 2L * 1024L * 1024L

    private var cache: SimpleCache? = null

    @Volatile
    private var cacheFailed = false

    private fun cache(context: Context): SimpleCache? {
        if (cacheFailed) return null
        cache?.let { return it }
        return synchronized(this) {
            cache?.let { return it }
            if (cacheFailed) return null
            try {
                val dir = File(context.cacheDir, "stream_segments")
                val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
                // Bump the cache if a previous session used a different version.
                val existing = SimpleCache(dir, evictor, StandaloneDatabaseProvider(context))
                cache = existing
                existing
            } catch (e: Exception) {
                // A broken cache must never break playback.
                cacheFailed = true
                android.util.Log.w(TAG, "cache unavailable, continuing without it: ${e.message}")
                null
            }
        }
    }

    /**
     * A plain HTTP factory for media requests, shared by the player and the
     * prefetcher so both send identical headers.
     */
    fun upstreamFactory(client: OkHttpClient, referer: String): DataSource.Factory {
        val headers = mutableMapOf<String, String>()
        val ref = referer.ifBlank { DEFAULT_REFERER }
        headers["Referer"] = ref
        headers["User-Agent"] = PlayerActivity.DEFAULT_USER_AGENT
        headers["Accept"] = "*/*"
        val origin = ref.removeSuffix("/")
        if (origin.startsWith("http")) headers["Origin"] = origin

        return OkHttpDataSource.Factory(client)
            .setDefaultRequestProperties(headers)
            .setUserAgent(PlayerActivity.DEFAULT_USER_AGENT)
    }

    /** The segment cache, or null if it could not be opened. */
    fun cacheFor(context: Context): SimpleCache? = cache(context)

    /**
     * Builds the data source chain used for every stream:
     * cache -> OkHttp.
     *
     * [referer] is applied to the HTTP requests. Some of the embed hosts require
     * the Referer/Origin of the site that embedded them, so this is per server.
     */
    fun dataSourceFactory(context: Context, referer: String): DataSource.Factory {
        val appContext = context.applicationContext

        val okHttpFactory = upstreamFactory(httpClient, referer)

        val simpleCache = cache(appContext)
        if (simpleCache == null) {
            // No cache available: still wrap so behaviour stays consistent.
            return DefaultDataSource.Factory(appContext, okHttpFactory)
        }

        val cacheFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(okHttpFactory)
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(simpleCache)
                    // Commits each segment to disk as it completes. The previous
                    // value equalled the whole cache size, so nothing was ever
                    // committed and every re-prepare re-downloaded the file.
                    .setFragmentSize(SEGMENT_FRAGMENT_BYTES)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DefaultDataSource.Factory(appContext, cacheFactory)
    }
}
