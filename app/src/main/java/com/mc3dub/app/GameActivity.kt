package com.mc3dub.app

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.opengl.EGL14
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GameActivity : Activity(), GLSurfaceView.Renderer {
    private lateinit var glView: PspView
    private lateinit var romPath: String
    @Volatile private var started = false
    @Volatile private var failed = false
    private var track: AudioTrack? = null
    private var lastCtx = 0L
    private val audioBuf = ShortArray(16384)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        romPath = intent.getStringExtra("rom") ?: run { finish(); return }

        glView = PspView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
            setRenderer(this@GameActivity)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(glView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ControlsView(this@GameActivity), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        hideBars()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun fail(msg: String) {
        failed = true
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); finish() }
    }

    private fun createAudio() {
        val rate = Native.info()[0].toInt()
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (failed) return
        if (!started) {
            try { Setup.ensure(this) } catch (e: Exception) { fail("Gagal menyiapkan data PPSSPP"); return }
            val sys = File(filesDir, "system").absolutePath
            val save = File(filesDir, "saves").apply { mkdirs() }.absolutePath
            if (!Native.start(romPath, sys, save)) { fail("Gagal memuat game"); return }
            started = true
            createAudio()
        }
        val h = EGL14.eglGetCurrentContext().nativeHandle
        if (h != lastCtx) {
            if (!Native.initGl()) { fail("Gagal menyiapkan grafis"); return }
            lastCtx = h
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Native.resize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!started || failed) return
        Native.runFrame()
        Native.draw()
        val n = Native.audio(audioBuf)
        if (n > 0) track?.write(audioBuf, 0, n)
    }

    override fun onPause() {
        Native.setInput(0, 0, 0)
        if (isFinishing && started) glView.queueEvent { started = false; Native.stop() }
        glView.onPause()
        track?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        track?.play()
    }

    override fun onDestroy() {
        track?.release()
        super.onDestroy()
        // Core PPSSPP tidak aman di-load dua kali dalam satu proses: matikan proses game.
        if (isFinishing) Process.killProcess(Process.myPid())
    }
}
