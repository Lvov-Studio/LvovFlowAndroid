package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.ArrayDeque

/**
 * LvovFlow SpeedGraphView — draws TX/RX speed history as mini line charts.
 * Stores up to MAX_SAMPLES data points and renders two smooth lines:
 *   - TX (upload):   cyan    #25C9EF
 *   - RX (download): indigo  #7B9BFF
 */
class SpeedGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MAX_SAMPLES = 60
    }

    private val txSamples = ArrayDeque<Long>(MAX_SAMPLES)
    private val rxSamples = ArrayDeque<Long>(MAX_SAMPLES)

    private val txPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#25C9EF")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val rxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7B9BFF")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val txPath = Path()
    private val rxPath = Path()

    fun addSample(txBytes: Long, rxBytes: Long) {
        if (txSamples.size >= MAX_SAMPLES) txSamples.pollFirst()
        if (rxSamples.size >= MAX_SAMPLES) rxSamples.pollFirst()
        txSamples.addLast(txBytes)
        rxSamples.addLast(rxBytes)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f || txSamples.size < 2) return

        drawLine(canvas, txSamples.toList(), txPaint, txPath, w, h)
        drawLine(canvas, rxSamples.toList(), rxPaint, rxPath, w, h)
    }

    private fun drawLine(
        canvas: Canvas,
        samples: List<Long>,
        paint: Paint,
        path: Path,
        w: Float,
        h: Float
    ) {
        if (samples.size < 2) return
        val maxVal = samples.max().coerceAtLeast(1L).toFloat()
        val stepX = w / (MAX_SAMPLES - 1).toFloat()
        val startX = w - stepX * (samples.size - 1)

        path.reset()
        samples.forEachIndexed { i, v ->
            val x = startX + i * stepX
            val y = h - (v / maxVal) * h * 0.9f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
