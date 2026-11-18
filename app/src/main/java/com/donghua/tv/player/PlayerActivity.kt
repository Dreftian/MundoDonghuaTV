package com.donghua.tv.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.donghua.tv.R
import com.donghua.tv.data.Api
import com.donghua.tv.data.Episode
import com.donghua.tv.data.Extractor
import com.donghua.tv.data.HttpStatusException
import com.donghua.tv.data.MediaCard
import com.donghua.tv.data.Parser
import com.donghua.tv.data.Prefs
import com.donghua.tv.data.SeriesDetail
import com.donghua.tv.data.ServerItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DonghuaPlayer"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PREV = "prev"
        private const val EXTRA_NEXT = "next"
        private const val EXTRA_THUMB = "thumb"

        /** Shared with [PlaybackEngine] so every request uses the same identity. */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // --- Buffering profile -------------------------------------------------
        // A good connection is assumed; buffer generously so brief WiFi jitter
        // never reaches the screen as a freeze. 30 s of video before the floor is
        // what actually absorbs that jitter.
        private const val MIN_BUFFER_MS = 30_000
        private const val MAX_BUFFER_MS = 90_000
        private const val BUFFER_FOR_PLAYBACK_MS = 2_500

        /**
         * How much must be re-buffered before playback resumes after a stall.
         *
         * This used to be 6 s, which was read as "be forgiving of dips". It is the
         * opposite: it is a *dead time added to every single stall*. The moment
         * the buffer underruns, the picture stops and then stays stopped until
         * 6 s of video has been downloaded again, even when those 6 s arrive in
         * well under a second. Every hiccup was therefore a six second freeze
         * rather than a barely perceptible one.
         *
         * The floor above already decides what counts as a dip worth surviving;
         * this only decides how fast playback picks back up once one happens, so
         * it is kept short.
         */
        private const val BUFFER_AFTER_REBUFFER_MS = 1_500

        /**
         * Highest rendition the player is allowed to consider, in pixels tall.
         *
         * Quality is now bounded rather than pinned (see [applyVideoQualityCap]).
         * 1080p is the top of what these sources offer and what a TV box of this
         * class can decode; the cap exists to stop a stray 4K variant being
         * chosen on a good estimate and then stalling.
         */
        private const val MAX_VIDEO_HEIGHT = 1080

        /**
         * Throughput the player assumes when a stream starts, before it has
         * measured anything.
         *
         * ExoPlayer begins on a deliberately pessimistic estimate and climbs, so
         * a fresh episode opens at a low rendition and takes a while to get
         * comfortable. Starting optimistic gives it the top rung immediately
         * while still leaving it free to step down the moment the real number
         * disagrees. 8 Mbps is a comfortable 1080p stream with room to spare.
         */
        private const val INITIAL_BANDWIDTH_BPS = 8_000_000L

        /** Hard cap on buffered bytes so a 2 GB TV stays responsive. */
        private const val MAX_BUFFER_BYTES = 40 * 1024 * 1024

        // --- Recovery profile --------------------------------------------------
        /** How long we wait for the first frame before declaring the server dead. */
        private const val FIRST_FRAME_TIMEOUT_MS = 20_000L

        /** Transient network failures are retried on the same source, not switched. */
        private const val MAX_NETWORK_RETRIES = 4
        private val RETRY_DELAYS_MS = longArrayOf(1_200L, 2_500L, 5_000L, 9_000L)

        /** If rendering stalls this long with data available, recycle the player. */
        private const val STALL_WATCHDOG_MS = 15_000L

        /** How long an embed gets before we give up and try another server. */
        /**
         * How long an embed gets to produce a stream before it is written off.
         *
         * Thirty seconds rather than sixteen, because several of these hosts front
         * the player with a proof-of-work check. The page loads, runs a few
         * hundred hashes on the main thread of the page's own JS engine, and only
         * then asks for the playlist. On a low powered TV box that step alone runs
         * several times longer than it does on a desktop, so a sixteen second
         * deadline was cutting the embed off while it was still legitimately
         * working, and declaring it broken mid-hash.
         */
        private const val EMBED_TIMEOUT_MS = 30_000L

        /**
         * Hard ceiling on how long a re-buffer may last. A hung segment request
         * can outlive the socket timeouts, so without this the picture can sit
         * frozen on a spinner indefinitely.
         */
        private const val BUFFERING_TIMEOUT_MS = 18_000L

        /** How many times we re-prepare the same source before trying another. */
        private const val MAX_BUFFERING_RETRIES = 3

        /**
         * Grace period before the loading spinner appears during playback. Short
         * re-buffers (usually a segment read from the disk cache) stay invisible.
         */
        private const val SPINNER_DELAY_MS = 600L

        /** How many times we retry server extraction before giving up. */
        private const val MAX_EXTRACT_ATTEMPTS = 3

        /**
         * How many times the whole server list is tried before the episode is
         * declared unplayable. Each pass re-reads the page, so a host that was
         * briefly down gets a genuine second chance.
         */
        private const val MAX_SERVER_PASSES = 3

        /**
         * The server picker's footprint, as a fraction of the screen.
         *
         * Narrower than the 0.48 it used to be because a row only has to hold
         * "Asura   Directo" and nothing else, and a 922px box for that much text
         * is mostly margin. The height is a cap rather than a size: the dialog
         * wraps its rows and only grows to this if some site ever offers twenty
         * servers.
         */
        private const val PROVIDER_DIALOG_WIDTH = 0.34f
        private const val PROVIDER_DIALOG_HEIGHT = 0.46f
        private const val EPISODES_DIALOG_WIDTH = 0.72f
        private const val EPISODES_DIALOG_HEIGHT = 0.82f

        /** The accent, shared by the header, the scrub bar and the server marks. */
        private val ORANGE = 0xFFF47521.toInt()

        /**
         * Chapters held in memory at once for the carousel.
         *
         * A bound, not a page size: the strip slides this window along instead
         * of loading the whole series. Martial Master has 691 chapters, and
         * 691 poster views with a bitmap each is not something to attempt on a
         * box with 1.9 GB of RAM. Twenty is roughly one and a half screens of
         * scrolling, which is as far as anyone reads before choosing.
         */
        private const val STRIP_WINDOW = 20
        /**
         * Glyph colour in the action row: white at rest, and near black once it
         * is sitting on the white focus disc.
         *
         * A white glyph on a white disc is not a focus state, it is a hole, and
         * it is the part of the reference's treatment that is easy to miss and
         * leaves the whole row looking broken without it.
         */
        /** The panel's three text roles, from the reference's own palette. */
        private val LABEL_BRIGHT = 0xFFE6ECF2.toInt()
        private val LABEL_MUTED = 0xFF9AA7B4.toInt()

        /** Text inside a selected or focused white pill. */
        private val PILL_SELECTED_TEXT = 0xFF0B0F14.toInt()

        /** How long the chrome survives without input. See [showFsControls]. */
        private const val AUTO_HIDE_MS = 10_000L

        private val GLYPH_IDLE = 0xFFE6ECF2.toInt()
        private val GLYPH_FOCUSED = 0xFF0B0F14.toInt()

        /** Trailing "Episodio 7" in a feed title, with any separator. */
        private val TRAILING_EPISODE =
            Regex("\\s*[-–|]?\\s*Episodio\\s*\\d+\\s*$", RegexOption.IGNORE_CASE)

        /**
         * Granularity of the on-screen skip buttons and of one press of the
         * D-pad while the scrub bar has focus. Ten seconds matches the buttons
         * and the usual remote shortcut.
         */
        private const val SCRUB_STEP_MS = 10_000L

        /**
         * How long the scrub preview waits after the last press before actually
         * seeking. Seeking on every keypress makes ExoPlayer re-prepare
         * repeatedly, which on a TV shows up as the picture stuttering.
         */
        private const val SCRUB_COMMIT_DELAY_MS = 450L

        /**
         * How long the 10s skip buttons stay on screen after a seek. Long enough
         * to hit them, short enough that they do not linger over the picture.
         */
        private const val SKIP_BAR_VISIBLE_MS = 3_200L

        /**
         * How long a transient message stays in the centre pill.
         *
         * Long enough to read at three metres, short enough that it is not the
         * thing you are watching instead of the video.
         */
        private const val FS_STATUS_MS = 2_200L

        /**
         * Pauses and mutes every media element on the current page.
         *
         * Runs before the page is discarded so the audio stops immediately rather
         * than when the new document happens to load. Written defensively: a page
         * can hold no media, replace `play`, or throw from these calls, and none of
         * that is worth failing over.
         */
        /**
 * Embed players of this kind (jwplayer, videojs and the sites' own bundles)
 * routinely host the media element inside a same-origin iframe. Searching only
 * the top document found nothing there, so the chapter carried on playing after
 * the activity had gone. Frames are walked recursively; a cross-origin frame
 * throws on contentDocument and is simply skipped.
 */
private val SILENCE_MEDIA_BODY =
            "var kill=function(doc,hard){" +
                "if(!doc){return;}" +
                "var m=doc.querySelectorAll('video,audio');" +
                "for(var i=0;i<m.length;i++){" +
                "try{m[i].pause();}catch(e){}" +
                "try{m[i].muted=true;}catch(e){}" +
                "try{m[i].volume=0;}catch(e){}" +
                // Only when tearing down. Detaching the source runs the resource
                // selection algorithm with nothing to select, which errors the
                // element out for good - see the note on SOFT_SILENCE.
                "if(hard){" +
                "try{m[i].removeAttribute('src');}catch(e){}" +
                "try{m[i].load&&m[i].load();}catch(e){}" +
                "}" +
                "}" +
                "var f=doc.querySelectorAll('iframe');" +
                "for(var j=0;j<f.length;j++){" +
                "try{kill(f[j].contentDocument,hard);}catch(e){}" +
                "}" +
                "};" +
                "kill(document,%s);"

/**
 * Stops an embed audibly but leaves it resumable.
 *
 * `WebView.onPause()` only pauses the renderer. It does **not** stop a media
 * element that is already playing, so leaving a chapter that was being played
 * through an embed left the audio going with no picture anywhere.
 *
 * This one deliberately does NOT detach the source. Detaching it is what made
 * onPause lethal: the activity is paused for every transient interruption - the
 * Home button, the shade, the screen blanking, a system dialog - and the embed
 * was destroyed so completely that onResume could not bring it back. Nothing
 * recovers from that: the watchdog skips a playing embed, the DOM poll cannot
 * promote it, and the viewer is left on a permanently black page with a status
 * line that never resolves. Pausing and muting is enough to silence it and the
 * page still plays when the activity comes back.
 */
private val SOFT_SILENCE_MEDIA_JS =
            "(function(){try{" + SILENCE_MEDIA_BODY.replace("%s", "false") + "}catch(e){}})();"

/** The destructive form, for when the WebView is about to be thrown away. */
private val SILENCE_MEDIA_JS =
            "(function(){try{" + SILENCE_MEDIA_BODY.replace("%s", "true") + "}catch(e){}})();"

        fun intent(
            context: Context,
            url: String,
            title: String,
            prevUrl: String? = null,
            nextUrl: String? = null,
            thumb: String? = null,
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_PREV, prevUrl)
            putExtra(EXTRA_NEXT, nextUrl)
            putExtra(EXTRA_THUMB, thumb)
        }
    }

    private lateinit var audioManager: AudioManager
    private lateinit var fsWebView: WebView
    private lateinit var fsVideo: PlayerView
    private lateinit var fsControls: View
    private lateinit var fsStatus: TextView
    private lateinit var fsTitle: TextView
    private lateinit var fsSubTitle: TextView
    private lateinit var fsServerTag: TextView
    private lateinit var fsCurrentTime: TextView
    private lateinit var fsTotalTime: TextView

    /**
     * Play/pause, as the first glyph in the action row.
     *
     * Not a centre button any more. Prime Video has no centre control at all:
     * the whole set is one left-aligned glyph row and OK toggles playback, so a
     * big button in the middle of the picture was both a second thing to look at
     * and an extra focus stop between the viewer and the controls.
     */
    private lateinit var fsPlay: LinearLayout

    /**
     * The rest of the glyph row: the chapter either side, the server to get it
     * from, and the favourite toggle. See [buildActionRows] for what was taken
     * out of this row and why.
     */
    private lateinit var fsPrev: View
    private lateinit var fsNext: View
    private lateinit var fsProviders: View

    /**
     * Typed as the container rather than as [View] like its neighbours, because
     * this is the one glyph whose artwork changes: [syncFavoriteGlyph] reaches
     * into it for the ImageView inside the disc and the caption underneath.
     */
    private lateinit var fsFavorite: LinearLayout

    private lateinit var fsActionsLeft: LinearLayout
    private lateinit var fsActionsRight: LinearLayout
    private lateinit var fsPills: LinearLayout
    private lateinit var fsSettingsPanel: LinearLayout
    private lateinit var fsSettingsRow: LinearLayout
    private lateinit var fsSettingsTitle: TextView
    private lateinit var fsSettingsHelp: TextView
    private lateinit var fsExit: LinearLayout
    private lateinit var fsTopBar: LinearLayout
    private lateinit var fsBottomBar: LinearLayout
    private lateinit var fsSeek: TvSeekBar
    private var fsSpinner: ProgressBar? = null
    private lateinit var fsErrorBox: View
    private lateinit var fsErrorTitle: TextView
    private lateinit var fsErrorMsg: TextView
    private lateinit var fsErrorRetry: View
    private lateinit var fsErrorServers: View

    /** Single source of truth for every size in the HUD. See [TvMetrics]. */
    private var tv: TvMetrics? = null

    private var exo: ExoPlayer? = null
    private var lastExoUrl: String? = null
    private var fsActive = false
    private var fsExoOk = false

    private var originalUrl: String = ""
    private var seriesSlug: String = ""
    private var seriesTitle: String = ""
    private var epNumber: Int = 1
    private var epThumb: String = ""
    private var prevUrl: String? = null
    private var nextUrl: String? = null
    private var autoNextFired = false

    private var seriesDetail: SeriesDetail? = null
    private var availableServers: List<ServerItem> = emptyList()
    private var currentServer: ServerItem? = null
    private var serverExtractJob: Job? = null
    private var detailJob: Job? = null
    private var currentVideoUrl: String? = null

    // --- Playback state / resilience -----------------------------------------
    /** True once a frame is actually on screen, not merely "ready". */
    private var firstFrameSeen = false

    /**
     * True once the current chapter has run to its end.
     *
     * It exists to stop the finished position being saved again. A chapter that
     * reached STATE_ENDED is not resumable, and writing its final position back
     * undid the clear, which made the chapter restart from zero on the next
     * visit.
     */
    private var playbackFinished = false

    /**
     * True when the current chapter was opened by the auto-advance rather than
     * by the viewer. It changes how a failure here is reported, and it has to
     * outlive [autoNextFired], which is cleared on every navigation.
     */
    private var arrivedByAutoAdvance = false

    /** Consecutive transient network failures on the current source. */
    private var networkRetries = 0

    /** Position to restore after a retry or a server change. */
    private var resumePositionMs = 0L

    /** Last observed position, persisted periodically. */
    private var lastKnownPositionMs = 0L

    /** True while the WebView embed is the visible player. */
    private var embedIsActive = false

    /** True once the embed page's own <video> is confirmed playing. */
    private var embedIsPlaying = false

    /** When the current embed was started, used for the fixed watchdog deadline. */
    private var embedStartedAtMs = 0L

    /** Referer currently in use, so the data source and media source agree. */
    private var currentReferer: String = PlaybackEngine.DEFAULT_REFERER

    /** Last position that actually advanced, used to detect a frozen renderer. */
    private var lastAdvanceMs = -1L

    /** Consecutive ticks without progress. */
    private var stallCount = 0

    /**
     * Whether this chapter is meant to be playing right now.
     *
     * `ExoPlayer.isPlaying` cannot answer that. It goes false for a deliberate
     * pause and also for a player that has quietly stopped advancing on its own,
     * and the two need opposite handling - the first is the viewer watching a
     * still frame on purpose, the second is the thing they are reporting when
     * they say the video "se traba".
     *
     * Observed on the device: a chapter reached STATE_READY, showed a frame,
     * and then simply stopped with the picture frozen mid-scene. Not buffering
     * (so the buffering watchdog, which only watches for STATE_BUFFERING, never
     * fired), no error, no spinner, and `checkStall` bailed out on `!isPlaying`
     * precisely when it mattered. Eighteen minutes later it was still frozen
     * on the same frame.
     */
    private var playRequested = false

    // --- Smoothness telemetry ---
    private var decoderInUse: String = "?"
    private var cumulativeDropped = 0
    private var lastDroppedSnapshot = 0
    private var lastDropLogMs = 0L

    /** Number of times playback had to stop and refill. */
    private var rebufferCount = 0

    /** Consecutive stuck-buffering recoveries on the current source. */
    private var bufferingRetries = 0

    /** When the current re-buffer started, for the stuck-buffering guard. */
    private var bufferingSinceMs = 0L

    /** Consecutive failed server extractions for the current episode. */
    private var extractAttempts = 0

    /** Full sweeps through [availableServers] made for the current episode. */
    private var serverPasses = 0

    // --- Scrub state -------------------------------------------------------
    /** Where the user is scrubbing to, in ms. Null when not scrubbing. */
    private var scrubTargetMs: Long? = null

    /** Commits the pending scrub once the user stops moving. */
    private val scrubCommit = Runnable {
        val target = scrubTargetMs ?: return@Runnable
        scrubTargetMs = null
        fsSeek.scrubTarget = null
        fsSeek.hideTooltip()
        seekTo(target)
    }

    /** Last values written to the HUD, so it is only touched when they change. */
    private var hudPositionText: String? = null
    private var hudRemainingText: String? = null
    private var hudServerText: String? = null
    private var hudIsPlaying: Boolean? = null

    /** Whether any bytes have arrived since the last prepare. */
    private var bytesFlowing = false

    /** Background downloader that fills the cache ahead of playback. */
    private var prefetcher: StreamPrefetcher? = null

    private val handler = Handler(Looper.getMainLooper())

    /** Whether this activity is the one holding the screen awake. See onCreate. */
    private var keepScreenOn = false

    private var providerDialog: android.app.Dialog? = null
    private var epDialog: android.app.Dialog? = null

    /** The chapter strip under the video. See [showChapterStrip]. */
    private lateinit var fsChapterStrip: LinearLayout
    private lateinit var fsChapterScroll: HorizontalScrollView
    private lateinit var fsChapterRow: LinearLayout

    /**
     * The strip's own heading, "Episodios".
     *
     * Held rather than looked up each time because its measured height is what
     * decides how tall the carousel's peek band is. See [peekBandPx].
     */
    private lateinit var fsEpisodesHeader: android.widget.TextView
    private var chapterStripOpen = false

    /**
     * True while the focus is inside the carousel rather than on the controls.
     *
     * This is the whole of the difference between the two layouts the reference
     * has, and it is why the strip needs two placements. See [setStripFocused].
     */
    private var stripFocused = false

    /**
     * Where the control row's top edge was, in root coordinates, at the moment
     * the strip took the focus.
     *
     * The strip is translated onto this line so it takes exactly the controls'
     * place, rather than onto an estimated fraction of the screen that only
     * happens to be right on one panel size.
     */
    private var stripHomeTop = 0

    /** The full chapter list and where the rendered window starts in it. */
    private var stripEpisodes: List<Episode> = emptyList()
    private var stripAnchor = 0

    private val fsHideRunnable = Runnable {
        // A hide can already be queued at the moment the chapter strip or a
        // settings panel opens. Honouring it then would fade out the very thing
        // the viewer is reading or choosing from.
        if (chapterStripOpen || openPanel != Panel.NONE) return@Runnable
        // Cancel any in-flight animation first. A hide interrupted by a show
        // would otherwise keep running and take the chrome down again moments
        // after it had been brought back, which reads as the remote doing nothing.
        fsControls.animate().cancel()
        fsControls.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                // INVISIBLE, not GONE: the chrome must stay measured, or the
                // next reveal comes back with stale zero-sized children.
                fsControls.visibility = View.INVISIBLE
                // The status pill goes with the chrome. It sits inside it and is
                // only ever shown alongside it, so leaving it behind is what put
                // "Guardado en favoritos" over the picture with no controls left
                // on screen to explain it or dismiss it.
                hideFsStatus()
                hideSystemUi()
            }
            .start()
    }

    private val audioFocusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .build()
    }

    /**
     * Runs inside the embed page. Returns two parts separated by a newline:
     * candidate stream URLs, then a flag telling us whether the page's own
     * <video> is actually playing. The flag is what lets us tell "working
     * embed" apart from "embed that loaded but will never play", which used to
     * leave the viewer staring at a black screen forever.
     */
    private val JS_CAPTURE = """
        (function(){
          var out = [];
          function add(u){ if(u && typeof u === 'string' && u && u.indexOf('blob:') !== 0 && out.indexOf(u) === -1) out.push(u); }
          var v = document.querySelector('video');
          if (v) {
            add(v.currentSrc || v.src || v.getAttribute('src') || '');
          }
          var src = document.querySelector('source[src]');
          if (src) { add(src.src || src.getAttribute('src') || ''); }

          if (typeof jwplayer === 'function') {
            try {
              var pl = jwplayer().getPlaylistItem();
              if (pl && pl.sources) {
                for (var i = 0; i < pl.sources.length; i++) {
                  var s = pl.sources[i];
                  add(s.file || s); if (s.hls) add(s.hls);
                }
              }
            } catch(e){}
          }
          if (typeof videojs !== 'undefined') {
            try {
              var vj = videojs('video') || videojs();
              if (vj && vj.currentSrc) add(vj.currentSrc());
            } catch(e){}
          }

          // The modern embeds build their player over MSE, so the <video> element
          // only ever carries a blob: URL and every real playlist URL lives in the
          // JavaScript that fetched it. Reading performance entries is what turns
          // those providers from "loaded but no stream" into something ExoPlayer
          // can open directly, which is what the seek bar and prefetch need.
          try {
            var entries = performance.getEntriesByType ? performance.getEntriesByType('resource') : [];
            for (var j = 0; j < entries.length; j++) {
              var n = entries[j].name || '';
              if (n.indexOf('.m3u8') !== -1 || n.indexOf('.m3u') !== -1 || n.indexOf('.urlset') !== -1 ||
                  n.indexOf('.mp4') !== -1 || n.indexOf('master.txt') !== -1 || n.indexOf('index.txt') !== -1) {
                add(n);
              }
            }
          } catch(e){}

          // Last resort: anything on the page that looks like a playlist.
          if (!out.length) {
            try {
              var html = document.documentElement.innerHTML;
              var re = /https?:\/\/[^"'<>\\\s)]+?\.(?:m3u8|m3u|mp4)[^"'<>\\\s)]*/g;
              var m;
              while ((m = re.exec(html)) !== null) { add(m[0]); if (out.length > 3) break; }
            } catch(e){}
          }

          var playing = '0';
          try {
            if (v) {
              // A readyState of 2 with the clock moving is the honest test. These
              // embeds set readyState early while still buffering, so it used to
              // report "playing" for a player that then never rendered a frame.
              var hasData = v.readyState >= 3 && v.currentTime > 0.3;
              playing = (hasData && !v.paused) ? '1' : '0';
            }
          } catch(e){}
          return out.join('|') + '\n' + playing;
        })()
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Cleared again in onDestroy. A window flag set in onCreate outlives the
        // activity's own scope from the window manager's point of view, and this
        // one was never cleared, so the television was told to keep the screen
        // alive for as long as the process lived: the picture stayed on behind the
        // launcher, and on a box with a panel timeout that means a viewer walking
        // away returns to a dark room and a TV that never blanks.
        keepScreenOn = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestAudioFocus()

        setContentView(R.layout.activity_player)

        originalUrl = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }
        // Applied on entry too, not just on navigation, so a deep link or a
        // stale intent cannot bypass it.
        originalUrl = canonicalEpisodeUrl(originalUrl)
        val rawTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Reproduciendo"
        prevUrl = intent.getStringExtra(EXTRA_PREV)
        nextUrl = intent.getStringExtra(EXTRA_NEXT)
        epThumb = intent.getStringExtra(EXTRA_THUMB) ?: ""

        parseTitleAndEpisode(rawTitle)
        computeNavUrlsIfMissing()

        // Pick up where the viewer left off, so a closed app or a crash never
        // costs them their place in the episode.
        Prefs.prunePositions()
        resumePositionMs = Prefs.savedPosition(originalUrl, -1L)
        if (resumePositionMs > 0L) {
            Log.i(TAG, "Resuming at ${formatTime(resumePositionMs)}")
        }

        initViews()
        // Metrics need the real viewport, which is not known until the view has
        // been measured. Re-applying on a size change is also what keeps the HUD
        // correct if the TV switches output resolution while the app is open.
        findViewById<View>(R.id.root).addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int,
                ol: Int, ot: Int, or: Int, ob: Int,
            ) {
                val m = TvMetrics.from(v, resources.configuration.fontScale)
                if (tv?.key != m.key) applyHudMetrics(v)
            }
        })
        fetchSeriesDetailInBackground()
        loadEpisode(originalUrl)
    }

    private fun extractSlugAndEpisode(url: String): Pair<String, Int> {
        val match = Regex("""/ver/([^/]+)/(\d+)""").find(url)
        if (match != null) {
            val slug = match.groupValues[1]
            val ep = match.groupValues[2].toIntOrNull() ?: 1
            return Pair(slug, ep)
        }
        val donghuaMatch = Regex("""/donghua/([^/]+)""").find(url)
        if (donghuaMatch != null) {
            return Pair(donghuaMatch.groupValues[1], 1)
        }
        return Pair("", 1)
    }

    private fun parseTitleAndEpisode(rawTitle: String) {
        val (slug, num) = extractSlugAndEpisode(originalUrl)
        if (slug.isNotBlank()) {
            seriesSlug = slug
            epNumber = num
        }
        // The feed titles come in two shapes, "Serie - Episodio 7" and
        // "Serie Episodio 7". Only the first was handled, so the second showed
        // the episode number twice: once in the title and once in the subtitle.
        seriesTitle = when {
            rawTitle.contains(" - Episodio", ignoreCase = true) ->
                rawTitle.substringBefore(" - Episodio").trim()
            rawTitle.contains(" - ", ignoreCase = true) ->
                rawTitle.substringBefore(" - ").trim()
            else -> TRAILING_EPISODE.replace(rawTitle.trim(), "").trim()
        }
        if (seriesTitle.isBlank()) seriesTitle = rawTitle.trim()
    }

    private fun computeNavUrlsIfMissing() {
        val (slug, num) = extractSlugAndEpisode(originalUrl)
        if (slug.isBlank() || num <= 0) return
        seriesSlug = slug
        epNumber = num
        if (prevUrl == null && num > 1) {
            prevUrl = "/ver/$slug/${num - 1}"
        }
        if (nextUrl == null) {
            nextUrl = "/ver/$slug/${num + 1}"
        }
    }

    /**
     * The "next" button is deliberately always left enabled.
     *
     * The series listing cannot be used to decide this: Renegade Immortal lists
     * a complete-looking 1-159 while episode 160 genuinely exists and is only
     * linked from the home feed. Hiding next would block real content, and
     * walking past the end is now handled precisely instead, by detecting the
     * 404 on the episode page and stepping back. See [handleMissingEpisode].
     */
    private fun refreshNextAvailability() {
        val eps = seriesDetail?.episodes
        if (eps.isNullOrEmpty()) return
        // Only ever fills a missing href, e.g. from the chapter list.
        if (nextUrl == null) {
            eps.firstOrNull { it.number == epNumber + 1 }?.let { nextUrl = it.url }
        }
    }

    /**
     * Runs when the chapter has nothing to play at all.
     *
     * Two situations land here and they are indistinguishable from the page
     * alone: a chapter past the end of a series, and one the site has not
     * published yet. The site answers both with a well formed page that simply
     * contains no providers; it never returns a 404 body.
     *
     * This used to navigate back to the previous chapter on its own. During an
     * auto-advance that meant finishing a chapter, discovering the next one does
     * not exist, and being silently returned to the chapter that had just played
     * to the end, restarting from zero. It looked exactly like a rewind.
     *
     * Now nothing navigates by itself. The reason is stated, and the viewer
     * decides. Silently starting a chapter nobody asked for is never the right
     * recovery, however convenient it seems.
     */
    private fun handleMissingEpisode() {
        Log.w(TAG, "Episode $epNumber has no providers (auto=$arrivedByAutoAdvance)")
        showFsSpinner(false)

        // Always the error box, never a silent navigation. The player has already
        // released the previous chapter by this point, so the alternative would be
        // a black screen with a status line on it. The box is also the only state
        // here that offers a way out without guessing what to press.
        showErrorBox(
            if (arrivedByAutoAdvance) "Fin de la serie" else "El Capítulo $epNumber no está disponible",
            if (arrivedByAutoAdvance) {
                "No hay más capítulos publicados de esta serie. " +
                    "Pulsa ATRÁS para volver a la serie."
            } else {
                "Puede que el capítulo aún no se haya publicado. " +
                    "Pulsa ATRÁS para volver o elige otro capítulo."
            },
            title = "Capítulo no disponible",
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initViews() {
        fsWebView = findViewById(R.id.fsWebView)
        fsWebView.setBackgroundColor(Color.BLACK)
        setupWebViewSettings(fsWebView)

        fsWebView.webViewClient = object : WebViewClient() {

            /**
             * Catches the stream request itself.
             *
             * This is the one that matters for the newer embeds. They do not put
             * a `<video src>` in the markup: they call `fetch()` from JavaScript
             * once a proof-of-work check has cleared, and `onLoadResource` is not
             * called for XHR or `fetch` at all. Watching only that callback is why
             * an embed like "Fm" loaded, ran its checks, and was then declared
             * broken for never producing a stream. The player was watching the
             * wrong channel.
             *
             * The response is left untouched: this only reads the URL.
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                request?.url?.toString()?.let { noteStreamUrl(it) }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectCleanEmbedCss(fsWebView)
                hideSpinner()
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                url?.let { noteStreamUrl(it) }
            }
        }

        fsVideo = findViewById(R.id.fsVideo)
        fsVideo.useController = false
        fsVideo.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) showFsControls()
            false
        }

        fsControls = findViewById(R.id.fsControls)
        fsSpinner = findViewById(R.id.fsSpinner)
        // The chrome starts as INVISIBLE with alpha 0, so it is measured once at
        // creation and every later reveal is just an alpha change.
        fsControls.alpha = 0f
        fsStatus = findViewById(R.id.fsStatus)
        fsTitle = findViewById(R.id.fsTitle)
        fsSubTitle = findViewById(R.id.fsSubTitle)
        fsServerTag = findViewById(R.id.fsServerTag)
        fsCurrentTime = findViewById(R.id.fsCurrentTime)
        fsTotalTime = findViewById(R.id.fsTotalTime)
        fsSeek = findViewById(R.id.fsSeek)
        // The play/pause glyph, built into the action row rather than declared
        // here: it is one of the row's buttons, so it is sized and focused with
        // the rest of them. See buildActionRows.
        fsTopBar = findViewById(R.id.topBar)
        fsBottomBar = findViewById(R.id.bottomBar)
        fsActionsLeft = findViewById(R.id.fsActionsLeft)
        fsActionsRight = findViewById(R.id.fsActionsRight)
        fsPills = findViewById(R.id.fsPills)
        fsSettingsPanel = findViewById(R.id.fsSettingsPanel)
        fsSettingsRow = findViewById(R.id.fsSettingsRow)
        fsSettingsTitle = findViewById(R.id.fsSettingsTitle)
        fsSettingsHelp = findViewById(R.id.fsSettingsHelp)
        fsChapterStrip = findViewById(R.id.fsChapterStrip)
        fsChapterScroll = findViewById(R.id.fsChapterScroll)
        fsChapterRow = findViewById(R.id.fsChapterRow)
        fsEpisodesHeader = findViewById(R.id.fsEpisodesHeader)
        fsErrorBox = findViewById(R.id.fsErrorBox)
        fsErrorTitle = findViewById(R.id.fsErrorTitle)
        fsErrorMsg = findViewById(R.id.fsErrorMsg)
        fsErrorRetry = findViewById(R.id.fsErrorRetry)
        fsErrorServers = findViewById(R.id.fsErrorServers)

        // The action buttons are built in code rather than declared, because
        // their size comes from TvMetrics and the whole row has to be weighted
        // so it cannot overflow on a narrow viewport.
        buildActionRows()

        fsErrorRetry.setOnClickListener {
            hideErrorBox()
            loadEpisode(originalUrl)
        }
        fsErrorServers.setOnClickListener {
            hideErrorBox()
            toggleProviderMenu()
        }

        // Focus is claimed on touch as well as by the D-pad, so a tap on the bar
        // is a no-op for playback rather than a seek. Seeking is only ever done
        // from the D-pad, where a preview can be shown first.
        fsSeek.setOnClickListener { /* handled in dispatchKeyEvent */ }

        updateHeaderUI()
    }

    /**
     * Builds the glyph row and the pill row at the bottom of the HUD.
     *
     * Follows the Amazon Prime Video TV player, which was measured rather than
     * guessed. That player has:
     *
     *  - a left-aligned row of glyphs with no permanent labels, where a label
     *    appears under a button only while it holds focus;
     *  - a separator splitting the row into transport and settings, so the two
     *    groups read as different kinds of control;
     *  - play/pause as the first glyph rather than a button in the middle of
     *    the picture;
     *  - the two remaining actions promoted into labelled pills above the scrub
     *    bar, which is where the reference puts "In scene" and "Cast".
     *
     * Safe to call again: the previous focus is restored afterwards, so a resize
     * does not dump the viewer back at the start of the row.
     */
    private fun buildActionRows() {
        val previouslyFocused = currentFocus
        fsActionsLeft.removeAllViews()
        fsActionsRight.removeAllViews()
        fsPills.removeAllViews()

        fsPlay = addAction(fsActionsLeft, R.drawable.ic_play_o, "Reproducir", "Reproducir o pausar") {
            toggleFsPlay()
        }
        // Everything else the reference's row carries and this one does not need
        // has been taken out: the two 10s skips, the episodes list, subtitles and
        // audio.
        //
        // The skips went because the scrub bar already does that job and does it
        // better, and two more glyphs in a row of five is two more glyphs to walk
        // past on the way to the last one. The episodes list went because it is
        // on screen already, peeking from the bottom of the player and one press
        // of the down arrow away, so a button that only opened it was a shortcut
        // to something visible. Subtitles and audio went on the same reasoning:
        // they are adjustments, not transport, and this row is transport.
        //
        // What is left is what actually moves the episode - play, the chapter
        // either side, the server to get it from - plus the one thing that does
        // not move it and belongs on the chapter all the same.
        fsPrev = addAction(fsActionsRight, R.drawable.ic_skip_prev_o, "Anterior", "Capítulo anterior") {
            prevUrl?.let { navigateTo(it) }
        }
        fsNext = addAction(fsActionsRight, R.drawable.ic_skip_next_o, "Siguiente", "Capítulo siguiente") {
            nextUrl?.let { navigateTo(it) }
        }
        fsProviders = addAction(fsActionsRight, R.drawable.ic_globe_o, "Servidores", "Cambiar servidor de video") {
            toggleProviderMenu()
        }
        // Saving the chapter, from the chapter. Everything else on this row moves
        // the viewer through the episode or changes where it comes from; this is
        // the only one that acts on the episode itself, and it had no button
        // anywhere - the star lives on the detail page, which is two screens away
        // from the only moment anyone actually wants to press it.
        //
        // It goes last, after the servers glyph rather than next to the transport
        // controls, because it is not transport. Grouping it with play/prev/next
        // would say "this moves the video" when what it does is remember it.
        fsFavorite = addAction(fsActionsRight, R.drawable.ic_heart_o, "Favorito", "Guardar el título en favoritos") {
            toggleFavorite()
        }
        syncFavoriteGlyph(readFavoriteState())

        // The gap that makes the divider read as a divider. Applied to the
        // second cluster rather than as padding inside the first, so the two
        // groups stay the same width and the row keeps one rhythm.
        (fsActionsRight.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)
            ?.let {
                it.leftMargin = tv?.u(32f) ?: 64
                fsActionsRight.layoutParams = it
            }

        // Rebuilding removes the focused view, which would otherwise leave the
        // chrome with nothing focused and every key looking broken.
        previouslyFocused?.takeIf { it.isAttachedToWindow }?.requestFocus()
    }

    /**
     * One glyph in the action row, with a label that only exists while focused.
     *
     * The label is always in the layout and always takes its space; only its
     * visibility changes. Reserving the row is deliberate: a label that appeared
     * on focus would push its neighbours sideways, and the row is the thing the
     * viewer is aiming at, so it must not move under them.
     */
    /**
     * One glyph in the action row, with a label that only exists while focused.
     *
     * Built the way the Amazon reference draws it, and the shape matters:
     *
     *  - the white circle is the *glyph's* background, not the button's. As the
     *    button's background it became a 96x118 white ellipse that swallowed
     *    the icons either side of it and swallowed its own label with them.
     *  - the label sits below the circle, on the video, not on top of it. It is
     *    the only thing that says what pressing the glyph will do, so it is
     *    drawn every time one is focused rather than as a permanent caption.
     *  - the glyph inverts to dark on focus, because a white glyph on a white
     *    circle is not a focus state, it is a hole.
     */
    private fun addAction(
        row: LinearLayout,
        iconRes: Int,
        label: String,
        description: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val m = tv
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = description
            // A click focusable on a TV can be triggered by the remote's OK
            // press as well, and this row must never react to that: a dead
            // action is better than one that opens a dialog when the viewer
            // only asked to move the focus.
            setOnClickListener { onClick() }
        }

        // 40 and 72, not the 28 and 48 this used to be. Both measured off the
        // Amazon player at 1920x1080: the focus disc is 72px across and the
        // glyph inside it is 40px. At 48/28 the disc barely cleared its own
        // glyph, so instead of a white ring around a dark icon the row showed a
        // white blob with a dark hole in it.
        val glyphSize = m?.u(40f) ?: (40 * resources.displayMetrics.density).toInt()
        val discSize = m?.u(72f) ?: (72 * resources.displayMetrics.density).toInt()

        val disc = FrameLayout(this)
        val glyph = android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(GLYPH_IDLE)
            layoutParams = FrameLayout.LayoutParams(glyphSize, glyphSize, android.view.Gravity.CENTER)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        disc.addView(glyph)
        // The label is allowed to be wider than the button. A state selector
        // cannot be used for the disc, because the disc is not the focusable
        // view: the button is. Driving it from the focus callback is what puts
        // the white circle behind the right glyph.
        container.clipChildren = false
        // No weight on the disc. In a vertical LinearLayout the height is only a
        // base and the weight hands the child the leftover space, so a 72px disc
        // in a 162px button came out as a 72x132 ellipse. That was the focus: a
        // white lozenge with a glyph floating in it, instead of a disc.
        container.addView(
            disc,
            LinearLayout.LayoutParams(discSize, discSize).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        )

        val caption = TextView(this).apply {
            text = label
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextPx(m?.textPx(20f)) ?: 20f
            setTextColor(0xFFE6ECF2.toInt())
            visibility = android.view.View.GONE
            // Wrap content, not match parent: the reference's labels are phrases
            // ("Start over", "Next episode"), and clamping them to a 96px button
            // turned every one of them into "Retrocede...".
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = m?.u(12f) ?: 0
                // Widened past the disc on both sides so a phrase fits without
                // the button having to be as wide as the text, which would push
                // its neighbours along.
                val overhang = ((m?.u(240f) ?: 480) - discSize) / 2
                leftMargin = -overhang
                rightMargin = -overhang
            }
        }
        container.addView(caption)

        row.addView(container)
        if (m != null) layoutAction(container, m)
        container.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            caption.visibility = if (hasFocus) android.view.View.VISIBLE else android.view.View.GONE
            glyph.setColorFilter(if (hasFocus) GLYPH_FOCUSED else GLYPH_IDLE)
            disc.setBackgroundResource(if (hasFocus) R.drawable.bg_hud_focus_disc else 0)
            // No scale: the reference does not grow the row as focus moves, and
            // growing it is what made the buttons shuffle along under the viewer.
        }
        return container
    }

    /**
     * Applies the metrics to a rebuilt action button.
     *
     * Separated from [addAction] because [applyHudMetrics] rebuilds the row when
     * the screen size changes, and the buttons have to be re-laid-out as well as
     * recreated.
     *
     * The button is a square focus target with the row's own pitch as its width,
     * so the glyphs sit at the spacing measured off the reference: a 28px glyph
     * on a 96px pitch.
     */
    private fun layoutAction(container: LinearLayout, m: TvMetrics) {
        val pitch = m.u(96f)
        // The button is taller than the glyph it holds, because the label that
        // appears underneath on focus is part of the control, not an overlay.
        // Sizing it to the glyph alone is what clipped the label into the last
        // twenty pixels of the screen.
        val target = m.u(72f) + m.u(TvMetrics.FOCUS_LABEL_ROOM)
        container.layoutParams = LinearLayout.LayoutParams(pitch, target).apply {
            val gap = m.u(8f)
            marginStart = gap
            marginEnd = gap
        }
        (container.getChildAt(1) as? TextView)?.apply {
            setTextPx(m.textPx(20f))
            (layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = m.u(12f)
                layoutParams = it
            }
        }
        // The disc is a FrameLayout holding the glyph, so the button is taller
        // than the glyph by exactly the disc and the label.
        (container.getChildAt(0) as? FrameLayout)?.let { disc ->
            disc.layoutParams = LinearLayout.LayoutParams(
                m.u(72f), m.u(72f)
            ).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
            (disc.getChildAt(0) as? android.widget.ImageView)?.let { img ->
                val size = m.u(40f)
                img.layoutParams = FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER)
            }
        }
    }

    /**
     * The two secondary actions, as labelled pills above the scrub bar.
     *
     * Sits where the reference puts "In scene" and "Cast". Its only real job is
     * to give the row below it somewhere to end: without it, the four glyphs
     * after the separator have to be justified across the full width, which
     * scattered them from the left edge to the right one.
     */
    private fun buildPillRow() {
        val m = tv ?: return
        fsPills.removeAllViews()
        addPill(m, R.drawable.ic_info_o, "Episodio $epNumber", "Información del capítulo") {
            showFsStatus("$seriesTitle · Episodio $epNumber")
        }
    }

    /** One labelled pill. See [buildPillRow]. */
    private fun addPill(m: TvMetrics, iconRes: Int, label: String, description: String, onClick: () -> Unit) {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_hud_pill)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = description
            setOnClickListener { onClick() }
            setPadding(m.u(24f), m.u(10f), m.u(24f), m.u(10f))
        }
        pill.addView(android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(m.u(20f), m.u(20f)).apply {
                marginEnd = m.u(10f)
            }
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        })
        pill.addView(TextView(this).apply {
            text = label
            setTextPx(m.textPx(20f))
            setTextColor(0xFFE6ECF2.toInt())
            includeFontPadding = false
        })
        fsPills.addView(
            pill,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = m.u(16f) },
        )
    }

    /**
     * Applies [TvMetrics] to every fixed size in the HUD, and wires the focus
     * graph in code. The XML cannot do this: the values depend on the real
     * viewport, and a `nextFocus` chain written in XML assumes a geometry that
     * only holds on one screen.
     */
    private fun applyHudMetrics(root: View) {
        val m = TvMetrics.from(root, resources.configuration.fontScale)
        tv = m

        // The exit button sits in the action-safe band and is the first thing
        // the up-arrow reaches, so leaving the player is always one press away
        // from wherever focus happens to be.
        fsExit = findViewById(R.id.fsExit)
        fsExit.setOnClickListener { finish() }
        fsExit.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            // The exit shares the glyph row's language: the filled white circle
            // from the same background, and nothing at rest. The focus grow is
            // deliberately absent, because it would make the row reflow.
            fsExit.alpha = if (hasFocus) 1f else 0.7f
        }

        val padH = m.safeStart
        val padTop = m.safeTop
        val padBottom = m.safeBottom

        // Titles clear the wider title-safe margin, the control row uses the
        // tighter action-safe one so it can use the full width.
        fsTopBar.setPadding(padH, padTop, padH, 0)
        // The bottom margin is generous on purpose. A focused glyph grows a
        // label underneath itself, and at the old margin that label was being
        // drawn into the last twenty pixels of the screen and clipped. The
        // reference leaves more room still, for the same reason.
        //
        // The last step of that is the chapter strip's peek. The carousel comes
        // up from the bottom edge while these controls are still on screen, and
        // the band it occupies is reserved here rather than borrowed at the last
        // moment. Borrowing it is what made the header two pixels tall: the
        // strip had nowhere to go but over the top of the row.
        //
        // The reservation is re-applied once the header has been measured, since
        // that is what decides how tall the band is. See [reservePeekBand].
        reservePeekBand(provisional = true)
        fsChapterRow.post { reservePeekBand(provisional = false) }

        // The divider between the transport cluster and the rest, as tall as the
        // glyph it separates. A pixel scan of the reference puts its rule at two
        // pixels wide by twenty-two, which is exactly the height of its own
        // twenty-two pixel glyphs. This is the same rule against this row's
        // 40px glyphs; the XML holds the width and the centring bias.
        findViewById<View>(R.id.fsActionsSep).layoutParams =
            findViewById<View>(R.id.fsActionsSep).layoutParams.apply {
                height = m.u(40f)
            }

        val exitSize = m.u(48f)
        fsExit.layoutParams = fsExit.layoutParams.apply {
            width = exitSize
            height = exitSize
        }
        (fsExit.getChildAt(0) as? android.widget.ImageView)?.let { img ->
            val icon = m.u(22f)
            img.layoutParams = android.widget.LinearLayout.LayoutParams(icon, icon)
        }
        val exitMargin = m.actionTop
        (fsExit.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)
            ?.let {
                it.rightMargin = m.actionEnd
                it.topMargin = exitMargin
                fsExit.layoutParams = it
            }

        fsStatus.setTextPx(m.textPx(17f))
        fsStatus.setPadding(m.u(26f), m.u(12f), m.u(26f), m.u(12f))

        // Title and subtitle, at the sizes measured off the Amazon reference.
        fsTitle.setTextPx(m.textPx(40f))
        fsSubTitle.setTextPx(m.textPx(24f))
        fsServerTag.setTextPx(m.textPx(15f))
        // Breathing room inside the tag. It had none at all, so the text sat on
        // its own 1px border and the box read as a mistake: two words in a frame
        // the width of the words, with the border cutting through the descenders.
        // The padding is generous enough that the longest name and the longest
        // quality both sit comfortably, and vertical so that the box is a bar
        // rather than a line of type with a rule round it.
        fsServerTag.setPadding(m.u(14f), m.u(7f), m.u(14f), m.u(7f))
        (fsServerTag.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)
            ?.let {
                // The gap to the subtitle above. Fourteen was not enough to read
                // as a separate line, because the subtitle's own line height
                // already accounts for most of it.
                it.topMargin = m.u(16f)
                // The tag hangs off the title block's start edge, which is the edge
                // of the block and not the edge of its text: without the margin it
                // was pinned to x=0 and sat half off the screen.
                it.leftMargin = padH
                fsServerTag.layoutParams = it
            }
        fsCurrentTime.setTextPx(m.textPx(20f))
        fsTotalTime.setTextPx(m.textPx(20f))
        fsErrorTitle.setTextPx(m.textPx(28f))
        fsErrorMsg.setTextPx(m.textPx(18f))

        // The play/pause glyph is sized with the rest of the action row, in
        // layoutAction. There is no centre button to size any more.
        fsSeek.applyMetrics(m, dark = true)
        // Rebuilt rather than only in initViews, so a change of screen size
        // restyles the action buttons too. initViews runs before the metrics
        // exist, so without this the actions kept fallback density sizes.
        if (::fsActionsLeft.isInitialized) {
            buildActionRows()
            buildPillRow()
        }
        // The carousel is inset on the left only. The strip runs off the right
        // edge on purpose, the way the reference's does: a row of cards that
        // stops short of the edge looks like a finished list of five, and a
        // fading or bleeding row is what says there are nineteen more.
        //
        // The left inset is the title-safe one so the cards line up with the
        // header above them, which is what the reference does: its header starts
        // at x98 and its first card at x96, the two pixels being the card's own
        // edge. They used to differ by 31 here and the band looked crooked.
        //
        // The vertical padding is room for the focus ring, which is what stops
        // a focused card at either end being clipped by its own bounds. The
        // bottom padding is the only thing keeping the chapter labels off the
        // last row of pixels, since the carousel is pinned to the bottom edge.
        val stripPadV = m.u(6f)
        val stripPadBottom = m.u(24f)
        fsChapterScroll.setPadding(m.safeStart, stripPadV, 0, stripPadBottom)
        fsChapterScroll.clipToPadding = false
        // The settings panel carries an inset like everything else, and it is
        // the title-safe one rather than the tighter action-safe: the reference
        // indents a panel to 5% while its control row sits at 3.5%, and the
        // difference is obvious the moment a panel opens and the row it
        // replaced disappears.
        fsSettingsPanel.setPadding(
            m.safeStart,
            m.u(TvMetrics.SPACE_MD),
            m.safeEnd,
            m.safeBottom,
        )
        // The header is a sibling of the scroll view, not a child of it, so it
        // does not inherit that padding and has to be given the same inset.
        //
        // The header's own inset. It is the title-safe one, not the action-safe
        // one the cards use: a pixel scan of the reference puts the left of its
        // "Episodes" at x98 and the left of the first card at x96, which is the
        // same 98 once the card's own edge is counted.
        //
        // No bottom margin: the gap between the header and the cards belongs to
        // one of them, not both. It was 12dp here on top of the scroll view's own
        // margin, and 44px of the peek band went on the space between a caption
        // and the thing it captions.
        (fsEpisodesHeader.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.leftMargin = m.safeStart
            it.bottomMargin = 0
            fsEpisodesHeader.layoutParams = it
        }
        applyFocusGraph()
    }

    /**
     * Records the focus order for the HUD.
     *
     * The chain is one loop across both action clusters, so left and right walk
     * the whole row instead of stopping at the seam, and it is enforced in
     * [dispatchKeyEvent] via [focusNeighbour] because `View` has no setters for
     * the `nextFocus*` attributes.
     *
     * This also fixes an inconsistency from the old XML: the -10s button dropped
     * focus to "servers" while the +10s button dropped it to "chapters", so the
     * same gesture did different things depending on which button you were on.
     */
    private fun applyFocusGraph() {
        // One loop across the whole row, so left and right walk the transport
        // controls into the navigation ones and back again instead of stopping
        // at the separator. The separator is a divider, not a wall.
        actionChain.clear()
        actionChain.addAll(listOf(fsPlay, fsPrev, fsNext, fsProviders, fsFavorite))
    }

    /**
     * Focus neighbours, resolved by identity rather than by resource id.
     *
     * `View` exposes `nextFocusLeft` and friends only as XML attributes, so there
     * is no setter. Rather than fight that, the chain is declared here and
     * enforced in [dispatchKeyEvent], which already has to decide what a
     * direction key means. That turned out to be the better place for it anyway:
     * the seam between the two action clusters is then handled deliberately
     * instead of by geometric search, which is what made the old row feel
     * unpredictable.
     *
     * @return the view that should receive focus for a horizontal step, or null
     *         if the caller should fall back to the default search.
     */
    private fun focusNeighbour(current: View?, forward: Boolean): View? {
        val chain = actionChain
        if (chain.isEmpty()) return null
        val index = chain.indexOf(current)
        if (index < 0) return null

        // Skip a disabled button rather than stopping on it, otherwise the row
        // becomes a dead end at the end of a finished series.
        var next = index
        repeat(chain.size) {
            next = if (forward) {
                if (next == chain.size - 1) 0 else next + 1
            } else {
                if (next == 0) chain.size - 1 else next - 1
            }
            if (chain[next].isEnabled) return chain[next]
        }
        return null
    }

    /**
     * The card the favourites list should hold for this title.
     *
     * Favourites are per *series*, not per chapter. Saving the chapter put
     * "Throne of Seal - Episodio 135" into the list as if it were a show, so the
     * Recopiladas screen filled up with near-duplicate entries that each opened a
     * single episode, and the series page and the player disagreed about whether
     * something was already saved.
     *
     * The identity is the `/donghua/{slug}` path, exactly the key the series page
     * uses, so a title saved from here shows the filled heart there too. The
     * poster falls back to the chapter artwork only when the series detail has
     * not arrived yet.
     */
    private fun seriesFavoriteCard(): MediaCard? {
        val slug = seriesSlug.ifBlank { extractSlugAndEpisode(originalUrl).first }
        if (slug.isBlank()) return null
        val path = "/donghua/$slug"
        val existing = Prefs.favorites().firstOrNull { it.url == path }
        val poster = seriesDetail?.poster?.takeIf { it.isNotBlank() }
            ?: existing?.thumb?.takeIf { it.isNotBlank() }
            ?: epThumb
        val title = seriesDetail?.title?.takeIf { it.isNotBlank() }
            ?: seriesTitle.takeIf { it.isNotBlank() }
            ?: existing?.title
            ?: return null
        return MediaCard(title = title, thumb = poster, url = path)
    }

    /** Whether the series on screen is already in the favourites list. */
    private fun readFavoriteState(): Boolean {
        val card = seriesFavoriteCard() ?: return false
        return Prefs.isFavorite(card.url)
    }

    /**
     * Draws the favourite button in the state that is actually true.
     *
     * Two states and two shapes, not two colours: the row tints every glyph white
     * when idle and dark when focused, so a tint on the artwork has nothing left
     * to say. Filled means saved. A button whose only feedback is a line of text
     * that appears and then fades is a button nobody is sure they pressed.
     */
    private fun syncFavoriteGlyph(saved: Boolean) {
        val button = fsFavorite ?: return
        val glyph = (button.getChildAt(0) as? FrameLayout)
            ?.getChildAt(0) as? android.widget.ImageView ?: return
        val caption = button.getChildAt(1) as? TextView
        glyph.setImageResource(if (saved) R.drawable.ic_heart_filled_o else R.drawable.ic_heart_o)
        caption?.text = if (saved) "En favoritos" else "Favorito"
        button.contentDescription =
            if (saved) "Quitar el título de favoritos" else "Guardar el título en favoritos"
    }

    /**
     * Adds or removes the *series* from the favourites list.
     *
     * Toggles rather than only adding, because a viewer who finds a star already
     * lit has plainly decided this title is in the list, and pressing it again to
     * take it out is the gesture they are already making.
     */
    private fun toggleFavorite() {
        val card = seriesFavoriteCard() ?: return
        val wasSaved = Prefs.isFavorite(card.url)
        Prefs.toggleFavorite(card)
        syncFavoriteGlyph(!wasSaved)
        showFsStatus(
            if (wasSaved) "${card.title} quitado de favoritos"
            else "${card.title} guardado en favoritos"
        )
    }

    /** The bottom action row, left to right. Populated by [buildActionRows]. */
    private val actionChain = mutableListOf<View>()

    /**
     * Whether the chrome is meant to be on screen, decided the moment a key
     * changes it rather than when the fade finishes.
     *
     * Routing on `fsControls.visibility` instead was a trap with a 220ms window
     * in it: back hides the chrome and starts fading it, and a second back
     * pressed inside that window still saw a visible chrome, so it hid it again
     * instead of leaving. Two quick presses on the remote is the gesture for
     * "I want out of here", and it has to work on the first try.
     */
    private var chromeOnScreen = false

    /** Throttles the once-a-second quality probe of a web embed. */
    private var lastEmbedProbeMs = 0L

    private fun updateHeaderUI() {
        fsTitle.text = seriesTitle
        fsSubTitle.text = "Episodio $epNumber · Full HD · Audio Original · Sub Español"
        // No invented quality. This used to say 1080p whatever was playing, and
        // the real number is written by updateStats once there is a frame to
        // measure. A tag that is wrong is worse than a tag that admits it does
        // not know yet.
        fsServerTag.text = "${currentServer?.name ?: "Buscando"} · cargando"

        // Dim the episode navigation rather than removing it, so the row does
        // not reflow when an episode becomes unreachable.
        setActionEnabled(fsPrev, prevUrl != null)
        setActionEnabled(fsNext, nextUrl != null)

        if (Prefs.historyEnabled) {
            Prefs.addHistory(
                MediaCard(
                    title = "$seriesTitle - Episodio $epNumber",
                    thumb = epThumb,
                    url = originalUrl,
                )
            )
        }

        // The heart belongs to the series, so it is re-read whenever the chapter
        // changes: `seriesTitle` and the poster both arrive with the series
        // detail, and until they do the card falls back to the chapter artwork.
        syncFavoriteGlyph(readFavoriteState())
    }

    private fun fetchSeriesDetailInBackground(onLoaded: ((SeriesDetail) -> Unit)? = null) {
        val (slug, _) = extractSlugAndEpisode(originalUrl)
        val targetSlug = if (slug.isNotBlank()) slug else seriesSlug
        if (targetSlug.isBlank()) return

        detailJob?.cancel()
        detailJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val detail = Api.fetchDetail("/donghua/$targetSlug")
                withContext(Dispatchers.Main) {
                    seriesDetail = detail
                    if (seriesTitle.isEmpty() && detail.title.isNotBlank()) {
                        seriesTitle = detail.title
                    }
                    refreshNextAvailability()
                    updateHeaderUI()
                    // The chapter listing usually arrives well after the chrome
                    // has already come up, so the strip that should be peeking
                    // from the bottom edge could not be built before this. Now
                    // that there is something to put in it, put it there.
                    if (fsControls.visibility == View.VISIBLE && onLoaded == null) {
                        showStripIfEpisodesKnown()
                    }
                    onLoaded?.invoke(detail)
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchSeriesDetail error: ${e.message}")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewSettings(wv: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = DEFAULT_USER_AGENT
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    }

    private fun injectCleanEmbedCss(wv: WebView) {
        val css = """
            body, html {
                margin: 0 !important;
                padding: 0 !important;
                width: 100% !important;
                height: 100% !important;
                overflow: hidden !important;
                background-color: #000 !important;
            }
            video {
                width: 100vw !important;
                height: 100vh !important;
                object-fit: contain !important;
            }
            .jwplayer, .video-js {
                width: 100vw !important;
                height: 100vh !important;
            }
        """.trimIndent().replace("\n", " ")

        val js = """
            (function() {
                var style = document.createElement('style');
                style.type = 'text/css';
                style.innerHTML = '$css';
                document.head.appendChild(style);
            })();
        """.trimIndent()
        wv.evaluateJavascript(js, null)
    }

    private fun loadEpisode(url: String) {
        showFsSpinner(true)
        showFsStatus("Cargando servidores...", 0L)
        // A fresh extraction is a fresh set of chances: the hosts rotate, and the
        // page a few seconds later often offers something the previous pass did not.
        embedAttempted.clear()
        serverExtractJob?.cancel()
        serverExtractJob = CoroutineScope(Dispatchers.Main).launch {
            // A 404 means the chapter is not there, which is a different problem
            // from the site being slow or a provider list being empty, so it is
            // separated out before the generic retry logic can hide it.
            var missing = false
            val servers = try {
                Api.fetchEpisodeServers(url)
            } catch (e: HttpStatusException) {
                if (e.code == 404) {
                    missing = true
                    Log.w(TAG, "Episode page returned 404: ${e.path}")
                } else {
                    Log.w(TAG, "fetchEpisodeServers HTTP ${e.code}: ${e.message}")
                }
                emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "fetchEpisodeServers error: ${e.message}")
                emptyList()
            }

            if (missing) {
                handleMissingEpisode()
                return@launch
            }

            availableServers = servers
            if (servers.isNotEmpty()) {
                hideErrorBox()
                Log.i(
                    TAG,
                    "Servers for ep$epNumber: ${servers.size} (${servers.count { it.isDirect }} direct) " +
                        servers.joinToString { it.name }
                )
                playServer(pickInitialServer(servers))
            } else {
                // The extractor can be beaten by a slow or inconsistent page, so
                // give it a couple of tries before telling the viewer it failed.
                if (extractAttempts < MAX_EXTRACT_ATTEMPTS) {
                    extractAttempts++
                    showFsStatus("Buscando servidores… (intento $extractAttempts)", 0L)
                    handler.postDelayed({ loadEpisode(url) }, 1500L)
                } else if (serverPasses < MAX_SERVER_PASSES) {
                    // The page loaded but listed nothing this time. That is
                    // usually a partial or cached response rather than a
                    // genuinely missing chapter, so re-read before giving up.
                    serverPasses++
                    showFsStatus("Buscando servidores…", 0L)
                    handler.postDelayed({ loadEpisode(url) }, 2000L)
                } else {
                    // Every retry found nothing. This is the page-empty case,
                    // so the viewer is moved somewhere watchable if possible.
                    handleMissingEpisode()
                }
            }
        }
    }

    /** Replaces a dead black screen with an explanation and a way out. */
    private fun showErrorBox(
        message: String,
        hint: String,
        title: String = "No se pudo reproducir este capítulo",
    ) {
        if (!::fsErrorBox.isInitialized) return
        fsActive = false
        embedIsActive = false
        hideSpinner()
        releaseExo()
        suspendWebView()
        // The status line still said "Cargando servidores..." over the failure
        // screen, because the loading message outlived the failure that replaced
        // it. Both are progress indicators, so they must not be on screen at once.
        hideFsStatus()
        fsVideo.visibility = View.GONE
        fsControls.visibility = View.GONE
        // The heading used to be hardcoded, so a missing-server problem and a
        // dead-server problem were reported with the same words.
        fsErrorTitle.text = title
        fsErrorMsg.text = hint
        fsErrorBox.visibility = View.VISIBLE
        fsErrorBox.requestFocus()
        fsErrorRetry.requestFocus()
        Log.w(TAG, "Showing error state: $title / $message")
    }

    private fun hideErrorBox() {
        if (!::fsErrorBox.isInitialized) return
        if (fsErrorBox.visibility != View.VISIBLE) return
        fsErrorBox.visibility = View.GONE
        extractAttempts = 0
    }

    /**
     * Which server to open an episode on.
     *
     * The remembered one wins outright, whatever kind it is. It used to be
     * demoted: a native stream was always preferred and the memory only chose
     * between natives. That quietly threw away the only quality control there
     * is. There is no setting behind this, and no master playlist to pick a
     * rendition out of either: the direct server hands over a single 720p
     * rendition and the web ones hand over whatever the page serves, which on
     * this site is 1080p. So the server *is* the quality, and every chapter
     * after the one where the viewer chose the 1080p server went back to 720p
     * without ever being asked.
     *
     * What the remembered choice must never do is resurrect a server that is
     * known to be dead, or one the probe already failed to resolve. That is why
     * it is looked up inside the usable list rather than the full one.
     *
     * With nothing remembered, a resolved playlist wins: it plays through
     * ExoPlayer straight away, and it can be scrubbed and prefetched.
     */
    private fun pickInitialServer(servers: List<ServerItem>): ServerItem {
        // Only ever choose among servers that are actually alive. A remembered
        // choice is honoured only while it is still usable, otherwise the viewer
        // is sent straight back to the server that already failed. Falling back
        // to `servers.first()` unconditionally is what put a dead host in front
        // every single time.
        val usable = servers.filter { it.isPlayable }.ifEmpty { servers }
        val remembered = usable.firstOrNull { it.id == Prefs.lastProvider }
        if (remembered != null) return remembered

        // A resolved playlist is worth far more than a page that needs a browser:
        // it plays through ExoPlayer straight away, it can be scrubbed and
        // prefetched, and it does not depend on a third-party page staying up.
        return usable.firstOrNull { it.isDirect }
            ?: usable.firstOrNull()
            ?: servers.first()
    }

    private fun playServer(server: ServerItem) {
        currentServer = server
        Prefs.lastProvider = server.id
        hideErrorBox()
        updateHeaderUI()

        // A new attempt deserves a clean slate for the per-source counters, or a
        // retry budget already spent on a previous server silently kills this one
        // before it has had a fair try.
        networkRetries = 0
        bufferingRetries = 0
        // Per-source, like the counters above: a permanent verdict about the
        // previous server says nothing about this one.
        lastErrorWasDeterministic = false
        sourceHasEverPlayed = false
        stopBackgroundPrefetch()

        showFsStatus("Conectando con ${server.name}…", 0L)
        Log.i(TAG, "playServer: ${server.name} (id=${server.id}, directHls=${server.directHlsUrl != null})")

        // Carry the resume point across server changes, otherwise a dead server
        // sends the viewer back to the beginning of the episode.
        exo?.let { if (firstFrameSeen) resumePositionMs = it.currentPosition }

        if (!server.directHlsUrl.isNullOrBlank()) {
            playNative(server.directHlsUrl, server.referer, resumePositionMs)
        } else if (!server.embedUrl.isNullOrBlank()) {
            playEmbed(server.embedUrl, server.referer)
        } else {
            showFsStatus("Servidor no disponible, probando siguiente…")
            autoNextServer()
        }
    }

    private fun playNative(
        url: String,
        referer: String = Api.BASE + "/",
        startPositionMs: Long = 0L,
    ) {
        // Last line of defence against an orphaned player. See [isDestroyed].
        if (isDestroyed) {
            Log.w(TAG, "playNative ignored: activity is destroyed (url=${url.take(80)})")
            return
        }
        Log.i(TAG, "playNative: $url (resume=${startPositionMs}ms)")
        // Reset per source: carrying a duration from a previous episode would
        // make the scrub bar land in the wrong place for a few seconds.
        knownDurationMs = 0L
        scrubTargetMs = null
        hudPositionText = null
        hudRemainingText = null
        fsActive = true
        // The embed's own <video>/<audio> is silenced here, not left to
        // onPause(). Promoting an embed to native playback used to clear this
        // flag without touching the WebView, after which every later guard of
        // the form `if (embedIsActive) muteEmbeddedMedia()` decided there was
        // nothing to silence - the page was still playing, and the chapter went
        // on being audible after the viewer had left. Unconditional here: if no
        // embed is loaded this is a no-op, and if one is, it must stop.
        muteEmbeddedMedia()
        embedIsActive = false
        fsExoOk = false
        firstFrameSeen = false
        cumulativeDropped = 0
        lastDroppedSnapshot = 0
        lastDropLogMs = 0L
        rebufferCount = 0
        bufferingSinceMs = 0L
        bytesFlowing = false
        handler.removeCallbacks(bufferingWatchdogRunnable)
        lastAdvanceMs = -1L
        stallCount = 0
        lastExoUrl = url
        currentReferer = referer.ifBlank { PlaybackEngine.DEFAULT_REFERER }

        hideSystemUi()

        // Park the WebView properly. Merely hiding it is not enough: an embed page
        // with a live <video> keeps a decoder and a hardware layer alive, which
        // steals CPU from ExoPlayer and shows up as dropped frames.
        suspendWebView()
        fsVideo.visibility = View.VISIBLE
        fsVideo.useController = false

        // Remember where we were so a retry or a server switch can come back here.
        exo?.let { if (firstFrameSeen) resumePositionMs = it.currentPosition }
        if (startPositionMs > 0L) resumePositionMs = startPositionMs

        // A previous attempt on this episode left a prefetcher behind holding a
        // thread pool and a segment window for a URL we are no longer playing.
        stopBackgroundPrefetch()
        releaseExo()
        val player = createExo(referer)
        player.addListener(exoListener)
        player.addAnalyticsListener(analyticsListener)
        exo = player
        fsVideo.player = player

        showFsSpinner(true)

        try {
            val isHls = looksLikeHls(url)

            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .apply {
                    if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8)
                }
                .build()

            val source = buildMediaSource(mediaItem, isHls)
            player.setMediaSource(source)
            player.prepare()
            if (resumePositionMs > 0L) {
                player.seekTo(resumePositionMs)
            }
            player.playWhenReady = true
            // Every entry into native playback is a request to play, including a
            // retry, a server switch and auto-advance. See [playRequested].
            playRequested = true

            handler.removeCallbacks(nativeTimeoutRunnable)
            handler.postDelayed(nativeTimeoutRunnable, FIRST_FRAME_TIMEOUT_MS)

            handler.removeCallbacks(fsTimeRunnable)
            handler.postDelayed(fsTimeRunnable, 500)

            showFsControls(2500)
        } catch (e: Exception) {
            Log.w(TAG, "playNative error: $e")
            handlePlaybackError(
                PlaybackException(
                    "No se pudo preparar el stream",
                    e,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                )
            )
        }
    }

    /**
     * Builds the media source. HLS gets an explicit factory so a rolling or
     * slightly malformed playlist cannot abort playback, and chunkless
     * preparation is disabled because it is a common source of startup stalls.
     */
    @OptIn(UnstableApi::class)
    private fun buildMediaSource(mediaItem: MediaItem, isHls: Boolean): MediaSource {
        val dataSource = PlaybackEngine.dataSourceFactory(this, currentReferer)
        if (isHls) {
            return HlsMediaSource.Factory(dataSource)
                // Retry a failed segment instead of killing the whole stream, and
                // skip only what is truly unrecoverable.
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy())
                .setAllowChunklessPreparation(false)
                .setUseSessionKeys(true)
                .createMediaSource(mediaItem)
        }
        return DefaultMediaSourceFactory(dataSource).createMediaSource(mediaItem)
    }

    private fun looksLikeHls(url: String): Boolean {
        val low = url.lowercase()
        val path = low.substringBefore("?")
        return path.endsWith(".m3u8") ||
            path.endsWith(".m3u") ||
            low.contains("redirector.php") ||
            low.contains("/hls") ||
            low.contains("master") ||
            low.contains("playlist")
    }

    /**
     * Stops the WebView dead: no timers, no page, no video element. This is the
     * single biggest win for smoothness when a fallback embed was in use.
     */
    private fun suspendWebView() {
        try {
            muteEmbeddedMedia(hard = true)
            fsWebView.onPause()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                fsWebView.pauseTimers()
            }
            fsWebView.stopLoading()
            fsWebView.loadUrl("about:blank")
            fsWebView.clearHistory()
            fsWebView.clearCache(false)
            fsWebView.visibility = View.GONE
        } catch (e: Exception) {
            Log.w(TAG, "suspendWebView: ${e.message}")
        }
    }

    /**
     * Silences any `<video>` / `<audio>` running inside an embed page.
     *
     * `WebView.onPause()` only pauses the renderer. It does **not** stop a media
     * element that is already playing, so leaving a chapter that was being played
     * through an embed left the audio going with no picture anywhere: the chapter
     * was still audible after the viewer had gone back to the series screen. That
     * is what this fixes, by pausing and muting every media element on the page
     * before the view is torn down.
     */
    private fun muteEmbeddedMedia(hard: Boolean = false) {
        try {
            fsWebView.evaluateJavascript(
                if (hard) SILENCE_MEDIA_JS else SOFT_SILENCE_MEDIA_JS,
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "muteEmbeddedMedia: ${e.message}")
        }
    }

    /** The counterpart of [muteEmbeddedMedia] for coming back from an interruption. */
    private fun restoreEmbeddedMedia() {
        try {
            fsWebView.evaluateJavascript(
                "(function(){try{" +
                    "var go=function(doc){" +
                    "if(!doc){return;}" +
                    "var m=doc.querySelectorAll('video,audio');" +
                    "for(var i=0;i<m.length;i++){" +
                    // Only lift the mute where the source is still attached;
                    // anything detached belongs to a chapter we are no longer on.
                    "try{if(m[i].hasAttribute('src')||m[i].currentSrc){" +
                    "m[i].muted=false;m[i].volume=1;var pr=m[i].play();" +
                    "if(pr&&pr.catch){pr.catch(function(){});}}" +
                    "}catch(e){}" +
                    "}" +
                    "var f=doc.querySelectorAll('iframe');" +
                    "for(var j=0;j<f.length;j++){" +
                    "try{go(f[j].contentDocument);}catch(e){}" +
                    "}" +
                    "};" +
                    "go(document);" +
                    "}catch(e){}})();",
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "restoreEmbeddedMedia: ${e.message}")
        }
    }

    private fun resumeWebView() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                fsWebView.resumeTimers()
            }
            fsWebView.onResume()
        } catch (e: Exception) {
            Log.w(TAG, "resumeWebView: ${e.message}")
        }
    }

    private fun playEmbed(embedUrl: String, referer: String) {
        Log.i(TAG, "playEmbed: $embedUrl")
        // Nothing native is going to play this chapter, so the prefetcher has no
        // reason to keep running. It was reachable from fallbackToEmbedOrNextServer
        // without going through playServer, which left three daemon threads
        // pulling a URL nobody is watching - stealing the bandwidth the WebView
        // needed and writing into the same LRU cache whose sizing exists to keep
        // the next segment alive.
        stopBackgroundPrefetch()
        // Recorded before anything else so a failure loop cannot come back here.
        embedAttempted.add(embedUrl)
        fsActive = true
        embedIsActive = true
        embedIsPlaying = false
        currentReferer = referer.ifBlank { PlaybackEngine.DEFAULT_REFERER }

        // Keep the place so promoting this embed to ExoPlayer, or a later
        // server switch, does not jump back to the start.
        exo?.let { if (firstFrameSeen) resumePositionMs = it.currentPosition }
        releaseExo()
        firstFrameSeen = false
        fsExoOk = false
        networkRetries = 0

        hideSystemUi()
        fsVideo.visibility = View.GONE
        fsWebView.visibility = View.VISIBLE
        resumeWebView()
        showFsSpinner(true)

        val headers = mutableMapOf("Referer" to referer)
        fsWebView.loadUrl(embedUrl, headers)
        showFsControls(2500)

        handler.removeCallbacks(embedPollRunnable)
        handler.removeCallbacks(embedWatchdogRunnable)
        embedStartedAtMs = System.currentTimeMillis()
        handler.postDelayed(embedPollRunnable, 2000)
        armEmbedWatchdog()
    }

    /**
     * Polls the embed page for a direct stream. Once we have one we hand playback
     * to ExoPlayer, which is far smoother than the WebView's own video tag.
     * Polling stops as soon as that handover happens so we never keep poking
     * JavaScript on the critical path.
     *
     * It also acts as the embed watchdog: if the page never yields a stream and
     * its own <video> never starts, we move on to the next server instead of
     * leaving a black screen up indefinitely.
     */
    private val embedPollRunnable = object : Runnable {
        override fun run() {
            if (!fsActive || !embedIsActive || fsWebView.visibility != View.VISIBLE) return

            fsWebView.evaluateJavascript(JS_CAPTURE) { res ->
                val raw = res?.trim()?.trim('"') ?: return@evaluateJavascript
                if (raw.isEmpty() || raw == "null") return@evaluateJavascript

                // Undo the JSON string escaping of the newline we injected.
                val parts = raw.replace("\\n", "\n").split("\n")
                val urlPart = parts.getOrNull(0).orEmpty()
                val playing = parts.getOrNull(1) == "1"
                if (playing) {
                    embedIsPlaying = true
                    hideSpinner()
                    handler.removeCallbacks(embedWatchdogRunnable)
                }

                val urls = urlPart.split("|").filter { it.isNotBlank() && it != "null" }
                // Prefer an HLS playlist, but not only by file extension: several
                // of these CDNs serve the manifest as `master.txt` or under a
                // `.urlset` directory, and a test for "m3u8" alone discarded a
                // perfectly good stream and left the embed playing in the WebView
                // with no seek bar and no prefetch.
                val preferred = urls.firstOrNull { isStreamUrl(it) }
                    ?: urls.firstOrNull()
                if (preferred != null && preferred != lastExoUrl) {
                    Log.i(TAG, "Embed promoted to ExoPlayer: $preferred")
                    handler.removeCallbacks(this)
                    // The embed is doing the work here, so credit it as the
                    // working server for this title before handing over.
                    currentServer?.let { Extractor.noteServerWorked(seriesSlug, it.id) }
                    playNative(preferred, currentReferer, resumePositionMs)
                }
            }

            handler.postDelayed(this, 2500)

            // Arm the watchdog against the moment the embed started, not against
            // this poll, otherwise every poll would push the deadline further out
            // and it would never fire.
            armEmbedWatchdog()
        }
    }

    /** (Re)arms the embed watchdog so it fires EMBED_TIMEOUT_MS after the start. */
    private fun armEmbedWatchdog() {
        handler.removeCallbacks(embedWatchdogRunnable)
        val elapsed = System.currentTimeMillis() - embedStartedAtMs
        val remaining = (EMBED_TIMEOUT_MS - elapsed).coerceAtLeast(500L)
        handler.postDelayed(embedWatchdogRunnable, remaining)
    }

    /**
     * Fires when an embed has had its chance and still is not playing. Moving to
     * another server is always better than a frozen black screen.
     */
    private val embedWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!fsActive || !embedIsActive) return
            if (embedIsPlaying || fsVideo.visibility == View.VISIBLE) return
            Log.w(TAG, "Embed produced no stream after ${EMBED_TIMEOUT_MS}ms, moving on")
            showFsStatus("Servidor ${currentServer?.name ?: "?"} no respondió, cambiando…", 0L)
            nextServerAfter(this)
        }
    }

    /**
     * Advances to the next server.
     *
     * When the list is exhausted the episode is re-extracted from scratch
     * before anything is declared broken. A provider list is a snapshot of a
     * page served by several different hosts, and it very often looks different
     * a few seconds later: a direct server can appear, or the failing one can
     * have been a transient outage. Giving up on the first pass through the list
     * is what produced the "all servers failed" screen while a working server
     * was one reload away.
     */
    private fun nextServerAfter(source: Runnable?) {
        if (source != null) handler.removeCallbacks(source)
        handler.removeCallbacks(embedPollRunnable)
        handler.removeCallbacks(embedWatchdogRunnable)
        embedIsActive = false
        embedIsPlaying = false
        suspendWebView()

        val idx = availableServers.indexOf(currentServer)
        // Walk forward to the next server that is still alive. Stepping onto one
        // already known to be dead only burns the retry budget that a working
        // server further down the list needed.
        val next = availableServers
            .drop(if (idx >= 0) idx + 1 else 0)
            .firstOrNull { it.isPlayable }
        if (next != null) {
            showFsStatus("Cambiando a ${next.name}…")
            handler.postDelayed({ playServer(next) }, 700)
            return
        }

        // Every server in this pass has been tried. Refresh the list instead of
        // showing a failure, and only give up once the refreshes run out.
        if (serverPasses < MAX_SERVER_PASSES) {
            serverPasses++
            Log.i(TAG, "All servers tried, re-extracting (pass $serverPasses)")
            showFsStatus("Reintentando con otros servidores…")
            extractAttempts = 0
            handler.postDelayed({ loadEpisode(originalUrl) }, 1500)
            return
        }

        // Distinguish "everything we can see is dead" from "one server misbehaved".
        // The first is an upstream outage that no retry will fix, and saying so
        // is far more useful than a generic failure the viewer cannot act on.
        val dead = availableServers.filter { !it.isPlayable }
        if (dead.isNotEmpty() && dead.size == availableServers.size) {
            showErrorBox(
                "Los servidores de video no están disponibles.",
                "Los ${dead.size} servidores de este capítulo están caídos o el " +
                    "video fue eliminado en el origen. No es un problema de la app: " +
                    "puede volver a funcionar más tarde. Prueba otro capítulo.",
                title = "Video no disponible ahora mismo",
            )
            return
        }

        showErrorBox(
            "Todos los servidores de video fallaron para este capítulo.",
            "Se probaron ${availableServers.size} servidores sin éxito. " +
                "Puedes reintentar o elegir otro servidor.",
            title = "No se pudo reproducir este capítulo",
        )
    }

    /**
     * Catches a stream URL that the embed page requested. Only acts while the
     * native player is not already up: hijacking a working stream is what used
     * to make playback restart on its own.
     */
    /**
     * Whether a URL is a media stream that ExoPlayer can open directly.
     *
     * Shared by the request sniffer and the DOM poll so the two cannot disagree
     * about what counts as a stream. That disagreement was the bug: the sniffer
     * recognised `.txt` playlists but the poll only looked for `m3u8`, so a stream
     * the first had already accepted was rejected by the second.
     */
    private fun isStreamUrl(url: String): Boolean {
        val low = url.lowercase()
        val path = low.substringBefore("?")
        return path.endsWith(".m3u8") ||
            path.endsWith(".m3u") ||
            path.endsWith(".txt") ||
            path.endsWith(".mp4") ||
            path.endsWith(".webm") ||
            low.contains("redirector.php") ||
            path.contains("videodelivery.net") ||
            path.contains(".urlset")
    }

    /**
     * Watches the embed's network traffic for the actual media URL, so playback
     * can be handed to ExoPlayer.
     *
     * Two filters here used to throw away real streams:
     *
     *  - `path.contains("/e/")` was meant to skip the embed page itself, but it
     *    also matched the media paths these hosts use, several of which are served
     *    from a `/e/`-style prefix. Every candidate was discarded on arrival.
     *  - `.txt` was not recognised. The HLS playlists on these CDNs are served as
     *    `master.txt` / `index.txt`, so a working stream looked like a text file.
     *
     * Static assets are still filtered, and so is anything that is not media.
     */
    /**
     * Records a stream URL seen in the embed's traffic and promotes it if we are
     * not already playing.
     *
     * Safe to call from ANY thread.
     *
     * `shouldInterceptRequest` is invoked on a WebView worker thread, not the main
     * one. Calling straight through to `playNative` from there touched the view
     * hierarchy off-thread and threw `CalledFromWrongThreadException`, which killed
     * the process and dropped the viewer back at the home screen the moment an
     * embed produced its stream. Everything that touches a view or an ExoPlayer
     * is posted to the main thread; the filtering below is pure string work and
     * safe to leave where it is.
     */
    private fun noteStreamUrl(url: String) {
        if (!isStreamUrl(url)) return
        val low = url.lowercase()
        // Definitely not media. Filtering here keeps the hot path from waking the
        // main thread for every stylesheet and font the page loads.
        if (low.contains("/cdn-cgi/") || low.endsWith(".css") || low.endsWith(".js") ||
            low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") ||
            low.endsWith(".gif") || low.endsWith(".svg") || low.endsWith(".ico") ||
            low.endsWith(".woff") || low.endsWith(".woff2") || low.endsWith(".ttf") ||
            low.endsWith(".map") || low.endsWith(".mpd")
        ) return

        if (currentVideoUrl == url) return
        currentVideoUrl = url
        Log.i(TAG, "Sniffed video URL: $url")

        handler.post {
            // Re-checked on the main thread: between seeing the request and this
            // running, the viewer may have switched servers or gone back.
            if (!embedIsActive) return@post
            if (exo != null && fsVideo.visibility == View.VISIBLE) return@post
            playNative(url, currentServer?.referer ?: PlaybackEngine.DEFAULT_REFERER, resumePositionMs)
        }
    }

    private fun createExo(referer: String): ExoPlayer {
        val http = PlaybackEngine.dataSourceFactory(this, referer)

        // Buffering tuned for a low powered TV box on a good connection.
        // The byte cap is what really matters here: the streams are 1080p at
        // ~3 Mbps, so 40 MB is over 100 s of video, which absorbs WiFi jitter
        // without letting a 2 GB box fill up with buffer.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_AFTER_REBUFFER_MS
            )
            // Honour targetBufferBytes so the download-ahead is actually bounded.
            .setPrioritizeTimeOverSizeThresholds(false)
            .setTargetBufferBytes(MAX_BUFFER_BYTES)
            .setBackBuffer(20_000, true)
            .build()

        // EXTENSION_RENDERER_MODE_OFF keeps playback on the hardware decoders.
        // Letting a software extension win on a 4-core box is a common source of
        // dropped frames, and every one of these streams is plain H.264/AAC.
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl)
            // Start optimistic rather than letting the player open each episode
            // on a pessimistic guess and spend the first few minutes climbing.
            // The estimate is only a starting point; it is re-measured from real
            // downloads within seconds and the player adapts from there.
            .setBandwidthMeter(
                DefaultBandwidthMeter.Builder(this)
                    .setInitialBitrateEstimate(INITIAL_BANDWIDTH_BPS)
                    .build()
            )
            .setSeekParameters(SeekParameters.EXACT)
            // Keep the box's CPU and Wi-Fi alive for long episodes, otherwise the
            // network is put to sleep mid-stream and the video stalls.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ false
            )
            .setHandleAudioBecomingNoisy(false)
            .build()
    }

    /**
     * Bounds how good a rendition the player may pick, without deciding which one.
     *
     * This used to pin the highest pixel count in the group outright, via a
     * [TrackSelectionOverride]. That was a deliberate fix for a real complaint
     * (episodes were opening at 720p because ExoPlayer starts its bandwidth
     * estimate low and never climbed), and it did solve that. But a pin is a
     * promise the player can never take back: an override removes the choice
     * entirely, so when the connection cannot sustain the top rendition the
     * player has nowhere to go. It sits on an empty buffer until the stall
     * recovery gives up, which is exactly the "it freezes and starts loading
     * again" symptom, and it happens on a perfectly good internet because the
     * binding constraint is the TV box's WiFi radio and decoder, not the
     * household connection.
     *
     * A cap gets the original benefit without the downside. Paired with the
     * optimistic initial estimate in [createExo], playback opens on the top
     * rendition on a healthy connection, and quietly drops one rung if the
     * measured throughput says the top one will not hold. Nothing is ever frozen
     * in place waiting for a stream that was never going to arrive.
     *
     * Applied from [Player.Listener.onTracksChanged], so it is re-asserted if the
     * parameters are rebuilt by the audio/subtitle picker. It is idempotent and
     * only touches the video type, so that picker keeps its own overrides.
     */
    private fun applyVideoQualityCap() {
        val e = exo ?: return
        val current = e.trackSelectionParameters
        if (current.maxVideoWidth == MAX_VIDEO_HEIGHT &&
            current.maxVideoHeight == MAX_VIDEO_HEIGHT
        ) {
            return
        }
        try {
            e.trackSelectionParameters = current.buildUpon()
                .clearVideoSizeConstraints()
                .setMaxVideoSize(MAX_VIDEO_HEIGHT, MAX_VIDEO_HEIGHT)
                .build()
            Log.i(TAG, "Video quality capped at ${MAX_VIDEO_HEIGHT}p, adaptive below that")
        } catch (t: Throwable) {
            Log.w(TAG, "could not apply video quality cap", t)
        }
    }

    /**
     * Objective smoothness telemetry. The renderer does not expose dropped frame
     * counts through [Player.Listener], so they are read here. If the decoder
     * starts dropping frames we surface it instead of silently stuttering.
     */
    @OptIn(UnstableApi::class)
    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializationDurationMs: Long,
        ) {
            Log.i(TAG, "Video decoder: $decoderName (init=${initializationDurationMs}ms)")
            decoderInUse = decoderName
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderingTimeUs: Long,
        ) {
            Log.i(TAG, "Analytics: first frame rendered")
        }

        /**
         * Any successful load means the source is alive and delivering bytes, so a
         * stall after this point is worth retrying. No load at all means the
         * source itself is dead.
         */
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            bytesFlowing = true
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            cumulativeDropped += droppedFrames
            if (lastDropLogMs == 0L) lastDropLogMs = eventTime.realtimeMs
            if (eventTime.realtimeMs - lastDropLogMs < 10_000) return

            val delta = cumulativeDropped - lastDroppedSnapshot
            lastDroppedSnapshot = cumulativeDropped
            lastDropLogMs = eventTime.realtimeMs
            Log.w(TAG, "DROPPED +$delta frames in 10s (total=$cumulativeDropped) decoder=$decoderInUse")
        }
    }

    private val exoListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    if (firstFrameSeen) {
                        // Playback has already started, so this is a re-buffer.
                        // The picture is still moving for a moment, and the
                        // segment often comes straight from the disk cache, so
                        // only show the spinner if the wait becomes visible.
                        armSpinner()
                        rebufferCount++
                        Log.w(TAG, "REBUFFER #$rebufferCount at ${lastKnownPositionMs}ms")
                        armBufferingWatchdog()
                    } else {
                        // Initial load: the spinner is already up, showing it
                        // early avoids a black screen before the first frame.
                        showFsSpinner(true)
                    }
                }
                Player.STATE_READY -> {
                    fsExoOk = true
                    networkRetries = 0
                    bufferingRetries = 0
                    bufferingSinceMs = 0L
                    handler.removeCallbacks(bufferingWatchdogRunnable)
                    // Once a frame has been on screen it is safe to take the
                    // spinner down here. Before that we keep it up, because
                    // STATE_READY arrives before the first frame is drawn and
                    // hiding it would flash black.
                    if (firstFrameSeen) hideSpinner()
                    handler.removeCallbacks(nativeTimeoutRunnable)
                    setPlayIcon(exo?.isPlaying == true)
                    Log.i(TAG, "ExoPlayer ready (firstFrame=$firstFrameSeen)")
                }
                Player.STATE_ENDED -> {
                    // A finished chapter is not a freeze. See [playRequested].
                    playRequested = false
                    Log.i(
                        TAG,
                        "ExoPlayer playback ended: ep=$epNumber nextUrl=$nextUrl " +
                            "prevUrl=$prevUrl autoNext=${Prefs.autoNext} fired=$autoNextFired"
                    )
                    hideSpinner()
                    clearSavedPosition()
                    // Latched before anything else can run. Without it, the position
                    // of the finished chapter was written straight back a moment
                    // later, so returning to it replayed the chapter from the start.
                    playbackFinished = true
                    if (Prefs.autoNext && nextUrl != null && !autoNextFired) {
                        autoNextFired = true
                        showFsStatus("Reproduciendo siguiente capítulo…")
                        handler.postDelayed({ navigateTo(nextUrl!!, fromAutoAdvance = true) }, 1500)
                    } else {
                        // Nothing to advance to. Stopping is the only honest
                        // outcome: the alternative plays a chapter the viewer
                        // did not ask for.
                        showFsStatus("Fin del capítulo")
                        showFsControls(6000)
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            setPlayIcon(isPlaying)
        }

        override fun onTracksChanged(tracks: Tracks) {
            applyVideoQualityCap()
        }

        override fun onRenderedFirstFrame() {
            firstFrameSeen = true
            fsExoOk = true
            sourceHasEverPlayed = true
            // Only now is a server known to work. Learning from anything weaker
            // (a resolved URL, a page that loaded) is what put hosts that
            // resolve but cannot be watched at the front of every later chapter.
            currentServer?.let { Extractor.noteServerWorked(seriesSlug, it.id) }
            handler.removeCallbacks(nativeTimeoutRunnable)
            handler.removeCallbacks(embedWatchdogRunnable)
            hideSpinner()
            hideFsStatus()
            updateStats()
            startBackgroundPrefetch()
            Log.i(TAG, "First frame rendered at ${resumePositionMs}ms")
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(
                TAG,
                "ExoPlayer error: ${error.errorCodeName} / ${PlaybackException.getErrorCodeName(error.errorCode)} " +
                    "uri=${error.errorCodeName} msg=${error.message}"
            )
            handler.post { handlePlaybackError(error) }
        }
    }

    /**
     * Decides what to do about a failure.
     *
     * The previous behaviour was to jump to a different server on *any* error.
     * With a good connection that is actively harmful: a single dropped request
     * tore down a working stream and restarted the episode from zero, which looks
     * exactly like "it keeps cutting". Now only genuinely unusable sources are
     * abandoned, and the resume point is carried over.
     */
    private fun handlePlaybackError(error: PlaybackException) {
        if (!fsActive) return
        hideSpinner()
        handler.removeCallbacks(nativeTimeoutRunnable)

        // Classify before acting. A refused or missing manifest answers the same
        // way on every attempt, so it must skip the retry loop; a transport
        // failure is worth trying again.
        lastErrorWasDeterministic = isPermanentError(error)
        if (lastErrorWasDeterministic) {
            Log.w(TAG, "Permanent failure (${error.errorCodeName}), not retrying this source")
        }

        val keepPosition = lastKnownPositionMs
        if (keepPosition > 0L) resumePositionMs = keepPosition

        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            -> scheduleRetry()
            else -> {
                // A 404/403 on a dead link is not worth retrying: try the embed
                // or another server instead.
                if (looksLikeHls(lastExoUrl ?: "")) {
                    Log.i(TAG, "HLS source unusable, falling back")
                    fallbackToEmbedOrNextServer()
                } else {
                    fallbackToEmbedOrNextServer()
                }
            }
        }
    }

    /** Retries the same source with backoff, resuming where it left off. */
    private fun scheduleRetry() {
        // A hard HTTP failure is the same answer every time, so retrying it only
        // delays finding a server that works. A missing file is missing; the four
        // attempts on a deleted chapter cost eighty seconds before the app moved
        // on to a host that could have answered in one. Timeouts are different:
        // those do clear, which is what this path exists for.
        val deterministic = lastErrorWasDeterministic
        if (deterministic || networkRetries >= MAX_NETWORK_RETRIES) {
            Log.w(
                TAG,
                if (deterministic) "Source failed permanently, switching server " +
                    "(retries used: $networkRetries)"
                else "Retry budget exhausted, switching server"
            )
            fallbackToEmbedOrNextServer()
            return
        }
        val delay = RETRY_DELAYS_MS.getOrElse(networkRetries) { RETRY_DELAYS_MS.last() }
        networkRetries++
        showFsStatus("Reintentando… (${networkRetries}/$MAX_NETWORK_RETRIES)")
        handler.postDelayed({
            if (!fsActive) return@postDelayed
            val url = lastExoUrl ?: return@postDelayed
            val referer = currentServer?.referer ?: Api.BASE + "/"
            Log.i(TAG, "Retry #$networkRetries in ${delay}ms from ${resumePositionMs}ms")
            playNative(url, referer, resumePositionMs)
        }, delay)
    }

    /**
     * True when the last failure will not fix itself.
     *
     * A 404 or 403 on the manifest means the file is gone or refused; the same
     * request a second later produces the same status. Timeouts, resets and
     * interrupted reads are transport, and those are worth another try.
     */
    private var lastErrorWasDeterministic = false

    /**
     * Whether a failure is one that cannot fix itself.
     *
     * Only the case where the server has given a final answer: a bad HTTP status
     * on the manifest. 404 and 403 repeat exactly on a second try, so spending four
     * attempts on them only delays reaching a server that works. Connection resets,
     * timeouts and read interruptions are transport, and those genuinely do clear,
     * which is what the retry budget is for.
     */
    private fun isPermanentError(error: PlaybackException): Boolean {
        val msg = (error.message ?: "") + " " + (error.cause?.message ?: "")
        // A bad status is always final: the server has answered.
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            return msg.contains(" 404") || msg.contains(" 403") ||
                msg.contains("404") || msg.contains("403") ||
                msg.contains("Not Found") || msg.contains("Forbidden")
        }
        // No frame after the full window, with the manifest never completing, is
        // how a dead CDN presents. That also arrives as a timeout, which is
        // otherwise the one case worth retrying, so the caller distinguishes it:
        // a source that has already produced a frame is retried, one that never
        // did is not. See [sourceHasEverPlayed].
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return !sourceHasEverPlayed
        }
        return false
    }

    /**
     * Whether this source has ever shown a frame on screen.
     *
     * The distinction that decides whether a timeout is worth retrying: a stream
     * that was playing and then stalled is a transport problem worth another go,
     * while one that never rendered anything is a dead source, and four attempts
     * on a dead source is eighty seconds of the viewer watching nothing.
     */
    private var sourceHasEverPlayed = false

    private fun fallbackToEmbedOrNextServer() {
        val server = currentServer
        if (embedIsActive) {
            // The embed is already what the user is watching; give up quietly.
            showFsStatus("No se pudo reproducir en este servidor", 0L)
            handler.postDelayed({ hideFsStatus() }, 3000)
            return
        }
        // Promote to this server's web player only if it has not already been
        // tried for this episode.
        //
        // Without this guard the failure looped on itself: the direct stream
        // failed, so the code loaded the same embed, the embed failed, and the
        // handler came back to promote the same embed again. The viewer watched
        // one dead source restart three times instead of being offered the next
        // server, which is the whole point of having a list.
        val alreadyTriedEmbed = server != null &&
            embedAttempted.contains(server.embedUrl ?: "")
        if (server?.embedUrl != null &&
            !alreadyTriedEmbed &&
            server.embedUrl != lastExoUrl &&
            !server.embedUrl.contains("redirector.php")
        ) {
            showFsStatus("Cambiando a reproductor del servidor…")
            handler.postDelayed({
                if (fsActive) playEmbed(server.embedUrl, server.referer)
            }, 800)
        } else {
            autoNextServer()
        }
    }

    /**
     * Embed pages already loaded for this episode.
     *
     * Kept per episode rather than per server so a genuine re-attempt after a
     * refresh still works, while a failure loop cannot walk back into the same
     * page. Cleared by [loadEpisode].
     */
    private val embedAttempted = mutableSetOf<String>()

    private fun autoNextServer() {
        nextServerAfter(null)
    }

    /**
     * Fires only when the source never produced a frame. It is a real failure,
     * not a hiccup, so it is treated as a retryable network problem.
     */
    private val nativeTimeoutRunnable = Runnable {
        if (fsActive && !firstFrameSeen && fsVideo.visibility == View.VISIBLE) {
            Log.w(TAG, "No first frame after ${FIRST_FRAME_TIMEOUT_MS}ms")
            handlePlaybackError(
                PlaybackException(
                    "No frames rendered",
                    null,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                )
            )
        }
    }

    /**
     * Cached duration.
     *
     * `Player.duration` is TIME_UNSET until the media is prepared, and on a
     * fresh start it can still be unset on the first ticks. Scrubbing is
     * impossible without a duration, and reading it from a stale value would
     * either do nothing or seek into nonsense, so the UI keeps its own copy
     * taken from the first real value the player reports.
     */
    private var knownDurationMs: Long = 0L

    private val fsTimeRunnable = object : Runnable {
        override fun run() {
            val e = exo
            if (!fsActive || e == null) return

            val p = e.currentPosition
            val d = e.duration
            val b = e.bufferedPosition
            lastKnownPositionMs = p

            if (d > 0 && d != C.TIME_UNSET) knownDurationMs = d

            // Only touch the HUD while it is on screen, and only when a value
            // actually changed. Every setText and every bar update remeasures the
            // bottom row, and doing that twice a second regardless is wasted work
            // on a 4-core box that is also decoding video.
            val duration = knownDurationMs
            if (fsControls.visibility == View.VISIBLE && duration > 0) {
                val shown = scrubTargetMs ?: p
                // The bar owns its own thumb position while scrubbing, so the
                // real playhead is what gets written, not the preview.
                fsSeek.progress = p.toFloat() / duration
                fsSeek.buffered = b.toFloat() / duration

                // The readouts follow the preview while scrubbing, so the time
                // agrees with where the bar is pointing.
                val posText = formatTime(shown)
                if (posText != hudPositionText) {
                    hudPositionText = posText
                    fsCurrentTime.text = posText
                }
                // Time remaining rather than the total: once you know how far in
                // you are, "how long is left" is the useful number.
                val remainText = "−" + formatTime((duration - shown).coerceAtLeast(0))
                if (remainText != hudRemainingText) {
                    hudRemainingText = remainText
                    fsTotalTime.text = remainText
                }
            }
            if (firstFrameSeen && !playbackFinished && p > 0 && p % 5_000 < 600) {
                val total = e.duration
                Prefs.savePosition(originalUrl, p, if (total > 0) total else -1L)
            }
            if (firstFrameSeen) checkStall(e, p)
            // Keep the on-disk window ahead of the playhead.
            if (firstFrameSeen && p > 0) prefetcher?.pump(p)
            updateStats()
            handler.postDelayed(this, 500)
        }
    }

    /**
     * Catches a genuinely frozen picture: the player says it is playing but the
     * clock never moves. This deliberately ignores the buffering state, because
     * after a seek or a resume the buffer legitimately refills for a while and
     * treating that as a freeze produces false alarms. Stuck buffering is
     * handled separately by [bufferingWatchdogRunnable] with its own deadline.
     */
    private fun checkStall(player: ExoPlayer, position: Long) {
        if (player.playbackState != Player.STATE_READY) {
            lastAdvanceMs = position
            stallCount = 0
            return
        }

        // Deliberate pause and silent death both look like `!isPlaying`, so
        // intent decides, not the flag. A viewer who paused is waiting; a player
        // that was asked to play and has not moved the position in fifteen
        // seconds is stuck, and the only honest reading of that is a freeze.
        if (!playRequested) {
            lastAdvanceMs = position
            stallCount = 0
            return
        }

        if (position != lastAdvanceMs) {
            lastAdvanceMs = position
            stallCount = 0
            return
        }
        stallCount++
        if (stallCount >= STALL_WATCHDOG_MS / 500) {
            stallCount = 0
            Log.w(TAG, "Picture not advancing at $position (playing=${player.isPlaying}), recycling")
            // Say something. The previous version recovered silently, so a freeze
            // was indistinguishable from the video simply holding still.
            showFsStatus("La imagen se quedó quieta, reintentando…", 2500L)
            recyclePlayerAt(position)
        }
    }

    /** Re-prepares the same source; cached segments make this near-instant. */
    private fun recyclePlayerAt(position: Long) {
        val url = lastExoUrl ?: return
        resumePositionMs = position
        playNative(url, currentReferer, position)
    }

    /**
     * Arms the stuck-buffering guard.
     *
     * A stalled segment request can hang far longer than the socket timeouts
     * (the loader retries internally), which leaves the picture frozen on a
     * spinner forever. This puts a hard ceiling on how long that may last.
     */
    private fun armBufferingWatchdog() {
        handler.removeCallbacks(bufferingWatchdogRunnable)
        bufferingSinceMs = System.currentTimeMillis()
        handler.postDelayed(bufferingWatchdogRunnable, BUFFERING_TIMEOUT_MS)
    }

    /**
     * Pulls the whole episode into the disk cache in the background.
     *
     * ExoPlayer downloads HLS segments one at a time, and this CDN delivers them
     * at barely faster than real time, so a single slow segment drains the buffer
     * and the picture freezes. Fetching the segments in parallel in the
     * background means the rest of the episode is already on disk before the
     * player gets there, which is what makes playback smooth and stop the
     * periodic "loading again".
     */
    private fun startBackgroundPrefetch() {
        val url = lastExoUrl ?: return
        if (prefetcher != null) {
            Log.i(TAG, "Prefetch already running, leaving it alone")
            return
        }
        if (!looksLikeHls(url)) {
            Log.i(TAG, "Not an HLS stream, skipping prefetch")
            return
        }
        val cache = PlaybackEngine.cacheFor(this)
        if (cache == null) {
            Log.w(TAG, "No cache available, skipping prefetch")
            return
        }
        try {
            val p = StreamPrefetcher(
                PlaybackEngine.prefetchClient,
                cache,
                currentReferer,
                PlaybackEngine.PREFETCH_BUDGET_BYTES
            )
            prefetcher = p
            p.start(CoroutineScope(Dispatchers.Main), url) {
                // Playlist parsed: keep a window of the episode ready from now on.
                p.pump(exo?.currentPosition ?: 0L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "prefetch could not start: ${e.message}")
            prefetcher = null
        }
    }

    private fun stopBackgroundPrefetch() {
        prefetcher?.cancel()
        prefetcher = null
    }

    private val bufferingWatchdogRunnable = object : Runnable {
        override fun run() {
            val e = exo ?: return
            if (e.playbackState != Player.STATE_BUFFERING) return
            val stuckFor = System.currentTimeMillis() - bufferingSinceMs
            Log.w(TAG, "Stuck buffering for ${stuckFor}ms (bytes=$bytesFlowing)")
            recoverFromStall(sourceIsDead = !bytesFlowing)
        }
    }

    /**
     * Restarts the current source, or gives up on it after a few attempts and
     * moves to another server.
     *
     * [sourceIsDead] is set when the stall happened with no bytes moving at all.
     * Re-preparing the same URL then achieves nothing, so the server is dropped
     * straight away instead of burning the retry budget.
     */
    private fun recoverFromStall(sourceIsDead: Boolean = false) {
        handler.removeCallbacks(bufferingWatchdogRunnable)
        if (sourceIsDead || bufferingRetries >= MAX_BUFFERING_RETRIES) {
            Log.w(TAG, "Abandoning source after ${bufferingRetries} retries")
            bufferingRetries = 0
            networkRetries = 0
            fallbackToEmbedOrNextServer()
            return
        }
        bufferingRetries++
        // The counter was already incremented on the line above, so the "+ 1"
        // made every attempt report one higher than the one being made: the
        // first said (2) and the last said (4).
        showFsStatus("Conexión lenta, reintentando… ($bufferingRetries)")
        recyclePlayerAt(lastKnownPositionMs)
    }

    /**
     * Keeps the server pill useful. The buffered duration reported by the player
     * is unreliable on some TV chipsets (it can report absurd values), so the
     * pill shows the real video resolution instead, which is what people
     * actually want to know.
     */
    private fun updateStats() {
        // The web servers are not ExoPlayer, so this has to be a two-way split.
        // It used to be one-way and returned early without an exo, which left the
        // tag showing whatever the header had put there, and the header put
        // "1080p" there every time. So a server that was plainly serving 720p was
        // labelled 1080p, for as long as the chapter lasted.
        if (embedIsActive) {
            probeEmbedQuality()
            return
        }
        val e = exo ?: return
        if (!firstFrameSeen) return
        val size = e.videoSize
        if (size.width <= 0 || size.height <= 0) return
        setServerTag(qualityLabel(size.height))
    }

    /**
     * The text in the quality tag, from a video height in pixels.
     *
     * One place, so the direct servers and the web ones cannot end up with two
     * different spellings of the same thing.
     */
    private fun qualityLabel(heightPx: Int): String = when {
        heightPx >= 2160 -> "4K"
        heightPx >= 1440 -> "1440p"
        heightPx >= 1080 -> "1080p"
        heightPx >= 720 -> "720p"
        heightPx >= 480 -> "480p"
        heightPx > 0 -> "${heightPx}p"
        else -> "cargando"
    }

    /**
     * Writes the tag as "server · quality", and leaves it alone if that is
     * already what it says, because it is written from a per-second probe and
     * setting a TextView's text re-lays out the title block every time.
     */
    private fun setServerTag(quality: String) {
        val name = currentServer?.name ?: return
        val text = "$name · $quality"
        if (fsServerTag.text != text) fsServerTag.text = text
    }

    /**
     * Asks the web page how big its video element actually is.
     *
     * The page knows and the app does not: an embed is a browser playing whatever
     * the site decided to hand it, and there is no track list to read. So the
     * only honest source is the element itself, and `evaluateJavascript` gets at
     * it through the return value rather than through a JavascriptInterface,
     * which would be a channel into an untrusted page.
     *
     * Throttled to once a second: the answer does not change within a chapter
     * and the value is already only a label.
     */
    private fun probeEmbedQuality() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEmbedProbeMs < 1000L) return
        lastEmbedProbeMs = now
        val wv = fsWebView
        if (wv.visibility != View.VISIBLE) return
        wv.evaluateJavascript(
            "(function(){var v=document.querySelector('video');" +
                "return v&&v.videoHeight?String(v.videoHeight):'';})();"
        ) { value ->
            val height = value?.trim()?.trim('"')?.toIntOrNull() ?: return@evaluateJavascript
            setServerTag(qualityLabel(height))
        }
    }

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val m = s / 60
        val h = m / 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m % 60, s % 60)
        } else {
            String.format("%02d:%02d", m, s % 60)
        }
    }

    private fun clearSavedPosition() {
        if (originalUrl.isNotBlank()) Prefs.savePosition(originalUrl, 0L)
    }

    /** Greys an action out without removing it from the focus chain. */
    private fun setActionEnabled(view: View, enabled: Boolean) {
        if (!::fsPrev.isInitialized || view !is android.widget.LinearLayout) return
        view.isEnabled = enabled
        view.isFocusable = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    private fun showFsSpinner(show: Boolean) {
        fsSpinner?.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Shows the loading spinner, but only if the re-buffer outlasts
     * [SPINNER_DELAY_MS]. Many re-buffers resolve in a few hundred milliseconds
     * because the segment is already on disk, and flashing the spinner for those
     * is more distracting than the stutter itself.
     */
    private fun armSpinner() {
        if (fsSpinner?.visibility == View.VISIBLE) return
        handler.removeCallbacks(spinnerShowRunnable)
        handler.postDelayed(spinnerShowRunnable, SPINNER_DELAY_MS)
    }

    /** Takes the spinner down immediately and cancels any pending show. */
    private fun hideSpinner() {
        handler.removeCallbacks(spinnerShowRunnable)
        showFsSpinner(false)
    }

    private val spinnerShowRunnable = Runnable {
        // Only while we are still waiting: a fast recovery will have cancelled it.
        if (exo?.playbackState == Player.STATE_BUFFERING) showFsSpinner(true)
    }

    /**
     * Shows a transient message in the centre pill.
     *
     * The pill schedules its own dismissal. That is the whole point of this
     * function rather than a bare `fsStatus.visibility = VISIBLE`: most of the
     * messages that go through here are acknowledgements with nothing else
     * expected to follow them, and each one used to schedule its own hide
     * separately, or not at all. The favourites message was one of the ones that
     * scheduled none, so "Guardado en favoritos" stayed on the picture for as
     * long as the viewer was watching.
     *
     * @param autoHideMs how long the message stays up. Pass 0 for the few that
     *   describe a persistent state and should not disappear on their own.
     */
    private fun showFsStatus(msg: String, autoHideMs: Long = FS_STATUS_MS) {
        // The pill and the skip buttons both come from a seek, and they used to
        // cancel each other out: seekNative revealed the skips and then showed
        // the pill, which hid them again, so the buttons never appeared. They now
        // coexist, separated by position.
        fsStatus.text = msg
        fsStatus.visibility = View.VISIBLE
        handler.removeCallbacks(hideStatusRunnable)
        if (autoHideMs > 0L) handler.postDelayed(hideStatusRunnable, autoHideMs)
    }

    private fun hideFsStatus() {
        handler.removeCallbacks(hideStatusRunnable)
        fsStatus.visibility = View.GONE
    }

    /**
     * How long the chrome stays up after the last key.
     *
     * Four seconds, which is what this was, is long enough to lose the row
     * between two presses. Reading a label, then looking away to see what it
     * labelled, is a normal thing to do and the row vanishing mid-glance is what
     * makes a control feel broken. The reference holds its chrome for noticeably
     * longer than this, and it holds it for the same reason.
     */
    private fun showFsControls(autoHideDelayMs: Long = AUTO_HIDE_MS) {
        handler.removeCallbacks(fsHideRunnable)
        // A leftover animation would overwrite the alpha set below on its very
        // next frame, so the cancel has to come first.
        fsControls.animate().cancel()
        // Read before the change below: this is the transition where the chrome
        // comes up, as opposed to another key arriving while it is already up.
        val wasHidden = fsControls.visibility != View.VISIBLE
        fsControls.visibility = View.VISIBLE
        fsControls.alpha = 1f
        chromeOnScreen = true
        // The carousel is not something you have to go and fetch. Every time the
        // reference brings the chrome up, pausing or seeking or pressing a key,
        // the strip is already peeking up from the bottom edge, because a viewer
        // who wants the next chapter should not have to find a button first. It
        // is done here rather than in the caller so that holds for every path
        // into the chrome, including a pause from the remote's media key.
        showStripIfEpisodesKnown()
        // Bringing the chrome up has to hand the focus to the transport, because
        // the window will otherwise pick it and its first choice is the close
        // button: the exit is the first focusable view in the layout, above the
        // control row, so a player that opened with the chrome showing would
        // quit on the first press of OK.
        //
        // Only on the way up. Once the chrome is up the focus is the viewer's
        // and this must not move it, least of all when they have walked it to
        // the exit on purpose.
        if (wasHidden) {
            val focus = currentFocus
            if (focus == null || focus === fsExit) actionChain.firstOrNull()?.requestFocus()
        }
        // No auto-hide while the focus is *inside* the strip. A viewer choosing a
        // chapter would have the row fade out from under them mid-decision. The
        // strip merely peeking from the bottom does not suppress the auto-hide,
        // because that is the resting state of the chrome and the reference
        // fades it on the same timer as everything else.
        if (!stripFocused && openPanel == Panel.NONE) handler.postDelayed(fsHideRunnable, autoHideDelayMs)
    }

    /**
     * Puts the strip up whenever there is something to put in it.
     *
     * The guard is the point: the strip appears because the chrome appeared, and
     * not as a side effect of somebody pressing the episodes button. A chapter
     * list that has not arrived yet simply does not open one, and the button
     * still works on its own, because the listing is fetched in the background
     * and usually lands after the chrome is already up.
     */
    private fun showStripIfEpisodesKnown() {
        if (chapterStripOpen || stripFocused) return
        val eps = seriesDetail?.episodes
        if (eps.isNullOrEmpty()) return
        showChapterStrip(eps)
    }

    private fun toggleFsPlay() {
        val e = exo
        if (e == null) {
            // The visible player is the web embed, so the native controls have
            // nothing to act on. Say so instead of silently doing nothing, which
            // read as a broken remote button.
            showFsStatus("Controles no disponibles en este servidor", 1800L)
            return
        }
        if (e.isPlaying) {
            playRequested = false
            e.pause()
            showFsStatus("Pausa", 1200L)
        } else {
            playRequested = true
            e.play()
            showFsStatus("Reproduciendo", 1200L)
        }
    }

    /** Keeps the play/pause glyph in step with the player, without redrawing it. */
    private fun setPlayIcon(isPlaying: Boolean) {
        if (hudIsPlaying == isPlaying) return
        hudIsPlaying = isPlaying
        // The glyph now lives inside the action row, so it is reached by
        // position rather than by id. See buildActionRows.
        if (!::fsPlay.isInitialized) return
        (fsPlay.getChildAt(0) as? android.widget.ImageView)
            ?.setImageResource(if (isPlaying) R.drawable.ic_pause_o else R.drawable.ic_play_o)
    }

    /** Absolute seek, used when a scrub is committed. */
    private fun seekTo(targetMs: Long) {
        val e = exo ?: return
        val dur = e.duration
        val clamped = targetMs.coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE)

        e.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        e.seekTo(clamped)
        handler.removeCallbacks(restoreExactSeek)
        handler.postDelayed(restoreExactSeek, 700)

        lastKnownPositionMs = clamped
        resumePositionMs = clamped
        Prefs.savePosition(originalUrl, clamped, if (dur > 0) dur else -1L)
        // No status pill here: the tooltip already showed the time, and the pill
        // would cover the very controls the viewer may want next. The skip glyphs
        // are permanent in the row now, so there is nothing to reveal either.
    }

    /**
     * Moves the scrub preview one step and schedules the actual seek.
     *
     * Committing on a timer rather than on every press matters on a TV: each
     * seek re-prepares the player, and a stream that is a second behind the
     * playhead turns a held direction key into visible stutter.
     */
    private fun scrubBy(deltaMs: Long) {
        val e = exo ?: return
        // The cached value, not Player.duration: the latter is still unset on the
        // first ticks after a prepare, which is exactly when the viewer is
        // already reaching for the remote.
        val dur = knownDurationMs.takeIf { it > 0 } ?: e.duration
        if (dur <= 0 || dur == C.TIME_UNSET) {
            // Still unknown, usually during the first load. Fall back to a plain
            // skip so the key does something instead of looking dead.
            seekNative(deltaMs)
            return
        }
        val base = scrubTargetMs ?: lastKnownPositionMs
        val target = (base + deltaMs).coerceIn(0, dur)

        scrubTargetMs = target
        fsSeek.scrubTarget = target.toFloat() / dur
        fsSeek.showTooltip(formatTime(target))
        // The readouts are refreshed here rather than waiting for the next tick,
        // so the time changes together with the bar.
        hudPositionText = null
        hudRemainingText = null

        handler.removeCallbacks(scrubCommit)
        handler.postDelayed(scrubCommit, SCRUB_COMMIT_DELAY_MS)
    }

    private fun seekNative(deltaMs: Long) {
        val e = exo ?: return
        val cur = e.currentPosition
        val dur = e.duration
        val target = (cur + deltaMs).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE)
        // The skips are the reason a seek happened, so keep them available for
        // another adjustment rather than letting them time out mid-decision.

        // Snap to the nearest sync frame for a fast, reliable jump, then go back
        // to exact seeking once we land. Staying on CLOSEST_SYNC permanently
        // degrades the rest of playback.
        e.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        e.seekTo(target)
        handler.removeCallbacks(restoreExactSeek)
        handler.postDelayed(restoreExactSeek, 700)

        lastKnownPositionMs = target
        Prefs.savePosition(originalUrl, target, exo?.duration ?: -1L)
        val targetFormatted = formatTime(target)
        showFsStatus(
            if (deltaMs > 0) "+10s  ($targetFormatted)" else "-10s  ($targetFormatted)",
            1200L,
        )
    }

    private val restoreExactSeek = Runnable {
        exo?.setSeekParameters(SeekParameters.EXACT)
    }

    private val hideStatusRunnable = Runnable { hideFsStatus() }

    /**
     * Builds a dialog sized for this television.
     *
     * The window size has to be applied *after* [android.app.Dialog.show],
     * because show() creates the decor view and resets the window layout params.
     * Setting it beforehand, as this screen used to, silently did nothing and
     * left the dialog sized to its content.
     *
     * The size is a percentage of the screen rather than a dp value, because a
     * fixed width is wrong at both ends of the range: at 460dp and density 2 it
     * was 48% of a 1080p set but only 24% of a 4K one, and on a 640dp-wide set
     * it ran off the edge entirely.
     */
    private fun buildTvDialog(
        content: View,
        widthFraction: Float,
        maxHeightFraction: Float,
    ): android.app.Dialog {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.show()
        val m = tv ?: TvMetrics.from(content, resources.configuration.fontScale)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Height wraps the content, up to [maxHeightFraction] of the screen.
            //
            // A fixed height is what made this dialog feel like a modal office
            // window: 0.62 of 1080 is 670px, and the five rows it holds are 380 of
            // them. The other 290 were a void, and a void in the middle of a TV
            // UI reads as a mistake even when nothing is wrong.
            setLayout(
                (m.widthPx * widthFraction).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(android.view.Gravity.CENTER)
        }
        return dialog
    }

    /**
     * Caps a wrap-content dialog at a share of the screen.
     *
     * Called after the rows are in, because only then is there a measured height
     * to cap. Without it a long server list would grow a dialog taller than the
     * panel and push its own first row off the top.
     */
    private fun capDialogHeight(dialog: android.app.Dialog, maxHeightFraction: Float) {
        val m = tv ?: return
        val root = dialog.findViewById<View>(android.R.id.content) ?: return
        root.post {
            val wanted = root.height
            val cap = (m.heightPx * maxHeightFraction).toInt()
            if (wanted > cap && cap > 0) {
                dialog.window?.setLayout(
                    dialog.window?.attributes?.width ?: (m.widthPx * 0.34f).toInt(),
                    cap
                )
            }
        }
    }

    /**
     * Applies [TvMetrics] to a dialog's text and padding, so a dialog is scaled by
     * the same rules as the HUD. Left at dp it was right on a 1080p set and far
     * too small on a 4K one.
     */
    private fun styleDialogText(dialog: android.app.Dialog, root: View) {
        val m = tv ?: return
        fun walk(v: View) {
            when (v) {
                is TextView -> v.setTextPx(m.textPx(16f))
                is android.widget.ImageView -> {
                    val size = m.u(26f)
                    v.layoutParams = v.layoutParams.apply {
                        width = size
                        height = size
                    }
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        root.setPadding(m.u(30f), m.u(24f), m.u(30f), m.u(24f))
        dialog.window?.setLayout(
            (m.widthPx * 0.52f).toInt(),
            (m.heightPx * 0.80f).toInt()
        )
    }

    /**
     * The server picker.
     *
     * Rebuilt small, in the same language as the audio and subtitles panels,
     * because that is what it is: a short list of choices, and the rest of the
     * player already has a perfectly good way of drawing one. It used to be a
     * 922x670 box with orange capsules in it, which is a different design
     * language dropped into the middle of this one and read as a dialog from
     * another app.
     *
     * Compact means four things together, not just a smaller box:
     *  - it wraps its rows instead of claiming 62% of the screen and leaving the
     *    rest empty;
     *  - the rows are one line of 16px text on a 44px plate, not 54px;
     *  - the title is one short line, and the explanatory subtitle is gone: the
     *    count is in the title and the hint was never read;
     *  - the focus is the white pill the panels use, so the same remote gesture
     *    looks the same everywhere in the player.
     */
    private fun toggleProviderMenu() {
        if (providerDialog?.isShowing == true) {
            providerDialog?.dismiss()
            providerDialog = null
            return
        }
        if (availableServers.isEmpty()) {
            showFsStatus("No hay servidores adicionales", 0L)
            return
        }

        val m = tv ?: TvMetrics.from(findViewById(R.id.root), resources.configuration.fontScale)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dialog_tv)
            setPadding(m.u(18f), m.u(14f), m.u(18f), m.u(14f))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        val title = TextView(this).apply {
            text = "Servidor · ${availableServers.size}"
            setTextColor(LABEL_BRIGHT)
            setTextPx(m.textPx(16f))
            setTypeface(null, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            setPadding(m.u(10f), 0, m.u(10f), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = m.u(10f) }
        }
        layout.addView(title)

        val dialog = buildTvDialog(layout, PROVIDER_DIALOG_WIDTH, PROVIDER_DIALOG_HEIGHT)

        var initialFocusBtn: View? = null

        availableServers.forEach { s ->
            val isCurrent = s.id == currentServer?.id
            // Labelling the native server matters: it is the one that plays
            // through ExoPlayer instead of a heavy web page, and that is exactly
            // the distinction that used to be invisible when it failed.
            //
            // Built as one string and then coloured in runs, so the name, the
            // "Directo" tag and the active marker can each be their own colour
            // without painting the whole row.
            val label = buildString {
                if (isCurrent) append("●  ")
                append(s.name)
                // Say plainly that a server is known to be dead, instead of
                // letting the viewer discover it by waiting through a failure.
                s.unavailableReason?.let { append("   ($it)") }
                if (s.isDirect) append("   Directo")
            }
            val row = TextView(this).apply {
                text = label
                if (isCurrent) {
                    // The dot and the trailing tag are the two runs that say
                    // "this one is playing". The tag only exists on a direct
                    // server, so it is only coloured when there is one there.
                    colour(0, 3, ORANGE, bold = true)
                    if (s.isDirect) colour(label.length - 7, label.length, ORANGE, bold = true)
                } else if (s.isDirect) {
                    colour(label.length - 7, label.length, LABEL_MUTED)
                }
                setTextColor(if (s.unavailableReason != null) LABEL_MUTED else LABEL_BRIGHT)
                setTextPx(m.textPx(16f))
                includeFontPadding = false
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_server_row)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding(m.u(14f), 0, m.u(14f), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    m.u(44f)
                ).apply { setMargins(0, m.u(2f), 0, m.u(2f)) }
                onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    (v as TextView).setTextColor(
                        if (hasFocus) PILL_SELECTED_TEXT else LABEL_BRIGHT
                    )
                }

                setOnClickListener {
                    dialog.dismiss()
                    providerDialog = null
                    playServer(s)
                }
            }
            if (isCurrent || initialFocusBtn == null) {
                initialFocusBtn = row
            }
            layout.addView(row)
        }

        capDialogHeight(dialog, PROVIDER_DIALOG_HEIGHT)
        providerDialog = dialog
        initialFocusBtn?.post { initialFocusBtn?.requestFocus() }
    }

    /**
     * Colours one run of a TextView's text.
     *
     * Applied after [android.widget.TextView.setText], because a Spannable handed
     * to setText is copied and the spans added to the original would be lost.
     */
    private fun TextView.colour(start: Int, end: Int, color: Int, bold: Boolean = false) {
        val editable = text as? android.text.Spannable ?: return
        if (start < 0 || end > editable.length || start >= end) return
        editable.setSpan(
            android.text.style.ForegroundColorSpan(color),
            start,
            end,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (bold) {
            editable.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start,
                end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /**
     * The episodes glyph. It enters the carousel rather than toggling it.
     *
     * The strip is already up whenever the chrome is, so there is nothing left
     * to close. Pressing the button means "show me the chapters properly", which
     * is the focused state where the control row steps aside.
     */
    private fun openEpisodesList() {
        if (stripFocused) {
            leaveStrip()
            return
        }

        val eps = seriesDetail?.episodes
        if (eps.isNullOrEmpty()) {
            showFsStatus("Cargando lista de capítulos…", 0L)
            fetchSeriesDetailInBackground { detail ->
                hideFsStatus()
                if (detail.episodes.isNotEmpty()) {
                    showChapterStrip(detail.episodes)
                    enterStrip()
                } else {
                    showFsStatus("No se encontraron capítulos", 0L)
                }
            }
            return
        }
        if (!chapterStripOpen) showChapterStrip(eps)
        enterStrip()
    }

    /**
     * Opens the chapter carousel.
     *
     * The strip does not replace the control row, it joins it. The reference
     * keeps both on screen: the header and the top edge of the first row of
     * cards peek up from the bottom of the screen, which is enough to say there
     * are more chapters without taking the transport away. See [setStripFocused]
     * for what happens when the strip is actually entered.
     *
     * Only a window of chapters is built at a time, see [STRIP_WINDOW]. That is
     * what lets this exist on this hardware at all, and it is also what makes
     * it feel like a strip rather than a list: walking into the next chapter
     * slides the window along instead of building three hundred cards at once.
     */
    private fun showChapterStrip(eps: List<Episode>) {
        stripEpisodes = eps
        // Anchor on the chapter being watched, or failing that the closest one
        // below it. The listing is not always complete: Renegade Immortal lists
        // 1-159 and yet episode 160 exists and plays. A plain indexOfFirst would
        // miss on 160 and drop the viewer at chapter 1, which reads as though
        // the strip were broken. Landing on the last chapter instead points at
        // the one they were just watching.
        val exact = eps.indexOfFirst { it.number == epNumber }
        val nearestBelow = eps.indexOfLast { it.number <= epNumber }
        val focusFrom = when {
            exact >= 0 -> exact
            nearestBelow >= 0 -> nearestBelow
            else -> 0
        }
        // Centre the window on the anchor, clamped so it never runs off either
        // end of the list.
        val half = STRIP_WINDOW / 2
        val maxAnchor = (eps.size - STRIP_WINDOW).coerceAtLeast(0)
        stripAnchor = (focusFrom - half).coerceIn(0, maxAnchor)
        chapterStripOpen = true
        fsChapterStrip.visibility = View.VISIBLE
        // Cancels any auto-hide that was already pending, so the strip is not
        // faded out from under the viewer a second after it opens.
        showFsControls(autoHideDelayMs = Long.MAX_VALUE / 2)
        applyVideoShrink(true)
        // Rendered without focus: the strip appears alongside the controls, and
        // the controls are the thing that still has focus.
        renderStripWindow((focusFrom - stripAnchor).coerceIn(0, (windowSize() - 1).coerceAtLeast(0)))
        fsChapterStrip.post { applyVideoShrink(true) }
    }

    /**
     * Moves the carousel between its two states, and never resizes it.
     *
     * Shared state: the strip sits at the bottom of the screen showing its
     * header and the top of the first row of cards, and *nothing else moves*.
     * The control row stays exactly where it was; the strip is simply clipped by
     * the bottom edge, which is how the reference peeks it at you.
     *
     * Focused state: the controls go away and the whole strip, unchanged in
     * size, moves up to take the row's place. That is the point of the reference's
     * design and the reason the sizes are fixed: one band, two positions, so
     * there is never a frame where the two are laid out on top of each other or
     * where the strip has to be squeezed to fit beside the controls.
     *
     * The row's own top is captured before it is hidden rather than hardcoded as
     * a fraction of the screen, so the strip lands exactly where the controls
     * were on any panel size.
     */
    private fun setStripFocused(focused: Boolean) {
        if (stripFocused == focused) return
        if (focused) {
            // Remembered in root coordinates, so the translation below can put
            // the strip's top exactly on this line.
            stripHomeTop = fsBottomBar.top
        }
        stripFocused = focused
        fsBottomBar.visibility = if (focused) View.GONE else View.VISIBLE
        fsPills.visibility = if (focused) View.GONE else View.VISIBLE
        applyVideoShrink(chapterStripOpen)
        fsChapterStrip.post { applyVideoShrink(chapterStripOpen) }
    }

    /**
     * Shrinks the picture so the strip gets a band of its own, below the video.
     *
     * Measured rather than assumed. A fixed fraction looked fine until the
     * scrub bar and the action row were taken into account: the strip was
     * positioned from the same number as the picture, so the two bottom clusters
     * ended up sharing one band and the controls drew straight over the posters.
     * The video now gets whatever is left once the strip and the bottom bar have
     * both reported their real heights, so the three can never overlap.
     */
    /**
     * Lifts the control row clear of the carousel when it is open.
     *
     * The picture is deliberately *not* resized. The Amazon reference keeps the
     * video at full height and lays the carousel over the bottom of it, and that
     * is the right call for two reasons: a 16:9 video on a 16:9 screen cannot
     * shrink without pillarboxing, and the frame you are choosing an episode
     * from is more useful than the last twenty percent of the picture.
     *
     * So the only thing that moves is the chrome, which is why the three bands
     * can never overlap: the strip is pinned to the bottom of the screen and the
     * row is lifted by exactly the strip's measured height.
     */
    /**
     * Reserves the carousel's peek band at the bottom of the control row.
     *
     * The band is the header, the gap under it and the slice of artwork that
     * clears the bottom edge. The first two are measured and the third is the
     * only constant, so the reservation has to be re-applied once the header
     * has a real height: before that, [peekBandPx] falls back to an estimate and
     * the row ends up a few pixels either side of where it should.
     *
     * @param provisional true while the header is still unmeasured, which only
     *        decides how far off it is for the first frame.
     */
    private fun reservePeekBand(provisional: Boolean) {
        val m = tv ?: return
        if (!provisional && fsEpisodesHeader.height <= 0) return
        fsBottomBar.setPadding(
            m.actionStart,
            m.u(TvMetrics.SPACE_MD),
            m.actionEnd,
            m.safeBottom + m.u(TvMetrics.SPACE_XS) + peekBandPx()
        )
        if (!provisional && chapterStripOpen) applyVideoShrink(true)
    }

    /**
     * The band the carousel occupies while the control row is still up, in
     * pixels: its header, the gap under the header, and the slice of artwork
     * that clears the bottom edge.
     *
     * Worked out from the measured header rather than kept as one number,
     * because the header is a text view whose height depends on the font that
     * happened to load, and a constant that included it went wrong on every
     * screen where that font was a different size. The one number that has to
     * land on a specific line is the artwork, so only that is a constant.
     */
    private fun peekBandPx(): Int {
        val m = tv ?: return 140
        val headerH = fsEpisodesHeader.height.takeIf { it > 0 } ?: m.textPx(22f).toInt()
        val gap = (fsChapterScroll.layoutParams as? LinearLayout.LayoutParams)?.topMargin
            ?: m.u(10f)
        return headerH + gap + m.u(TvMetrics.STRIP_PEEK_CARD)
    }

    private fun applyVideoShrink(shrunk: Boolean, attempt: Int = 0) {
        val root = findViewById<View>(R.id.root)
        val rootH = root.height
        if (rootH <= 0) return
        val stripH = if (shrunk) fsChapterStrip.height else 0
        if (shrunk && stripH <= 0) {
            // The strip has not been measured yet. One retry on the next frame
            // covers that, and no more: a persistent zero means it is gone.
            if (attempt < 3) fsChapterStrip.post { applyVideoShrink(true, attempt + 1) }
            return
        }
        // Two positions for one band, and its height never changes.
        //
        // Shared: the strip's top sits `peek` pixels above the bottom of the
        // screen, so its header and the top of the first row of cards are all
        // that shows and the rest is cut off by the edge. Nothing else on screen
        // moves; the control row is exactly where it was.
        //
        // Focused: the strip's top lands on the line the control row used to
        // occupy, which is where the reference puts it. The row is hidden rather
        // than squeezed, so the two are never both laid out.
        //
        // The peek is capped by the space actually left below the control row.
        // Without the cap it is a fixed number that quietly overlaps the row on
        // one screen size and wastes the bottom edge on another.
        // The space the peek may have is measured from where the control row's
        // *content* ends, not from where the view ends. The row is a wrap_content
        // box pinned to the bottom of the screen, so its bottom edge is the
        // bottom of the screen whatever is in it, and measuring to that line
        // always comes out as zero available space and hides the strip again.
        val rowBottom = if (stripFocused) rootH
        else fsBottomBar.bottom - fsBottomBar.paddingBottom
        val available = (rootH - rowBottom).coerceAtLeast(0)
        val peek = minOf(peekBandPx(), available)
        val target = when {
            stripH <= 0 -> rootH
            stripFocused -> minOf(stripHomeTop, rootH - stripH - (tv?.safeBottom ?: 0))
            else -> rootH - peek
        }
        fsChapterStrip.translationY = (target - (rootH - stripH)).toFloat()
        // The status pill is a direct child of the root, so it has no vertical
        // bias to move. It stays at the centre of the screen, which is the centre
        // of the picture, because the picture is no longer resized. A translation
        // rather than a margin, so nothing is remeasured.
        findViewById<View>(R.id.fsStatus).translationY = 0f
    }

    /** How many chapters the current window actually holds. */
    private fun windowSize(): Int =
        (stripAnchor + STRIP_WINDOW).coerceAtMost(stripEpisodes.size) - stripAnchor

    private fun hideChapterStrip() {
        if (!chapterStripOpen) return
        chapterStripOpen = false
        fsChapterStrip.visibility = View.GONE
        fsChapterRow.removeAllViews()
        applyVideoShrink(false)
        // Hand focus back to the control row, so closing the strip does not leave
        // the focus nowhere. It used to go back to the episodes button that opened
        // it, and there is no episodes button in this row any more.
        if (stripFocused) leaveStrip() else actionChain.firstOrNull()?.requestFocus()
        // Resume the auto-hide that opening the strip suspended.
        showFsControls()
    }

    /** Rebuilds the visible window, starting the window at [stripAnchor]. */
    private fun renderStripWindow(focusIndex: Int) {
        val m = tv ?: return
        val all = stripEpisodes
        if (all.isEmpty()) return
        stripAnchor = stripAnchor.coerceIn(0, (all.size - 1).coerceAtLeast(0))
        val end = (stripAnchor + STRIP_WINDOW).coerceAtMost(all.size)
        val window = all.subList(stripAnchor.coerceAtMost(end), end)

        // 384 and 50, measured off the reference's own strip at 1920x1080 rather
        // than guessed. A pixel scan of its card row gives the left edges at
        // 96, 530, 964, 1398 and 1832: a 434 pitch on a 384 card, so the gap is
        // 50 and not the 44 that was here. The card is 16:9, so four and a half
        // of them cross the screen, which is what the reference shows.
        val gap = m.u(50f)
        val cardW = m.u(384f)
        // Whether a card held the focus before the row is emptied, because
        // emptying it takes that focus away and the window then picks a new
        // owner for itself.
        val hadFocusInStrip =
            (0 until fsChapterRow.childCount).any { fsChapterRow.getChildAt(it).hasFocus() }
        fsChapterRow.removeAllViews()
        for (ep in window) {
            val card = EpisodeCardView(this).apply {
                applyMetrics(m)
                // The site carries no per-chapter name, so the label's second
                // line stays empty rather than repeating the series title twenty
                // times down the row.
                layoutParams = LinearLayout.LayoutParams(cardW, 0).apply { marginEnd = gap }
                // After the width, because it works the total height out from it.
                sizeToWidth(cardW)
            }
            val progress = Prefs.savedPosition(ep.url, -1L)
            val total = Prefs.durationOf(ep.url)
            val percent = if (progress > 0 && total > 0) {
                ((progress * 100) / total).toInt().coerceIn(0, 100)
            } else 0
            card.bind(
                episodeNumber = ep.number,
                season = 1,
                // Falls back to the series poster so a chapter with a missing
                // thumbnail still shows art instead of an empty square.
                thumbUrl = ep.thumb?.takeIf { it.isNotBlank() } ?: seriesDetail?.poster,
                isCurrent = ep.number == epNumber,
                isWatched = total > 0 && progress > total - 30_000L,
                resumePercent = percent,
            )
            card.setOnClickListener {
                hideChapterStrip()
                navigateTo(ep.url)
            }
            fsChapterRow.addView(card)
        }
        // The strip is only *shown* here. Taking the focus is a separate step,
        // because the reference does not take it when the strip appears: the
        // controls keep the focus and the strip is the top of a row of cards
        // peeking in from the bottom edge. See [setStripFocused].
        val focusOn = focusIndex.coerceIn(0, window.size - 1)
        fsChapterRow.post {
            if (stripFocused) {
                fsChapterRow.getChildAt(focusOn)?.let { c ->
                    c.requestFocus()
                    centerInStrip(c)
                }
            } else if (!hadFocusInStrip && currentFocus == null) {
                // The shared state is entered without a card holding the focus,
                // so the rebuild above drops the focus the control row had and
                // leaves the window to pick one. It picks the first focusable it
                // finds, which is the close button, and a player that opened
                // with the focus on the X would close on the first press of OK.
                // Putting it back on the transport is the same answer every
                // other path in this file gives.
                actionChain.firstOrNull()?.requestFocus()
            }
        }
    }

    /**
     * Moves the focus into the carousel and switches it to its own layout.
     *
     * The controls disappear and the strip comes up whole, which is what the
     * reference does the moment the focus crosses that line. Keeping the controls
     * and squeezing both onto the screen is what the reference refuses to do, and
     * it is right not to: a 216px card plus two label lines plus a scrub bar plus
     * a glyph row does not fit in 1080, so one of them has to give way, and the
     * reference gives up the transport rather than the chapter labels.
     */
    private fun enterStrip() {
        if (!chapterStripOpen) {
            openEpisodesList()
            return
        }
        if (stripFocused) return
        setStripFocused(true)
        // The chapter being played, so entering the strip lands on where the
        // viewer already is rather than at one end of a long row.
        //
        // The coerce is not decoration. indexOfFirst answers -1 when the chapter
        // is missing from the listing, which Renegade Immortal and Swallowed Star
        // both do for their final episode, and -1 minus the window anchor is a
        // large negative number that silently focused nothing. Landing on the
        // last card in the window is the same answer showChapterStrip already
        // decided on when it chose where to anchor.
        val row = fsChapterRow
        val at = (0 until row.childCount).firstOrNull { row.getChildAt(it).hasFocus() }
            ?: (stripEpisodes.indexOfFirst { it.number == epNumber }
                .let { if (it < 0) row.childCount - 1 else it - stripAnchor }
                .coerceIn(0, (row.childCount - 1).coerceAtLeast(0)))
        // Posted, because the cards have not been measured yet. Their left and
        // their width are both zero until a measure pass has run and centring
        // needs both, so asking in the same frame silently did nothing and left
        // the strip sitting there with nothing focused.
        row.post {
            row.getChildAt(at)?.let { card ->
                card.requestFocus()
                centerInStrip(card)
            }
        }
    }

    /** Hands the focus back to the control row and restores the shared layout. */
    private fun leaveStrip(): Boolean {
        if (!stripFocused) return false
        setStripFocused(false)
        (actionChain.firstOrNull() ?: fsSeek)?.requestFocus()
        return true
    }

    /**
     * Scrolls the strip so a card sits at the left inset, level with the title.
     *
     * Left, not centred. The reference puts the chapter you are on at the start
     * of the visible row with the ones after it running off to the right, and
     * that is what makes the strip read forwards: the next chapter is the one
     * already in front of you, and the one before it is behind, where it
     * belongs.
     *
     * Clamped to the scrollable range, so the first and last cards of a window
     * are held at their natural ends rather than dragged in from nowhere. The
     * padding is counted on both sides because a scroll view lays its child out
     * from the inner edge of its padding, not from zero, so leaving it out
     * over-scrolls by a full inset and the card lands off the side.
     */
    private fun centerInStrip(card: View) {
        val strip = fsChapterScroll
        if (card.width <= 0 || strip.width <= 0) return
        val target = card.left - strip.paddingLeft
        val maxScroll = (
            strip.paddingLeft + fsChapterRow.width - strip.width + strip.paddingRight
            ).coerceAtLeast(0)
        strip.smoothScrollTo(target.coerceIn(0, maxScroll), 0)
    }

    /**
     * Moves focus along the strip, sliding the window when the edge is reached.
     *
     * The strip owns the horizontal axis only while it holds the focus. That
     * check is not an optimisation, it is the whole behaviour: the strip is now
     * on screen every time the chrome is, whether or not anybody is looking at
     * it, so testing only "is it open" let every left and right press on the
     * control row be swallowed here. Worse, with no card focused the strip took
     * the first one, so the next centre press landed on a chapter card and
     * changed episode while the focus ring was still drawn on a button.
     *
     * @return true when the key was consumed by the strip.
     */
    private fun moveInStrip(forward: Boolean): Boolean {
        if (!chapterStripOpen || !stripFocused) return false
        val row = fsChapterRow
        val count = row.childCount
        if (count == 0) return true
        val at = (0 until count).firstOrNull { row.getChildAt(it).hasFocus() } ?: -1
        val next = if (at < 0) 0 else at + if (forward) 1 else -1
        if (next in 0 until count) {
            row.getChildAt(next)?.let { c ->
                c.requestFocus()
                centerInStrip(c)
            }
            return true
        }
        // Walked off the end of the window, so slide the window along and come
        // back in at the near edge. A long series then scrolls indefinitely
        // without ever holding more than STRIP_WINDOW cards.
        val half = STRIP_WINDOW / 2
        if (forward) {
            val maxAnchor = (stripEpisodes.size - STRIP_WINDOW).coerceAtLeast(0)
            if (stripAnchor < maxAnchor) {
                stripAnchor = (stripAnchor + half).coerceAtMost(maxAnchor)
                renderStripWindow(0)
            }
        } else if (stripAnchor > 0) {
            stripAnchor = (stripAnchor - half).coerceAtLeast(0)
            renderStripWindow(count - 1)
        }
        return true
    }

    @Suppress("unused")
    private fun legacyEpisodesDialog(eps: List<Episode>) {
        val m = tv ?: TvMetrics.from(findViewById(R.id.root), resources.configuration.fontScale)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dialog_tv)
            setPadding(m.u(30f), m.u(24f), m.u(30f), m.u(24f))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        val title = TextView(this).apply {
            text = seriesTitle
            setTextColor(Color.parseColor("#F47521"))
            setTextPx(m.textPx(20f))
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        layout.addView(title)

        val subtitle = TextView(this).apply {
            text = "Capítulos (${eps.size})"
            setTextColor(Color.parseColor("#9AA7B4"))
            setTextPx(m.textPx(14f))
            setPadding(0, m.u(2f), 0, m.u(14f))
        }
        layout.addView(subtitle)

        val lv = ListView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            choiceMode = ListView.CHOICE_MODE_SINGLE
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = m.u(6f)
            setPadding(0, 0, m.u(8f), 0)
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val items = eps.map { ep ->
            if (ep.number == epNumber) "▶  Episodio ${ep.number}   Reproduciendo"
            else "     Episodio ${ep.number}"
        }

        val adapter = object : ArrayAdapter<String>(this, 0, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val ep = eps.getOrNull(position)
                val isCurrent = ep?.number == epNumber
                val tv = (convertView as? TextView) ?: TextView(this@PlayerActivity).apply {
                    setBackgroundResource(R.drawable.bg_prime_pill_btn)
                    setPadding(m.u(20f), 0, m.u(20f), 0)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    setTextPx(m.textPx(16f))
                }
                tv.text = items[position]
                tv.setTextColor(if (isCurrent) Color.parseColor("#F47521") else Color.WHITE)
                // A fixed row height stops the list from resizing as it scrolls
                // and keeps the focus highlight a predictable size on a TV.
                tv.layoutParams = android.widget.AbsListView.LayoutParams(
                    android.widget.AbsListView.LayoutParams.MATCH_PARENT, m.u(52f)
                )
                return tv
            }
        }
        lv.adapter = adapter

        val curIdx = eps.indexOfFirst { it.number == epNumber }.coerceAtLeast(0)

        val dialog = buildTvDialog(layout, EPISODES_DIALOG_WIDTH, EPISODES_DIALOG_HEIGHT)

        lv.setOnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            epDialog = null
            eps.getOrNull(pos)?.let { selectedEp -> navigateTo(selectedEp.url) }
        }

        // A TV remote sends DPAD_CENTER, which a ListView does not treat as a
        // click by itself, so the selection is resolved explicitly here.
        lv.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                val pos = lv.selectedItemPosition
                if (pos in eps.indices) {
                    dialog.dismiss()
                    epDialog = null
                    navigateTo(eps[pos].url)
                    return@setOnKeyListener true
                }
            }
            false
        }

        layout.addView(lv)
        epDialog = dialog

        // Focus has to be claimed after the rows exist, otherwise the first
        // focusable view found is the static title and the list cannot be moved.
        lv.post {
            lv.requestFocus()
            lv.setSelection(curIdx)
        }
    }

    private fun navigateTo(url: String, fromAutoAdvance: Boolean = false) {
        val targetUrl = if (url.startsWith("http")) url else Api.BASE + url
        Log.i(TAG, "Navigating to episode URL: $targetUrl (auto=$fromAutoAdvance)")

        // Persist the outgoing episode before we forget it, but never a finished
        // one. It was reaching the end, then this wrote the final position back
        // and undid the clear, so the next visit resumed a completed chapter
        // instead of starting the following one.
        if (originalUrl.isNotBlank() && firstFrameSeen && !playbackFinished) {
            val total = exo?.duration ?: -1L
            Prefs.savePosition(originalUrl, lastKnownPositionMs, total)
        }

        // Clear the outgoing episode's server state before the new one starts, so
        // a stale prefetcher or embed never outlives the episode it belonged to.
        stopBackgroundPrefetch()
        handler.removeCallbacks(embedPollRunnable)
        handler.removeCallbacks(embedWatchdogRunnable)
        handler.removeCallbacks(nativeTimeoutRunnable)
        handler.removeCallbacks(bufferingWatchdogRunnable)
        handler.removeCallbacks(spinnerShowRunnable)

        // Normalised before anything else keys off it, so the saved position,
        // the history entry and the page request all refer to the same episode.
        val canonical = canonicalEpisodeUrl(targetUrl)
        val finalUrl = if (canonical != targetUrl) {
            Log.i(TAG, "Normalised episode URL: $targetUrl -> $canonical")
            canonical
        } else {
            targetUrl
        }

        originalUrl = finalUrl
        autoNextFired = false
        // Latched rather than read from autoNextFired, which is reset right
        // here. It has to survive the navigation to be able to tell "the next
        // chapter does not exist" from "the viewer asked for it and it is broken".
        arrivedByAutoAdvance = fromAutoAdvance
        currentVideoUrl = null
        lastExoUrl = null
        prevUrl = null
        nextUrl = null
        firstFrameSeen = false
        playbackFinished = false
        embedIsActive = false
        networkRetries = 0
        // Another chapter, another set of pages: nothing that failed here says
        // anything about there.
        embedAttempted.clear()
        lastErrorWasDeterministic = false
        sourceHasEverPlayed = false
        extractAttempts = 0
        serverPasses = 0
        lastAdvanceMs = -1L
        stallCount = 0
        availableServers = emptyList()
        currentServer = null
        // Under `originalUrl`, not `targetUrl`. Every write in this class keys
        // on the canonical form, and canonicalEpisodeUrl rewrites the tokenised
        // `/ver/{slug}/{n}/{base64}` links that the site hands out. Reading the
        // raw href looked under a key nothing was ever written to, so resuming
        // silently started every such chapter from the beginning.
        resumePositionMs = Prefs.savedPosition(originalUrl, -1L)

        val (slug, num) = extractSlugAndEpisode(targetUrl)
        if (slug.isNotBlank()) {
            seriesSlug = slug
            epNumber = num
        }

        computeNavUrlsIfMissing()
        updateHeaderUI()

        releaseExo()
        suspendWebView()
        fetchSeriesDetailInBackground()
        loadEpisode(targetUrl)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Drops the token segment from a tokenised episode link. That link form is
     * served with a reduced provider list, so it is never worth keeping.
     */
    private fun canonicalEpisodeUrl(url: String): String = Parser.canonicalEpisodeUrl(url)

    private fun isHandledKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND

    /**
     * Remote handling.
     *
     * The model is the one Prime Video uses, and it matters more than any
     * visual detail:
     *
     *  - Chrome hidden: the D-pad is a set of shortcuts. Left/right skip 10s,
     *    centre toggles play, up/down reveal the chrome.
     *  - Chrome visible: the D-pad navigates the chrome, and left/right no longer
     *    skip. That is what made the old player feel stuck: with a button
     *    focused, left jumped the video instead of moving to the previous
     *    button, so the row could not be traversed with the remote at all.
     *  - With the scrub bar focused, left/right scrub, with a preview and a
     *    delayed commit.
     *  - Up from the bottom row goes to the centre control, and up from the
     *    centre hides the chrome.
     *
     * Media keys stay global, since they are unambiguous and are expected to
     * work whatever is on screen.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (event.action != KeyEvent.ACTION_DOWN) {
            return isHandledKey(keyCode) || super.dispatchKeyEvent(event)
        }

        if (providerDialog?.isShowing == true || epDialog?.isShowing == true) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                providerDialog?.dismiss()
                providerDialog = null
                epDialog?.dismiss()
                epDialog = null
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Back while the carousel owns the screen gives the screen back, the same
        // as the up arrow does. Only when the carousel has the focus.
        //
        // It used to fire on the strip merely being *open*, which after the strip
        // was made permanent meant it fired every single time. Back dismissed an
        // invisible thing and then immediately put the chrome back up, so the
        // button looked broken: it took three presses to leave the player, and
        // the first one appeared to do nothing at all.
        if (stripFocused && keyCode == KeyEvent.KEYCODE_BACK) {
            leaveStrip()
            return true
        }

        // The settings panel is not a screen of its own either. It sits closer
        // to the chrome than the carousel does, so it is closed by Back and by
        // up as well as by picking an option, and left and right walk its
        // options instead of the glyph row that it replaced.
        if (openPanel != Panel.NONE) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP -> {
                    hidePanel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val row = fsSettingsRow
                    val count = row.childCount
                    if (count > 0) {
                        val at = (0 until count).firstOrNull { row.getChildAt(it).hasFocus() } ?: 0
                        val next = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) (at + 1) % count
                        else (at - 1 + count) % count
                        panelChild(next)?.requestFocus()
                    }
                    return true
                }
            }
        }

        if (::fsErrorBox.isInitialized && fsErrorBox.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val focused = currentFocus
                    if (focused != null && focused !== fsErrorBox) focused.performClick()
                    else fsErrorRetry.performClick()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    fsErrorRetry.requestFocus(); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    fsErrorServers.requestFocus(); return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    finish(); return true
                }
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                showFsControls()
                toggleFsPlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                showFsControls()
                seekNative(-SCRUB_STEP_MS)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                showFsControls()
                seekNative(SCRUB_STEP_MS)
                return true
            }
        }

        if (!chromeOnScreen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    toggleFsPlay()
                    return true
                }
                // With the chrome hidden the D-pad is a set of shortcuts, which
                // is the model the Amazon reference uses.
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    showFsControls()
                    seekNative(-SCRUB_STEP_MS)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    showFsControls()
                    seekNative(SCRUB_STEP_MS)
                    return true
                }
                // Up lands on the scrub bar, which is the band the eye is
                // already drawn to by the progress readout.
                KeyEvent.KEYCODE_DPAD_UP -> {
                    showFsControls()
                    if (!fsSeek.requestFocus()) actionChain.firstOrNull()?.requestFocus()
                    return true
                }
                // Down lands on the glyph row, since that is where every action
                // lives and it is the thing people press down to reach.
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showFsControls()
                    if (actionChain.firstOrNull()?.requestFocus() != true) fsSeek.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // Chrome is up: keep it up while it is in use, and let anything
        // unhandled fall through to the real focus search.
        showFsControls()
        val seekFocused = fsSeek.isFocused

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // Commit any scrub being previewed, then take the chrome down.
                //
                // This is the gesture for "just the video", which is what a viewer
                // pressing Back on a player means. Making it leave instead - on
                // the grounds that the chrome is nearly always up and so the
                // button looked dead - was worse than the problem: it threw the
                // viewer out of the chapter every time they just wanted to watch
                // it. From here the chrome simply goes away, and Back again - now
                // with it already hidden - leaves for real, which is the same
                // two-press shape every TV player has.
                handler.removeCallbacks(scrubCommit)
                scrubCommit.run()
                hideChrome()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (seekFocused) {
                    // The bar is focused, so OK means "go to the previewed spot".
                    handler.removeCallbacks(scrubCommit)
                    scrubCommit.run()
                } else {
                    val focused = currentFocus
                    if (focused != null && focused !== fsControls) focused.performClick()
                    else {
                        fsPlay.requestFocus()
                        toggleFsPlay()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (moveInStrip(forward = false)) return true
                if (seekFocused) {
                    scrubBy(-SCRUB_STEP_MS)
                } else {
                    // Move within the action row instead of seeking. This is the
                    // behaviour that makes the row usable with a remote: before,
                    // left/right always jumped the video, so the buttons could
                    // never be reached by walking the row.
                    focusNeighbour(currentFocus, forward = false)?.requestFocus()
                }
                return true
            }
            // A right press with the scrub bar focused scrubs; anywhere else it
            // walks the glyph row, which is the only way to reach the buttons
            // with a remote.
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (moveInStrip(forward = true)) return true
                if (seekFocused) {
                    scrubBy(SCRUB_STEP_MS)
                } else {
                    focusNeighbour(currentFocus, forward = true)?.requestFocus()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Down crosses into the carousel and hands the screen over to
                // it. It does not step past the strip, which is what it used to
                // do, and that made the strip a dead end: the only way out of it
                // was the same key you had to press to get in.
                if (stripFocused) return true
                if (chapterStripOpen) {
                    enterStrip()
                    return true
                }
                when (currentFocus) {
                    // Pills, then the scrub bar, then the glyph row. Reading the
                    // bands top to bottom is what the up arrow walks back down,
                    // so down and up are inverses of each other.
                    null -> if (!fsSeek.requestFocus()) actionChain.firstOrNull()?.requestFocus()
                    fsExit -> if (!fsSeek.requestFocus()) actionChain.firstOrNull()?.requestFocus()
                    fsSeek -> if (actionChain.firstOrNull()?.requestFocus() != true) fsSeek.requestFocus()
                    else -> focusNeighbour(currentFocus, forward = true)?.requestFocus()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // Up leaves the carousel and brings the controls back. The
                // reference pairs the two arrows exactly like this: down to go
                // in, up to come out, and neither one skips past the strip.
                if (stripFocused) {
                    leaveStrip()
                    return true
                }
                when (currentFocus) {
                    fsExit -> hideChrome()
                    // The scrub bar is the band above the glyph row.
                    //
                    // The fallback is the glyph row, never the exit button. Up
                    // used to fall through to the X, which put focus on "close the
                    // player" and left the very next press on the remote quitting
                    // the film. A key that cannot reach its target should leave
                    // the focus where it can still be seen, not arm the one
                    // control that ends everything.
                    else -> if (!fsSeek.requestFocus()) {
                        if (actionChain.firstOrNull()?.requestFocus() != true) hideChrome()
                    }
                }
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }


    /**
     * Fades the chrome out and hands focus back to the video, so the picture is
     * the whole screen again.
     *
     * This is what the first press of back does. The second one leaves the
     * player, and the flag below is set here rather than in the fade's end
     * action so that the two presses work even when they are close together.
     */
    private fun hideChrome() {
        handler.removeCallbacks(scrubCommit)
        fsSeek.scrubTarget = null
        fsSeek.hideTooltip()
        handler.removeCallbacks(fsHideRunnable)
        chromeOnScreen = false
        fsControls.animate().cancel()
        fsControls.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction {
                fsControls.visibility = View.INVISIBLE
                hideSystemUi()
            }
            .start()
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun releaseExo() {
        exo?.let {
            // Capture the position before tearing down so retries and the next
            // episode always know where the viewer was. Skipped once the chapter
            // has finished, for the same reason as in navigateTo: its position was
            // deliberately cleared and must stay cleared.
            if (firstFrameSeen && !playbackFinished) {
                lastKnownPositionMs = it.currentPosition
                resumePositionMs = lastKnownPositionMs
                val total = it.duration
                Prefs.savePosition(originalUrl, lastKnownPositionMs, if (total > 0) total else -1L)
            }
            it.removeListener(exoListener)
            it.stop()
            it.clearMediaItems()
            it.release()
        }
        exo = null
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(audioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        hasAudioFocus = true
    }

    /**
     * Gives the audio focus back while the player is in the background.
     *
     * The focus was requested once in `onCreate` and only ever abandoned in
     * `onDestroy`, so holding a `USAGE_MEDIA` focus kept this app at the top of
     * the system's focus stack after the viewer had left the player. That is what
     * left a chapter audible behind whatever they opened next: the pause in
     * `onPause` stops the decoder, but an app that still owns the focus looks
     * like it is playing to everything else on the system, and any surface that
     * keys off focus rather than off the player kept it audible.
     *
     * Re-acquired in `onResume`, so returning to the player is seamless.
     */
    private fun abandonAudioFocusIfHeld() {
        if (!hasAudioFocus) return
        hasAudioFocus = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest)
            } else {
                @Suppress("DEPRECATED")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "abandonAudioFocus failed: ${e.message}")
        }
    }

    /** Whether this activity currently owns the audio focus. */
    private var hasAudioFocus = false

    /**
 * Whether playback was running when the activity lost the foreground.
 *
 * This is what separates "the system interrupted us and the viewer is coming
 * straight back" from "the viewer pressed pause and meant it".
 */
private var interruptedWhilePlaying = false

/**
 * Set once the activity is being torn down.
 *
 * Two callbacks can still call back in after that point. shouldInterceptRequest
 * runs on a WebView worker thread, so its post can land after
 * removeCallbacksAndMessages; and destroy() does not drain a pending JS-bridge
 * callback synchronously. Either one reaching playNative would build a fresh
 * ExoPlayer with playWhenReady=true on a dead activity, with nothing left alive
 * to ever release it - orphaned audio, which is the exact symptom the embed
 * silencing was added to stop.
 */
private var isDestroyed = false

/** Set by onPause, consumed by onResume. Native and embed are tracked apart. */
private var interruptedEmbedWhilePlaying = false

override fun onResume() {
        super.onResume()
        // Pausing gave the focus up; take it back now that we are on screen.
        if (!hasAudioFocus) requestAudioFocus()
        if (embedIsActive) resumeWebView()
        hideSystemUi()

        // Put playback back the way it was.
        //
        // `ExoPlayer.pause()` is setPlayWhenReady(false), and nothing else in
        // this class ever set it true again except building a fresh player - so
        // every interruption that pauses on the way out left the video frozen on
        // a frame with the play glyph drawn, for good. The Home button, the
        // notification shade and the screen blanking all pause; all three are
        // followed by the viewer coming straight back.
        //
        // Only when it was playing on the way out, so a deliberate pause is
        // still a pause.
        if (interruptedWhilePlaying) {
            interruptedWhilePlaying = false
            exo?.play()
            setPlayIcon(true)
        }
        if (interruptedEmbedWhilePlaying) {
            interruptedEmbedWhilePlaying = false
            restoreEmbeddedMedia()
        }
    }

    override fun onPause() {
        super.onPause()
        // Remember the place so a brief interruption never rewinds the chapter.
        // Skipped for a finished chapter, whose position was deliberately cleared.
        exo?.let {
            if (it.isPlaying) interruptedWhilePlaying = true
            if (firstFrameSeen && !playbackFinished) {
                lastKnownPositionMs = it.currentPosition
                Prefs.savePosition(originalUrl, lastKnownPositionMs, it.duration)
            }
            it.pause()
        }
        // An embed's media keeps playing through `onPause()` alone, which is what
        // left a chapter audible after the viewer navigated away. Unconditional:
        // the flag that used to guard this could already be false while the
        // page was still playing, and an embed that is merely *dressed* rather
        // than active is just as audible as one that is active.
        if (embedIsActive) interruptedEmbedWhilePlaying = embedIsPlaying
        muteEmbeddedMedia()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try { fsWebView.onPause() } catch (e: Exception) { /* ignore */ }
        }
        // Hand the audio focus back the moment we lose the foreground, not only
        // when the activity is destroyed. Holding it while paused is what made the
        // chapter carry on into whatever the viewer opened next.
        abandonAudioFocusIfHeld()
    }

    override fun onStop() {
        super.onStop()
        exo?.let {
            if (firstFrameSeen && !playbackFinished) {
                Prefs.savePosition(originalUrl, it.currentPosition, it.duration)
            }
            it.pause()
        }
        // Leaving the activity entirely, rather than just covering it: the page is
        // about to be torn down and the audio has to stop with it. Same
        // unconditional reasoning as onPause().
        muteEmbeddedMedia()
        abandonAudioFocusIfHeld()
    }

    override fun onDestroy() {
        isDestroyed = true
        handler.removeCallbacksAndMessages(null)
        serverExtractJob?.cancel()
        detailJob?.cancel()
        stopBackgroundPrefetch()
        // The `!playbackFinished` guard that every other save site carries. STATE_ENDED
        // clears the saved position on purpose, and writing it back here undid
        // that clear for every finished chapter the viewer backed out of.
        if (firstFrameSeen && !playbackFinished && lastKnownPositionMs > 0L) {
            Prefs.savePosition(originalUrl, lastKnownPositionMs, exo?.duration ?: -1L)
        }
        // Before the WebView goes away: an element that is still playing keeps its
        // audio until the renderer is actually destroyed, and `destroy()` does not
        // wait for that. This is the one place the source is detached outright -
        // onPause/onStop deliberately stop at pausing, because they also run for
        // interruptions the viewer comes straight back from.
        muteEmbeddedMedia(hard = true)
        releaseExo()
        try { fsWebView.destroy() } catch (e: Exception) { /* ignore */ }
        // Unconditional, and not just when this activity believes it holds focus:
        // the last line of defence against leaving a USAGE_MEDIA focus behind.
        abandonAudioFocusIfHeld()
        if (keepScreenOn) {
            keepScreenOn = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // The settings panel: subtitles and audio
    // ---------------------------------------------------------------------

    private enum class Panel { NONE, SUBTITLES, AUDIO }

    private var openPanel = Panel.NONE

    /** Index of the chosen option in each panel, so the highlight survives a reopen. */
    private var subtitleChoice = 0
    private var audioChoice = 0

    /** Where focus goes back to when a panel closes. */
    private var panelReturnFocus: View? = null

    /**
     * Opens the subtitles or audio panel, in place of the control row.
     *
     * The reference does not open a dialog. It swaps the row of glyphs for a
     * band: a title, the options, and a line of help underneath. That keeps the
     * picture and the scrub bar on screen, which is the whole point: you open
     * this panel because you could not hear the last line or could not read it,
     * so hiding the frame to read a menu is exactly the wrong trade.
     *
     * The options come from the player's own track list, never from a hardcoded
     * list. A menu that offers a language the stream cannot send is worse than
     * one that says there is nothing to choose.
     */
    private fun showPanel(which: Panel) {
        val m = tv ?: return
        if (openPanel == which) {
            hidePanel()
            return
        }
        openPanel = which
        panelReturnFocus = currentFocus
        fsSettingsPanel.visibility = View.VISIBLE
        fsBottomBar.visibility = View.GONE
        fsSettingsRow.removeAllViews()

        val options: List<String> = when (which) {
            Panel.SUBTITLES -> trackLabels(C.TRACK_TYPE_TEXT)
            Panel.AUDIO -> trackLabels(C.TRACK_TYPE_AUDIO)
            else -> emptyList()
        }

        fsSettingsTitle.text = when (which) {
            Panel.SUBTITLES -> "Subtítulos y Closed Captions"
            else -> "Opciones de audio"
        }
        fsSettingsHelp.text = when (which) {
            Panel.SUBTITLES ->
                "Los subtítulos que ofrece este capítulo. Ninguno es la opción por defecto."
            else ->
                "Las pistas de audio que ofrece este capítulo en el servidor actual."
        }

        if (options.isEmpty()) {
            // A single honest entry rather than an empty band. A panel with
            // nothing in it looks like the app failed to load.
            addChoice(m, "No disponible", true, enabled = false) {}
        } else {
            options.forEachIndexed { i, label ->
                addChoice(m, label, selected = i == currentTrackOf(which)) {
                    selectTrack(which, i)
                }
            }
        }

        // Focus the current selection, not simply the first one: opening a panel
        // should not silently move the highlight away from what is playing.
        fsSettingsRow.post {
            val idx = currentTrackOf(which).coerceIn(0, (fsSettingsRow.childCount - 1).coerceAtLeast(0))
            panelChild(idx)?.requestFocus()
        }
        // No auto-hide: the panel stays until it is closed, because it is a
        // decision being made rather than a control being glanced at.
        showFsControls(autoHideDelayMs = Long.MAX_VALUE / 3)
    }

    private fun hidePanel() {
        if (openPanel == Panel.NONE) return
        openPanel = Panel.NONE
        fsSettingsPanel.visibility = View.GONE
        fsBottomBar.visibility = View.VISIBLE
        // Whatever opened it, if it is still on screen, and otherwise the first
        // control in the row. There is no episodes button to fall back to any
        // more, and a null here would leave the focus nowhere at all.
        (panelReturnFocus?.takeIf { it.isAttachedToWindow } ?: actionChain.firstOrNull())
            ?.requestFocus()
        panelReturnFocus = null
        showFsControls()
    }

    private fun panelChild(i: Int): View? =
        (fsSettingsRow.getChildAt(i) as? LinearLayout)?.getChildAt(1)

    /**
     * One option: a label above, a pill below.
     *
     * The label sits on top rather than inside the pill, which is what the
     * reference does. It is also what lets the pill shrink to the width of its
     * own text, so "Unavailable" and "Español (Latinoamérica)" both look
     * deliberate instead of both being forced into one fixed box.
     */
    private fun addChoice(
        m: TvMetrics,
        label: String,
        selected: Boolean,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.START
            isFocusable = false
        }
        column.addView(TextView(this).apply {
            text = label
            setTextPx(m.textPx(18f))
            setTextColor(LABEL_MUTED)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        val pill = TextView(this).apply {
            text = label
            setTextPx(m.textPx(20f))
            setTextColor(if (selected) PILL_SELECTED_TEXT else LABEL_BRIGHT)
            includeFontPadding = false
            gravity = android.view.Gravity.CENTER
            background = getDrawable(R.drawable.bg_hud_choice)
            isSelected = selected
            isFocusable = enabled
            isFocusableInTouchMode = enabled
            isClickable = enabled
            minWidth = m.u(180f)
            minHeight = m.u(52f)
            setPadding(m.u(28f), 0, m.u(28f), 0)
            if (enabled) setOnClickListener { onClick() }
            // The text inverts with the pill, exactly as the glyph does in the
            // action row. White on white is not a selected state, it is a hole.
            onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                (v as TextView).setTextColor(
                    when {
                        !enabled -> LABEL_MUTED
                        hasFocus || selected -> PILL_SELECTED_TEXT
                        else -> LABEL_BRIGHT
                    }
                )
            }
        }
        column.addView(
            pill,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, m.u(52f)
            ).apply {
                topMargin = m.u(10f)
                marginEnd = m.u(24f)
            }
        )
        fsSettingsRow.addView(column)
    }
    /**
     * The tracks the player actually has, as readable names.
     *
     * Read from ExoPlayer rather than from anything the app decided, so the panel
     * cannot offer a language the stream does not carry. Returns nothing when the
     * visible player is a web embed, which is the honest answer: there is no
     * track list to choose from.
     */
    private fun trackLabels(type: Int): List<String> {
        val e = exo ?: return emptyList()
        return try {
            e.currentTracks.groups
                .filter { it.type == type }
                .map { group ->
                    (0 until group.length)
                        .firstNotNullOfOrNull { group.getTrackFormat(it).language }
                }
                .filterNotNull()
                .filter { it.isNotBlank() }
                .map { languageLabel(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "track list unavailable", t)
            emptyList()
        }
    }

    /**
     * Which of the options the panel shows is currently in use.
     *
     * Remembered rather than derived. Deriving it means matching the player's
     * selected track back to an index in a list that was already filtered, and
     * any mismatch there moves the highlight onto the wrong option, which reads
     * as the panel having changed the setting behind the viewer's back.
     */
    private fun currentTrackOf(which: Panel): Int =
        if (which == Panel.SUBTITLES) subtitleChoice else audioChoice

    /**
     * Applies a choice from the panel.
     *
     * "Off" disables the track type outright, which is the only way to reliably
     * hide a text track: an empty override still leaves the track selected, just
     * with no format chosen, and the player then falls back to the first one it
     * finds.
     *
     * Picking a language enables the type again and pins it to that group. With
     * the single group an HLS stream normally carries, which is the case this
     * app is built around, that is exact. With several renditions of the same
     * language the player stays free to choose within the pinned group, which is
     * the behaviour it has everywhere else.
     */
    private fun selectTrack(which: Panel, index: Int) {
        if (which == Panel.SUBTITLES) subtitleChoice = index else audioChoice = index
        val e = exo ?: return
        try {
            val type = if (which == Panel.SUBTITLES) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
            val builder = e.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
            if (which == Panel.SUBTITLES && index == 0) {
                builder.setTrackTypeDisabled(type, true)
            } else {
                val groups = e.currentTracks.groups.filter { it.type == type }
                // The panel's list starts with "Off", so the real tracks are
                // offset by one against it.
                val offset = if (which == Panel.SUBTITLES) index - 1 else index
                groups.getOrNull(offset)?.let { g ->
                    builder.addOverride(TrackSelectionOverride(g.mediaTrackGroup, emptyList()))
                }
                builder.setTrackTypeDisabled(type, false)
            }
            e.trackSelectionParameters = builder.build()
            hidePanel()
        } catch (t: Throwable) {
            Log.w(TAG, "could not select track", t)
        }
    }

    /** A two-letter code as a language name, without pulling in a locale table. */
    private fun languageLabel(code: String): String = when (code.uppercase()) {
        "ES" -> "Español"
        "EN" -> "English"
        "PT" -> "Português"
        "DE" -> "Deutsch"
        "FR" -> "Français"
        "IT" -> "Italiano"
        "JA" -> "\u65E5\u672C\u8A9E"
        "ZH" -> "\u4E2D\u6587"
        "KO" -> "\uD55C\uAD6D\uC5B4"
        else -> code
    }
}
