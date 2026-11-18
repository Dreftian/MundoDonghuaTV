package com.donghua.tv.data

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Port of the MDonghua extension decryption and extraction engine.
 *
 * Mundodonghua's Tamamo provider hands out a protected base64 key via
 * /api_donghua.php?slug=... which previously pointed to an obsolete
 * mdnemonicplayer.xyz embed. MDonghua decrypts the key client-side using
 * AES-256-CBC to extract the underlying Dailymotion video ID and direct stream.
 */
object TamamoExtractor {
    private const val TAG = "TamamoExtractor"

    // Key and IV derived from MDonghua/crypto.js masks:
    // Key: 64eafb71fb218e1ec0fd962f2dd12217 (32 bytes UTF-8)
    // IV:  c4c1dc199dc1faa5 (16 bytes UTF-8)
    private val AES_KEY_BYTES = "64eafb71fb218e1ec0fd962f2dd12217".toByteArray(StandardCharsets.UTF_8)
    private val AES_IV_BYTES = "c4c1dc199dc1faa5".toByteArray(StandardCharsets.UTF_8)

    private val ID_PATTERN = Pattern.compile("^[A-Za-z0-9]{15,22}$")
    private val DM_URL_PATTERN = Pattern.compile("(?:dailymotion\\.com/(?:embed/)?video/|/video/|/embed/video/)([A-Za-z0-9]{6,24})", Pattern.CASE_INSENSITIVE)
    private val TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9]{15,22}")

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Decrypts a raw base64 key from Tamamo / Nemonic player into a Dailymotion video ID.
     */
    fun decryptKey(rawKey: String): String? {
        if (rawKey.isBlank()) return null
        val candidates = buildCipherCandidates(rawKey)
        if (candidates.isEmpty()) return null

        var loose: String? = null

        val keySpec = SecretKeySpec(AES_KEY_BYTES, "AES")
        val ivSpec = IvParameterSpec(AES_IV_BYTES)

        for (cipherBytes in candidates) {
            // Try PKCS5Padding first, then NoPadding
            for (padding in listOf("AES/CBC/PKCS5Padding", "AES/CBC/NoPadding")) {
                try {
                    val cipher = Cipher.getInstance(padding)
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                    val decrypted = cipher.doFinal(cipherBytes)
                    val plaintext = String(decrypted, StandardCharsets.UTF_8)
                        .trim { it <= ' ' || it == '\u0000' }
                    val videoId = extractVideoId(plaintext)
                    if (videoId != null) {
                        if (looksStructured(plaintext)) {
                            Log.i(TAG, "Decrypted video ID: $videoId from structured plaintext")
                            return videoId
                        }
                        if (loose == null) loose = videoId
                    }
                } catch (_: Exception) {
                    // Try next candidate/padding
                }
            }
        }
        return loose
    }

    /**
     * Handles nested base64 layers (up to 3 levels) as implemented in MDonghua.
     */
    private fun buildCipherCandidates(raw: String): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        var current = raw.trim()
        for (depth in 0 until 3) {
            val bytes = decodeBase64Safe(current) ?: break
            list.add(bytes)
            val asText = String(bytes, StandardCharsets.ISO_8859_1).trim()
            if (!isBase64Text(asText)) break
            current = asText
        }
        return list.reversed()
    }

    private fun decodeBase64Safe(input: String): ByteArray? {
        var str = input.trim()
        if (str.contains("%")) {
            try { str = URLDecoder.decode(str, "UTF-8") } catch (_: Exception) {}
        }
        str = str.replace("\\s+".toRegex(), "+")
            .replace("-", "+")
            .replace("_", "/")
            .trimEnd('=')
        if (str.isEmpty()) return null
        val padding = (4 - (str.length % 4)) % 4
        val padded = str + "=".repeat(padding)
        return try {
            Base64.decode(padded, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }

    private fun isBase64Text(value: String): Boolean =
        value.length >= 16 && value.matches("^[A-Za-z0-9+/=_-]+$".toRegex())

    private fun looksStructured(text: String): Boolean =
        text.startsWith("{") || text.startsWith("[") || text.startsWith("http") ||
            text.matches("^\\d{6,14}\\s*[|;,:\\s].*".toRegex())

    private fun looksLikeVideoId(token: String): Boolean {
        val trimmed = token.trim()
        return ID_PATTERN.matcher(trimmed).matches() &&
            trimmed.any { it.isDigit() } &&
            trimmed.any { it.isLetter() }
    }

    private fun extractVideoId(plaintext: String): String? {
        val text = plaintext.trim { it <= ' ' || it == '\u0000' }
        if (text.isEmpty()) return null

        // 1. JSON parsing
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                if (text.startsWith("{")) {
                    val obj = JSONObject(text)
                    val found = walkJson(obj)
                    if (found != null) return found
                } else {
                    val arr = JSONArray(text)
                    val found = walkJson(arr)
                    if (found != null) return found
                }
            } catch (_: Exception) {}
        }

        // 2. Dailymotion URL pattern
        val dmMatcher = DM_URL_PATTERN.matcher(text)
        if (dmMatcher.find()) {
            val id = dmMatcher.group(1)
            if (id != null && (looksLikeVideoId(id) || id.length in 6..24)) return id
        }

        // 3. Delimited segments (e.g. 539724999|k1zKO0gUfyMIN9ML61Y|810677741|114957)
        val segments = text.split("[|;,\\s]+".toRegex())
            .map { it.trim().trim('"', '\'', '[', '{', ']', '}') }
            .filter { it.isNotBlank() }
        for (seg in segments) {
            if (looksLikeVideoId(seg)) return seg
        }

        // 4. Token scan by length
        val matcher = TOKEN_PATTERN.matcher(text)
        val candidates = mutableListOf<String>()
        while (matcher.find()) {
            val token = matcher.group(0)
            if (token != null && looksLikeVideoId(token)) {
                candidates.add(token)
            }
        }
        if (candidates.isNotEmpty()) {
            candidates.sortByDescending { it.length }
            return candidates[0]
        }

        return null
    }

    private fun walkJson(node: Any, depth: Int = 0): String? {
        if (depth > 12) return null
        when (node) {
            is String -> {
                if (looksLikeVideoId(node)) return node.trim()
                val m = TOKEN_PATTERN.matcher(node)
                while (m.find()) {
                    val token = m.group(0)
                    if (token != null && looksLikeVideoId(token)) return token
                }
                return null
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val child = node.opt(i) ?: continue
                    val found = walkJson(child, depth + 1)
                    if (found != null) return found
                }
                return null
            }
            is JSONObject -> {
                val preferred = listOf("id", "videoId", "video_id", "key", "token")
                for (k in preferred) {
                    if (node.has(k)) {
                        val v = node.opt(k) ?: continue
                        val found = walkJson(v, depth + 1)
                        if (found != null) return found
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k) ?: continue
                    val found = walkJson(v, depth + 1)
                    if (found != null) return found
                }
                return null
            }
            else -> return null
        }
    }

    /**
     * Resolves a Dailymotion video ID into a stream and embed ServerItem.
     */
    fun resolveDailymotion(
        videoId: String,
        httpClient: OkHttpClient,
    ): ServerItem {
        val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
        val referer = "https://www.dailymotion.com/"

        var directUrl: String? = null

        // Query Dailymotion's endpoints for direct HLS playlist
        val endpoints = listOf(
            "https://geo.dailymotion.com/videos/${URLEncoder.encode(videoId, "UTF-8")}",
            "https://www.dailymotion.com/player/metadata/video/${URLEncoder.encode(videoId, "UTF-8")}"
        )

        for (endpoint in endpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", DEFAULT_UA)
                    .header("Referer", referer)
                    .header("Origin", "https://www.dailymotion.com")
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        if (body.startsWith("{")) {
                            val json = JSONObject(body)
                            // 1. data.stream.url
                            val streamObj = json.optJSONObject("data")?.optJSONObject("stream")
                                ?: json.optJSONObject("stream")
                            val streamUrl = streamObj?.optString("url")
                            if (!streamUrl.isNullOrBlank() && streamUrl.contains(".m3u8")) {
                                directUrl = streamUrl
                                return@use
                            }
                            // 2. qualities.auto
                            val autoList = json.optJSONObject("qualities")?.optJSONArray("auto")
                            if (autoList != null && autoList.length() > 0) {
                                val autoUrl = autoList.optJSONObject(0)?.optString("url")
                                if (!autoUrl.isNullOrBlank() && autoUrl.contains(".m3u8")) {
                                    directUrl = autoUrl
                                    return@use
                                }
                            }
                        }
                    }
                }
                if (directUrl != null) break
            } catch (e: Exception) {
                Log.w(TAG, "Error querying $endpoint: ${e.message}")
            }
        }

        Log.i(TAG, "Tamamo Dailymotion resolved: videoId=$videoId directHls=${directUrl != null}")
        return ServerItem(
            id = "tamamo",
            name = "Tamamo",
            directHlsUrl = directUrl,
            embedUrl = embedUrl,
            referer = referer
        )
    }
}
