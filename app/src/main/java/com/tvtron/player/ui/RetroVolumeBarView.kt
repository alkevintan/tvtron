package com.tvtron.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.tvtron.player.R

/**
 * 90s segmented green LED volume OSD. Pure horizontal block array,
 * black background, fades out 2s after last update.
 */
class RetroVolumeBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.osd_bg)
    }
    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.osd_segment_on)
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.osd_segment_dim)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.osd_label)
        textSize = context.resources.displayMetrics.density * 14f
        isFakeBoldText = true
    }

    var max: Int = 15
    var value: Int = 0
        set(v) {
            field = v.coerceIn(0, max)
            invalidate()
        }

    private val rect = RectF()
    private val cornerR = context.resources.displayMetrics.density * 2f
    private val padDp = context.resources.displayMetrics.density * 12f
    private val gapDp = context.resources.displayMetrics.density * 4f

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { fadeOut() }
    private var fadeAnim: ValueAnimator? = null

    fun show(volume: Int, max: Int) {
        this.max = max
        this.value = volume
        fadeAnim?.cancel()
        alpha = 1f
        visibility = VISIBLE
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 2000L)
    }

    private fun fadeOut() {
        fadeAnim = ValueAnimator.ofFloat(alpha, 0f).apply {
            duration = 350L
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
        postDelayed({ visibility = GONE }, 400L)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Background panel
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerR * 2, cornerR * 2, bgPaint)

        // Label
        val label = "VOL"
        canvas.drawText(label, padDp, padDp + labelPaint.textSize, labelPaint)

        // Segments below label
        val barTop = padDp + labelPaint.textSize + padDp * 0.6f
        val barBottom = h - padDp
        val barLeft = padDp
        val barRight = w - padDp
        val totalW = barRight - barLeft
        val segW = (totalW - gapDp * (max - 1)) / max

        for (i in 0 until max) {
            val x0 = barLeft + i * (segW + gapDp)
            rect.set(x0, barTop, x0 + segW, barBottom)
            canvas.drawRoundRect(rect, cornerR, cornerR, if (i < value) onPaint else dimPaint)
        }
    }
}
