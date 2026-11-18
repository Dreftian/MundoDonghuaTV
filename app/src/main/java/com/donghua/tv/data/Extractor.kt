package com.donghua.tv.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * One video server offered for an episode.
 *
 * [directHlsUrl] is a playlist ExoPlayer can open on its own. [embedUrl] is a
 * web page that only a browser can drive, so it is a second best option: it needs
 * a WebView, is far heavier on a TV box, and many of these hosts are simply dead.
 */
data class ServerItem(
    val id: String,
    val name: String,
    val directHlsUrl: String? = null,
    val embedUrl: String? = null,
    val referer: String = Api.BASE + "/",
    /**
     * Set when this server was probed and found to be dead: the host is down, or
     * the file was deleted upstream. The player greys it out and moves on instead
     * of offering a button that can only fail.
     */
    val unavailableReason: String? = null,
) {
    /** True when the stream can be handed straight to ExoPlayer. */
    val isDirect: Boolean get() = !directHlsUrl.isNullOrBlank()

    /** True when there is at least one way to try to play this. */
    val isPlayable: Boolean
        get() = unavailableReason == null && (isDirect || !embedUrl.isNullOrBlank())
}

object Extractor {
    private const val TAG = "DonghuaExtractor"

    /**
     * Shared with [Api]'s cookie jar behaviour but kept local so a probe never
     * disturbs the main client. The site hands out a PHPSESSID on the episode
     * page and the provider endpoints answer differently without it, so the
     * session has to survive across the page fetch and the follow-up call.
     */
    private val cookieJar = InMemoryCookieJar()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .cookieJar(cookieJar)
        .build()

    /**
     * Separate client for the parallel server probes: a short connect timeout so
     * an offline host is abandoned quickly, and its own dispatcher so a burst of
     * probes cannot starve the pool the episode page fetch uses.
     */
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        // Retrying a host that has already timed out only delays the answer.
        .retryOnConnectionFailure(false)
        .cookieJar(cookieJar)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 12 })
        .build()

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Total time we are willing to spend probing every server for a direct
     * stream. Every server is probed, not just the embeds: the two "direct"
     * servers the site advertises (Asura and Tamamo) point at hosts that are
     * routinely offline, and when they answer they hand back a player page
     * rather than a playlist, so they have to be resolved the same way.
     *
     * Sized from measurement rather than guessed. Probes run in parallel, so the
     * wall-clock cost is that of the slowest one, and the two dead hosts sit
     * right at the 10 s connect timeout. When the budget sat below that, the
     * whole probe was abandoned and nothing at all resolved, including the one
     * server that does work. The margin keeps a genuinely slow host from eating
     * the budget on its own.
     */
    private const val EMBED_PROBE_BUDGET_MS = 20_000L

    /**
     * Per-probe ceiling, applied inside the shared client so a single dead host
     * cannot hold up the ones that are healthy.
     */
    private const val PROBE_TIMEOUT_SECONDS = 12L

    /**
     * Marks a server the page itself already told us is dead.
     *
     * Both of these are served as ordinary 200 responses, so without this check
     * they were offered to the viewer as if they worked: `embedwish` says the
     * file "expired or has been deleted", and the short `bysekoze` shell is a
     * page that only ever renders the video inside a browser.
     */
    private val EXPIRED_MARKERS = listOf(
        "no longer available",
        "expired or has been deleted",
        "video not found",
        "this video is unavailable",
    )

    /**
     * Wording that means the host is refusing to serve this video at all.
     *
     * Distinct from [EXPIRED_MARKERS]: the file may well exist, the host simply
     * will not hand it over from this client. `bysekoze` answers with
     * "embedding from bysekoze.com is not allowed for this video", which arrives
     * as an ordinary HTTP 200, so without this the server looked like a live
     * candidate and the player then sat on it for the whole embed timeout.
     */
    private val REFUSAL_MARKERS = listOf(
        "is not allowed for this video",
        "not allowed for this video",
        "embedding is not allowed",
        "not permitted for this video",
        "referrer not allowed",
    )

    /**
     * Hosts that answer "no" through an endpoint of their own instead of saying
     * so in the page.
     *
     * Keyed by our server id, and only ever consulted for a host listed here, so
     * this stays a targeted extra check rather than a request per candidate.
     */
    private val EMBED_API_URL = mapOf(
        "fmoon" to "https://bysekoze.com/api/videos/{id}/embed/details",
    )

    /** The Referer those endpoints expect, which is the embed page, not the site. */
    private val EMBED_PAGE_URL = mapOf(
        "fmoon" to "https://bysekoze.com/e/{id}",
    )

    /**
     * The video id, which for every host above is the trailing segment of the
     * embed URL we already hold: `bysekoze.com/e/qq64au5z737b` -> `qq64au5z737b`.
     */
    private fun embedIdOf(candidate: ServerItem): String? =
        candidate.embedUrl?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /**
     * Fallback host for the shared player, used only when the page exposes no
     * direct player URL to derive it from.
     */
    private const val DEFAULT_PLAYER_HOST = "https://www.mdnemonicplayer.xyz/"

    // ---------------------------------------------------------------------
    // Patterns
    // ---------------------------------------------------------------------

    private val PACKED_RE = Pattern.compile(
        "eval\\(function\\(p,a,c,k,e,d\\).*?\\}\\('(.*?)',\\s*(\\d+),\\s*(\\d+),\\s*'([^']*)'\\.split\\('\\|'\\)",
        Pattern.DOTALL
    )

    /**
     * The Asura-family direct player.
     *
     * Matched on the distinctive `/nemonicplayer/redirector.php` path instead of
     * on the host name on purpose. The site has already served this same player
     * from `mdplayer.xyz` and from `mdnemonicplayer.xyz` on different episodes,
     * and pinning the host is exactly what made this server disappear: the only
     * server that plays natively was silently skipped and the viewer was left
     * with nothing but web embeds, all of which failed.
     */
    private val REDIRECTOR_RE = Pattern.compile(
        "https?://[a-zA-Z0-9.\\-]+/nemonicplayer/redirector\\.php\\?slug=[^\"'<>\\\\\\s]+"
    )

    /** The shared player used by Tamamo, once the key has been resolved. */
    private val DMPLAYER_RE = Pattern.compile(
        "https?://[a-zA-Z0-9.\\-]+/nemonicplayer/dmplayer\\.php\\?key=[^\"'<>\\\\\\s]+"
    )

    /**
     * Tamamo hands the key out through an API call rather than inline.
     *
     * The gap between the endpoint and the field name is matched with a lazy
     * wildcard rather than a "no quotes" class. In the real payload the two are
     * separated by `",data:{"`, so excluding quotes made this pattern
     * unmatchable and the server silently never appeared in the list.
     */
    private val TAMAMO_SLUG_RE = Pattern.compile(
        "api_donghua\\.php.{0,240}?\"slug\"\\s*:\\s*\"([^\"]+)\"",
        Pattern.DOTALL
    )

    /** Embeds are always injected as an iframe, with single or double quotes. */
    private val IFRAME_RE = Pattern.compile(
        "<iframe[^>]+src=['\"]([^'\"\\s>]+)['\"]",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Known embed hosts, matched directly as well as through their iframe. The
     * iframe pass is what makes a brand new provider show up; this pass keeps
     * working if the site stops rendering one inside an iframe.
     */
    private val EMBED_HOSTS: List<Pair<Pattern, String>> = listOf(
        Pattern.compile("https?://[a-zA-Z0-9.\\-]*vidhidepro\\.com/[ve]/[a-zA-Z0-9]+") to "Vidhide",
        Pattern.compile("https?://[a-zA-Z0-9.\\-]*embedwish\\.com/[ve]/[a-zA-Z0-9]+") to "Swish",
        Pattern.compile("https?://[a-zA-Z0-9.\\-]*bysekoze\\.com/e/[a-zA-Z0-9]+") to "Fm",
        Pattern.compile("https?://[a-zA-Z0-9.\\-]*voe\\.sx/e/[a-zA-Z0-9]+") to "Voe",
        Pattern.compile("https?://[a-zA-Z0-9.\\-]*vgembed\\.com/e/[a-zA-Z0-9]+") to "VGembed",
    )

    /** Prefers the servers that need no browser, then the ones that do. */
    private val PREFERRED_ORDER = listOf(
        "asura", "tamamo", "vidhide", "swish", "fmoon", "amagi", "kaga"
    )

    /**
     * Servers that actually delivered a stream recently, per series.
     *
     * The static [PREFERRED_ORDER] above is a guess about which host tends to be
     * up, and it goes stale immediately: the two servers it puts first are on
     * hosts that are frequently offline, so every chapter opened on a server that
     * was already known to be dead while a working one sat further down.
     *
     * This remembers what actually worked, keyed by series, and promotes it. It is
     * a health signal, not a hardcoded order, so an outage on one host moves that
     * host down for that title without hiding it entirely.
     */
    private val workingServers = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()

    private fun noteWorking(slug: String, serverId: String) {
        val list = workingServers.getOrPut(slug) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            list.remove(serverId)
            list.add(0, serverId)
            // Keep it short: the hosts rotate, and a long memory would keep
            // recommending one that stopped working weeks ago.
            while (list.size > 3) list.removeAt(list.size - 1)
        }
    }

    private fun rankFor(slug: String, id: String): Int {
        val remembered = workingServers[slug]?.indexOf(id) ?: -1
        if (remembered >= 0) return remembered
        val idx = PREFERRED_ORDER.indexOf(id)
        return if (idx >= 0) idx + PREFERRED_ORDER.size else PREFERRED_ORDER.size * 2
    }

    // ---------------------------------------------------------------------
    // JavaScript unpacking
    // ---------------------------------------------------------------------

    /**
     * Unpacks JavaScript packed with eval(function(p,a,c,k,e,d)...)
     */
    fun unpackJs(packedJs: String): String {
        val matcher = PACKED_RE.matcher(packedJs)
        if (!matcher.find()) return packedJs

        val p = matcher.group(1) ?: return packedJs
        // Both the radix and the keyword count come straight out of the page, and
        // neither is trustworthy. base 0 divides by zero, base 1 never shrinks
        // n so this loop spins until the StringBuilder eats the heap, and the
        // map was sized from an attacker-chosen integer. A packed block is
        // untrusted input that exists precisely to be malformed, so it is
        // bounded here rather than assumed.
        val a = matcher.group(2)?.toIntOrNull()?.takeIf { it in 2..36 } ?: 62
        val maxKeywords = 100_000
        val c = matcher.group(3)?.toIntOrNull()?.coerceIn(0, maxKeywords) ?: 0
        val rawKeywords = matcher.group(4) ?: ""
        val keywords = rawKeywords.split("|")

        fun baseEncode(number: Int, base: Int): String {
            val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
            val sb = StringBuilder()
            var n = number
            if (n == 0) return "0"
            while (n > 0) {
                val rem = n % base
                sb.append(if (rem > 35) (rem + 29).toChar() else digits[rem])
                n /= base
            }
            return sb.reverse().toString()
        }

        val dict = HashMap<String, String>()
        for (i in 0 until c) {
            val key = baseEncode(i, a)
            dict[key] = keywords.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
        }

        val wordPattern = Pattern.compile("\\b\\w+\\b")
        val wordMatcher = wordPattern.matcher(p)
        val sb = StringBuffer()
        while (wordMatcher.find()) {
            val word = wordMatcher.group()
            val replacement = dict[word] ?: word
            wordMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
        }
        wordMatcher.appendTail(sb)

        return sb.toString().replace("\\'", "'").replace("\\\"", "\"")
    }

    /**
     * Unpacks all packed scripts found within an HTML string.
     */
    fun unpackAll(html: String): List<String> {
        val list = mutableListOf<String>()
        val matcher = PACKED_RE.matcher(html)
        while (matcher.find()) {
            matcher.group(0)?.let { list.add(unpackJs(it)) }
        }
        return list
    }

    // ---------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------

    private suspend fun fetchText(url: String, referer: String = Api.BASE + "/"): String? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", DEFAULT_UA)
                    .header("Referer", referer)
                    .header("Accept", "*/*")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchText error for $url: ${e.message}")
                null
            }
        }

    /**
     * Fetches a server page to see whether it yields a playlist.
     *
     * Uses the probe client so a dead host fails in [PROBE_TIMEOUT_SECONDS]
     * rather than at the main client's much longer connect timeout, and so the
     * probes share one connection pool instead of opening a new client each.
     * Carries the episode page's session cookie, which several hosts require.
     */
    private suspend fun fetchProbe(url: String, referer: String, parent: String? = null): String? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", DEFAULT_UA)
                    .header("Referer", referer)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .apply {
                        // bysekoze's endpoint identifies the embedding page with this
                        // header rather than by the Referer alone, and answers 403
                        // without it.
                        parent?.let { header("x-embed-parent", it) }
                    }
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()
                    // A non-2xx body is still worth reading.
                    //
                    // Several of these hosts explain themselves in the body of an
                    // error: bysekoze answers 403 with
                    // "embedding from bysekoze.com is not allowed for this video".
                    // Dropping the body on any non-2xx threw that verdict away, so
                    // the server looked merely unreachable instead of refusing, and
                    // the player then waited out the full embed timeout on it.
                    if (resp.isSuccessful) body else body?.takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {
                Log.i(TAG, "probe ${hostOf(url)} unreachable: ${e.javaClass.simpleName}")
                null
            }
        }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** `https://host/path` -> `https://host/`, used for the Referer header. */
    private fun originOf(url: String): String {
        val m = Pattern.compile("^(https?://[^/]+)").matcher(url)
        return if (m.find()) "${m.group(1)}/" else Api.BASE + "/"
    }

    private fun hostOf(url: String): String =
        Pattern.compile("^https?://([^/]+)").matcher(url)
            .let { if (it.find()) it.group(1).lowercase() else "" }

    private fun firstGroup(matcher: Matcher): String? =
        if (matcher.find()) matcher.group(0) else null

    private fun firstGroup(matcher: Matcher, group: Int): String? =
        if (matcher.find()) matcher.group(group) else null

    /**
     * Maps an embed URL onto a stable server id and a display name, so the same
     * host is never listed twice and a remembered choice keeps working.
     */
    private fun classifyEmbed(url: String): Pair<String, String> {
        val host = hostOf(url).substringAfter("www.")
        val known = EMBED_HOSTS.firstOrNull { (pattern, _) ->
            pattern.matcher(url).find()
        }
        if (known != null) {
            val id = when (known.second) {
                "Vidhide" -> "vidhide"
                "Swish" -> "swish"
                "Fm" -> "fmoon"
                "Voe" -> "amagi"
                "VGembed" -> "kaga"
                else -> "x_${known.second.lowercase()}"
            }
            return id to known.second
        }
        // An unknown provider is still better than being dropped: the site can
        // rotate hosts at any time and the viewer should still get the choice.
        val label = host.ifBlank { "Reproductor" }
        return "x_${label.replace(Regex("[^a-zA-Z0-9]"), "")}" to label
    }

    /**
     * Looks for a playlist inside an embed page, in raw and in unpacked form.
     *
     * Two details are load-bearing:
     *
     *  - The URL is searched in the *unpacked* scripts too. Several of these
     *    hosts (vidhidepro among them) hide the playlist inside a packed
     *    `eval(function(p,a,c,k...))` block, so a raw scan finds nothing.
     *  - The query string must be kept. These CDN links carry their access token
     *    in `?t=...&s=...`; trimming it yields a 403 on every segment.
     */
    private fun findPlaylistIn(html: String): String? {
        val m3u8 = Pattern.compile("https?://[^\"'<>\\\\\\s)]+\\.m3u8[^\"'<>\\\\\\s)]*")
        m3u8.matcher(html).let { if (it.find()) return it.group(0) }
        for (unpacked in unpackAll(html)) {
            m3u8.matcher(unpacked).let { if (it.find()) return it.group(0) }
        }
        return null
    }

    private fun looksLikePlaylist(url: String): Boolean {
        val path = url.substringBefore("?").lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u") ||
            path.contains("master") || path.contains("playlist")
    }

    // ---------------------------------------------------------------------
    // Extraction
    // ---------------------------------------------------------------------

    /**
     * Extracts every server the episode page offers, best first.
     *
     * A direct playlist is worth far more than an embed on a TV: it plays through
     * ExoPlayer, it is smooth, and it does not depend on a heavy web page that
     * may be down. So those are resolved first and the embed pages are only
     * probed when there is nothing else to play.
     *
     * @param episodePath the `/ver/{slug}/{n}` path the page was fetched from.
     *   The provider URLs are opaque tokens with no series name in them, so this
     *   is the only place the slug can come from, and it is what the per-title
     *   server health is remembered against.
     */
    suspend fun extractServers(
        html: String,
        episodePath: String = "",
    ): List<ServerItem> = withContext(Dispatchers.IO) {
        val unpacked = unpackAll(html)
        // Unpacked scripts are where the provider URLs live, but scanning the
        // raw page too means a change in how the site packs its scripts degrades
        // into "fewer servers" instead of "no servers".
        val haystack = buildString {
            append(unpacked.joinToString("\n"))
            append('\n')
            append(html)
        }

        val found = LinkedHashMap<String, ServerItem>()

        // 1. Asura family: a redirector endpoint that is *not* a playlist.
        //
        //    This used to be published as `directHlsUrl`, which made the player
        //    hand a PHP page straight to ExoPlayer and, worse, made the code
        //    below believe a native server already existed so it skipped
        //    resolving everything else. The redirector is a jwplayer setup page
        //    that then points at the CDN, so it has to be resolved like any other
        //    server. Its host is also frequently offline (measured: connection
        //    timeout), which is precisely why it must not be trusted up front.
        val redirector = firstGroup(REDIRECTOR_RE.matcher(haystack))
        if (redirector != null) {
            Log.i(TAG, "Asura redirector found: ${redirector.take(70)}")
            found["asura"] = ServerItem(
                id = "asura",
                name = "Asura",
                embedUrl = redirector,
                referer = Api.BASE + "/",
            )
        }

        // 2. Tamamo: an API hands out a key for the shared player.
        resolveTamamo(haystack)?.let { found["tamamo"] = it }

        // 3. Every iframe on the page, classified by host. This is what keeps
        //    newly added providers visible instead of silently missing.
        val iframeMatcher = IFRAME_RE.matcher(haystack)
        while (iframeMatcher.find()) {
            val url = iframeMatcher.group(1) ?: continue
            if (!url.startsWith("http")) continue
            if (url.contains(Api.BASE)) continue
            val (id, name) = classifyEmbed(url)
            if (found[id] == null) found[id] = embedServer(id, name, url)
        }

        // 4. Safety net for the known hosts, in case one is no longer in an iframe.
        EMBED_HOSTS.forEach { (pattern, name) ->
            val url = firstGroup(pattern.matcher(haystack)) ?: return@forEach
            val (id, resolvedName) = classifyEmbed(url)
            if (found[id] == null) found[id] = embedServer(id, resolvedName.ifEmpty { name }, url)
        }

        // The slug is what health is remembered per, so the ordering can put whatever
        // actually worked for this series first instead of a stale global guess.
        // Taken from the path we were asked for, falling back to the page.
        val slug = slugOf(episodePath).ifBlank { seriesSlugOf(haystack) }
        val ordered = orderServers(found.values.toList(), slug)
        Log.i(TAG, "Servers extracted: ${ordered.size} for '$slug'")

        // Always resolve. Every server is probed, in parallel and time-boxed.
        //
        // The previous version returned as soon as one server was labelled
        // "direct", on the assumption that a native playlist needs no further
        // work. That assumption was what broke playback: the only servers ever
        // labelled direct were the Asura redirector (a PHP page, on a host that
        // is usually offline) and Tamamo (the same family). So the app took the
        // fast path, skipped resolving the embeds that do hold a real playlist,
        // and the viewer was left with a list where nothing played. Resolution is
        // now unconditional, and the ordering below decides what to try first.
        val resolved = probeServersForPlaylists(ordered, slug)
        Log.i(TAG, "Servers resolved: ${resolved.size} (${resolved.count { it.isDirect }} direct)")
        resolved.forEach {
            Log.i(
                TAG,
                "  - ${it.id} direct=${it.isDirect} dead=${it.unavailableReason != null} " +
                    "url=${(it.directHlsUrl ?: it.embedUrl)?.take(70)}"
            )
        }
        resolved
    }

    /** The slug out of a `/ver/{slug}/{n}` path, absolute or relative. */
    private fun slugOf(path: String): String {
        val m = Pattern.compile("/ver/([a-zA-Z0-9\\-]+)/\\d+").matcher(path)
        return if (m.find()) m.group(1) else ""
    }

    /** Fallback: the same slug read out of the page's own episode links. */
    private fun seriesSlugOf(haystack: String): String {
        val m = Pattern.compile("/ver/([a-zA-Z0-9\\-]+)/\\d+").matcher(haystack)
        return if (m.find()) m.group(1) else ""
    }

    /**
     * Records that a server actually delivered a stream for a series.
     *
     * Called from the player once playback has really started, not from the
     * extractor. A playlist that resolves is not proof of playback, and learning
     * from anything weaker is how a server that resolves but cannot be watched
     * ended up first in the list for every later chapter.
     */
    fun noteServerWorked(seriesSlug: String, serverId: String) {
        if (seriesSlug.isBlank() || serverId.isBlank()) return
        noteWorking(seriesSlug, serverId)
    }

    private fun embedServer(id: String, name: String, url: String) = ServerItem(
        id = id,
        name = name,
        directHlsUrl = null,
        embedUrl = url,
        referer = originOf(url),
    )

    /**
     * Direct servers first, then within each group by remembered health.
     *
     * @param slug the series, used to look up which server worked for it before.
     */
    private fun orderServers(servers: List<ServerItem>, slug: String = ""): List<ServerItem> {
        fun rank(s: ServerItem): Int =
            if (slug.isNotBlank()) rankFor(slug, s.id)
            else PREFERRED_ORDER.indexOf(s.id).let { if (it >= 0) it else PREFERRED_ORDER.size }
        return servers.sortedWith(
            compareByDescending<ServerItem> { it.isDirect }.thenBy { rank(it) }.thenBy { it.id }
        )
    }

    /**
     * Resolves Tamamo's key through the site's API.
     *
     * The page does not inline the player URL: it hands out a per-episode slug
     * and expects an AJAX call to `/api_donghua.php` for the key. That call also
     * needs the PHPSESSID the page fetch established, which is why this shares
     * the extractor's cookie jar. Without the session the endpoint returns
     * nothing and the server silently disappears from the list.
     *
     * The player is a web page, not a playlist, so it is published as an embed
     * and resolved by the same probe as everything else.
     */
    private suspend fun resolveTamamo(haystack: String): ServerItem? {
        // Already have a complete player URL.
        val ready = firstGroup(DMPLAYER_RE.matcher(haystack))
        if (ready != null) {
            val keyParam = Pattern.compile("[?&]key=([^&#\\s\"'>]+)").matcher(ready)
            if (keyParam.find()) {
                val key = keyParam.group(1)
                val videoId = key?.let { TamamoExtractor.decryptKey(it) }
                if (videoId != null) {
                    return TamamoExtractor.resolveDailymotion(videoId, httpClient)
                }
            }
            return ServerItem("tamamo", "Tamamo", embedUrl = ready, referer = originOf(ready))
        }

        val slug = firstGroup(TAMAMO_SLUG_RE.matcher(haystack), 1)?.takeIf { it.isNotBlank() }
            ?: return null

        return try {
            val apiUrl = "${Api.BASE}/api_donghua.php?slug=${URLEncoder.encode(slug, "UTF-8")}"
            val body = fetchText(apiUrl) ?: return null
            val arr = JSONArray(body)
            if (arr.length() == 0) return null
            val key = arr.optJSONObject(0)?.optString("url").orEmpty()
            if (key.isBlank()) return null

            // Decrypt key using MDonghua crypto algorithm
            val videoId = TamamoExtractor.decryptKey(key)
            if (videoId != null) {
                Log.i(TAG, "Tamamo key decrypted to Dailymotion video ID: $videoId")
                return TamamoExtractor.resolveDailymotion(videoId, httpClient)
            }

            // Fallback for legacy embeds
            val host = firstGroup(REDIRECTOR_RE.matcher(haystack))?.let { originOf(it) }
                ?: DEFAULT_PLAYER_HOST
            val embed = "$host/nemonicplayer/dmplayer.php?key=$key"
            Log.i(TAG, "Tamamo fallback: ${embed.take(80)}")
            ServerItem("tamamo", "Tamamo", embedUrl = embed, referer = originOf(embed))
        } catch (e: Exception) {
            Log.w(TAG, "Tamamo api error: ${e.message}")
            null
        }
    }

    /**
     * Gives every server a short, parallel chance to yield a real playlist so it
     * can be promoted to ExoPlayer.
     *
     * This also records the servers that are definitively dead. That matters on a
     * TV: a list of seven buttons where five are known-broken is worse than a
     * short list, because the viewer has no way to tell which is which and
     * reasonably assumes the app is at fault.
     *
     * Bounded on purpose: a dead host must not delay playback, and one that needs
     * a full browser simply stays an embed.
     */
    private suspend fun probeServersForPlaylists(
        servers: List<ServerItem>,
        slug: String = "",
    ): List<ServerItem> {
        val candidates = servers.filter { !it.isDirect && !it.embedUrl.isNullOrBlank() }
        if (candidates.isEmpty()) return servers

        val upgraded = withTimeoutOrNull(EMBED_PROBE_BUDGET_MS) {
            coroutineScope {
                candidates.map { candidate -> async { probeOne(candidate) } }.awaitAll()
            }
        }

        // On timeout keep the servers we have, unchanged. Falling back to
        // `candidates` alone would silently discard servers that were never even
        // attempted, which is how a working server ends up hidden.
        val probed = if (upgraded == null) {
            Log.i(TAG, "Probe budget exhausted, keeping ${candidates.size} unverified servers")
            null
        } else {
            upgraded.associateBy { it.id }
        }

        val merged = servers.map { s -> probed?.get(s.id) ?: s }
        // Ordered again on the results: a server that resolved to a working
        // playlist has just proved itself and belongs above one that merely
        // answered. `slug` carries through so the per-title health memory still
        // applies.
        val ordered = orderServers(merged, slug)

        Log.i(
            TAG,
            "Probe summary: ${ordered.count { it.isDirect }} direct, " +
                "${ordered.count { !it.isPlayable }} unusable, ${ordered.count { it.isPlayable }} playable"
        )
        return ordered
    }

    /** Resolves one server into a playable playlist, or explains why it cannot. */
    private suspend fun probeOne(candidate: ServerItem): ServerItem {
        val embed = candidate.embedUrl ?: return candidate

        // Bound each probe on its own, not just the batch. Without this the two
        // offline hosts consume the entire shared budget and the servers that
        // would have resolved never get their turn.
        val page = withTimeoutOrNull(PROBE_TIMEOUT_SECONDS * 1000L + 2_000L) {
            fetchProbe(embed, referer = candidate.referer)
        }
            ?: return candidate.copy(unavailableReason = "No responde")

        if (EXPIRED_MARKERS.any { page.contains(it, ignoreCase = true) }) {
            Log.i(TAG, "${candidate.name}: file expired upstream")
            return candidate.copy(unavailableReason = "Eliminado")
        }

        // A page that loads is not a page that plays.
        //
        // Some of these hosts serve a healthy shell and only then refuse to play:
        // bysekoze answers its own API with "embedding from bysekoze.com is not
        // allowed for this video", and the shell itself contains no playlist. The
        // page therefore looked alive, was offered to the player, and then the
        // player burned the full embed timeout on a server that could never work.
        // Both of those words on the same page mean the same thing, and catching
        // it here turns a 30 second wait into an immediate skip.
        if (REFUSAL_MARKERS.any { page.contains(it, ignoreCase = true) }) {
            Log.i(TAG, "${candidate.name}: host refuses to embed this video")
            return candidate.copy(unavailableReason = "No permitido")
        }

        val playlist = findPlaylistIn(page)?.takeIf { looksLikePlaylist(it) }
        if (playlist != null) {
            // Finding a playlist URL is not the same as having a working stream.
            // Several of these CDNs hand out a link that then hangs or refuses
            // every segment, and offering it as a resolved server put a dead
            // stream at the front of the list where a working embed was waiting
            // behind it. One cheap request settles it.
            if (isPlaylistReachable(playlist, candidate.referer)) {
                Log.i(TAG, "${candidate.name} resolved to a working playlist")
                return candidate.copy(directHlsUrl = playlist)
            }
            Log.i(TAG, "${candidate.name}: playlist found but not playable, treating as embed")
        }

        // No usable playlist in the shell, and the host may still refuse outright.
        // Ask it before offering the server to the player.
        if (hostRefusesEmbedding(candidate)) {
            return candidate.copy(unavailableReason = "No permitido")
        }

        // No usable playlist, but the host answered and did not say the file is
        // gone. It may still be a page a WebView can drive, so keep it playable.
        return candidate
    }

    /**
     * Asks a host that hides its verdict whether it will serve this video.
     *
     * `bysekoze` renders a plain shell and keeps its answer for a later API call,
     * so nothing in the page body reveals that the embed is forbidden: the page
     * arrives healthy, is offered to the player, and only fails once the WebView
     * runs it. Its own endpoint says so outright, in one cheap request, and that
     * is the difference between skipping this server immediately and spending the
     * whole embed timeout discovering the same thing.
     *
     * Only consulted for hosts that expose such an endpoint, and a failure to ask
     * is treated as "unknown", never as "dead": guessing wrong here would hide a
     * working server.
     */
    private suspend fun hostRefusesEmbedding(candidate: ServerItem): Boolean {
        val endpoint = EMBED_API_URL[candidate.id] ?: return false
        val id = embedIdOf(candidate) ?: return false
        val url = endpoint.replace("{id}", java.net.URLEncoder.encode(id, "UTF-8"))
        val page = EMBED_PAGE_URL[candidate.id]?.replace("{id}", id) ?: candidate.referer
        val body = withTimeoutOrNull(PROBE_TIMEOUT_SECONDS * 1000L) {
            fetchProbe(url, referer = page, parent = page)
        } ?: return false
        val refusal = REFUSAL_MARKERS.any { body.contains(it, ignoreCase = true) }
        if (refusal) Log.i(TAG, "${candidate.name}: API refuses this embed")
        return refusal
    }

    /**
     * Whether a playlist URL actually serves a manifest.
     *
     * Deliberately a short, single request: the goal is to tell "this link works"
     * from "this link is dead", not to play the video twice. A 200 with a body
     * that looks like a playlist is the pass.
     */
    private suspend fun isPlaylistReachable(url: String, referer: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", DEFAULT_UA)
                    .header("Referer", referer)
                    .header("Origin", originOf(url).trimEnd('/'))
                    .header("Accept", "*/*")
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false
                    val body = resp.body?.string().orEmpty()
                    // A playlist always starts with #EXTM3U. An HTML error page
                    // served with a 200 is the common failure here.
                    body.trimStart().startsWith("#EXTM3U")
                }
            } catch (e: Exception) {
                Log.i(TAG, "playlist unreachable: ${e.javaClass.simpleName}")
                false
            }
        }
}
