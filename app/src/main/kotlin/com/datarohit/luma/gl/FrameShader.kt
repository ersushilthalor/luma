package com.datarohit.luma.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Spatial effect applied around a focus point (portrait bokeh, food tilt-shift ring). */
enum class FocusEffect(val id: Int) { NONE(0), PORTRAIT(1), TILT_SHIFT(2) }

/** Everything the fragment shader needs for one draw, except textures. */
data class ShadeParams(
    /** LUT strength (0 = off). */
    val lutMix: Float = 0f,
    val effect: FocusEffect = FocusEffect.NONE,
    /** Effect centre in output UV (0..1, y down). */
    val centerX: Float = 0.5f,
    val centerY: Float = 0.45f,
    /** Sharp-zone radius as a fraction of the output height. */
    val radius: Float = 0.25f,
    val feather: Float = 0.18f,
    /** Maximum blur radius as a fraction of the output height. */
    val blur: Float = 0f,
    /** Horizontal / vertical stretch of the sharp zone (portrait = taller ellipse). */
    val ellipseX: Float = 1f,
    val ellipseY: Float = 1f,
    val warmth: Float = 0f,
    val saturation: Float = 1f,
    /** Green(-) / magenta(+) tint, -1..1. */
    val tint: Float = 0f,
    val exposure: Float = 0f,
    val peaking: Boolean = false,
    val peakColor: Int = 0xFFFF3B30.toInt(),
    /** Edge threshold 0..1 (lower = more sensitive). */
    val peakThreshold: Float = 0.18f,
    /** Zebra threshold in IRE (0 = off). */
    val zebraIre: Float = 0f,
    val falseColor: Boolean = false,
    /** Portrait: blur follows the subject mask (person segmentation) instead of the focus ellipse. */
    val useMask: Boolean = false,
    /** Bokeh rendering style (see [BokehStyle]). */
    val bokehStyle: Int = 0,
)

/** Portrait bokeh styles: kernel shape and highlight rendering. */
enum class BokehStyle(val label: String) {
    NATURAL("Natural"), BUBBLE("Bubble"), STARRY("Starry"), CINEMATIC("Cinematic"), SWIRL("Swirl"),
}

/** Per-draw colour-engine settings (see FrameShader `colorEngine`). */
data class EngineStage(
    /** 0 = pass through, 1 = filmic display rendering, 2 = scene log. */
    val tone: Int,
    val inputLog: Boolean,
    /** The sampled texture already holds linear light (temporal denoiser output). */
    val inputLinear: Boolean = false,
    val gain: Float = 1f,
    val white: Float = 1f,
    val shoulderK: Float = 1f,
    /** Display luminance of scene mid grey (0.18). */
    val midOut: Float = 0.18f,
    val profileTexture: Int = 0,
    val profileSize: Int = 2,
    val grain: Float = 0f,
    val seed: Float = 0f,
    /** Blurred log2-luminance map ([LocalToneMap]); 0 = no local tone mapping. */
    val localTexture: Int = 0,
    /** Large-scale contrast compression around mid grey (0..1). */
    val localStrength: Float = 0f,
    /** Local detail gain on top of the compressed base (1 = unchanged). */
    val localDetail: Float = 1f,
    /** Small-radius, halo-limited luminance detail lift (0 = off). */
    val sharpen: Float = 0f,
)

/**
 * The one fragment pipeline: sample (with optional spatial blur) → grade (exposure, warmth, tint,
 * saturation) → 3D LUT → monitoring overlays (peaking, zebra, false colour). External (camera) and 2D
 * (bitmap) variants share the source.
 */
class FrameShader(external: Boolean) {

    private val program: Int
    private val aPos: Int
    private val uTexMatrix: Int
    private val uGeom: Int
    private val uTexel: Int
    private val uLutMix: Int
    private val uLutSize: Int
    private val uEffect: Int
    private val uCenter: Int
    private val uRadius: Int
    private val uFeather: Int
    private val uBlur: Int
    private val uEllipse: Int
    private val uAspect: Int
    private val uGrade: Int
    private val uMonitor: Int
    private val uPeak: Int
    private val uZebra: Int
    private val uBalance: Int
    private val uTone: Int
    private val uInLog: Int
    private val uExpGain: Int
    private val uShoulder: Int
    private val uProfile: Int
    private val uProfileMix: Int
    private val uProfileSize: Int
    private val uGrain: Int
    private val uSeed: Int
    private val uLocal: Int
    private val uLtm: Int
    private val uSharp: Int
    private val uMask: Int
    private val uMaskOn: Int
    private val uBokeh: Int
    private val uTex: Int
    private val uLut: Int
    private val textureTarget = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D

    init {
        val header = if (external) "#define EXTERNAL 1\n" else ""
        program = link(VERTEX, FRAGMENT_PREFIX + header + FRAGMENT)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uGeom = GLES30.glGetUniformLocation(program, "uGeom")
        uTexel = GLES30.glGetUniformLocation(program, "uTexel")
        uLutMix = GLES30.glGetUniformLocation(program, "uLutMix")
        uLutSize = GLES30.glGetUniformLocation(program, "uLutSize")
        uEffect = GLES30.glGetUniformLocation(program, "uEffect")
        uCenter = GLES30.glGetUniformLocation(program, "uCenter")
        uRadius = GLES30.glGetUniformLocation(program, "uRadius")
        uFeather = GLES30.glGetUniformLocation(program, "uFeather")
        uBlur = GLES30.glGetUniformLocation(program, "uBlur")
        uEllipse = GLES30.glGetUniformLocation(program, "uEllipse")
        uAspect = GLES30.glGetUniformLocation(program, "uAspect")
        uGrade = GLES30.glGetUniformLocation(program, "uGrade")
        uMonitor = GLES30.glGetUniformLocation(program, "uMonitor")
        uPeak = GLES30.glGetUniformLocation(program, "uPeak")
        uZebra = GLES30.glGetUniformLocation(program, "uZebra")
        uBalance = GLES30.glGetUniformLocation(program, "uBalance")
        uTone = GLES30.glGetUniformLocation(program, "uTone")
        uInLog = GLES30.glGetUniformLocation(program, "uInLog")
        uExpGain = GLES30.glGetUniformLocation(program, "uExpGain")
        uShoulder = GLES30.glGetUniformLocation(program, "uShoulder")
        uProfile = GLES30.glGetUniformLocation(program, "uProfile")
        uProfileMix = GLES30.glGetUniformLocation(program, "uProfileMix")
        uProfileSize = GLES30.glGetUniformLocation(program, "uProfileSize")
        uGrain = GLES30.glGetUniformLocation(program, "uGrain")
        uSeed = GLES30.glGetUniformLocation(program, "uSeed")
        uLocal = GLES30.glGetUniformLocation(program, "uLocal")
        uLtm = GLES30.glGetUniformLocation(program, "uLtm")
        uSharp = GLES30.glGetUniformLocation(program, "uSharp")
        uMask = GLES30.glGetUniformLocation(program, "uMask")
        uMaskOn = GLES30.glGetUniformLocation(program, "uMaskOn")
        uBokeh = GLES30.glGetUniformLocation(program, "uBokeh")
        uTex = GLES30.glGetUniformLocation(program, "uTex")
        uLut = GLES30.glGetUniformLocation(program, "uLut")
    }

    /**
     * Draws [texture] into the current viewport ([outW] x [outH] pixels).
     * [geom] maps output UV (centred) to source UV (centred), column-major 3x3.
     */
    fun draw(
        texture: Int,
        texMatrix: FloatArray,
        geom: FloatArray,
        outW: Int,
        outH: Int,
        p: ShadeParams,
        lutTexture: Int,
        lutSize: Int,
        monitors: Boolean,
        /** Per-channel gain (lens-switch colour continuity); null = neutral. */
        balance: FloatArray? = null,
        /** Colour engine stage (scene-log decode, tone mapping, profile); null = pass the input through. */
        engine: EngineStage? = null,
        /** Subject mask texture (R8, upright source space); 0 = none. */
        mask: Int = 0,
    ) {
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(textureTarget, texture)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glUniform1i(uLut, 1)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES30.glUniformMatrix3fv(uGeom, 1, false, geom, 0)
        GLES30.glUniform2f(uTexel, 1f / outW, 1f / outH)
        GLES30.glUniform1f(uLutMix, if (lutTexture != 0) p.lutMix else 0f)
        GLES30.glUniform1f(uLutSize, lutSize.toFloat().coerceAtLeast(2f))
        GLES30.glUniform1i(uEffect, if (p.blur > 0f) p.effect.id else 0)
        GLES30.glUniform2f(uCenter, p.centerX, p.centerY)
        GLES30.glUniform1f(uRadius, p.radius)
        GLES30.glUniform1f(uFeather, p.feather.coerceAtLeast(0.01f))
        GLES30.glUniform1f(uBlur, p.blur)
        GLES30.glUniform2f(uEllipse, p.ellipseX, p.ellipseY)
        GLES30.glUniform1f(uAspect, outW.toFloat() / outH)
        GLES30.glUniform4f(uGrade, p.exposure, p.warmth, p.tint, p.saturation)
        val flags = if (!monitors) 0 else (if (p.peaking) 1 else 0) or (if (p.zebraIre > 0f) 2 else 0) or (if (p.falseColor) 4 else 0)
        GLES30.glUniform1i(uMonitor, flags)
        val c = p.peakColor
        GLES30.glUniform4f(uPeak, ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, p.peakThreshold)
        GLES30.glUniform1f(uZebra, p.zebraIre / 100f)
        if (balance != null) GLES30.glUniform3f(uBalance, balance[0], balance[1], balance[2]) else GLES30.glUniform3f(uBalance, 1f, 1f, 1f)
        GLES30.glUniform1i(uTone, engine?.tone ?: 0)
        GLES30.glUniform1i(uInLog, when {
            engine?.inputLinear == true -> 2
            engine?.inputLog == true -> 1
            else -> 0
        })
        GLES30.glUniform1f(uExpGain, engine?.gain ?: 1f)
        GLES30.glUniform3f(uShoulder, engine?.white ?: 1f, engine?.shoulderK ?: 1f, engine?.midOut ?: 0.18f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, if (engine != null && engine.profileTexture != 0) engine.profileTexture else lutTexture)
        GLES30.glUniform1i(uProfile, 2)
        GLES30.glUniform1f(uProfileMix, if (engine != null && engine.profileTexture != 0) 1f else 0f)
        GLES30.glUniform1f(uProfileSize, (engine?.profileSize ?: 2).toFloat())
        GLES30.glUniform1f(uGrain, engine?.grain ?: 0f)
        GLES30.glUniform1f(uSeed, engine?.seed ?: 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, engine?.localTexture ?: 0)
        GLES30.glUniform1i(uLocal, 3)
        val ltmOn = engine != null && engine.localTexture != 0 && engine.localStrength > 0f
        GLES30.glUniform2f(uLtm, if (ltmOn) engine!!.localStrength else 0f, if (ltmOn) engine!!.localDetail else 1f)
        GLES30.glUniform1f(uSharp, if (engine != null && engine.tone != 2) engine.sharpen else 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mask)
        GLES30.glUniform1i(uMask, 4)
        GLES30.glUniform1i(uMaskOn, if (p.useMask && mask != 0) 1 else 0)
        GLES30.glUniform1i(uBokeh, p.bokehStyle)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, QUAD)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPos)
    }

    fun release() = GLES30.glDeleteProgram(program)

    companion object {
        private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

        val IDENTITY4 = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        private const val VERTEX = """#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        private const val FRAGMENT_PREFIX = "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\n"

        private const val FRAGMENT = """precision highp float;
#ifdef EXTERNAL
uniform samplerExternalOES uTex;
#else
uniform sampler2D uTex;
#endif
uniform mediump sampler3D uLut;
uniform mat4 uTexMatrix;
uniform mat3 uGeom;
uniform vec2 uTexel;
uniform float uLutMix;
uniform float uLutSize;
uniform int uEffect;
uniform vec2 uCenter;
uniform float uRadius;
uniform float uFeather;
uniform float uBlur;
uniform vec2 uEllipse;
uniform float uAspect;
uniform vec4 uGrade;
uniform int uMonitor;
uniform vec4 uPeak;
uniform float uZebra;
uniform vec3 uBalance;
uniform int uTone;          // 0 = pass through, 1 = filmic (display), 2 = scene log, 3 = HLG, 4 = PQ
uniform int uInLog;         // 0 = display-referred input, 1 = Luma scene-log, 2 = linear (denoised history)
uniform float uExpGain;     // linear gain before tone mapping (undoes highlight headroom)
uniform vec3 uShoulder;     // x = scene white after gain, y = shoulder strength, z = display mid grey
uniform mediump sampler3D uProfile;
uniform float uProfileMix;
uniform float uProfileSize;
uniform float uGrain;
uniform float uSeed;
uniform sampler2D uLocal;   // blurred log2 luminance, camera texture space
uniform vec2 uLtm;          // x = base compression, y = detail gain
uniform float uSharp;       // detail lift amount
uniform sampler2D uMask;    // subject probability, upright source space
uniform int uMaskOn;
uniform int uBokeh;         // BokehStyle ordinal
in vec2 vUv;
out vec4 fragColor;

bool inside(vec2 s) { return s.x >= 0.0 && s.x <= 1.0 && s.y >= 0.0 && s.y <= 1.0; }

vec3 sampleAt(vec2 uv) {
    vec3 g = uGeom * vec3(uv - 0.5, 1.0);
    vec2 s = g.xy + 0.5;
    if (!inside(s)) return vec3(0.0);
    // Texture space is y-up; UV here is y-down.
    vec2 t = (uTexMatrix * vec4(s.x, 1.0 - s.y, 0.0, 1.0)).xy;
    return texture(uTex, t).rgb;
}

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// ---- Colour engine -------------------------------------------------------------------------
const float LOG_K = 48.0;
vec3 logDecode(vec3 c) { return (exp(c * log(1.0 + LOG_K)) - 1.0) / LOG_K; }
vec3 logEncode(vec3 x) { return log(1.0 + LOG_K * clamp(x, 0.0, 1.0)) / log(1.0 + LOG_K); }
vec3 srgbDecode(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 srgbEncode(vec3 x) { x = max(x, 0.0); return mix(12.92 * x, 1.055 * pow(x, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, x)); }

// OKLab (Ottosson) from linear Rec.709 / sRGB primaries.
const mat3 OK_M1 = mat3(0.4122214708, 0.2119034982, 0.0883024619, 0.5363325363, 0.6806995451, 0.2817188376, 0.0514459929, 0.1073969566, 0.6299787005);
const mat3 OK_M2 = mat3(0.2104542553, 1.9779984951, 0.0259040371, 0.7936177850, -2.4285922050, 0.7827717662, -0.0040720468, 0.4505937099, -0.8086757660);
const mat3 OK_I2 = mat3(1.0, 1.0, 1.0, 0.3963377774, -0.1055613458, -0.0894841775, 0.2158037573, -0.0638541728, -1.2914855480);
const mat3 OK_I1 = mat3(4.0767416621, -1.2684380046, -0.0041960863, -3.3077115913, 2.6097574011, -0.7034186147, 0.2309699292, -0.3413193965, 1.7076147010);
vec3 cbrt3(vec3 v) { return sign(v) * pow(abs(v), vec3(1.0 / 3.0)); }
vec3 toOklab(vec3 rgb) { return OK_M2 * cbrt3(OK_M1 * rgb); }
vec3 fromOklab(vec3 lab) { vec3 l = OK_I2 * lab; return OK_I1 * (l * l * l); }

// Scene luminance -> display luminance. Toe: slope 1.2 in log below mid grey keeps a true black floor and
// shadow depth (no lifted haze). Shoulder: exponential roll-off reaching 1.0 exactly at the scene white.
float filmic(float x) {
    const float mid = 0.18;
    float mo = uShoulder.z;
    if (x <= mid) return mo * pow(max(x, 0.0) / mid, 1.35);
    float k = uShoulder.y;
    float u = (x - mid) / max(uShoulder.x - mid, 1e-3);
    return min(1.0, mo + (1.0 - mo) * (1.0 - exp(-k * u)) / (1.0 - exp(-k)));
}

// Hue-preserving tone mapping on luminance, highlight desaturation and gamut mapping at constant OKLab
// lightness and hue (no cyan skies or orange skin when highlights compress).
vec3 toneMap(vec3 lin, vec2 p) {
    // Black point: remove a little veiling flare / pedestal so blacks are black, not milky grey.
    vec3 w = max(lin * uExpGain - 0.0025, 0.0) / (1.0 - 0.0025);
    if (uLtm.x > 0.0) {
        // Local tone mapping: compress the blurred (large-scale) luminance toward mid grey, keep the detail on
        // top. Dark regions rise, bright regions settle, texture stays; the lift fades out in near-black areas
        // so noise is not pulled up.
        float y0 = max(luma(w), 1e-5);
        float l = log2(y0);
        float lb = texture(uLocal, p).r + log2(uExpGain);
        const float mid = -2.4739;  // log2(0.18)
        // Asymmetric: shadows are opened more than highlights are pulled down (keeps bright areas bright).
        float k = lb < mid ? uLtm.x * smoothstep(mid - 7.0, mid - 4.0, lb) : uLtm.x * 0.45;
        float lb2 = mid + clamp(lb - mid, -5.0, 5.0) * (1.0 - k) + (lb - mid - clamp(lb - mid, -5.0, 5.0));
        float ln = lb2 + (l - lb) * uLtm.y;
        w *= exp2(clamp(ln - l, -2.5, 2.5));
    }
    float y = luma(w);
    float yd = filmic(y);
    vec3 t = w * (yd / max(y, 1e-6));
    vec3 lab = toOklab(t);
    lab.yz *= 1.0 - 0.85 * smoothstep(0.70, 1.0, yd);
    vec3 rgb = fromOklab(lab);
    if (any(greaterThan(rgb, vec3(1.0005))) || any(lessThan(rgb, vec3(-0.0005)))) {
        float lo = 0.0;
        float hi = 1.0;
        for (int i = 0; i < 7; i++) {
            float m = 0.5 * (lo + hi);
            vec3 r = fromOklab(vec3(lab.x, lab.yz * m));
            if (all(greaterThanEqual(r, vec3(-0.0005))) && all(lessThanEqual(r, vec3(1.0005)))) lo = m; else hi = m;
        }
        rgb = fromOklab(vec3(lab.x, lab.yz * lo));
    }
    return clamp(rgb, 0.0, 1.0);
}

// ---- HDR (BT.2020) ---------------------------------------------------------------------------
const mat3 M709_2020 = mat3(0.6274, 0.0691, 0.0164, 0.3293, 0.9195, 0.0880, 0.0433, 0.0114, 0.8956);
vec3 hlgOetf(vec3 e) {
    vec3 lo = sqrt(3.0 * e);
    vec3 hi = 0.17883277 * log(max(12.0 * e - 0.28466892, 1e-6)) + 0.55991073;
    return mix(lo, hi, step(1.0 / 12.0, e));
}
vec3 pqOetf(vec3 nits) {
    vec3 y = pow(clamp(nits / 10000.0, 0.0, 1.0), vec3(0.1593017578125));
    return pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), vec3(78.84375));
}
// Scene light in BT.2020 with a soft shoulder only in the last stop below the sensor clip, and highlight
// desaturation there (no hue-shifted clipped colours); mid grey stays at 0.18 scene-referred.
vec3 hdrScene(vec3 lin) {
    vec3 w = max(lin * uExpGain, 0.0);
    float y = luma(w);
    float white = uShoulder.x;
    float knee = white * 0.5;
    float yc = y <= knee ? y : knee + (white - knee) * (1.0 - exp(-(y - knee) / (white - knee)));
    vec3 t = w * (yc / max(y, 1e-6));
    vec3 lab = toOklab(t);
    lab.yz *= 1.0 - 0.6 * smoothstep(knee, white, y);
    return max(M709_2020 * fromOklab(lab), 0.0);
}

// ---- Portrait: mask-driven, physically averaged bokeh ------------------------------------------
vec2 srcUv(vec2 uv) { vec3 g = uGeom * vec3(uv - 0.5, 1.0); return g.xy + 0.5; }
vec3 toLinear(vec3 c) { return uInLog == 2 ? c : (uInLog == 1 ? logDecode(c) : srgbDecode(c)); }
vec3 fromLinear(vec3 c) { return uInLog == 2 ? c : (uInLog == 1 ? logEncode(c) : srgbEncode(c)); }
float subjectAt(vec2 uv) { return smoothstep(0.32, 0.68, texture(uMask, srcUv(uv)).r); }

// Gathers the background around uv in linear light. Subject pixels are excluded (no colour halo around hair and
// shoulders); bright samples weigh more, so speculars bloom into bokeh balls; the style shapes the kernel.
vec3 bokeh(vec2 uv, float r) {
    float ang = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) * 6.2831853;
    vec2 rc = uv - 0.5;
    float rl = length(rc);
    vec2 tang = rl > 1e-4 ? vec2(-rc.y, rc.x) / rl : vec2(0.0);
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 40; i++) {
        float t = float(i) * 2.3999632 + ang;
        float rr = sqrt((float(i) + 0.5) / 40.0);
        float rad = rr;
        if (uBokeh == 2) rad *= 0.6 + 0.4 * pow(abs(cos(3.0 * t)), 8.0);            // starry: six-point stars
        vec2 o = vec2(cos(t), sin(t)) * rad;
        if (uBokeh == 3) o.x *= 0.5;                                                  // cinematic: anamorphic ovals
        if (uBokeh == 4) o = mix(o, tang * dot(o, tang) * 1.8, clamp(rl * 1.8, 0.0, 1.0)); // swirl: tangential
        o *= r * vec2(1.0 / uAspect, 1.0);
        vec2 q = clamp(uv + o, vec2(0.001), vec2(0.999));
        vec3 c = toLinear(sampleAt(q));
        float w = 1.0 - 0.97 * subjectAt(q);
        w *= 1.0 + 10.0 * smoothstep(0.45, 1.0, luma(c));
        if (uBokeh == 1) w *= 0.35 + 1.65 * rr * rr * rr * rr;                        // bubble: bright rims
        acc += c * w;
        wsum += w;
    }
    return fromLinear(acc / max(wsum, 1e-4));
}

float hash12(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }

vec3 colorEngine(vec3 c, vec2 p) {
    if (uTone == 0) return c * uBalance;
    vec3 lin = (uInLog == 2 ? c : (uInLog == 1 ? logDecode(c) : srgbDecode(c))) * uBalance;
    if (uTone == 2) return logEncode(lin);
    if (uTone == 3) return hlgOetf(clamp(hdrScene(lin) * 0.2647, 0.0, 1.0));
    if (uTone == 4) return pqOetf(hdrScene(lin) * 203.0);
    vec3 d = srgbEncode(toneMap(lin, p));
    if (uProfileMix > 0.0) {
        vec3 lc = d * ((uProfileSize - 1.0) / uProfileSize) + 0.5 / uProfileSize;
        d = mix(d, texture(uProfile, lc).rgb, uProfileMix);
    }
    if (uGrain > 0.0) {
        // Fine luminance grain, strongest in the shadows: breaks up 8-bit steps and compression blocks.
        float n = hash12(gl_FragCoord.xy + uSeed) + hash12(gl_FragCoord.yx * 1.37 + uSeed) - 1.0;
        d += n * uGrain * (1.0 - smoothstep(0.05, 0.6, luma(d)));
    }
    return clamp(d, 0.0, 1.0);
}

vec3 falseColor(float y) {
    float ire = y * 100.0;
    if (ire < 2.5) return vec3(0.45, 0.10, 0.60);
    if (ire < 10.0) return vec3(0.05, 0.25, 0.90);
    if (ire < 20.0) return vec3(0.10, 0.55, 0.60);
    if (ire < 42.0) return vec3(y);
    if (ire < 52.0) return vec3(0.25, 0.75, 0.25);
    if (ire < 58.0) return vec3(0.95, 0.60, 0.70);
    if (ire < 78.0) return vec3(y);
    if (ire < 84.0) return vec3(0.95, 0.90, 0.30);
    if (ire < 97.5) return vec3(1.00, 0.55, 0.10);
    return vec3(1.0, 0.0, 0.0);
}

void main() {
    vec3 color;
    if (uEffect == 1 && uMaskOn == 1) {
        // Portrait with a subject mask: blur grows with distance behind the subject; the lower frame (floor,
        // nearer to the camera) stays a little sharper, like a real shallow depth of field.
        float subject = subjectAt(vUv);
        float depth = mix(0.65, 1.0, smoothstep(0.98, 0.35, vUv.y));
        float amount = (1.0 - subject) * depth;
        vec3 sharp = sampleAt(vUv);
        color = amount > 0.02 ? mix(bokeh(vUv, uBlur * amount), sharp, subject) : sharp;
    } else if (uEffect != 0) {
        vec2 d = (vUv - uCenter) * vec2(uAspect, 1.0) / uEllipse;
        float dist = length(d);
        float amount = smoothstep(uRadius, uRadius + uFeather, dist);
        if (amount > 0.001) {
            float r = uBlur * amount;
            // Golden-angle disc, randomly rotated per pixel: smooth bokeh instead of ghosted copies.
            float ang = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) * 6.2831853;
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 32; i++) {
                float t = float(i) * 2.3999632 + ang;
                float rr = sqrt((float(i) + 0.5) / 32.0);
                vec2 o = vec2(cos(t), sin(t)) * rr * r * vec2(1.0 / uAspect, 1.0);
                acc += sampleAt(clamp(vUv + o, vec2(0.001), vec2(0.999)));
            }
            color = acc / 32.0;
        } else {
            color = sampleAt(vUv);
        }
    } else {
        color = sampleAt(vUv);
        if (uSharp > 0.0) {
            // Detail lift: radius-1 luminance difference in a perceptual encoding, cored (no noise boost) and
            // clamped (no halos), applied as a brightness change so colour is untouched.
            vec3 n = (sampleAt(vUv + vec2(uTexel.x, 0.0)) + sampleAt(vUv - vec2(uTexel.x, 0.0))
                + sampleAt(vUv + vec2(0.0, uTexel.y)) + sampleAt(vUv - vec2(0.0, uTexel.y))) * 0.25;
            float lc = luma(uInLog == 2 ? sqrt(max(color, 0.0)) : color);
            float ln = luma(uInLog == 2 ? sqrt(max(n, 0.0)) : n);
            float d = lc - ln;
            d = sign(d) * max(abs(d) - 0.004, 0.0);
            d = clamp(d * uSharp, -0.03, 0.03);
            if (uInLog == 2) {
                float s = sqrt(max(luma(color), 1e-6));
                color *= ((s + d) * (s + d)) / max(s * s, 1e-6);
            } else {
                color += d;
            }
        }
    }

    // Colour engine (lens continuity gain, tone mapping, profile), then grade: exposure, warmth, tint, saturation.
    // Position in the camera texture's own space (before its transform): where the local map is sampled.
    vec3 gp = uGeom * vec3(vUv - 0.5, 1.0);
    color = colorEngine(color, vec2(gp.x + 0.5, 0.5 - gp.y));
    color *= exp2(uGrade.x);
    color.r *= 1.0 + 0.10 * uGrade.y;
    color.b *= 1.0 - 0.12 * uGrade.y;
    color.g *= 1.0 - 0.08 * uGrade.z;
    float yv = luma(color);
    color = clamp(mix(vec3(yv), color, uGrade.w), 0.0, 1.0);

    if (uLutMix > 0.0) {
        vec3 lc = color * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize;
        color = mix(color, texture(uLut, lc).rgb, uLutMix);
    }

    if (uMonitor != 0) {
        float y = luma(color);
        if ((uMonitor & 4) != 0) color = falseColor(y);
        if ((uMonitor & 2) != 0 && y >= uZebra) {
            float stripe = mod(gl_FragCoord.x + gl_FragCoord.y, 12.0);
            if (stripe < 5.0) color = vec3(0.0); else if (stripe < 7.0) color = vec3(1.0);
        }
        if ((uMonitor & 1) != 0) {
            // Peaking is not evaluated on the outermost pixels (no edge against the black border).
            if (vUv.x < 2.0 * uTexel.x || vUv.y < 2.0 * uTexel.y || vUv.x > 1.0 - 2.0 * uTexel.x || vUv.y > 1.0 - 2.0 * uTexel.y) { fragColor = vec4(color, 1.0); return; }
            float tl = luma(sampleAt(vUv + vec2(-uTexel.x, -uTexel.y)));
            float tc = luma(sampleAt(vUv + vec2(0.0, -uTexel.y)));
            float tr = luma(sampleAt(vUv + vec2(uTexel.x, -uTexel.y)));
            float ml = luma(sampleAt(vUv + vec2(-uTexel.x, 0.0)));
            float mr = luma(sampleAt(vUv + vec2(uTexel.x, 0.0)));
            float bl = luma(sampleAt(vUv + vec2(-uTexel.x, uTexel.y)));
            float bc = luma(sampleAt(vUv + vec2(0.0, uTexel.y)));
            float br = luma(sampleAt(vUv + vec2(uTexel.x, uTexel.y)));
            float gx = -tl - 2.0 * ml - bl + tr + 2.0 * mr + br;
            float gy = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
            if (length(vec2(gx, gy)) > uPeak.w) color = uPeak.rgb;
        }
    }
    fragColor = vec4(color, 1.0);
}
"""

        fun link(vs: String, fs: String): Int {
            val v = compile(GLES30.GL_VERTEX_SHADER, vs)
            val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, v)
            GLES30.glAttachShader(p, f)
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "link failed: " + GLES30.glGetProgramInfoLog(p) }
            GLES30.glDeleteShader(v)
            GLES30.glDeleteShader(f)
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "compile failed: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }
    }
}
