package com.donghua.tv.player

import android.util.TypedValue
import android.view.View
import android.widget.TextView
import kotlin.math.min
/**
 * Resolution independent metrics for the TV player.
 *
 * Why this exists, and why it is not just `dp`:
 *
 * On a phone, `dp` is a reasonable proxy for physical size, so laying out in dp
 * "just works" across devices. On a TV it does not. A 4K TV and a 1080p TV are
 * physically the same size, and they routinely report the *same* density, so a
 * 42dp button ends up twice as small on the 4K panel in physical terms. The
 * other extreme is common too, cheap 720p boxes reporting density 1.0, where
 * the same 42dp becomes a 1280dp wide viewport and every fixed size looks
 * different again.
 *
 * A real spread of viewport heights, all 16:9, from actual TV devices:
 *
 *   720p @ 1.0  ->  720 px     1080p @ 1.0 -> 1080 px
 *   720p @ 1.33 ->  720 px     1080p @ 2.0 -> 1080 px
 *   720p @ 2.0  ->  720 px     4K    @ 2.0 -> 2160 px
 *   720p @ 3.0  ->  720 px     4K    @ 3.0 -> 2160 px
 *
 * Density varies, pixels do not. So the design is authored against a reference
 * 1080px height and scaled by the real viewport height. That makes every size
 * in the HUD occupy the same fraction of the screen on every television, which
 * is the only definition of "responsive" that means anything at 3 metres.
 *
 * The same mistake is made with the safe area: many sets crop the outer few
 * percent, so margins are taken as a fraction of the screen rather than a
 * fixed dp.
 */
class TvMetrics private constructor(
    val widthPx: Int,
    val heightPx: Int,
    // Kept as a property, not just a constructor argument, because textPx needs
    // it from a member function and constructor arguments are not visible there.
    private val fontScale: Float,
) {
    /** Screen width as a fraction, for the handful of things that must be relative to it. */
    fun wp(fraction: Float): Int = (widthPx * fraction).toInt()

    /** Screen height as a fraction. */
    fun hp(fraction: Float): Int = (heightPx * fraction).toInt()

    /**
     * Converts a size authored in reference pixels (that is, the px it should
     * be on a 1080p screen) into real pixels for this screen.
     */
    fun u(referencePx: Float): Int = (referencePx * scale + 0.5f).toInt()

    /**
     * Converts a reference pixel size into scaled pixels. For view dimensions
     * that have to be laid out before the first measure pass.
     */
    fun f(referencePx: Float): Float = referencePx * scale

    /** Text size in sp for this screen, honouring but clamping the system font scale. */
    fun sp(referenceSp: Float): Float = referenceSp * textScale

    /**
     * Text height in real pixels, on the same scale as [u].
     *
     * This exists because [sp] cannot be used beside [u]. [sp] returns sp units,
     * which Android then multiplies by the density a second time, while [u]
     * already returns final pixels. Mixing them put 60px of text inside a 50px
     * row on a density-320 set, and the text was clipped away entirely, which is
     * why the server names rendered as empty buttons. Everything that is laid
     * out in [u] has to be measured in [textPx].
     *
     * The system font scale is still honoured, but capped harder than in [textScale],
     * because here it multiplies a height that some fixed rows are sized against.
     */
    fun textPx(referencePx: Float): Float = referencePx * (scale * fontScale.coerceIn(MIN_FONT, MAX_FONT))

    val safeStart: Int get() = wp(TITLE_SAFE_WIDTH)
    val safeEnd: Int get() = wp(TITLE_SAFE_WIDTH)
    val safeTop: Int get() = hp(TITLE_SAFE_HEIGHT)
    val safeBottom: Int get() = hp(TITLE_SAFE_HEIGHT)

    /**
     * The action-safe margin, for anything the viewer has to aim at.
     *
     * Related to the title-safe margin above but deliberately smaller. Text has
     * to clear the overscan on every set, while a control only has to stay on
     * screen, so it can sit closer to the edge. Amazon's own TV template draws
     * the same two tiers, and 3.5% versus 5% is a useful amount of width to get
     * back on a control row.
     */
    val actionStart: Int get() = wp(ACTION_SAFE_WIDTH)
    val actionEnd: Int get() = wp(ACTION_SAFE_WIDTH)
    val actionTop: Int get() = hp(ACTION_SAFE_HEIGHT)
    val actionBottom: Int get() = hp(ACTION_SAFE_HEIGHT)

    /** Clamped so an absurdly short window cannot render the HUD unusable. */
    private val scale: Float = (heightPx / REFERENCE_HEIGHT)
        .coerceIn(MIN_SCALE, MAX_SCALE)

    /**
     * Identifies this viewport, so a size change can be detected and the HUD
     * reapplied. A TV can change its output resolution at any time, and without
     * this the player would keep the scale of the screen it started on.
     */
    val key: String get() = "$widthPx x $heightPx"

    /**
     * A very large system font is a real setting on TVs and used to clip the
     * fixed height rows. It is honoured, but only so far, because past a point
     * it stops being accessibility and starts being a broken layout.
     */
    private val textScale: Float = (fontScale * scale).coerceIn(MIN_TEXT, MAX_TEXT)

    /**
     * Applies the focus treatment to a control: a small grow plus a glow.
     *
     * The grow is the part that actually reads as focus from across a room, more
     * than the colour change does. Both it and the glow are set through
     * `scaleX`/`scaleY` and a shadow layer, which are compositor concerns, so
     * this does not trigger a re-layout per frame. That matters here: the
     * alternative, animating the background, would redraw the row on the UI
     * thread while the video is decoding.
     */
    fun applyFocus(view: View, focused: Boolean) {
        val target: Float = if (focused) FOCUS_SCALE else 1f
        view.animate().cancel()
        view.scaleX = target
        view.scaleY = target
        // Elevation takes a float in pixels, hence the explicit type.
        val lift: Float = if (focused) u(SPACE_SM).toFloat() else 0f
        view.elevation = lift
    }

    companion object {
        /** All HUD sizes in this project are authored against a 1080p height. */
        const val REFERENCE_HEIGHT = 1080f

        /**
         * Title-safe margins, 5% of each screen dimension. Several televisions
         * crop the outer few percent, and text that runs into that band is
         * simply gone, so this is the tier used for anything that has to be read.
         */
        const val TITLE_SAFE_WIDTH = 0.05f
        const val TITLE_SAFE_HEIGHT = 0.05f

        /**
         * Action-safe margins, 3.5%. Used for controls, which only need to stay
         * on screen and can sit closer to the edge than text can.
         */
        const val ACTION_SAFE_WIDTH = 0.035f
        const val ACTION_SAFE_HEIGHT = 0.035f

        /**
         * A small spacing scale, in reference pixels at 1080p. Using named steps
         * instead of one-off numbers is what keeps the rhythm even once
         * everything is multiplied by the screen scale.
         */
        const val SPACE_XS = 8f
        const val SPACE_SM = 12f
        const val SPACE_MD = 20f
        const val SPACE_LG = 32f
        const val SPACE_XL = 48f

        /**
         * How much of a chapter's artwork is left on screen while the control
         * row is still up, in reference pixels at 1080p.
         *
         * 47, measured on the reference: a pixel scan of its shared carousel
         * puts the header's cap line at y981 and the top edge of the cards at
         * y1033, so 1080 - 1033 is the 47px of poster it shows. What is above
         * that is the header and the gap under it, and both of those are the
         * layout's business rather than a number, so the band is worked out from
         * them at the moment the strip is placed rather than guessed here.
         *
         * The 140 this replaces was too much. It was chosen so the poster read
         * from the couch, and it did, but it also pushed the cards up over the
         * control row's own label space and made the peek a slab rather than a
         * hint. The reference shows this much and it reads.
         */
        const val STRIP_PEEK_CARD = 47f

        /** Smallest comfortable focus target, matching the platform guidance. */
        const val MIN_FOCUS_TARGET = 48f

        /** Breathing room between an element and its focus indicator. */
        const val FOCUS_PADDING = 4f

        /** How far a focused element grows, which is most of what reads as focus. */
        const val FOCUS_SCALE: Float = 1.05f

        /**
         * Clear space kept below the action row for the label a focused glyph
         * grows underneath itself.
         *
         * The label is the whole of the reference's affordance: a glyph on its
         * own says "replay", a glyph with "Start over" under it says what
         * pressing it will do. It is only drawn while focused, so the space for
         * it has to be reserved whether or not it is currently on screen, or the
         * first button to be focused is the one that gets clipped.
         */
        const val FOCUS_LABEL_ROOM = 90f

        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 2.4f
        private const val MIN_TEXT = 0.8f
        private const val MAX_TEXT = 1.75f

        /**
         * The system font scale is capped tighter for [textPx] than for [sp].
         *
         * A 1.5x font is a reasonable accessibility setting. At 1.5x in a row
         * sized for 1.0x the text is clipped rather than merely large, so the
         * ceiling here is what keeps a fixed-height row usable.
         */
        private const val MIN_FONT = 0.85f
        private const val MAX_FONT = 1.25f

        /**
         * Measures against a view so the metrics follow the surface the HUD is
         * actually laid out on, which is not always the raw display: a TV can be
         * forced to a lower output resolution than its panel.
         */
        fun from(root: View, fontScale: Float): TvMetrics {
            val w = if (root.width > 0) root.width else root.resources.displayMetrics.widthPixels
            val h = if (root.height > 0) root.height else root.resources.displayMetrics.heightPixels
            return TvMetrics(maxOf(w, 1), maxOf(h, 1), fontScale)
        }

        /** Convenience for the smallest dimension, used for min-size clamping. */
        fun minSide(m: TvMetrics): Int = min(m.widthPx, m.heightPx)
    }
}

/**
 * Sets text size in pixels, on the same scale as [TvMetrics.u].
 *
 * Kotlin's `textSize = x` maps to `setTextSize(float)`, which is interpreted in
 * **sp** and so gets multiplied by the density a second time. That is the wrong
 * unit to pair with a layout measured in [TvMetrics.u] pixels, and pairing the
 * two clipped text clean out of its own row. Set text through here instead.
 */
fun TextView.setTextPx(px: Float?) {
    if (px == null) return
    setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
}
