package com.mc3dub.app

object Native {
    init { System.loadLibrary("frontend") }
    external fun start(romPath: String, sysDir: String, saveDir: String): Boolean
    external fun info(): DoubleArray
    external fun initGl(): Boolean
    external fun resize(w: Int, h: Int)
    external fun runFrame()
    external fun draw()
    external fun audio(out: ShortArray): Int
    external fun setInput(mask: Int, ax: Int, ay: Int)
    external fun debug(): String
    external fun stop()
}
