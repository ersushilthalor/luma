package com.datarohit.luma.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies the viewfinder's shader pipeline (portrait bokeh, food tilt-shift + grade, LUT) to a finished
 * photo, so the saved image matches what the preview showed. Runs on the caller's thread with a private
 * EGL context; call from a background thread.
 */
object StillEffects {

    private val FLIP_Y = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

    @Synchronized
    fun apply(src: Bitmap, params: ShadeParams, lut: Lut3d? = null, mask: com.datarohit.luma.portrait.SubjectMask? = null): Bitmap {
        val egl = EglCore()
        val surface = egl.createPbuffer(16, 16)
        try {
            egl.makeCurrent(surface)
            val maxTex = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
            require(src.width <= maxTex && src.height <= maxTex) { "photo larger than GL_MAX_TEXTURE_SIZE ($maxTex)" }
            val shader = FrameShader(external = false)
            val tex = IntArray(4)
            GLES30.glGenTextures(4, tex, 0)
            val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }
            try {
                // Source photo.
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                val upload = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, upload, 0)
                // LUT.
                val lutData = lut ?: LutLibrary.generate("identity", 2) { r, g, b -> floatArrayOf(r, g, b) }
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex[2])
                val bytes = ByteBuffer.allocateDirect(lutData.data.size).order(ByteOrder.nativeOrder())
                lutData.data.forEach { bytes.put((it * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()) }
                bytes.rewind()
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
                GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, lutData.size, lutData.size, lutData.size, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, bytes)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                // Subject mask (portrait), same orientation as the photo.
                if (mask != null) {
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[3])
                    GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, mask.width, mask.height, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE,
                        ByteBuffer.wrap(mask.data))
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                }
                // Target.
                val w = src.width
                val h = src.height
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[1])
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[1], 0)
                GLES30.glViewport(0, 0, w, h)
                val geom = GlRenderer.geometry(w.toFloat() / h, w.toFloat() / h, 0f, false, 1f, flipY = true)
                shader.draw(tex[0], FLIP_Y, geom, w, h, params, tex[2], lutData.size, monitors = false, mask = if (mask != null) tex[3] else 0)
                val out = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
                GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, out)
                out.rewind()
                val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, false, src.colorSpace ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                result.copyPixelsFromBuffer(out)
                if (upload !== src) upload.recycle()
                return result
            } finally {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glDeleteFramebuffers(1, fbo, 0)
                GLES30.glDeleteTextures(4, tex, 0)
                shader.release()
            }
        } finally {
            egl.releaseSurface(surface)
            egl.release()
        }
    }
}
