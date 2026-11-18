package com.donghua.tv.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps a rolling window of the episode on disk, a little ahead of playback.
 *
 * This is what fixes the "every so often it starts loading again" problem.
 * ExoPlayer fetches HLS segments strictly one after another, and this CDN
 * serves them slowly when fetched serially (measured: 12.4 s for a 10 s
 * segment), so the buffer can never grow and every dip freezes the picture.
 * Fetching the same segments a few at a time got them down to ~300 ms each.
 *
 * The window is deliberately bounded rather than "download everything": one
 * episode here is around 200 MB and the TV only has a few hundred MB free, so
 * prefetching the whole file would fill the device. Instead this keeps a window
 * of video ready ahead of the playhead and extends it as playback advances, so
 * the player never has to wait on the network.
 *
 * The bound is expressed in *bytes*, not seconds, and that matters. The window
 * used to be a flat 240 s, which is fine for a 3 Mbps stream and wildly too much
 * for a 6 Mbps one: 240 s at 6 Mbps is ~180 MB against a ~96 MB cache, so the
 * window could never fit. The LRU then evicted segments the player had not
 * reached yet, the next pump saw them missing and fetched them again, and the
 * cycle repeated — spending the box's bandwidth re-downloading video instead of
 * playing it, which drains the very buffer this class exists to protect. The
 * look-ahead is now derived from the throughput actually measured while
 * downloading, so it shrinks to fit whatever the cache can really hold.
 */
@OptIn(UnstableApi::class)
internal class StreamPrefetcher(
    private val httpClient: OkHttpClient,
    private val cache: SimpleCache,
    private val referer: String,
    /** How many bytes of look-ahead the cache can hold without evicting live data. */
    private val budgetBytes: Long,
) {

    private companion object {
        const val TAG = "DonghuaPrefetch"

        /** Parallel downloads. Enough to outrun the CDN without hammering it. */
        const val PARALLEL_DOWNLOADS = 3

        /** Upper bound on the look-ahead, in seconds. */
        const val MAX_LOOK_AHEAD_SECONDS = 240.0

        /**
         * Lower bound, in seconds.
         *
         * Whatever the throughput says, the prefetcher must stay at least this far
         * ahead of the player, or it stops being a prefetch and becomes a second
         * source of stalls. Well past the player's own 90 s buffer ceiling.
         */
        const val MIN_LOOK_AHEAD_SECONDS = 120.0

        /** Commit each segment to disk as it completes. */
        const val SEGMENT_FRAGMENT_BYTES = 2L * 1024L * 1024L

        /** Safety cap on segments in one playlist. */
        const val MAX_SEGMENTS = 500
    }

    private data class Segment(val uri: String, val startSec: Double, val endSec: Double)

    private val cancelled = AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)
    private val downloaded = AtomicInteger(0)
    private val failed = AtomicInteger(0)
    private val resolved = AtomicBoolean(false)
    private val listLock = Any()

    /** Running total of segment bytes, used to size the window. */
    private val bytesWritten = AtomicLong(0)

    /** Total declared duration of the playlist, used to size the window. */
    private var totalDurationSec = 0.0

    private var segments: List<Segment> = emptyList()
    private val queued = HashSet<String>()
    private val done = HashSet<String>()
    private var lastStatusLog = 0L

    private val workers = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS) { r ->
        Thread(r, "dh-prefetch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private var job: Job? = null

    /** Stops everything. Safe to call more than once. */
    fun cancel() {
        if (cancelled.getAndSet(true)) return
        workers.shutdownNow()
        job?.cancel()
    }

    /**
     * Resolves the segment list once. [onReady] fires when the playlist has been
     * parsed, after which [pump] can be called repeatedly.
     */
    fun start(scope: CoroutineScope, streamUrl: String, onReady: () -> Unit = {}) {
        job = scope.launch(Dispatchers.IO) {
            try {
                val parsed = resolveSegments(streamUrl)
                if (parsed.isEmpty()) {
                    Log.w(TAG, "No segments found, prefetch inactive")
                    return@launch
                }
                val totalSec = parsed.last().endSec
                synchronized(listLock) {
                    segments = parsed
                    totalDurationSec = totalSec
                }
                resolved.set(true)
                Log.i(
                    TAG,
                    "Prefetch ready: ${parsed.size} segments, ${totalSec.toInt()}s total, " +
                        "budget=${budgetBytes / (1024 * 1024)}MB"
                )
                withContext(Dispatchers.Main) { onReady() }
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch not available: ${e.message}")
            }
        }
    }

    /**
     * How far ahead to keep the cache filled, in seconds of video.
     *
     * Derived from the throughput this stream has actually delivered rather than
     * assumed, so the window always fits inside [budgetBytes] and the LRU never
     * has to evict something the player has not watched yet.
     *
     * Until a first segment has landed there is nothing to measure, so the
     * maximum is assumed; a window that is briefly too large for one pump costs
     * nothing, and every later pump corrects it.
     */
    private fun lookAheadSeconds(): Double {
        val doneCount = downloaded.get()
        val written = bytesWritten.get()
        val duration = totalDurationSec
        if (doneCount <= 0 || written <= 0L || duration <= 0.0) return MAX_LOOK_AHEAD_SECONDS

        val bytesPerSecond = written.toDouble() / duration
        if (bytesPerSecond <= 0.0) return MAX_LOOK_AHEAD_SECONDS

        val affordable = budgetBytes / bytesPerSecond
        return affordable.coerceIn(MIN_LOOK_AHEAD_SECONDS, MAX_LOOK_AHEAD_SECONDS)
    }

    /**
     * Makes sure the segments covering [positionMs] plus the look-ahead window
     * are cached. Cheap to call often; it only submits work that is missing.
     */
    fun pump(positionMs: Long) {
        if (cancelled.get() || !resolved.get()) return
        val list = synchronized(listLock) { segments }
        if (list.isEmpty()) return

        val positionSec = positionMs / 1000.0
        val lookAhead = lookAheadSeconds()
        val targetSec = positionSec + lookAhead

        val snapshot: List<Segment>
        synchronized(queued) {
            snapshot = list.filter { seg ->
                seg.endSec > positionSec && seg.startSec < targetSec
            }
        }

        var submitted = 0
        for (seg in snapshot) {
            if (cancelled.get()) return
            val shouldFetch = synchronized(queued) {
                if (done.contains(seg.uri) || queued.contains(seg.uri)) {
                    false
                } else {
                    queued.add(seg.uri)
                    true
                }
            }
            if (!shouldFetch) continue
            if (isCached(seg.uri)) {
                markDone(seg.uri)
                continue
            }
            submit(seg.uri)
            submitted++
        }

        // "Nothing left to submit" does NOT mean "the window is on disk". A
        // segment can also be sitting in [queued] with its download wedged, and
        // that is precisely the failure this line used to report as success: it
        // printed "window ready" every thirty seconds while the prefetcher had
        // been dead for minutes. So the claim is checked against the cache
        // instead of inferred from the absence of work.
        if (submitted == 0) {
            val now = System.currentTimeMillis()
            if (now - lastStatusLog > 30_000) {
                lastStatusLog = now
                val missing = snapshot.count { !isCached(it.uri) }
                if (missing == 0) {
                    Log.i(
                        TAG,
                        "window ready: ${snapshot.size} segments ahead of ${positionSec.toInt()}s " +
                            "(fetched=${downloaded.get()} failed=${failed.get()})"
                    )
                } else {
                    Log.w(
                        TAG,
                        "window INCOMPLETE: $missing of ${snapshot.size} segments ahead of " +
                            "${positionSec.toInt()}s are not on disk " +
                            "(fetched=${downloaded.get()} failed=${failed.get()} " +
                            "inFlight=${inFlight.get()}) - the player will fetch these itself"
                    )
                }
            }
        } else {
            Log.i(
                TAG,
                "queued $submitted segments (ahead of ${positionSec.toInt()}s, inFlight=${inFlight.get()})"
            )
        }
    }

    private fun markDone(uri: String) {
        synchronized(queued) {
            done.add(uri)
            queued.remove(uri)
        }
    }

    private fun submit(uri: String) {
        inFlight.incrementAndGet()
        workers.execute {
            try {
                val written = download(uri)
                downloaded.incrementAndGet()
                bytesWritten.addAndGet(written)
                markDone(uri)
            } catch (e: Exception) {
                if (!cancelled.get()) {
                    failed.incrementAndGet()
                    Log.w(TAG, "segment failed ${uri.substringAfterLast('/')}: ${e.message}")
                }
                // Let it be retried on a later pump.
                synchronized(queued) { queued.remove(uri) }
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /** Writes one segment into the cache and returns how many bytes landed. */
    private fun download(uri: String): Long {
        val spec = DataSpec.Builder().setUri(uri).setKey(uri).build()
        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(PlaybackEngine.upstreamFactory(httpClient, referer))
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(cache)
                    .setFragmentSize(SEGMENT_FRAGMENT_BYTES)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        // Media3 1.5's CacheWriter.cache() returns void, so the size written is
        // read back off the cache rather than taken from the call.
        CacheWriter(dataSource, spec, ByteArray(64 * 1024), null).cache()
        return cachedLength(uri)
    }

    /** How many bytes of [uri] are on disk, or 0 if it is not there at all. */
    private fun cachedLength(uri: String): Long =
        runCatching {
            cache.getCachedLength(uri, 0, androidx.media3.common.C.LENGTH_UNSET.toLong())
        }.getOrDefault(0L)

    /**
     * Whether this segment is already on disk.
     *
     * This asked [androidx.media3.datasource.cache.Cache.isCached] for years,
     * and it answered `false` for every segment even immediately after writing
     * it. Verified on the device by logging the write and the query side by
     * side:
     *
     *     wrote=4001016B ... isCached=false
     *     wrote=7057144B ... isCached=false
     *
     * The bytes were there - `getCachedLength`, on the very same key, returned
     * them. The difference is that segments are written through a 2 MB fragment
     * sink, so a 5-7 MB segment lands as several cache spans, and `isCached`
     * does not aggregate them the way `getCachedLength` does.
     *
     * The cost was not cosmetic. The prefetcher read this as "not downloaded
     * yet", queued the segment again on the very next pump, and so spent its
     * entire bandwidth re-fetching files it had already written. It never got
     * ahead of the playhead and the window never filled - which is exactly the
     * stall this class exists to prevent, and the source gives about one segment
     * per ten seconds, so there was never any spare to waste.
     *
     * [cachedLength] is the call the evidence supports, so that is the call made.
     */
    private fun isCached(uri: String): Boolean = cachedLength(uri) > 0L

    /** Follows a master playlist to its media playlist, then reads the segments. */
    private fun resolveSegments(streamUrl: String): List<Segment> {
        val master = fetchText(streamUrl)
        val playlistUrl: String
        val body: String
        if (master.contains("#EXT-X-STREAM-INF")) {
            val variant = master.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return emptyList()
            playlistUrl = URI(streamUrl).resolve(variant).toString()
            body = fetchText(playlistUrl)
        } else {
            playlistUrl = streamUrl
            body = master
        }
        return parseSegments(body, playlistUrl)
    }

    /**
     * Reads segment URIs with their start/end times, so a playback position can
     * be mapped onto segment indices.
     */
    private fun parseSegments(playlist: String, playlistUrl: String): List<Segment> {
        val out = ArrayList<Segment>()
        var clock = 0.0
        var pendingDur = -1.0
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF") -> {
                    pendingDur = line.substringAfter(':', "").substringBefore(',').toDoubleOrNull() ?: -1.0
                }
                line.startsWith("#") -> Unit
                else -> if (pendingDur >= 0) {
                    val start = clock
                    clock += pendingDur
                    pendingDur = -1.0
                    out.add(Segment(resolve(playlistUrl, line), start, clock))
                }
            }
            if (out.size >= MAX_SEGMENTS) break
        }
        return out
    }

    private fun resolve(playlistUrl: String, url: String): String =
        if (url.startsWith("http")) url else URI(playlistUrl).resolve(url).toString()

    private fun fetchText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", PlayerActivity.DEFAULT_USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} for ${url.take(80)}")
            }
            return resp.body?.string().orEmpty()
        }
    }
}
