package org.bc3pool.miner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class WorkerHashrateChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(52, 67, 83); strokeWidth = 1f }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(116, 227, 154); strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var values = emptyList<Double>()

    fun setValues(samples: List<Double>) { values = samples.takeLast(60); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 10f; val right = width - 10f; val top = 10f; val bottom = height - 10f
        repeat(5) { i -> val y = top + (bottom - top) * i / 4f; canvas.drawLine(left, y, right, y, grid) }
        if (values.size < 2) return
        val maximum = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0) * 1.1
        val step = (right - left) / (values.size - 1)
        var px = left; var py = bottom - (values.first() / maximum * (bottom - top)).toFloat()
        for (i in 1 until values.size) {
            val x = left + step * i; val y = bottom - (values[i] / maximum * (bottom - top)).toFloat()
            canvas.drawLine(px, py, x, y, line); px = x; py = y
        }
    }
}
