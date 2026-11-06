package com.mc3dub.app

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

object Setup {
    /** Ekstrak aset sistem PPSSPP (font, atlas) dari APK ke penyimpanan app, sekali saja. */
    @Synchronized
    fun ensure(ctx: Context) {
        val sys = File(ctx.filesDir, "system").apply { mkdirs() }
        val dst = File(sys, "PPSSPP")
        if (File(dst, ".ok").exists()) return
        dst.deleteRecursively()
        val tmp = File(sys, "_tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val root = tmp.canonicalPath
        ZipInputStream(ctx.assets.open("PPSSPP.zip").buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                var name = e.name
                if (!name.startsWith("PPSSPP/")) name = "PPSSPP/$name"
                val out = File(tmp, name)
                if (out.canonicalPath.startsWith(root)) {
                    if (e.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zin.copyTo(it) }
                    }
                }
                e = zin.nextEntry
            }
        }
        File(tmp, "PPSSPP").mkdirs()
        File(tmp, "PPSSPP/.ok").writeText("ok")
        File(tmp, "PPSSPP").renameTo(dst)
        tmp.deleteRecursively()
    }
}
