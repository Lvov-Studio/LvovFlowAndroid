package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * LvovFlow SpeedSparklineView — a mini line chart showing download speed
 * over the last N data points. Lightweight Canvas-based, no dependencies.
 */
class SpeedSparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxPoints = 60  // ~30 seconds at 500ms interval
    private val dataPoints = mutableListOf<Long>()

    // Line paint — gradient will be set in onSizeChanged
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Fill paint — gradient area under the line
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Dot paint — current value indicator
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF22C55E.toInt()
    }

    // Glow around dot
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x4422C55E
    }

    private val linePath = Path()
    private val fillPath = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Gradient from left (cyan) to right (green)
        val lineGradient = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            0xFF22D3EE.toInt(), 0xFF22C55E.toInt(),
            Shader.TileMode.CLAMP
        )
        linePaint.shader = lineGradient

        val fillGradient = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0x3022C55E, 0x00000000,
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = fillGradient
    }

    fun addSpeed(bytesPerSec: Long) {
        dataPoints.add(bytesPerSec)
        if (dataPoints.size > maxPoints) {
            dataPoints.removeAt(0)
        }
        invalidate()
    }

    fun clear() {
        dataPoints.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padding = 8f

        val maxVal = (dataPoints.maxOrNull() ?: 1L).coerceAtLeast(1024L) // min 1KB scale
        val count = dataPoints.size
        val stepX = (w - padding * 2) / (maxPoints - 1)
        val startX = w - padding - (count - 1) * stepX

        linePath.reset()
        fillPath.reset()

        for (i in dataPoints.indices) {
            val x = startX + i * stepX
            val y = h - padding - ((dataPoints[i].toFloat() / maxVal) * (h - padding * 2))

            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h - padding)
                fillPath.lineTo(x, y)
            } else {
                // Smooth curve using cubic bezier
                val prevX = startX + (i - 1) * stepX
                val prevY = h - padding - ((dataPoints[i - 1].toFloat() / maxVal) * (h - padding * 2))
                val cx = (prevX + x) / 2f
                linePath.cubicTo(cx, prevY, cx, y, x, y)
                fillPath.cubicTo(cx, prevY, cx, y, x, y)
            }
        }

        // Close fill path
        val lastX = startX + (count - 1) * stepX
        val lastY = h - padding - ((dataPoints.last().toFloat() / maxVal) * (h - padding * 2))
        fillPath.lineTo(lastX, h - padding)
        fillPath.close()

        // Draw fill, then line
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // Draw current value dot with glow
        canvas.drawCircle(lastX, lastY, 8f, glowPaint)
        canvas.drawCircle(lastX, lastY, 4f, dotPaint)
    }
}
