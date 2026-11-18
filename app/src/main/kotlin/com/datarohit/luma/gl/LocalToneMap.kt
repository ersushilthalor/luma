package com.datarohit.luma.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Local adaptation map for video local tone mapping: a small, blurred log2-luminance image of the current frame
 * in the camera texture's own space (like [TemporalDenoiser]). The colour shader compresses this large-scale
 * base around mid grey and keeps the detail on top, which lifts dark regions and holds bright ones locally
 * (wide dynamic range without the flat, washed look of a global curve).
 */
class LocalToneMap {

    private val down: Int
    private val blur: Int
    private val fbo = IntArray(3)
    private val tex = IntArray(3)
    private var width = 0
    private var height = 0

    /** Blurred log2 luminance (R channel), RGBA16F, [MAP_W] x proportional height. */
    val output: Int get() = tex[2]

    init {
        down = FrameShader.link(VERTEX, DOWN)
        blur = FrameShader.link(VERTEX, BLUR)
    }

    /**
     * Rebuilds the map from the camera texture (scene-log or display-referred, with its transform) or, when
     * [linearTexture] != 0, from the denoised linear history (identity transform).
     */
    fun update(cameraTexture: Int, matrix: FloatArray, inputLog: Boolean, linearTexture: Int, srcW: Int, srcH: Int) {
        val w = MAP_W
        val h = (MAP_W * srcH.toFloat() / srcW).toInt().coerceIn(16, 256)
        ensure(w, h)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glViewport(0, 0, w, h)
        // 1: 4x4 supersampled log-luminance at low resolution.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glUseProgram(down)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(down, "uCam"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, linearTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(down, "uLin"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(down, "uSrc"), if (linearTexture != 0) 1 else if (inputLog) 0 else 2)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(down, "uMatrix"), 1, false, if (linearTexture != 0) FrameShader.IDENTITY4 else matrix, 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(down, "uStep"), 1f / w / 4f, 1f / h / 4f)
        quad(down)
        // 2, 3: separable Gaussian blur (sigma ~ 3 map pixels).
        pass(fbo[1], tex[0], 1f / w, 0f)
        pass(fbo[2], tex[1], 0f, 1f / h)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun pass(target: Int, source: Int, dx: Float, dy: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
        GLES30.glUseProgram(blur)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blur, "uSrc"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blur, "uDir"), dx, dy)
        quad(blur)
    }

    private fun quad(program: Int) {
        val aPos = GLES30.glGetAttribLocation(program, "aPos")
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, QUAD)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPos)
    }

    private fun ensure(w: Int, h: Int) {
        if (w == width && h == height && tex[0] != 0) return
        release()
        GLES30.glGenTextures(3, tex, 0)
        GLES30.glGenFramebuffers(3, fbo, 0)
        for (i in 0..2) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, w, h)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[i], 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        width = w
        height = h
    }

    fun release() {
        if (tex[0] != 0) GLES30.glDeleteTextures(3, tex, 0)
        if (fbo[0] != 0) GLES30.glDeleteFramebuffers(3, fbo, 0)
        tex.fill(0)
        fbo.fill(0)
        width = 0
        height = 0
    }

    fun releasePrograms() {
        GLES30.glDeleteProgram(down)
        GLES30.glDeleteProgram(blur)
    }

    companion object {
        const val MAP_W = 192

        private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

        private const val VERTEX = """#version 300 es
in vec2 aPos;
out vec2 vP;
void main() {
    vP = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        private const val DOWN = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uCam;
uniform sampler2D uLin;
uniform int uSrc;
uniform mat4 uMatrix;
uniform vec2 uStep;
in vec2 vP;
out vec4 fragColor;
vec3 rgbAt(vec2 p) {
    if (uSrc == 1) return texture(uLin, p).rgb;
    vec3 c = texture(uCam, (uMatrix * vec4(p, 0.0, 1.0)).xy).rgb;
    if (uSrc == 0) return (exp(c * log(49.0)) - 1.0) / 48.0;
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}
void main() {
    float acc = 0.0;
    for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++) {
        vec2 o = (vec2(float(i), float(j)) - 1.5) * uStep;
        acc += log2(max(dot(rgbAt(vP + o), vec3(0.2126, 0.7152, 0.0722)), 1.0e-4));
    }
    fragColor = vec4(acc / 16.0, 0.0, 0.0, 1.0);
}
"""

        private const val BLUR = """#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform vec2 uDir;
in vec2 vP;
out vec4 fragColor;
void main() {
    // 9-tap Gaussian, sigma ~ 3 texels (weights normalised).
    float w[5] = float[](0.2270, 0.1946, 0.1216, 0.0540, 0.0162);
    float acc = texture(uSrc, vP).r * w[0];
    for (int i = 1; i < 5; i++) {
        acc += texture(uSrc, vP + uDir * float(i) * 1.5).r * w[i];
        acc += texture(uSrc, vP - uDir * float(i) * 1.5).r * w[i];
    }
    fragColor = vec4(acc, 0.0, 0.0, 1.0);
}
"""
    }
}
