package com.zemin.downloader.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.zemin.downloader.R
import com.zemin.downloader.ui.motion.UiMotion

class DownloadProgressBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private enum class Mode { INDETERMINATE, PROGRESS, SUCCESS, ERROR }

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
    private var mode = Mode.INDETERMINATE
    private var displayedProgress = 0f
    private var rotationDegrees = 0f
    private var progressAnimator: ValueAnimator? = null
    private val spinnerAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
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
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    fun showIndeterminate(description: String) {
        contentDescription = description
        val startsNewOperation = visibility != VISIBLE || mode != Mode.INDETERMINATE
        mode = Mode.INDETERMINATE
        if (startsNewOperation) displayedProgress = 0f
        progressAnimator?.cancel()
        revealIfNeeded()
        if (UiMotion.animationsEnabled() && !spinnerAnimator.isRunning) spinnerAnimator.start()
        invalidate()
    }

    fun showProgress(value: Int, description: String) {
        contentDescription = description
        if (visibility != VISIBLE) displayedProgress = 0f
        mode = Mode.PROGRESS
        spinnerAnimator.cancel()
        revealIfNeeded()
        animateProgressTo(value.coerceIn(0, 100).toFloat())
    }

    fun showSuccess(description: String) {
        contentDescription = description
        mode = Mode.SUCCESS
        spinnerAnimator.cancel()
        progressAnimator?.cancel()
        displayedProgress = 100f
        revealIfNeeded()
        UiMotion.emphasize(this)
        invalidate()
    }

    fun showError(description: String) {
        contentDescription = description
        mode = Mode.ERROR
        spinnerAnimator.cancel()
        progressAnimator?.cancel()
        revealIfNeeded()
        UiMotion.reject(this)
        invalidate()
    }

    fun hide() {
        spinnerAnimator.cancel()
        progressAnimator?.cancel()
        UiMotion.fadeOut(this)
    }

    override fun onDetachedFromWindow() {
        spinnerAnimator.cancel()
        progressAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = dp(4f)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) / 2f - dp(3f)

        fillPaint.color = ContextCompat.getColor(context, R.color.white)
        canvas.drawCircle(centerX, centerY, radius, fillPaint)

        trackPaint.strokeWidth = stroke
        trackPaint.color = ContextCompat.getColor(context, R.color.dy_stroke)
        progressPaint.strokeWidth = stroke
        progressPaint.color = ContextCompat.getColor(
            context,
            when (mode) {
                Mode.SUCCESS -> R.color.dy_success
                Mode.ERROR -> R.color.dy_error
                else -> R.color.dy_cyan_dark
            },
        )
        val inset = dp(8f)
        arcBounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)

        when (mode) {
            Mode.INDETERMINATE -> canvas.drawArc(
                arcBounds,
                rotationDegrees,
                92f,
                false,
                progressPaint,
            )
            Mode.PROGRESS -> canvas.drawArc(
                arcBounds,
                -90f,
                displayedProgress * 3.6f,
                false,
                progressPaint,
            )
            Mode.SUCCESS, Mode.ERROR -> canvas.drawArc(
                arcBounds,
                -90f,
                360f,
                false,
                progressPaint,
            )
        }

        textPaint.color = ContextCompat.getColor(
            context,
            when (mode) {
                Mode.SUCCESS -> R.color.dy_success
                Mode.ERROR -> R.color.dy_error
                else -> R.color.dy_primary
            },
        )
        textPaint.textSize = dp(if (mode == Mode.PROGRESS) 11f else 15f)
        val label = when (mode) {
            Mode.INDETERMINATE -> "↓"
            Mode.PROGRESS -> "${displayedProgress.toInt()}%"
            Mode.SUCCESS -> "✓"
            Mode.ERROR -> "!"
        }
        val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, centerX, baseline, textPaint)
    }

    private fun revealIfNeeded() {
        if (visibility != VISIBLE) {
            UiMotion.popIn(this)
        } else {
            animate().cancel()
            alpha = 1f
        }
    }

    private fun animateProgressTo(target: Float) {
        progressAnimator?.cancel()
        if (!UiMotion.animationsEnabled()) {
            displayedProgress = target
            invalidate()
            return
        }
        progressAnimator = ValueAnimator.ofFloat(displayedProgress, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayedProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
