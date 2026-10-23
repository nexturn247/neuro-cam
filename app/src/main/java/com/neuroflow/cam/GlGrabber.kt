package com.neuroflow.cam

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Recibe el video en una textura de OpenGL fuera de pantalla (igual que hace un TextureView por dentro)
 * y saca fotos para la IA. Funciona con la pantalla apagada y en cualquier celular.
 */
class GlGrabber(private val w: Int = 640, private val h: Int = 360) {

    private val thread = HandlerThread("gl").apply { start() }
    private val gl = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var tex = 0
    private var program = 0
    private var st: SurfaceTexture? = null
    @Volatile var surface: Surface? = null
        private set
    @Volatile var frames = 0L          // imágenes recibidas (para diagnóstico)
        private set
    private var hasFrame = false
    private val matrix = FloatArray(16)
    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())

    // dos triángulos que cubren todo; coordenadas de textura invertidas en Y porque glReadPixels lee de abajo hacia arriba
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
        )); position(0)
    }

    /** Arranca OpenGL y devuelve la Surface donde el reproductor tiene que dibujar el video. */
    fun start(): Surface {
        val lock = Object()
        var result: Surface? = null
        var error: Exception? = null
        gl.post {
            try {
                setupEgl()
                setupGl()
                val t = SurfaceTexture(tex)
                t.setDefaultBufferSize(w, h)
                t.setOnFrameAvailableListener({
                    try { it.updateTexImage(); hasFrame = true; frames++ } catch (_: Exception) {}
                }, gl)
                st = t
                result = Surface(t)
                surface = result
            } catch (e: Exception) { error = e }
            synchronized(lock) { lock.notifyAll() }
        }
        synchronized(lock) { while (result == null && error == null) lock.wait(3000).also { if (result == null && error == null) error = IllegalStateException("OpenGL no respondió") } }
        error?.let { throw it }
        return result!!
    }

    /** Saca una foto de la última imagen recibida. Llama a [done] en el hilo de OpenGL. */
    fun grab(done: (Bitmap?) -> Unit) {
        gl.post {
            val t = st
            if (t == null || !hasFrame) { done(null); return@post }
            try {
                t.getTransformMatrix(matrix)
                GLES20.glViewport(0, 0, w, h)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uM"), 1, false, matrix, 0)
                val aPos = GLES20.glGetAttribLocation(program, "aPos")
                val aTex = GLES20.glGetAttribLocation(program, "aTex")
                quad.position(0)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
                GLES20.glEnableVertexAttribArray(aPos)
                quad.position(2)
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
                GLES20.glEnableVertexAttribArray(aTex)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                pixels.position(0)
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                pixels.position(0)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(pixels)
                done(bmp)
            } catch (_: Exception) { done(null) }
        }
    }

    fun release() {
        gl.post {
            try { surface?.release() } catch (_: Exception) {}
            try { st?.release() } catch (_: Exception) {}
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, eglSurface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            surface = null; st = null
            thread.quitSafely()
        }
    }

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "EGL init" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "EGL config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "EGL current" }
    }

    private fun setupGl() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val vs = """
            attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uM; varying vec2 vT;
            void main() { gl_Position = aPos; vT = (uM * aTex).xy; }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vT; uniform samplerExternalOES s;
            void main() { gl_FragColor = texture2D(s, vT); }
        """.trimIndent()
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(program)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }
}
