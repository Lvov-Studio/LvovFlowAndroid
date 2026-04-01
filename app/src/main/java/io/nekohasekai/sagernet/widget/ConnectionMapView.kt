package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * LvovFlow ConnectionMapView — animated route visualization:
 * 📱 You ━━•━━ ⚡ LvovFlow ━━•━━ 🌐 Internet
 * Dots flow left→right when active.
 */
class ConnectionMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var isActive = false
    private var dotProgress = 0f

    // Line paint
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF1E3A5F.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    // Active line paint (gradient)
    private val activeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }

    // Dot paint
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Glow paint for dots
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Node circle paint
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0F2035.toInt()
    }

    private val nodeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // Text paints
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        color = 0xFF94A3B8.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val centerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        color = 0xFF22D3EE.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val gradient = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            0xFF22D3EE.toInt(), 0xFF22C55E.toInt(),
            Shader.TileMode.CLAMP
        )
        activeLinePaint.shader = gradient
        dotPaint.shader = gradient
    }

    fun setActive(active: Boolean) {
        isActive = active
        if (active) startAnimation() else stopAnimation()
        invalidate()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                dotProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        dotProgress = 0f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h * 0.40f
        val labelY = h * 0.82f

        val nodeRadius = 18f
        val padding = 40f

        // 3 node positions
        val leftX = padding + nodeRadius
        val centerX = w / 2f
        val rightX = w - padding - nodeRadius

        // Draw lines
        val paint = if (isActive) activeLinePaint else linePaint
        canvas.drawLine(leftX + nodeRadius + 4f, centerY, centerX - nodeRadius - 4f, centerY, paint)
        canvas.drawLine(centerX + nodeRadius + 4f, centerY, rightX - nodeRadius - 4f, centerY, paint)

        // Animated dots (3 dots per segment)
        if (isActive) {
            val segLen1 = centerX - leftX - nodeRadius * 2 - 8f
            val segLen2 = rightX - centerX - nodeRadius * 2 - 8f
            val startSeg1 = leftX + nodeRadius + 4f
            val startSeg2 = centerX + nodeRadius + 4f

            for (i in 0..2) {
                val offset = (dotProgress + i * 0.33f) % 1f

                // Segment 1 dots
                val x1 = startSeg1 + offset * segLen1
                glowPaint.color = 0x3022D3EE
                canvas.drawCircle(x1, centerY, 6f, glowPaint)
                dotPaint.color = 0xFF22D3EE.toInt()
                canvas.drawCircle(x1, centerY, 3f, dotPaint)

                // Segment 2 dots
                val x2 = startSeg2 + offset * segLen2
                glowPaint.color = 0x3022C55E
                canvas.drawCircle(x2, centerY, 6f, glowPaint)
                dotPaint.color = 0xFF22C55E.toInt()
                canvas.drawCircle(x2, centerY, 3f, dotPaint)
            }

            // Reset dot paint shader for node borders
            dotPaint.shader = null
        }

        // Draw node circles
        // Left: phone
        nodeBorderPaint.color = if (isActive) 0xFF94A3B8.toInt() else 0xFF334155.toInt()
        canvas.drawCircle(leftX, centerY, nodeRadius, nodePaint)
        canvas.drawCircle(leftX, centerY, nodeRadius, nodeBorderPaint)

        // Center: bolt (LvovFlow)
        nodeBorderPaint.color = if (isActive) 0xFF22D3EE.toInt() else 0xFF334155.toInt()
        canvas.drawCircle(centerX, centerY, nodeRadius + 2f, nodePaint)
        canvas.drawCircle(centerX, centerY, nodeRadius + 2f, nodeBorderPaint)

        // Right: globe
        nodeBorderPaint.color = if (isActive) 0xFF22C55E.toInt() else 0xFF334155.toInt()
        canvas.drawCircle(rightX, centerY, nodeRadius, nodePaint)
        canvas.drawCircle(rightX, centerY, nodeRadius, nodeBorderPaint)

        // Draw vector icons inside nodes
        val iconSize = 20
        val halfIcon = iconSize / 2

        // Left: phone icon
        val phoneIcon = androidx.core.content.ContextCompat.getDrawable(context, io.nekohasekai.sagernet.R.drawable.ic_map_phone)
        phoneIcon?.let {
            val tint = if (isActive) 0xFFE2E8F0.toInt() else 0xFF64748B.toInt()
            it.setTint(tint)
            it.setBounds(
                (leftX - halfIcon).toInt(), (centerY - halfIcon).toInt(),
                (leftX + halfIcon).toInt(), (centerY + halfIcon).toInt()
            )
            it.draw(canvas)
        }

        // Center: bolt icon
        val boltIcon = androidx.core.content.ContextCompat.getDrawable(context, io.nekohasekai.sagernet.R.drawable.ic_map_bolt)
        boltIcon?.let {
            val tint = if (isActive) 0xFF22D3EE.toInt() else 0xFF64748B.toInt()
            it.setTint(tint)
            it.setBounds(
                (centerX - halfIcon).toInt(), (centerY - halfIcon).toInt(),
                (centerX + halfIcon).toInt(), (centerY + halfIcon).toInt()
            )
            it.draw(canvas)
        }

        // Right: globe icon
        val globeIcon = androidx.core.content.ContextCompat.getDrawable(context, io.nekohasekai.sagernet.R.drawable.ic_map_globe)
        globeIcon?.let {
            val tint = if (isActive) 0xFF22C55E.toInt() else 0xFF64748B.toInt()
            it.setTint(tint)
            it.setBounds(
                (rightX - halfIcon).toInt(), (centerY - halfIcon).toInt(),
                (rightX + halfIcon).toInt(), (centerY + halfIcon).toInt()
            )
            it.draw(canvas)
        }

        // Labels
        val lp = if (isActive) Paint(labelPaint).apply { color = 0xFFE2E8F0.toInt() } else labelPaint
        canvas.drawText("Вы", leftX, labelY, lp)
        canvas.drawText("LvovFlow", centerX, labelY, if (isActive) centerLabelPaint else labelPaint)
        val rp = if (isActive) Paint(labelPaint).apply { color = 0xFF22C55E.toInt() } else labelPaint
        canvas.drawText("Интернет", rightX, labelY, rp)
    }
}
