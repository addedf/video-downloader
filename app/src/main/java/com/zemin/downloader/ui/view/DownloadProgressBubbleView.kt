package com.zemin.downloader.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.zemin.downloader.R

class DownloadProgressBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val arcBounds = RectF()
    private var progress: Int? = null
    private var rotationDegrees = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationDegrees = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun showIndeterminate(description: String) {
        contentDescription = description
        progress = null
        visibility = VISIBLE
        if (!animator.isRunning) animator.start()
        invalidate()
    }

    fun showProgress(value: Int, description: String) {
        contentDescription = description
        progress = value.coerceIn(0, 100)
        visibility = VISIBLE
        animator.cancel()
        invalidate()
    }

    fun hide() {
        animator.cancel()
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = dp(4f)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) / 2f - dp(3f)

        fillPaint.color = ContextCompat.getColor(context, R.color.white)
        fillPaint.setShadowLayer(dp(5f), 0f, dp(2f), 0x40000000)
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        fillPaint.clearShadowLayer()

        trackPaint.strokeWidth = stroke
        trackPaint.color = ContextCompat.getColor(context, R.color.dy_stroke)
        progressPaint.strokeWidth = stroke
        progressPaint.color = ContextCompat.getColor(context, R.color.dy_cyan_dark)
        val inset = dp(8f)
        arcBounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)

        val value = progress
        if (value == null) {
            canvas.drawArc(arcBounds, rotationDegrees, 92f, false, progressPaint)
        } else {
            canvas.drawArc(arcBounds, -90f, value * 3.6f, false, progressPaint)
        }

        textPaint.color = ContextCompat.getColor(context, R.color.dy_primary)
        textPaint.textSize = dp(if (value == null) 13f else 11f)
        val label = value?.let { "$it%" } ?: "↓"
        val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, centerX, baseline, textPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
