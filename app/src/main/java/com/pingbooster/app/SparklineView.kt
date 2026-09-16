package com.pingbooster.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Dependency-free latency graph.
 *
 * Draws the rolling window held by [StatusBus] using three pre-allocated paints and a single
 * reused RectF: no allocations, no animation loop, no invalidate while nothing changed, so it
 * stays perfectly smooth and costs no measurable CPU.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val failPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.danger)
        alpha = 180
    }
    private val baselinePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.stroke)
        strokeWidth = density
    }

    private val rect = RectF()
    private val radius = 1.5f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        val baselineY = height - 2f * density
        canvas.drawLine(0f, baselineY, width, baselineY, baselinePaint)

        val count = StatusBus.historyCount
        if (count == 0) return

        val gap = 2f * density
        val slot = width / StatusBus.HISTORY
        val barWidth = (slot - gap).coerceAtLeast(1.5f * density)

        // Scale against the busiest visible sample so small changes stay readable.
        var peak = 1f
        for (i in 0 until StatusBus.HISTORY) {
            val value = StatusBus.history[i]
            if (value > peak) peak = value
        }
        val scale = (height - 6f * density) / peak

        val start = (StatusBus.historyHead - count + StatusBus.HISTORY) % StatusBus.HISTORY
        for (i in 0 until count) {
            val sample = StatusBus.history[(start + i) % StatusBus.HISTORY]
            val left = i * slot
            if (sample <= 0f) {
                // Failed probe: a small baseline stub instead of a bar.
                rect.set(left, baselineY - 3f * density, left + barWidth, baselineY)
                canvas.drawRoundRect(rect, radius, radius, failPaint)
            } else {
                val barHeight = (sample * scale).coerceAtLeast(3f * density)
                rect.set(left, baselineY - barHeight, left + barWidth, baselineY)
                canvas.drawRoundRect(rect, radius, radius, barPaint)
            }
        }
    }
}
