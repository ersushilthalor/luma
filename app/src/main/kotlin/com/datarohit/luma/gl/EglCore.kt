package com.datarohit.luma.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Minimal EGL 1.4 / GLES 3 wrapper. One context renders to every surface (preview and encoders);
 * all configs are recordable so the same context can feed MediaCodec input surfaces.
 */
class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config8: EGLConfig
    private val config10: EGLConfig?
    val context: EGLContext

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        config8 = chooseConfig(8, 8, 8, 8) ?: error("no RGBA8888 recordable EGL config")
        config10 = chooseConfig(10, 10, 10, 2)
        val attrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config8, EGL14.EGL_NO_CONTEXT, attrs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    val supportsTenBit: Boolean get() = config10 != null

    private fun chooseConfig(r: Int, g: Int, b: Int, a: Int): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, r, EGL14.EGL_GREEN_SIZE, g, EGL14.EGL_BLUE_SIZE, b, EGL14.EGL_ALPHA_SIZE, a,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(8)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, configs.size, count, 0) || count[0] == 0) return null
        // eglChooseConfig sorts deeper configs first; take an exact match.
        return configs.take(count[0]).filterNotNull().firstOrNull { c ->
            val v = IntArray(1)
            EGL14.eglGetConfigAttrib(display, c, EGL14.EGL_RED_SIZE, v, 0) && v[0] == r
        }
    }

    /**
     * [colorSpace] (EGL_GL_COLORSPACE_KHR value, e.g. [COLORSPACE_BT2020_HLG]) tags the surface's dataspace, so the
     * encoder converts RGB to YCbCr with BT.2020 coefficients and writes HLG / PQ colour metadata.
     */
    fun createWindowSurface(surface: Surface, tenBit: Boolean = false, colorSpace: Int = 0): EGLSurface {
        val cfg = if (tenBit && config10 != null) config10 else config8
        val attrs = if (colorSpace != 0) intArrayOf(EGL_GL_COLORSPACE_KHR, colorSpace, EGL14.EGL_NONE) else intArrayOf(EGL14.EGL_NONE)
        val s = EGL14.eglCreateWindowSurface(display, cfg, surface, attrs, 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        return s
    }

    fun createPbuffer(width: Int, height: Int): EGLSurface {
        val s = EGL14.eglCreatePbufferSurface(display, config8, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        return s
    }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
    }

    fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(surface: EGLSurface, ns: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, ns)
    }

    fun surfaceSize(surface: EGLSurface): Pair<Int, Int> {
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, h, 0)
        return w[0] to h[0]
    }

    fun releaseSurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        // No eglTerminate: the display is shared by every context in the process (preview + still effects).
    }

    companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val COLORSPACE_BT2020_PQ = 0x3340
        const val COLORSPACE_BT2020_HLG = 0x3540
    }
}
