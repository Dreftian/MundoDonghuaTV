package com.donghua.tv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val FILE = "donghua_prefs"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var lastProvider: String?
        get() = sp.getString("last_provider", null)
        set(v) = sp.edit().putString("last_provider", v).apply()

    var autoNext: Boolean
        get() = sp.getBoolean("auto_next", true)
        set(v) = sp.edit().putBoolean("auto_next", v).apply()

    var historyEnabled: Boolean
        get() = sp.getBoolean("history_enabled", true)
        set(v) = sp.edit().putBoolean("history_enabled", v).apply()

    var a11yHintShown: Boolean
        get() = sp.getBoolean("a11y_hint", false)
        set(v) = sp.edit().putBoolean("a11y_hint", v).apply()

    private const val POS_PREFIX = "pos_"
    private const val POS_DUR_PREFIX = "posd_"
    private const val POS_MAX_ENTRIES = 60

    /** Positions within this many ms of the end are not worth resuming. */
    private const val NEAR_END_MS = 20_000L

    /**
     * Remember where the user stopped so a stall, a server switch or leaving the
     * app never loses the place in an episode. The duration is stored too so we
     * can later tell a mid-episode position from one sitting at the very end.
     */
    fun savePosition(url: String, positionMs: Long, durationMs: Long = -1L) {
        if (url.isBlank()) return
        val editor = sp.edit()
            .putLong(POS_PREFIX + url, positionMs)
            .putLong("pos_ts_" + url, System.currentTimeMillis())
        if (durationMs > 0L) editor.putLong(POS_DUR_PREFIX + url, durationMs)
        editor.apply()
    }

    /**
     * Returns a stored position only if it is actually worth resuming. A
     * position at the very end of the episode is rejected: restoring it leaves
     * the player with nothing to play, which shows up as a loading screen that
     * never resolves.
     */
    fun savedPosition(url: String, durationMs: Long = -1L): Long {        if (url.isBlank()) return 0L
        val pos = sp.getLong(POS_PREFIX + url, 0L)
        if (pos <= 0L) return 0L
        // Ignore positions that are basically the start.
        if (pos < 10_000L) return 0L

        val known = when {
            durationMs > 0L -> durationMs
            else -> sp.getLong(POS_DUR_PREFIX + url, -1L)
        }
        if (known > 0L && pos > known - NEAR_END_MS) return 0L
        return pos
    }

    /**
     * Stored duration for an episode, or -1.
     *
     * Needed by the chapter grid to turn a saved position into a percentage and
     * to tell a finished chapter from an untouched one, neither of which can be
     * judged from the position alone.
     */
    fun durationOf(url: String): Long {
        if (url.isBlank()) return -1L
        return sp.getLong(POS_DUR_PREFIX + url, -1L)
    }

    /**
     * Everything a "where did I stop" screen needs about one episode, in one read.
     *
     * [savedPosition] is the wrong thing to build that screen on. It answers
     * "should the player resume here", and it answers 0 for three quite
     * different situations: never started, a few seconds in, and watched to the
     * end. A history screen that showed 0 for all three would say the same thing
     * about an episode you finished and one you never opened.
     *
     * So this returns the numbers as stored and lets the caller decide what they
     * mean, which is the only way "Terminado" and "sin progreso" can be told
     * apart at all.
     */
    fun resumeOf(url: String): Resume {
        if (url.isBlank()) return Resume.NONE
        val pos = sp.getLong(POS_PREFIX + url, 0L)
        val dur = sp.getLong(POS_DUR_PREFIX + url, -1L)
        return Resume(pos, dur)
    }

    /**
     * A stored playback position and the length it is a position within.
     *
     * [known] is false when either number is missing, which is the honest answer
     * rather than a guess: without a duration there is no fraction to draw and no
     * "de 24:10" to print.
     */
    data class Resume(val positionMs: Long, val durationMs: Long) {
        val known: Boolean get() = positionMs > 0L && durationMs > 0L

        /** 0f..1f, or 0f when [known] is false. */
        val fraction: Float
            get() = if (known) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

        /**
         * Watched to the end, by the same margin the player uses to decide it is
         * not worth resuming. Sharing the constant is the point: if the two
         * disagreed, a chapter could be marked finished here and then resumed
         * from the last twenty seconds when it was opened.
         */
        val finished: Boolean get() = known && positionMs >= durationMs - NEAR_END_MS

        /** Barely started, so there is no place to point at. */
        val untouched: Boolean get() = positionMs < 10_000L

        companion object {
            val NONE = Resume(0L, -1L)
        }
    }

    /** Keeps the position store bounded so it never grows without limit. */
    fun prunePositions() {
        val keys = sp.all.keys.filter { it.startsWith("pos_ts_") }
        if (keys.size <= POS_MAX_ENTRIES) return
        val entries = keys.map { it.removePrefix("pos_ts_") to (sp.getLong(it, 0L) ?: 0L) }
            .sortedBy { it.second }
        val editor = sp.edit()
        entries.take(keys.size - POS_MAX_ENTRIES).forEach { (url, _) ->
            editor.remove(POS_PREFIX + url).remove("pos_ts_" + url).remove(POS_DUR_PREFIX + url)
        }
        editor.apply()
    }

    fun clearPositions() {
        val editor = sp.edit()
        sp.all.keys.filter {
            it.startsWith(POS_PREFIX) || it.startsWith("pos_ts_") || it.startsWith(POS_DUR_PREFIX)
        }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun cardToJson(c: MediaCard): JSONObject = JSONObject().apply {
        put("t", c.title)
        put("i", c.thumb)
        put("u", c.url)
    }

    private fun cardFromJson(o: JSONObject): MediaCard =
        MediaCard(o.optString("t"), o.optString("i"), o.optString("u"))

    fun history(): List<MediaCard> = readCards("history")

    fun addHistory(card: MediaCard) {
        if (!historyEnabled) return
        if (card.url.isBlank()) return
        val list = history().filterNot { it.url == card.url }.toMutableList()
        list.add(0, card)
        writeCards("history", list.take(30))
    }

    fun clearHistory() = sp.edit().remove("history").apply()

    fun favorites(): List<MediaCard> = readCards("favorites")

    fun isFavorite(url: String): Boolean =
        favorites().any { it.url == url }

    fun toggleFavorite(card: MediaCard) {
        val list = favorites().toMutableList()
        if (list.any { it.url == card.url }) {
            list.removeAll { it.url == card.url }
        } else {
            list.add(0, card)
        }
        writeCards("favorites", list.take(100))
    }

    fun clearFavorites() = sp.edit().remove("favorites").apply()

    private fun readCards(key: String): List<MediaCard> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            // optJSONObject returns null for any element that is not an object, and
            // cardFromJson takes a non-null parameter, so the intrinsic null
            // check threw and the catch below turned one bad element into an
            // empty list - silently discarding every favourite and the entire
            // history. Skipping the bad element is what mapNotNull was asking
            // for in the first place.
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { cardFromJson(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeCards(key: String, cards: List<MediaCard>) {
        val arr = JSONArray()
        cards.forEach { arr.put(cardToJson(it)) }
        sp.edit().putString(key, arr.toString()).apply()
    }

    fun recentSearches(): List<String> {
        val raw = sp.getString("recent_searches", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addRecentSearch(q: String) {
        val qq = q.trim()
        if (qq.isEmpty()) return
        val list = recentSearches().filterNot { it.equals(qq, ignoreCase = true) }.toMutableList()
        list.add(0, qq)
        val arr = JSONArray()
        list.take(8).forEach { arr.put(it) }
        sp.edit().putString("recent_searches", arr.toString()).apply()
    }

    fun clearRecentSearches() = sp.edit().remove("recent_searches").apply()
}
