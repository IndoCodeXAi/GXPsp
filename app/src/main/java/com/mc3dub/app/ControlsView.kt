package com.mc3dub.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

class ControlsView(c: Context) : View(c) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private var mask = 0
    private var ax = 0f
    private var ay = 0f
    private val lRect = RectF()
    private val rRect = RectF()
    private val selRect = RectF()
    private val startRect = RectF()

    private val normal = Color.parseColor("#44FFFFFF")
    private val pressed = Color.parseColor("#99FFFFFF")

    private fun dpadC() = PointF(width * 0.24f, height * 0.26f)
    private fun dpadR() = minOf(width * 0.2f, height * 0.12f)
    private fun stickC() = PointF(width * 0.24f, height * 0.64f)
    private fun stickR() = minOf(width * 0.2f, height * 0.13f)
    private fun faceC() = PointF(width * 0.76f, height * 0.42f)
    private fun faceD() = minOf(width * 0.14f, height * 0.09f)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val sh = minOf(h * 0.08f, w * 0.12f)
        lRect.set(w * 0.03f, h * 0.01f, w * 0.30f, h * 0.01f + sh)
        rRect.set(w * 0.70f, h * 0.01f, w * 0.97f, h * 0.01f + sh)
        selRect.set(w * 0.30f, h * 0.88f, w * 0.48f, h * 0.88f + sh * 0.7f)
        startRect.set(w * 0.52f, h * 0.88f, w * 0.70f, h * 0.88f + sh * 0.7f)
        label.textSize = minOf(w * 0.04f, h * 0.05f)
    }

    private fun on(bit: Int) = (mask shr bit) and 1 == 1
    private fun col(bit: Int) = if (on(bit)) pressed else normal

    private fun txt(canvas: Canvas, s: String, x: Float, y: Float) =
        canvas.drawText(s, x, y + label.textSize / 3, label)

    override fun onDraw(canvas: Canvas) {
        // D-pad
        val dc = dpadC(); val r = dpadR(); val a = r * 0.38f
        fill.color = normal
        canvas.drawRect(dc.x - a, dc.y - a, dc.x + a, dc.y + a, fill)
        fill.color = col(6); canvas.drawRect(dc.x - r, dc.y - a, dc.x - a, dc.y + a, fill)
        fill.color = col(7); canvas.drawRect(dc.x + a, dc.y - a, dc.x + r, dc.y + a, fill)
        fill.color = col(4); canvas.drawRect(dc.x - a, dc.y - r, dc.x + a, dc.y - a, fill)
        fill.color = col(5); canvas.drawRect(dc.x - a, dc.y + a, dc.x + a, dc.y + r, fill)

        // Stik analog
        val sc = stickC(); val sr = stickR()
        fill.color = Color.parseColor("#22FFFFFF")
        canvas.drawCircle(sc.x, sc.y, sr, fill)
        fill.color = if (ax != 0f || ay != 0f) pressed else normal
        canvas.drawCircle(sc.x + ax * sr, sc.y + ay * sr, sr * 0.45f, fill)

        // Tombol aksi
        val fc = faceC(); val d = faceD(); val br = d * 0.7f
        fill.color = col(9); canvas.drawCircle(fc.x, fc.y - d, br, fill)
        fill.color = col(1); canvas.drawCircle(fc.x - d, fc.y, br, fill)
        fill.color = col(8); canvas.drawCircle(fc.x + d, fc.y, br, fill)
        fill.color = col(0); canvas.drawCircle(fc.x, fc.y + d, br, fill)
        txt(canvas, "\u25B3", fc.x, fc.y - d)
        txt(canvas, "\u25A1", fc.x - d, fc.y)
        txt(canvas, "\u25CB", fc.x + d, fc.y)
        txt(canvas, "\u2715", fc.x, fc.y + d)

        // Bahu, Start, Select
        fill.color = col(10); canvas.drawRoundRect(lRect, 24f, 24f, fill)
        fill.color = col(11); canvas.drawRoundRect(rRect, 24f, 24f, fill)
        fill.color = col(2); canvas.drawRoundRect(selRect, 24f, 24f, fill)
        fill.color = col(3); canvas.drawRoundRect(startRect, 24f, 24f, fill)
        txt(canvas, "L", lRect.centerX(), lRect.centerY())
        txt(canvas, "R", rRect.centerX(), rRect.centerY())
        txt(canvas, "SELECT", selRect.centerX(), selRect.centerY())
        txt(canvas, "START", startRect.centerX(), startRect.centerY())
    }

    private fun dist(x: Float, y: Float, p: PointF): Float {
        val dx = x - p.x; val dy = y - p.y
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun handle(e: MotionEvent) {
        val act = e.actionMasked
        var m = 0
        var sx = 0f
        var sy = 0f
        if (act != MotionEvent.ACTION_UP && act != MotionEvent.ACTION_CANCEL) {
            val skip = if (act == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
            for (i in 0 until e.pointerCount) {
                if (i == skip) continue
                val x = e.getX(i); val y = e.getY(i)

                val dc = dpadC(); val r = dpadR()
                val dx = x - dc.x; val dy = y - dc.y
                if (dx * dx + dy * dy < r * r * 1.7f) {
                    val t = r * 0.3f
                    if (dx < -t) m = m or (1 shl 6)
                    if (dx > t) m = m or (1 shl 7)
                    if (dy < -t) m = m or (1 shl 4)
                    if (dy > t) m = m or (1 shl 5)
                }

                val sc = stickC(); val sr = stickR()
                if (dist(x, y, sc) < sr * 1.6f) {
                    var nx = (x - sc.x) / sr
                    var ny = (y - sc.y) / sr
                    val len = Math.sqrt((nx * nx + ny * ny).toDouble()).toFloat()
                    if (len > 1f) { nx /= len; ny /= len }
                    sx = nx; sy = ny
                }

                val fc = faceC(); val d = faceD(); val br = d * 0.75f
                if (dist(x, y, PointF(fc.x, fc.y - d)) < br) m = m or (1 shl 9)
                if (dist(x, y, PointF(fc.x - d, fc.y)) < br) m = m or (1 shl 1)
                if (dist(x, y, PointF(fc.x + d, fc.y)) < br) m = m or (1 shl 8)
                if (dist(x, y, PointF(fc.x, fc.y + d)) < br) m = m or 1

                if (lRect.contains(x, y)) m = m or (1 shl 10)
                if (rRect.contains(x, y)) m = m or (1 shl 11)
                if (selRect.contains(x, y)) m = m or (1 shl 2)
                if (startRect.contains(x, y)) m = m or (1 shl 3)
            }
        }
        mask = m; ax = sx; ay = sy
        Native.setInput(m, (sx * 32767).toInt(), (sy * 32767).toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        handle(e)
        invalidate()
        return true
    }
}
