package com.nikolay.myvpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.LinkedList

class TrafficGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxDataPoints = 40
    private val dataPoints = LinkedList<Float>()

    init {
        // Предварительное заполнение нулевыми точками для корректного отображения начальной прямой линии
        for (i in 0 until maxDataPoints) {
            dataPoints.add(0f)
        }
    }

    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#03A9F4") // Основной цвет линии активного VPN-подключения
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A03A9F4") // Мягкая заливка под линией графика
        style = Paint.Style.FILL
    }

    private val path = Path()
    private val fillPath = Path()

    fun addDataPoint(value: Float) {
        if (dataPoints.size >= maxDataPoints) {
            dataPoints.removeFirst()
        }
        dataPoints.addLast(value)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        path.reset()
        fillPath.reset()

        val dx = w / (maxDataPoints - 1)
        val maxVal = dataPoints.maxOrNull()?.coerceAtLeast(1f) ?: 1f

        for (i in 0 until dataPoints.size) {
            val value = dataPoints[i]
            val x = i * dx
            val y = h - (value / maxVal * (h * 0.7f)) - (h * 0.15f)

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }

            if (i == dataPoints.size - 1) {
                fillPath.lineTo(x, h)
                fillPath.close()
            }
        }

        canvas.drawPath(fillPath, paintFill)
        canvas.drawPath(path, paintLine)
    }
}
