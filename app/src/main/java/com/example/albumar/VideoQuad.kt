package com.example.albumar

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/** Видео на плоскости фото. Вписывается в рамки фото, плавно появляется и исчезает. */
class VideoQuad {
    private var texId = 0
    private var prog = 0
    private var aPos = 0; private var aTex = 0
    private var uMvp = 0; private var uTexMat = 0; private var uAlpha = 0
    private var st: SurfaceTexture? = null
    private var mp: MediaPlayer? = null
    private val frameAvail = AtomicBoolean(false)
    private var hasFrame = false
    @Volatile private var aspect = 16f / 9f

    var muted = false
    var paused = false
    private var alpha = 0f
    private var fadeDir = 0
    private var lastT = 0L

    private val texMat = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private var exX = 0f
    private var exZ = 0f
    private val verts = Gl.buf(FloatArray(12))
    private val uvs = Gl.buf(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

    val isActive get() = st != null
    val isFadingOut get() = fadeDir == -1

    fun init() {
        texId = Gl.oesTexture()
        prog = Gl.program(
            "uniform mat4 u_Mvp; uniform mat4 u_TexMat; attribute vec4 a_Pos; attribute vec2 a_Tex; varying vec2 v_Tex;" +
                "void main(){ gl_Position=u_Mvp*a_Pos; v_Tex=(u_TexMat*vec4(a_Tex,0.0,1.0)).xy; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float;" +
                "varying vec2 v_Tex; uniform samplerExternalOES s; uniform float u_Alpha;" +
                "void main(){ vec4 c=texture2D(s,v_Tex); gl_FragColor=vec4(c.rgb,u_Alpha); }"
        )
        aPos = GLES20.glGetAttribLocation(prog, "a_Pos"); aTex = GLES20.glGetAttribLocation(prog, "a_Tex")
        uMvp = GLES20.glGetUniformLocation(prog, "u_Mvp"); uTexMat = GLES20.glGetUniformLocation(prog, "u_TexMat")
        uAlpha = GLES20.glGetUniformLocation(prog, "u_Alpha")
        Matrix.setIdentityM(texMat, 0)
    }

    /** GL-поток. */
    fun play(path: String) {
        stop()
        hasFrame = false; frameAvail.set(false); paused = false
        alpha = 0f; fadeDir = 1; lastT = System.currentTimeMillis()
        val t = SurfaceTexture(texId)
        t.setOnFrameAvailableListener { frameAvail.set(true) }
        st = t
        mp = MediaPlayer().apply {
            setDataSource(path)
            setSurface(Surface(t))
            isLooping = true
            setOnVideoSizeChangedListener { _, w, h -> if (h > 0) aspect = w.toFloat() / h }
            setOnPreparedListener { val v = if (muted) 0f else 1f; it.setVolume(v, v); it.start() }
            prepareAsync()
        }
    }

    fun fadeOut() { if (st != null) fadeDir = -1 }

    fun stop() {
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        try { st?.release() } catch (_: Exception) {}
        st = null; hasFrame = false; fadeDir = 0; alpha = 0f
    }

    fun togglePause(): Boolean {
        try { mp?.let { if (it.isPlaying) { it.pause(); paused = true } else { it.start(); paused = false } } } catch (_: Exception) {}
        return paused
    }

    fun toggleMute(): Boolean {
        muted = !muted
        val v = if (muted) 0f else 1f
        try { mp?.setVolume(v, v) } catch (_: Exception) {}
        return muted
    }

    /**
     * @param modelM матрица позы фото (null — рисуем по последней известной, во время затухания)
     */
    fun draw(vp: FloatArray, modelM: FloatArray?, extentX: Float, extentZ: Float) {
        val t = st ?: return
        if (modelM != null) { System.arraycopy(modelM, 0, model, 0, 16); exX = extentX; exZ = extentZ }
        val now = System.currentTimeMillis()
        val dt = now - lastT; lastT = now
        if (fadeDir == 1) { alpha += dt / 350f; if (alpha >= 1f) { alpha = 1f; fadeDir = 0 } }
        if (fadeDir == -1) { alpha -= dt / 350f; if (alpha <= 0f) { stop(); return } }

        if (frameAvail.getAndSet(false)) {
            try { t.updateTexImage(); t.getTransformMatrix(texMat); hasFrame = true } catch (_: Exception) {}
        }
        if (!hasFrame || exX <= 0f) return
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        var w = exX; var h = exX / aspect
        if (h > exZ) { h = exZ; w = exZ * aspect }
        val hx = w / 2; val hz = h / 2; val y = 0.002f
        verts.position(0); verts.put(floatArrayOf(-hx, y, -hz, hx, y, -hz, -hx, y, hz, hx, y, hz)); verts.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat, 0)
        GLES20.glUniform1f(uAlpha, alpha)
        uvs.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, verts)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, uvs)
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
