package com.zemin.downloader.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.zemin.downloader.R

class DyTabButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val cornerRadius = dp(6f)

    init {
        gravity = Gravity.CENTER
        includeFontPadding = false
        minHeight = dp(26f).toInt()
        textSize = 12f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = ContextCompat.getColor(context, if (isSelected) R.color.white else R.color.dy_surface)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        setTextColor(ContextCompat.getColor(context, if (isSelected) R.color.dy_primary else R.color.dy_text_muted))
        super.onDraw(canvas)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
