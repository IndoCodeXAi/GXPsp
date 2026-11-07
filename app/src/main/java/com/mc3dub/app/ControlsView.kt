package com.mc3dub.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Kontrol sentuh transparan yang menimpa layar game (mode landscape). */
class ControlsView(c: Context) : View(c) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        textAlign = Paint.Align.CENTER
    }
    private var mask = 0
    private var ax = 0f
    private var ay = 0f
    private val lRect = RectF()
    private val rRect = RectF()
    private val selRect = RectF()
    private val startRect = RectF()

    private val normal = Color.parseColor("#33FFFFFF")
    private val pressed = Color.parseColor("#88FFFFFF")

    private fun dpadC() = PointF(width * 0.12f, height * 0.34f)
    private fun dpadR() = height * 0.17f
    private fun stickC() = PointF(width * 0.12f, height * 0.78f)
    private fun stickR() = height * 0.14f
    private fun faceC() = PointF(width * 0.88f, height * 0.50f)
    private fun faceD() = height * 0.11f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val bw = h * 0.40f
        val bh = h * 0.12f
        lRect.set(w * 0.02f, h * 0.03f, w * 0.02f + bw, h * 0.03f + bh)
        rRect.set(w * 0.98f - bw, h * 0.03f, w * 0.98f, h * 0.03f + bh)
        val sw = h * 0.30f
        val sh = h * 0.10f
        selRect.set(w * 0.5f - sw - h * 0.02f, h * 0.88f, w * 0.5f - h * 0.02f, h * 0.88f + sh)
        startRect.set(w * 0.5f + h * 0.02f, h * 0.88f, w * 0.5f + h * 0.02f + sw, h * 0.88f + sh)
        label.textSize = h * 0.045f
    }

    private fun on(bit: Int) = (mask shr bit) and 1 == 1
    private fun col(bit: Int) = if (on(bit)) pressed else normal

    private fun txt(canvas: Canvas, s: String, x: Float, y: Float) =
        canvas.drawText(s, x, y + label.textSize / 3, label)

    override fun onDraw(canvas: Canvas) {
        val dc = dpadC(); val r = dpadR(); val a = r * 0.38f
        fill.color = normal
        canvas.drawRect(dc.x - a, dc.y - a, dc.x + a, dc.y + a, fill)
        fill.color = col(6); canvas.drawRect(dc.x - r, dc.y - a, dc.x - a, dc.y + a, fill)
        fill.color = col(7); canvas.drawRect(dc.x + a, dc.y - a, dc.x + r, dc.y + a, fill)
        fill.color = col(4); canvas.drawRect(dc.x - a, dc.y - r, dc.x + a, dc.y - a, fill)
        fill.color = col(5); canvas.drawRect(dc.x - a, dc.y + a, dc.x + a, dc.y + r, fill)

        val sc = stickC(); val sr = stickR()
        fill.color = Color.parseColor("#1AFFFFFF")
        canvas.drawCircle(sc.x, sc.y, sr, fill)
        fill.color = if (ax != 0f || ay != 0f) pressed else normal
        canvas.drawCircle(sc.x + ax * sr, sc.y + ay * sr, sr * 0.45f, fill)

        val fc = faceC(); val d = faceD(); val br = d * 0.7f
        fill.color = col(9); canvas.drawCircle(fc.x, fc.y - d, br, fill)
        fill.color = col(1); canvas.drawCircle(fc.x - d, fc.y, br, fill)
        fill.color = col(8); canvas.drawCircle(fc.x + d, fc.y, br, fill)
        fill.color = col(0); canvas.drawCircle(fc.x, fc.y + d, br, fill)
        txt(canvas, "\u25B3", fc.x, fc.y - d)
        txt(canvas, "\u25A1", fc.x - d, fc.y)
        txt(canvas, "\u25CB", fc.x + d, fc.y)
        txt(canvas, "\u2715", fc.x, fc.y + d)

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
                if (dist(x, y, sc) < sr * 1.4f) {
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
