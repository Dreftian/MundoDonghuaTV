package com.donghua.tv.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import kotlin.math.roundToInt

/**
 * One chapter in the carousel.
 *
 * The shape and every size come from a pixel scan of Amazon Prime Video playing a
 * real series on this television at 1920x1080, not from an estimate. Measured on
 * the reference's own chapter card, whose artwork runs x147..631 and y398..670:
 *
 *   card            384 x 216 of artwork, so 16:9
 *   gap             50, giving a 434 pitch: 96, 530, 964, 1398, 1832
 *   corner radius   8, the ring's straight edge reached 9px below its corner
 *   focus ring      4px, on the card's own edge. The reference's is 3px of
 *                   white; this is 4px of the accent orange, and the reason is
 *                   in [FOCUS_STROKE]
 *   idle card       no border at all
 *   progress bar    4px tall at y659..664, inset 13 from each side, 6 above the
 *                   artwork's bottom edge
 *   label           "S1 E3" at y634..650, so its baseline sits 20px above the
 *                   bottom edge and 9px clear of the progress bar
 *
 * Three things here are corrections rather than translations, and they are why
 * this card used to look like a hole in the picture instead of a poster.
 *
 * **The label lives inside the artwork, not under it.** It used to sit below the
 * frame on whatever was behind the carousel, which is the video. A label on the
 * video's black is not a label, it is a gap, and the card read as a rectangle cut
 * out of the frame with a caption floating underneath. The reference puts it at
 * the bottom left of the artwork on a scrim, which is why its card is one solid
 * square and this one was two.
 *
 * **The card is a solid plate and its children are clipped to it.** It was a
 * rounded *stroke* with a transparent middle, so the ring was rounded and the
 * artwork was not: at each corner the poster stuck out past the ring and the
 * card looked like it had square corners with a white wire laid over it.
 * `clipToOutline` cuts the children to the card's own outline, so the corners
 * agree, and the fill is [PLATE] so the square is a surface rather than a hole
 * even before the artwork has loaded.
 *
 * **Unfocused cards are not washed back.** The reference does not dim them: the
 * four cards in its row are all the same brightness and the only difference is
 * the ring on the focused one. The heavy wash here (55% black) was doing the
 * opposite of what it was for, taking the poster's own colour away, which is
 * precisely the "it looks like a background" complaint. Focus is now the ring
 * plus a small growth, both of which the reference already uses elsewhere in the
 * player, and the artwork is left alone.
 *
 * The chapter number is not painted on the artwork, because the label already
 * says it. Only the chapter being played gets a mark, and that is the
 * "Now playing" badge in the corner, which is what tells you where you are in a
 * row of forty.
 */
class EpisodeCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val poster = ImageView(context)
    private val nowPlaying = TextView(context)
    private val scrim = View(context)

    /**
     * The watch progress the reference draws along the bottom of every card.
     *
     * A track and a fill rather than a single view, because the reference shows
     * both: a dim line for the whole episode and a bright one for the part
     * already watched. A card you have finished is a full white line, a card you
     * abandoned shows how far you got, and a card you have not started shows a
     * dim line with nothing filled. On a row of twenty that is the fastest way
     * to find the one you stopped on.
     */
    private val progressTrack = View(context)
    private val progressFill = View(context)

    private var metrics: TvMetrics? = null
    private var baseRadius = 0
    private var cardW = 0

    /** The outline at rest, so focus can put it back. */
    private var idleStroke = IDLE_STROKE

    private val frame = FrameLayout(context)
    private val progressHolder = FrameLayout(context)

    private val episodeLabel = TextView(context)
    private val episodeName = TextView(context)

    init {
        orientation = VERTICAL

        // The plate. This is where the ring lives, and it is deliberately NOT
        // clipped to its own outline: `clipToOutline` cuts the canvas to the
        // shape's path, and a stroke is drawn straddling that path, so clipping
        // to it takes most of the ring away with it. The clipping happens one
        // level down instead, on the frame, which is what actually needs it.
        background = plate()

        // The frame clips the artwork to the card's rounded shape. Its own fill
        // is transparent on purpose: the card's plate is the surface, and an
        // opaque fill here would paint straight over the ring, which straddles
        // the card's edge and is drawn by the card's own background underneath.
        // A transparent GradientDrawable still reports a rounded outline, which
        // is all `clipToOutline` needs, so the corners stay cut and nothing is
        // hidden.
        frame.background = clipOnly()
        frame.clipToOutline = true

        frame.addView(
            poster,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // The scrim. It exists for the label and the badge, both of which the
        // reference draws inside the artwork at the bottom, and it is sized off
        // their measurements: 80px is the 100px of scrim the reference has over
        // a 272px card, which is the same fraction of a 216px one. It stops
        // where they stop, so there is no seam across the picture.
        frame.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM
            )
        )
        // The label, at the bottom left of the artwork, on the scrim. The
        // reference's "S1 E1" is 18px from the left edge and its baseline 20px
        // above the bottom, and it is white on every card, focused or not.
        frame.addView(
            episodeLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            )
        )
        frame.addView(
            episodeName,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            )
        )
        frame.addView(
            nowPlaying,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        // The progress line sits inside the artwork, on its own layer above the
        // scrim so it is never dimmed by it.
        progressHolder.addView(
            progressTrack,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        progressHolder.addView(
            progressFill,
            FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        frame.addView(
            progressHolder,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )

        // The whole card is the artwork. There is nothing below it, so the
        // strip's height is the height of a row of cards and a change to either
        // is a change to both.
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        nowPlaying.apply {
            text = NOW_PLAYING
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        episodeLabel.apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.START
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        episodeName.apply {
            setTextColor(0xFFB6C0CB.toInt())
            gravity = Gravity.START
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = GONE
        }
        progressTrack.setBackgroundColor(TRACK)
        progressFill.setBackgroundColor(Color.WHITE)

        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
    }

    /**
     * The name the listing gives this chapter, shown on the label's second line.
     *
     * Blank rather than repeated when the site has no per-chapter name: twenty
     * cards all reading the series title is noise, and the empty line is honest.
     */
    var episodeTitle: String? = null

    fun bind(
        episodeNumber: Int,
        season: Int,
        thumbUrl: String?,
        isCurrent: Boolean,
        isWatched: Boolean,
        resumePercent: Int,
    ) {
        val m = metrics
        poster.setImageDrawable(null)
        if (thumbUrl.isNullOrBlank()) {
            poster.setBackgroundColor(PLACEHOLDER)
        } else {
            poster.load(thumbUrl) {
                crossfade(180)
                // Coil takes a drawable resource id here, not a colour. Passing a
                // packed colour int resolved to no resource at all, so the request
                // never produced a bitmap and every card stayed a flat square.
                placeholder(ColorDrawable(PLACEHOLDER))
                error(ColorDrawable(PLACEHOLDER))
                listener(
                    onError = { _, result ->
                        Log.w(TAG, "chapter art failed: $thumbUrl (${result.throwable})")
                    }
                )
            }
        }

        // The badge, at the top left. The reference puts the "now playing" mark
        // in the corner of the artwork rather than in the label strip, and the
        // scrim is at the bottom, so up here is where it can be read.
        nowPlaying.visibility = if (isCurrent) VISIBLE else GONE
        (nowPlaying.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.leftMargin = m?.u(PROGRESS_INSET) ?: 0
            it.topMargin = m?.u(PROGRESS_INSET) ?: 0
            it.rightMargin = m?.u(PROGRESS_INSET) ?: 0
            it.bottomMargin = 0
            nowPlaying.layoutParams = it
        }
        nowPlaying.setTextPx(m?.textPx(20f))

        // "S1 E3", the way the reference writes it. The season only appears when
        // it is not the first one, which is also how a reader would write it.
        episodeLabel.text = if (season > 1) "S$season E$episodeNumber" else "E$episodeNumber"
        episodeLabel.setTextPx(m?.textPx(20f))
        // Assigned in one expression, not as `apply { …; layoutParams = this }`.
        // Inside apply, `layoutParams` is not a property of LayoutParams, so the
        // name falls through to the enclosing view's and the *label's* params
        // object gets handed to the card itself. The card then lays out with a
        // wrap-content height and no right margin, which is what silently
        // removed the gap between the cards in the carousel.
        //
        // Bottom margin rather than top: the label is bottom-aligned to the
        // artwork, and the reference measures its baseline 20px up from the
        // bottom edge with the progress line 6px below that, so the two offsets
        // are counted from the same edge.
        (episodeLabel.layoutParams as FrameLayout.LayoutParams).let {
            it.leftMargin = m?.u(LABEL_INSET) ?: 0
            it.bottomMargin = (m?.u(PROGRESS_GAP) ?: 0) + (m?.u(PROGRESS_H) ?: 0)
            episodeLabel.layoutParams = it
        }

        val name = episodeTitle?.takeIf { it.isNotBlank() }
        // Hidden rather than a space when there is no name. A blank line inside
        // the card is 20px of scrim covering artwork for nothing, and the site
        // gives no per-chapter name for most series, so it would be almost every
        // card.
        episodeName.visibility = if (name == null) GONE else VISIBLE
        episodeName.text = name ?: ""
        episodeName.setTextPx(m?.textPx(16f))
        (episodeName.layoutParams as FrameLayout.LayoutParams).let {
            it.leftMargin = m?.u(LABEL_INSET) ?: 0
            // Stacked above the label rather than below it, which is the only
            // direction left once both are bottom-aligned to the artwork.
            it.bottomMargin = (m?.u(PROGRESS_GAP) ?: 0) + (m?.u(PROGRESS_H) ?: 0) +
                (m?.textPx(20f)?.toInt() ?: 20) + (m?.u(4f) ?: 0)
            episodeName.layoutParams = it
        }

        // 72px of scrim, ending at 30% black.
        //
        // Both numbers are read off the reference rather than chosen. Its
        // gradient covers about the bottom third of the card and the artwork
        // under it is still perfectly legible: the face in its still is fully
        // readable right down to the progress line, which a 70% scrim would have
        // crushed to nothing. It is there to make white text readable, not to
        // darken the poster, and the 70% this used to end at was doing the
        // second thing, which is what turned the bottom of every card into a
        // black band.
        val scrimH = m?.u(72f) ?: 72
        scrim.layoutParams = scrim.layoutParams.apply {
            width = LayoutParams.MATCH_PARENT
            height = scrimH
        }
        scrim.background = GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00000000, 0x4D000000)
        )

        setProgress(resumePercent)
        contentDescription = buildString {
            append("Episodio ").append(episodeNumber)
            if (!name.isNullOrBlank()) append(", ").append(name)
            if (isWatched) append(", visto")
            if (isCurrent) append(", reproduciéndose")
        }
        // The chapter being played keeps a mark of its own even when the focus
        // is elsewhere, so you can find your place in a long strip. A badge, not
        // a ring: the ring is the focus's colour, and a row where two ideas share
        // one colour is a row where neither can be read.
        idleStroke = IDLE_STROKE
        applyEmphasis(hasFocus())
        redrawBackground()
    }

    /**
     * How much of the episode has been watched, as the reference's own line.
     *
     * The fill is given an absolute width rather than a weight, because the
     * holder has no idea how wide it is until it has been laid out and a weight
     * against an unmeasured parent silently produces nothing.
     */
    private fun setProgress(percent: Int) {
        val m = metrics
        val inset = m?.u(PROGRESS_INSET) ?: 0
        val h = m?.u(PROGRESS_H) ?: 0
        val w = cardW - inset * 2
        if (w <= 0 || h <= 0) return
        progressHolder.layoutParams = (progressHolder.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = inset
            rightMargin = inset
            // 6, measured: the reference's line is 6px clear of the bottom edge.
            bottomMargin = m?.u(PROGRESS_GAP) ?: 0
            height = h
        }
        val filled = (w * percent.coerceIn(0, 100) / 100f).roundToInt()
        progressFill.layoutParams = FrameLayout.LayoutParams(filled, FrameLayout.LayoutParams.MATCH_PARENT)
        progressFill.visibility = if (filled > 0) VISIBLE else INVISIBLE
    }

    /**
     * The one thing that says which card you are about to press.
     *
     * The ring is the reference's, unchanged at 3px of white. The growth is
     * added on top of it because a 3px hairline on a dark card is genuinely hard
     * to see from a couch, and growth is the other thing the reference does for
     * focus everywhere else in the player, including the glyph disc. Nothing is
     * drawn *over* the artwork of the cards you are not on, and the label is
     * white on every card, which is also what the reference does.
     */
    private fun applyEmphasis(focused: Boolean) {
        val scale = if (focused) FOCUS_SCALE else 1f
        scaleX = scale
        scaleY = scale
        redrawBackground()
    }

    /**
     * Redraws the card's plate and ring.
     *
     * Two drawables at the same radius, one on the card and one on the frame, so
     * the artwork is cut to the card's shape while the ring is still drawn in
     * full on top of it. One drawable cannot do both: `clipToOutline` trims the
     * canvas to the shape's path, and a stroke straddles that path, so the ring
     * clips itself away. That is why the ring was a 6px stroke and appeared as
     * a one-pixel hairline.
     *
     * The stroke width goes through the metrics rather than through `dp`, so a
     * ring measured at 3px on a 1080p television is 3px on a 4K one instead of
     * being multiplied by the density a second time.
     */
    private fun redrawBackground() {
        val radius = baseRadius.takeIf { it > 0 } ?: dp(8)
        // Behind the artwork: the surface the card is, and nothing else.
        background = plate(radius, null)
        // Over the artwork: the ring.
        //
        // It cannot be the card's background. The frame is a child of exactly the
        // card's size and it holds the artwork, so a ring drawn behind the
        // children is under the poster and only its outermost sliver should ever
        // have shown. In practice none of it did: measured on the device, a 12px
        // ring on the background produced not one pixel, while the same ring with
        // the frame hidden was plainly visible. A foreground is drawn after the
        // children, so the ring is on top of the picture by construction and
        // cannot be argued out of existence by whatever goes in the frame.
        foreground = ringDrawable(radius, if (hasFocus()) FOCUS_STROKE else idleStroke)
        frame.background = clipOnly()
        frame.clipToOutline = true
    }

    /**
     * The ring on its own, with no fill, over a hairline that keeps it legible.
     *
     * A single orange stroke reads on the left and the right and vanishes on the
     * top and the bottom, and it is not the artwork's fault so much as the
     * colour's. Measured on the device with a 4px stroke: the two sides came out
     * as 3px of solid orange over 216 rows, because outside them is the dark
     * strip between the cards, while the top and bottom rows came out blended
     * into the still (134,101,109 where the orange alone is 244,117,33). The
     * same orange on a dark frame reads; the same orange on a bright one
     * disappears into it.
     *
     * So the ring carries its own contrast: a dark hairline just inside it, and
     * a stroke thick enough that three of its four pixels are outside the
     * artwork and therefore always solid. Two layers, the hairline inset so the
     * two never overlap, and the whole thing on the foreground so the artwork
     * cannot be argued over it.
     */
    private fun ringDrawable(radius: Int, stroke: Int): Drawable? {
        if (stroke == IDLE_STROKE) return null
        val width = metrics?.u(RING_W) ?: 4
        val ring = GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(width, stroke)
        }
        if (width <= 4) return ring
        return LayerDrawable(arrayOf(ring, hairline(radius, width))).apply {
            // The hairline sits inside the ring rather than under it, so the ring
            // stays a clean band and the dark line only ever touches the artwork.
            setLayerInset(1, width / 2, width / 2, width / 2, width / 2)
        }
    }

    /**
     * The one-pixel dark line that separates the ring from the still.
     *
     * Dark rather than black: pure black is a drawn border of its own, and this
     * line's whole job is to make the orange look like it has an edge.
     */
    private fun hairline(radius: Int, inset: Int): Drawable = GradientDrawable().apply {
        cornerRadius = (radius - inset).toFloat().coerceAtLeast(0f)
        setColor(Color.TRANSPARENT)
        setStroke(metrics?.u(1f) ?: 1, HAIRLINE)
    }

    /**
     * A shape that is there only for its outline.
     *
     * `clipToOutline` works off the background's outline, so the artwork's
     * corners need a rounded shape to be cut against. It has to be transparent,
     * because the thing that is actually drawn on the card is the card's own
     * background, and anything opaque in here would cover the ring.
     */
    private fun clipOnly(radius: Int = baseRadius.takeIf { it > 0 } ?: dp(8)): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(Color.TRANSPARENT)
        }

    /**
     * The card's own surface, with an optional outline.
     *
     * A solid fill, which is the point. A GradientDrawable with a transparent
     * colour and a stroke is a frame, not a card, and a frame drawn over the
     * video is a hole with a border.
     */
    private fun plate(radius: Int = baseRadius.takeIf { it > 0 } ?: dp(8), stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(PLATE)
            if (stroke != null && stroke != IDLE_STROKE) {
                val m = metrics
                setStroke(m?.u(RING_W) ?: 4, stroke)
            }
        }

    fun applyMetrics(m: TvMetrics) {
        metrics = m
        baseRadius = m.u(8f)
        poster.scaleType = ImageView.ScaleType.CENTER_CROP
    }

    /**
     * Gives the card its width, and the artwork height follows from it.
     *
     * 16:9 is worked out here rather than left to the layout, so the strip's
     * total height is known before it is measured. That is what lets the player
     * decide how much room the carousel needs before it hands any over.
     *
     * The card is the artwork and nothing else: the label and the progress line
     * are both inside it now, so the card's height is exactly the frame's. The
     * ring is drawn inward by the outline rather than added around the outside,
     * so it does not change the size either.
     */
    fun sizeToWidth(widthPx: Int) {
        val frameH = widthPx * 9 / 16
        cardW = widthPx
        // Fixed width on the frame, not MATCH_PARENT. Measured as a match-parent
        // it follows whatever the parent happens to be, which is how a card asked
        // for 384 came out 292.
        frame.layoutParams = LayoutParams(widthPx, frameH)
        (layoutParams as? LayoutParams)?.let {
            it.height = frameH
            layoutParams = it
        }
        setProgress(0)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        // White, like the focus disc in the action row. The orange is reserved
        // for "this is the one you are on", so the two ideas never wear the same
        // colour, which is the rule the reference holds to throughout.
        applyEmphasis(gainFocus)
    }

    /**
     * Keeps the card at the width the strip gave it.
     *
     * The strip is a horizontal row, so a card in it is measured with a width
     * spec that is not EXACTLY: either UNSPECIFIED, from the scroll view, or
     * AT_MOST, from the row. A vertical LinearLayout in that position takes its
     * width from its widest child, and every child here is either MATCH_PARENT
     * or a short piece of label text, so the cards came out 292 wide instead of
     * the 384 they were asked for and quietly overlapped their neighbours.
     *
     * The strip is what decided these cards are 384 wide, and that decision has
     * to survive the measuring pass, so anything that is not already exact is
     * turned into an exact spec of our own width.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val requested = layoutParams?.width ?: 0
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY && requested > 0) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(requested, MeasureSpec.EXACTLY),
                heightMeasureSpec
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "DonghuaArt"
        const val PLACEHOLDER = 0xFF1B2430.toInt()

        /**
         * The surface the card is drawn on.
         *
         * Dark enough to sit under white text without glare, light enough to
         * read as a panel rather than as a hole. This is the colour that was
         * missing, and its absence is what made the card look transparent.
         */
        const val PLATE = 0xFF141A22.toInt()

        /**
         * The unwatched part of the progress line.
         *
         * Barely there, and that is measured. On the reference a card you have
         * not started shows no visible line at all, only a hint of one; the line
         * you actually see is the white fill. At 35% the track was a bright rule
         * drawn across every poster in the row, which is a different graphic
         * from the one being copied.
         */
        const val TRACK = 0x1AFFFFFF

        const val NOW_PLAYING = "▶  Reproduciendo"

        /**
         * The progress line's geometry, measured on the reference's card:
         * 4px tall at y659..664, inset 13 from each side, sitting 6px clear of
         * the artwork's bottom edge.
         */
        const val PROGRESS_H = 4f
        const val PROGRESS_INSET = 13f
        const val PROGRESS_GAP = 6f

        /**
         * The label's inset from the left edge, measured on the reference: its
         * "S1 E1" starts 18px in. A shade more than the progress line's 13,
         * because the reference's text is inset from the card while the line is
         * inset from the card's own border.
         */
        const val LABEL_INSET = 18f

        /**
         * Outline states.
         *
         * The focus ring is the accent orange rather than the white the
         * reference uses on its own card. That is a deliberate departure: the
         * cards here sit on a dark picture with a white heading above them and a
         * white label on every one, so a white ring reads as another piece of
         * text and the chosen card has to be hunted for. The orange is already
         * this project's "you are on this one" colour, it is the one the scrub
         * bar and the server tag use, and a viewer who has learnt it in the
         * control row reads it in the carousel without being told.
         *
         * Nothing else in the row is orange, so it cannot be confused with a
         * chapter's own artwork.
         */
        val FOCUS_STROKE = 0xFFF47521.toInt()
        val IDLE_STROKE = 0x00000000

        /**
         * The ring's width, in reference pixels at 1080p.
         *
         * The reference's is 3px of white, and white is legible over any still
         * because nothing in a picture is white by accident. Orange is not, so
         * this is 6 and not 4: at 4 the measured ring lost its top and bottom
         * edges into the artwork, and at 6 three of every four pixels sit
         * outside the still against the dark strip, which is the same band the
         * left and right edges were already reading cleanly from.
         */
        const val RING_W = 6f

        /** The dark line inside the ring. See [ringDrawable]. */
        const val HAIRLINE = 0xB3000000.toInt()

        /**
         * How far the focused card grows.
         *
         * Small, and small on purpose. The reference's own focus grows the glyph
         * disc by a similar amount, so this is the same gesture rather than a
         * new one; but it is not doing the work of saying which card is chosen,
         * the ring is. It is there so the ring is not the *only* thing.
         */
        const val FOCUS_SCALE = 1.04f
    }
}
