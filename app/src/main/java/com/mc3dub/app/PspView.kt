package com.mc3dub.app

import android.content.Context
import android.opengl.GLSurfaceView

/** Layar game 16:9 (480x272) yang diletakkan di tengah dan memenuhi tinggi layar. */
class PspView(c: Context) : GLSurfaceView(c) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val pw = MeasureSpec.getSize(wSpec)
        val ph = MeasureSpec.getSize(hSpec)
        var h = ph
        var w = h * 480 / 272
        if (w > pw) { w = pw; h = w * 272 / 480 }
        setMeasuredDimension(w, h)
    }
}
