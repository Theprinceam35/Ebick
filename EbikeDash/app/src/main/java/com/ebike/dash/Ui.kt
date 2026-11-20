package com.ebike.dash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.Locale

val BG = Color.parseColor("#0F1512")
val CARD = Color.parseColor("#17201B")
val INK = Color.parseColor("#E8EFE9")
val MUTE = Color.parseColor("#93A39A")
val OK = Color.parseColor("#5BD6A0")
val WARN = Color.parseColor("#F0A35A")
val LOW = Color.parseColor("#FF7A7A")
val BLUE = Color.parseColor("#6DB3FF")
val OFF = Color.parseColor("#26322B")

class SegBar(c: Context) : View(c) {
    var n = 16
    var on = 0
    var color = OK
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val gap = 6f
        val w = (width - gap * (n - 1)) / n
        for (i in 0 until n) {
            p.color = if (i < on) color else OFF
            cv.drawRoundRect(i * (w + gap), 0f, i * (w + gap) + w, height.toFloat(), 6f, 6f, p)
        }
    }
}

class CellsView(c: Context) : View(c) {
    var cells: List<Double> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = c.resources.displayMetrics.density
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (maxOf(cells.size, 1) * 30 * d).toInt())
    }
    override fun onDraw(cv: Canvas) {
        if (cells.isEmpty()) return
        val mn = cells.min()
        val mx = cells.max()
        val flat = mx - mn < 0.005
        p.textSize = 14 * d
        val rowH = 30 * d
        val left = 28 * d
        val right = 64 * d
        for ((i, v) in cells.withIndex()) {
            val y = i * rowH
            p.color = MUTE; p.textAlign = Paint.Align.LEFT
            cv.drawText("${i + 1}", 0f, y + rowH * 0.68f, p)
            p.color = OFF
            cv.drawRoundRect(left, y + 5 * d, width - right, y + rowH - 5 * d, 6f, 6f, p)
            val frac = ((v - mn + 0.02) / (mx - mn + 0.04)).toFloat()
            p.color = if (flat) OK else if (v == mn) WARN else if (v == mx) BLUE else OK
            cv.drawRoundRect(left, y + 5 * d, left + (width - right - left) * frac, y + rowH - 5 * d, 6f, 6f, p)
            p.color = INK; p.textAlign = Paint.Align.RIGHT
            cv.drawText(String.format(Locale.US, "%.3f", v), width.toFloat(), y + rowH * 0.68f, p)
        }
    }
}
