package com.donghua.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A non-2xx HTTP response, kept distinct from a transport failure.
 *
 * The player needs to tell "the site is unreachable" apart from "this episode
 * does not exist", because only the second one should step back to the previous
 * chapter. Both used to arrive as an opaque [RuntimeException].
 */
class HttpStatusException(val code: Int, val path: String) :
    Exception("HTTP $code para $path")

/**
 * Session cookies for the scraper.
 *
 * Two things it has to get right, both of which it did not:
 *
 * OkHttp calls this from arbitrary dispatcher threads and the extractor shares
 * one jar across two clients that probe concurrently, so the backing map has to
 * be concurrent. A plain LinkedHashMap threw ConcurrentModificationException
 * under that, and because every caller wraps requests in a broad catch the
 * symptom was not a crash but intermittent "server unreachable" verdicts.
 *
 * And `CookieJar` is defined in terms of *replacing* a cookie of the same
 * name/domain/path, not appending to it. Appending let every re-issued
 * PHPSESSID pile up for the life of the process - session cookies are stored
 * with no expiry, so nothing ever pruned them - and the request then carried
 * several same-named values, which providers answer by rejecting the request.
 */
class InMemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        // Keyed per host, so replacement only has to consider this host's set.
        val key = "${url.host}|${url.port}"
        val current = store.getOrPut(key) { CopyOnWriteArrayList() }
        val incoming = cookies.toMutableList()
        current.removeAll { incoming.any { n -> n.name == it.name && n.domain == it.domain && n.path == it.path } }
        current.addAll(incoming)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val key = "${url.host}|${url.port}"
        val list = store[key] ?: return emptyList()
        val now = System.currentTimeMillis()
        val valid = list.filter { it.expiresAt > now }
        if (valid.size != list.size) store[key] = CopyOnWriteArrayList(valid)
        return valid
    }
}

object Api {
    const val BASE = "https://www.mundodonghua.com"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // readTimeout only bounds the gap between two reads, so a host that
        // answers with headers and then a byte every 59 s would hold a call open
        // indefinitely - and with five attempts on top, "Cargando servidores…"
        // could sit there for minutes with no way out. This is the only setting
        // that bounds the call as a whole.
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .cookieJar(InMemoryCookieJar())
        .build()

    private val headers: Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "es-ES,es;q=0.9")
        .add("Referer", BASE + "/")
        .build()

    suspend fun getHtml(path: String, extraHeaders: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            val delays = longArrayOf(1000, 2500, 5000, 8000)
            for (attempt in 0..delays.size) {
                try {
                    val req = Request.Builder()
                        .url(if (path.startsWith("http")) path else BASE + path)
                        .headers(headers)
                    extraHeaders.forEach { (k, v) -> req.header(k, v) }
                    client.newCall(req.build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw HttpStatusException(resp.code, path)
                        return@withContext resp.body!!.string()
                    }
                } catch (e: Exception) {
                    lastError = e
                    // A status the server already answered is not worth another go.
                    //
                    // Everything non-2xx used to be retried like a dropped
                    // connection, so opening a chapter that does not exist sat
                    // through four attempts and sixteen seconds of backoff before
                    // the player was finally told the chapter is missing. A 404 is
                    // 404 on the next request too; the retries are only for
                    // transport, which is what the rest of this catches.
                    val final = e is HttpStatusException &&
                        (e.code == 404 || e.code == 410 || e.code == 403 || e.code == 401)
                    if (final) throw e
                    if (attempt < delays.size) kotlinx.coroutines.delay(delays[attempt])
                }
            }
            throw lastError ?: RuntimeException("Error de red para $path")
        }

    suspend fun getDoc(path: String, extraHeaders: Map<String, String> = emptyMap()): Document =
        Jsoup.parse(getHtml(path, extraHeaders), BASE)

    suspend fun fetchHome(): HomeData = withContext(Dispatchers.IO) {
        Parser.parseHome(getDoc("/"))
    }

    suspend fun fetchMoreEpisodes(offset: Int): List<MediaCard> = withContext(Dispatchers.IO) {
        val json = getHtml("/ajax_latest_episodes.php?offset=$offset", mapOf("X-Requested-With" to "XMLHttpRequest"))
        val html = try {
            JSONObject(json).getString("html")
        } catch (e: Exception) {
            ""
        }
        Parser.parseCardsHtml(html)
    }

    suspend fun fetchList(path: String, page: Int): ListPage = withContext(Dispatchers.IO) {
        val base = if (page > 1) "$path/$page" else path
        Parser.parseList(getDoc(base), path, page)
    }

    suspend fun fetchDetail(path: String): SeriesDetail = withContext(Dispatchers.IO) {
        Parser.parseDetail(getDoc(path))
    }

    suspend fun search(query: String): List<MediaCard> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val q = URLEncoder.encode(trimmed, "UTF-8")
        Parser.parseSearch(getDoc("/busquedas/$q"))
    }

    suspend fun fetchEpisodeServers(path: String): List<ServerItem> = withContext(Dispatchers.IO) {
        val html = getHtml(path)
        // The path carries the series slug, which the provider URLs do not, and
        // the extractor needs it to remember which server worked for this title.
        Extractor.extractServers(html, path)
    }
}
