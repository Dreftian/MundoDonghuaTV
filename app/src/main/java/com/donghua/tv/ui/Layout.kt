package com.donghua.tv.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * Screen-aware sizing for the browse screens.
 *
 * The same trap the player HUD had to solve applies here. A television reports a
 * density that says nothing about its physical size, so a 55 inch 1080p set and
 * a 32 inch 720p set routinely report identical metrics. Anything sized in dp
 * therefore lands at a different apparent size on every panel, and a fixed
 * column count is the worst offender: six posters on a 4K screen become postage
 * stamps, and on a small one they overflow the width.
 *
 * So sizes are scaled from the physical width against a 1920px reference, and
 * the column count is derived from that scale rather than hardcoded.
 */
object TvLayout {

    /** Authoring reference, matching the player HUD's 1080p baseline. */
    private const val REFERENCE_WIDTH_PX = 1920f

    private const val MIN_SCALE = 0.72f
    private const val MAX_SCALE = 1.9f

    /** Widest and narrowest a poster may be, which is what really pins columns. */
    private const val MIN_CARD_WIDTH_PX = 190f
    private const val MAX_CARD_WIDTH_PX = 330f

    private const val MIN_COLUMNS = 4
    private const val MAX_COLUMNS = 8

    /**
     * How much bigger a focused card is drawn than an unfocused one.
     *
     * Shared rather than written into each card, because the grid spacing below
     * is derived from it and a card that used a different value would quietly
     * start overlapping its neighbours again.
     */
    const val CARD_FOCUS_SCALE = 1.07f

    /** Poster proportions, as height over width. The card body is this tall. */
    private const val CARD_POSTER_RATIO = 2.95f / 2f

    /**
     * Approximate height of the two title lines under the poster, as a fraction
     * of the card width.
     *
     * Only the gap maths needs it, and it needs the card's full height, so it is
     * estimated here rather than measured. A guess is the right trade: it only
     * sets how much breathing room a row gets, and erring high costs a few dp of
     * whitespace rather than an overlap.
     */
    private const val CARD_TEXT_RATIO = 0.42f

    /** Side padding and the gap between columns, matching what the grids use. */
    const val GRID_SIDE_PADDING_DP = 28f
    const val GRID_H_GAP_DP = 14f

    /**
     * Clear space left around a focused card beyond its own growth.
     *
     * Without it the focus ring lands exactly on the neighbouring row, which is
     * what the home screen was doing: a 1.07x card is about 9dp taller on each
     * side than the 6dp row gap it was given, so the ring was drawn straight
     * over the card below.
     */
    private const val GRID_FOCUS_SLACK_DP = 6f

    /**
     * The width of one poster in dp, as the grid will really lay it out.
     *
     * Taken from the pixels the grid has to work with rather than from the
     * authoring reference, so it stays correct once the column count has been
     * chosen.
     */
    fun cardWidthDp(context: Context): Float {
        val density = context.resources.displayMetrics.density.coerceAtLeast(0.01f)
        val cols = columns(context)
        val sidePx = GRID_SIDE_PADDING_DP * density * 2f
        val gapPx = GRID_H_GAP_DP * density * (cols - 1)
        val usablePx = (widthPx(context) - sidePx - gapPx).coerceAtLeast(1f)
        return (usablePx / cols) / density
    }

    /** The full height of a card in dp: the poster plus its two title lines. */
    fun cardHeightDp(context: Context): Float =
        cardWidthDp(context) * (CARD_POSTER_RATIO + CARD_TEXT_RATIO)

    /**
     * How far a focused card grows past its cell, in dp, on the top and bottom.
     *
     * A `scale` in Compose grows a view about its centre, so the growth on each
     * side is half the total. The grids derive their row spacing from this,
     * which is what keeps a focused card off its neighbours on any screen size
     * instead of only on the one the spacing was guessed for.
     */
    fun cardFocusGrowthDp(context: Context): Float =
        cardHeightDp(context) * (CARD_FOCUS_SCALE - 1f) / 2f

    /** Row spacing: the focus growth plus a little clearance. */
    fun gridVerticalGapDp(context: Context): Float =
        cardFocusGrowthDp(context) + GRID_FOCUS_SLACK_DP

    /**
     * Padding above the first row and below the last.
     *
     * Same reasoning as the row gap, applied at the scroll edges: a focused card
     * at the top of the viewport otherwise grows up into the navigation bar, and
     * one at the bottom is cut off by the screen edge.
     */
    fun gridEdgePaddingDp(context: Context): Float =
        cardFocusGrowthDp(context) + GRID_FOCUS_SLACK_DP

    /**
     * The physical width the UI is actually being drawn into, in pixels.
     *
     * The display metrics are used rather than a screen-size qualifier: a 4K
     * panel that renders the interface at 1080p reports the render size, and
     * that is the right number to scale against, because it is what the viewer
     * can actually see.
     */
    fun widthPx(context: Context): Int =
        context.resources.displayMetrics.widthPixels.coerceAtLeast(1)

    /** Everything is authored against a 1920px-wide screen and scaled from it. */
    fun scale(context: Context): Float =
        (widthPx(context) / REFERENCE_WIDTH_PX).coerceIn(MIN_SCALE, MAX_SCALE)

    /**
     * Poster columns for this screen.
     *
     * Derived from a target card width rather than a fixed count, so a small set
     * gets fewer, larger posters instead of a grid too wide to read, and a large
     * one gets more without any card becoming unreadable.
     *
     * Floored, not rounded. Rounding up is what puts a grid past the edge of the
     * screen: 1280px at the scaled target works out to 5.5 cards, and rounding
     * that to 6 asks for 1380px of posters in 1280px of width. Flooring always
     * leaves the last fraction of a card as slack, which is also where the
     * column spacing comes from.
     */
    fun columns(context: Context): Int {
        val target = (REFERENCE_WIDTH_PX / 6f * scale(context))
            .coerceIn(MIN_CARD_WIDTH_PX, MAX_CARD_WIDTH_PX)
        return floor(widthPx(context) / target).toInt()
            .coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }
}

/**
 * The context used by the grid navigation maths.
 *
 * Those closures are plain functions, not composables, so they cannot read a
 * [LocalContext]. This is filled in once by [InstallTvLayout] at the root. The
 * application context is used deliberately: the width a screen draws into is a
 * property of the display, not of any one activity, so there is no reason for
 * the value to follow a configuration change.
 */
private var appContext: Context? = null

/**
 * Column count for the grids, readable from non-composable code.
 *
 * The grid navigation in every browse screen indexes with this, so it has to be
 * the same number the grid is actually drawn with. Routing both through here is
 * what stops them drifting apart, which is how "right" ends up dropping off the
 * end of a row.
 */
fun tvColumns(): Int = appContext?.let { TvLayout.columns(it) } ?: 6

/** Call once at the root composable. */
@Composable
fun InstallTvLayout() {
    val context = LocalContext.current
    appContext = context.applicationContext
    // Read the configuration so a genuine configuration change recomposes.
    LocalConfiguration.current
}

/** A scale factor for this screen, remembered across recompositions. */
@Composable
fun rememberTvScale(): Float {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    return remember(config, context) { TvLayout.scale(context) }
}

/** A dp value scaled from the 1920px authoring reference. */
@Composable
fun tvDp(value: Float): Dp = (value * rememberTvScale()).dp

/**
 * A dp value scaled but capped.
 *
 * Padding is the case that most needs the cap: growing it in step with the
 * screen is right, growing it without limit is what pushes a row of controls
 * into the screen edge on a very large panel.
 */
@Composable
fun tvDp(value: Float, max: Float): Dp = (value * rememberTvScale()).coerceAtMost(max).dp

/** The scale a focused card is drawn at. Shared by the cards and the grids. */
val cardFocusScale: Float get() = TvLayout.CARD_FOCUS_SCALE

/**
 * Row spacing for a poster grid.
 *
 * Reads as ordinary `Arrangement.spacedBy` at the call site, but the number is
 * derived from the focus growth rather than picked, so it cannot fall behind it.
 */
@Composable
fun gridVerticalGap(): Dp = TvLayout.gridVerticalGapDp(LocalContext.current).dp

/** Side padding and inter-column gap for a poster grid. */
@Composable
fun gridHorizontalGap(): Dp = TvLayout.GRID_H_GAP_DP.dp

@Composable
fun gridSidePadding(): Dp = TvLayout.GRID_SIDE_PADDING_DP.dp

/**
 * Padding for the top and bottom edges of a poster grid.
 *
 * Reserves the room a focused card grows into, so the row at the top of the
 * viewport is never clipped by the navigation bar, and the one at the bottom is
 * never cut off by the screen edge.
 */
@Composable
fun gridEdgePadding(): Dp = TvLayout.gridEdgePaddingDp(LocalContext.current).dp
