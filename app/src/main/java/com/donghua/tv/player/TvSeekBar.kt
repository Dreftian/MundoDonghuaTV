package com.donghua.tv.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat

/**
 * The player scrub bar.
 *
 * A plain [android.widget.ProgressBar] cannot express what a TV needs: a
 * buffered range that reads differently from the played range, a focus state
 * that is obvious from three metres, and a draggable thumb. It is drawn here
 * instead, which also keeps the layout free of a second seek widget.
 *
 * Every dimension comes from [TvMetrics], so the bar has the same visual weight
 * on a 720p stick and a 4K panel.
 */
class TvSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // The bar is operated with the remote, so it must be able to take focus.
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
    }

    private val rect = RectF()

    private var trackHeight = 6f
    private var thumbRadius = 0f
    private var focusStroke = 3f
    private var thumbInset = 0f
    private var ringColor = 0xFFFFFFFF.toInt()

    var accentColor: Int = ORANGE
        set(value) {
            field = value
            playedPaint.color = value
            thumbPaint.color = value
            invalidate()
        }

    /** 0..1 */
    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    /** 0..1, drawn as a dimmer band behind the played range. */
    var buffered: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    /**
     * While the user is scrubbing this shows the position being moved to,
     * distinct from the position actually playing.
     */
    var scrubTarget: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    var metrics: TvMetrics? = null

    /** Label shown above the bar while scrubbing. */
    private var tooltip: TextView? = null

    fun applyMetrics(m: TvMetrics, dark: Boolean) {
        metrics = m
        trackHeight = m.f(6f)
        thumbRadius = m.f(11f)
        focusStroke = m.f(3f)
        thumbInset = m.f(3f)
        trackPaint.color = if (dark) TRACK else TRACK_LIGHT
        bufferedPaint.color = if (dark) BUFFERED else BUFFERED_LIGHT
        focusPaint.color = if (dark) 0xFFFFFFFF.toInt() else 0xFF10151E.toInt()
        // Remembered so the resting ring can be restored after the focused one
        // is drawn with a different colour.
        ringColor = if (dark) 0xFFFFFFFF.toInt() else 0xFF10151E.toInt()
        playedPaint.color = accentColor
        thumbPaint.color = accentColor
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val m = metrics
        val h = if (m != null) {
            // Room for the thumb plus its focus ring, not just the track, so the
            // ring is never clipped on an unremarkable display.
            (((thumbRadius + focusStroke) * 2) + m.f(10f)).toInt()
        } else {
            (48 * resources.displayMetrics.density).toInt()
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val m = metrics ?: return
        val w = width.toFloat()
        val cy = height / 2f

        val usable = w - thumbRadius * 2
        val left = thumbRadius

        // While scrubbing, the thumb tracks the previewed position, so the whole
        // bar shows where the viewer is about to land rather than where the
        // playhead currently is.
        val shown = scrubTarget ?: progress
        val thumbX = left + usable * shown.coerceIn(0f, 1f)

        val half = trackHeight / 2f

        // Unplayed track.
        rect.set(left, cy - half, left + usable, cy + half)
        canvas.drawRoundRect(rect, half, half, trackPaint)

        // Buffered range. Drawn only where it is ahead of what has played,
        // otherwise it would read as if already watched.
        if (buffered > shown) {
            val bEnd = left + usable * buffered.coerceIn(0f, 1f)
            rect.set(thumbX, cy - half, bEnd, cy + half)
            canvas.drawRoundRect(rect, half, half, bufferedPaint)
        }

        // Played range.
        if (progress > 0f) {
            rect.set(left, cy - half, thumbX, cy + half)
            canvas.drawRoundRect(rect, half, half, playedPaint)
        }

        // The scrub preview thumb, while the user is moving it.
        val target = scrubTarget
        if (target != null) {
            val tx = left + usable * target.coerceIn(0f, 1f)
            val tr = thumbRadius * m.f(1.15f)
            rect.set(tx - tr, cy - tr, tx + tr, cy + tr)
            canvas.drawCircle(tx, cy, tr, focusPaint)
        }

        // Focus ring. This is the only thing on a TV that says "the remote is
        // pointing here", so when focused it is drawn large, solid and bright;
        // when not focused there is no ring at all, only the thumb. An always on
        // faint ring read as a second marker and made the bar look unfocused
        // even while it was.
        if (isFocused) {
            val ringExtra = focusStroke + m.f(3f)
            focusPaint.color = ringColor
            canvas.drawCircle(thumbX, cy, thumbRadius + ringExtra, focusPaint)
        }

        canvas.drawCircle(thumbX, cy, thumbRadius, thumbPaint)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    /** Shows a time label floating above the bar, used while scrubbing. */
    fun showTooltip(text: String) {
        val m = metrics
        val tv = tooltip ?: TextView(context).apply {
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            includeFontPadding = false
            if (m != null) {
                textSize = m.sp(26f)
                setPadding(m.u(22f), m.u(10f), m.u(22f), m.u(10f))
            }
            tooltip = this
            // Added to the bar's own parent so it can sit above the bar without
            // changing the HUD's layout, and so it is removed with the bar.
            (parent as? ViewGroup)?.addView(this)
        }
        if (m != null) tv.textSize = m.sp(26f)
        tv.text = text
        tv.visibility = VISIBLE
        tv.bringToFront()
        tv.post {
            val gap = m?.f(10f) ?: 0f
            tv.x = ((this.width - tv.width) / 2f).coerceAtLeast(0f)
            tv.y = -tv.height.toFloat() - gap
            // On a very short screen there may be no room above, so fall below.
            if (tv.y < 0) tv.y = height.toFloat() + gap
        }
    }

    fun hideTooltip() {
        tooltip?.visibility = GONE
    }

    override fun onDetachedFromWindow() {
        tooltip?.let { (it.parent as? ViewGroup)?.removeView(it) }
        tooltip = null
        super.onDetachedFromWindow()
    }

    companion object {
        private val ORANGE = 0xFFF47521.toInt()
        private val TRACK = 0x40FFFFFF.toInt()
        private val BUFFERED = 0x80FFFFFF.toInt()
        private val TRACK_LIGHT = 0x1F000000
        private val BUFFERED_LIGHT = 0x59000000
    }
}
