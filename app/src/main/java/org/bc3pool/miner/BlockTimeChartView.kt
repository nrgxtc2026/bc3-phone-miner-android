package org.bc3pool.miner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class BlockTimeChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(72, 89, 107)
        strokeWidth = 1f
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(74, 214, 232)
        strokeWidth = 2f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(247, 147, 26)
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var seconds = emptyList<Double>()

    fun setBlockTimes(values: List<Double>) {
        seconds = values.takeLast(14)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val left = 6f
        val right = width - 6f
        val top = 8f
        val bottom = height - 8f
        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        val maxSeconds = maxOf(1_200.0, seconds.maxOrNull() ?: 1_200.0)
        val targetY = bottom - (600.0 / maxSeconds * (bottom - top)).toFloat()
        canvas.drawLine(left, targetY, right, targetY, targetPaint)
        if (seconds.size < 2) return
        val step = (right - left) / (seconds.size - 1)
        var previousX = left
        var previousY = bottom - (seconds.first() / maxSeconds * (bottom - top)).toFloat()
        for (index in 1 until seconds.size) {
            val x = left + step * index
            val y = bottom - (seconds[index] / maxSeconds * (bottom - top)).toFloat()
            canvas.drawLine(previousX, previousY, x, y, linePaint)
            previousX = x
            previousY = y
        }
    }
}
