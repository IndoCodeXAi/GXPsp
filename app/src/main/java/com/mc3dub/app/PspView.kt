package com.mc3dub.app

import android.content.Context
import android.opengl.GLSurfaceView

class PspView(c: Context) : GLSurfaceView(c) {
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, width * 272 / 480)
    }
}
