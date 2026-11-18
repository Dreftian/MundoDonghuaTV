package com.donghua.tv.player

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Logs what the player is actually doing on the wire, so a stall can be told
 * apart from a re-download.
 *
 * The interesting signals are:
 *  - the same segment being requested more than once (wasted bandwidth, and a
 *    sign the cache is not doing its job),
 *  - redirects, because these CDNs bounce every request to a different host,
 *  - a gap in requests while the picture is supposed to be playing, which means
 *    the player has stopped loading entirely.
 */
internal class StreamLogger : Interceptor {

    private companion object {
        const val TAG = "DonghuaNet"

        /** Query strings carry a signed token; the path is what identifies media. */
        fun shortName(url: String): String {
            val path = url.substringBefore('?')
            val file = path.substringAfterLast('/')
            return if (file.length > 34) file.take(18) + "…" + file.takeLast(12) else file
        }
    }

    private val requestCount = ConcurrentHashMap<String, Int>()
    private val bytes = AtomicLong(0)
    private val requestTotal = AtomicLong(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val full = request.url.toString()
        val name = shortName(full)
        val nth = requestCount.merge(name, 1, Int::plus) ?: 1

        val started = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.w(TAG, "FAIL $name (#$nth) after ${ms}ms: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        val len = response.body?.contentLength() ?: -1L

        requestTotal.incrementAndGet()
        if (len > 0) bytes.addAndGet(len)

        // Does the signed query string change between requests for the same
        // file? If it does, a cache keyed on the full URL can never hit.
        val q = request.url.query
        val qSig = if (q.isNullOrEmpty()) "-" else Integer.toHexString(q.hashCode())
        val dup = if (nth > 1) " REPEAT#$nth q=$qSig" else " q=$qSig"
        val redirect = if (response.isRedirect) " -> ${response.header("Location")?.let { shortName(it) }}" else ""
        Log.d(
            TAG,
            "${response.code} $name${if (len > 0) " ${len / 1024}KB" else ""} ${ms}ms$dup$redirect"
        )
        return response
    }

    /** Called by the player periodically so the log shows the trend. */
    fun snapshot(): String =
        "requests=${requestTotal.get()} bytes=${bytes.get() / 1024}KB"
}
