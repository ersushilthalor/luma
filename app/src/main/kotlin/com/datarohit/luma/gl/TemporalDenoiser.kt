package com.datarohit.luma.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Motion-adaptive temporal noise reduction for the camera stream (night video and dim telephoto).
 *
 * Each frame is decoded from Luma scene-log to linear light and blended into an RGBA16F history in the camera
 * buffer's own texture space (so later passes sample it exactly like the camera texture). Per pixel, the
 * difference between a 5-tap mean of the new frame and the history is compared with the expected sensor noise
 * at that brightness: static areas take up to [strength] of the history (≈ 1/(1-s) frames of averaging), moving
 * areas fall back to the current frame with light spatial smoothing, so motion leaves no trails.
 */
class TemporalDenoiser {

    private val program: Int
    private val aPos: Int
    private val uCam: Int
    private val uPrev: Int
    private val uMatrix: Int
    private val uTexel: Int
    private val uStrength: Int
    private val uNoise: Int
    private val uValid: Int
    private val uInLog: Int

    private val fbo = IntArray(2)
    private val tex = IntArray(2)
    private var width = 0
    private var height = 0
    private var current = 0
    private var valid = false

    init {
        program = FrameShader.link(VERTEX, FRAGMENT)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        uCam = GLES30.glGetUniformLocation(program, "uCam")
        uPrev = GLES30.glGetUniformLocation(program, "uPrev")
        uMatrix = GLES30.glGetUniformLocation(program, "uMatrix")
        uTexel = GLES30.glGetUniformLocation(program, "uTexel")
        uStrength = GLES30.glGetUniformLocation(program, "uStrength")
        uNoise = GLES30.glGetUniformLocation(program, "uNoise")
        uValid = GLES30.glGetUniformLocation(program, "uValid")
        uInLog = GLES30.glGetUniformLocation(program, "uInLog")
    }

    /** Linear-light RGBA16F texture holding the latest denoised frame (camera texture space). */
    val output: Int get() = tex[current]

    /** Forgets the history (new session, lens restart, large exposure jump). */
    fun reset() {
        valid = false
    }

    /**
     * Denoises one camera frame. [noise] is the relative noise level (≈ sigma at mid grey, from the sensor ISO);
     * [strength] the maximum history weight for static pixels. Leaves the FBO binding at 0.
     */
    fun process(cameraTexture: Int, matrix: FloatArray, w: Int, h: Int, inputLog: Boolean, strength: Float, noise: Float) {
        ensure(w, h)
        val next = 1 - current
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[next])
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glUniform1i(uCam, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[current])
        GLES30.glUniform1i(uPrev, 1)
        GLES30.glUniformMatrix4fv(uMatrix, 1, false, matrix, 0)
        GLES30.glUniform2f(uTexel, 1f / w, 1f / h)
        GLES30.glUniform1f(uStrength, strength)
        GLES30.glUniform1f(uNoise, noise)
        GLES30.glUniform1i(uValid, if (valid) 1 else 0)
        GLES30.glUniform1i(uInLog, if (inputLog) 1 else 0)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, QUAD)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        current = next
        valid = true
    }

    private fun ensure(w: Int, h: Int) {
        if (w == width && h == height && tex[0] != 0) return
        release()
        GLES30.glGenTextures(2, tex, 0)
        GLES30.glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
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
        valid = false
    }

    fun release() {
        if (tex[0] != 0) GLES30.glDeleteTextures(2, tex, 0)
        if (fbo[0] != 0) GLES30.glDeleteFramebuffers(2, fbo, 0)
        tex.fill(0)
        fbo.fill(0)
        width = 0
        height = 0
        valid = false
    }

    fun releaseProgram() = GLES30.glDeleteProgram(program)

    private companion object {
        val QUAD: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

        const val VERTEX = """#version 300 es
in vec2 aPos;
out vec2 vP;
void main() {
    vP = aPos * 0.5 + 0.5;   // texture space, y up
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uCam;
uniform sampler2D uPrev;
uniform mat4 uMatrix;
uniform vec2 uTexel;
uniform float uStrength;
uniform float uNoise;
uniform int uValid;
uniform int uInLog;
in vec2 vP;
out vec4 fragColor;

const float LOG_K = 48.0;
vec3 decode(vec3 c) {
    if (uInLog == 1) return (exp(c * log(1.0 + LOG_K)) - 1.0) / LOG_K;
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}
vec3 cam(vec2 p) { return decode(texture(uCam, (uMatrix * vec4(p, 0.0, 1.0)).xy).rgb); }
float lum(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

void main() {
    vec3 c = cam(vP);
    vec3 m = (c * 4.0 + cam(vP + vec2(uTexel.x, 0.0)) + cam(vP - vec2(uTexel.x, 0.0))
        + cam(vP + vec2(0.0, uTexel.y)) + cam(vP - vec2(0.0, uTexel.y))) / 8.0;
    if (uValid == 0 || uStrength <= 0.0) { fragColor = vec4(c, 1.0); return; }
    vec3 prev = texture(uPrev, vP).rgb;
    // Shot-noise model: sigma grows with sqrt(signal); uNoise is sigma at mid grey (0.18).
    float y = max(lum(m), 0.0005);
    float sigma = uNoise * sqrt(y / 0.18) + 0.0004;
    float d = length(m - prev) / (sigma * 0.75 + 0.15 * y * uNoise);
    float still = 1.0 - smoothstep(1.0, 3.5, d);
    float a = uStrength * still;
    // Moving pixels: a light spatial average instead of the raw frame (no trails, less grain).
    vec3 spatial = mix(c, m, clamp(uNoise * 6.0, 0.0, 0.6));
    fragColor = vec4(mix(spatial, prev, a), 1.0);
}
"""
    }
}
