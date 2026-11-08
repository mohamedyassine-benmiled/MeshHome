package com.mybm.meshhome

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Small, density-independent line icons; no image assets or icon library required. */
internal class MeshIcon(private val name: String, color: Int, private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        when (name) {
            "home" -> {
                canvas.drawArc(3f, 3f, 21f, 21f, -50f, 280f, false, paint)
                canvas.drawLine(12f, 2f, 12f, 11f, paint)
            }
            "devices" -> {
                canvas.drawRoundRect(2f, 3f, 16f, 15f, 2f, 2f, paint)
                canvas.drawLine(9f, 15f, 9f, 20f, paint)
                canvas.drawLine(5f, 20f, 12f, 20f, paint)
                canvas.drawRoundRect(15f, 9f, 22f, 21f, 1.5f, 1.5f, paint)
            }
            "routing" -> {
                canvas.drawPath(Path().apply {
                    moveTo(5f, 5f); lineTo(5f, 17f); quadTo(5f, 19f, 7f, 19f); lineTo(16f, 19f)
                    moveTo(5f, 11f); lineTo(16f, 11f)
                }, paint)
                canvas.drawCircle(5f, 3f, 2f, paint)
                canvas.drawCircle(19f, 11f, 3f, paint)
                canvas.drawCircle(19f, 19f, 3f, paint)
            }
            else -> {
                listOf(5f to 8f, 12f to 16f, 19f to 10f).forEach { (y, x) ->
                    canvas.drawLine(3f, y, x - 3f, y, paint)
                    canvas.drawLine(x + 3f, y, 21f, y, paint)
                    canvas.drawCircle(x, y, 3f, paint)
                }
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity is unused on modern Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
