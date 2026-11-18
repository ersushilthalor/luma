package com.datarohit.luma.core.camera.session

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.TonemapCurve
import kotlin.math.exp
import kotlin.math.ln

/** How much of the camera HAL's own look a stream keeps. */
enum class IspMode {
    /** HAL defaults (Samsung tone curve, sharpening, noise reduction): the stock look. */
    CAMERA,

    /**
     * Luma's colour engine: no HAL sharpening, minimal HAL noise reduction, and a scene-log tone curve
     * instead of the HAL's tone mapping, so the stream reaches the GPU as log-encoded scene-linear light.
     */
    NEUTRAL_LOG,
}

/**
 * Luma's scene-log encoding (the curve handed to TONEMAP_CURVE and decoded on the GPU):
 * code = ln(1 + K·x) / ln(1 + K), x = scene-linear (1 = sensor clip after white balance and CCM).
 * K = 48 spreads about 11.6 stops over 8 bits with the finest steps in the shadows.
 */
object SceneLog {
    const val K = 48f
    private val NORM = ln(1f + K)

    fun encode(linear: Float): Float = (ln(1f + K * linear.coerceIn(0f, 1f)) / NORM).coerceIn(0f, 1f)
    fun decode(code: Float): Float = (exp(code.coerceIn(0f, 1f) * NORM) - 1f) / K

    /** Curve points for TonemapCurve, [points] samples, denser where the curve bends (shadows). */
    fun curve(points: Int): FloatArray {
        val n = points.coerceIn(2, 512)
        return FloatArray(n * 2) { i ->
            // Sample uniformly in code space, so the shadows get most of the points.
            val code = (i / 2).toFloat() / (n - 1)
            val x = decode(code).coerceIn(0f, 1f)
            if (i % 2 == 0) x else encode(x)
        }
    }
}

/**
 * Neutralises the camera HAL's post-processing per request, from what the camera reports it supports
 * (measured on SM-S948B: EDGE_MODE_OFF halves the fine-detail energy of the stream and lowers its
 * frame-to-frame noise; the contrast curve is honoured with up to 512 points).
 */
object IspTuning {

    private val curveCache = HashMap<Int, TonemapCurve>()

    fun supportsLog(c: CameraCharacteristics): Boolean =
        c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true &&
            (c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0) >= 32

    /** Streams (preview, video, analysis, RAW bursts): no sharpening, minimal NR, scene-log curve. */
    fun applyStream(b: CaptureRequest.Builder, c: CameraCharacteristics, mode: IspMode) {
        if (mode == IspMode.CAMERA) return
        edgeOff(b, c)
        val nr = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toSet().orEmpty()
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, when {
            CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL in nr -> CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
            CameraMetadata.NOISE_REDUCTION_MODE_OFF in nr -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
            else -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
        })
        hotPixels(b, c)
        b.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)
        if (supportsLog(c)) {
            val points = (c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 32).coerceAtMost(128)
            val curve = synchronized(curveCache) {
                curveCache.getOrPut(points) { SceneLog.curve(points).let { TonemapCurve(it, it, it) } }
            }
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, curve)
        }
    }

    /**
     * HAL-processed stills (lenses without usable RAW): the HAL's tone curve stays (Luma does not re-tone a
     * finished JPEG), but its sharpening goes; noise reduction stays at high quality because Luma has no
     * second denoiser on this path.
     */
    fun applyHalStill(b: CaptureRequest.Builder, c: CameraCharacteristics, mode: IspMode) {
        if (mode == IspMode.CAMERA) {
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
            return
        }
        edgeOff(b, c)
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        hotPixels(b, c)
        // Undo the stream's scene-log curve: the JPEG gets the HAL's display tone curve.
        b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
    }

    private fun edgeOff(b: CaptureRequest.Builder, c: CameraCharacteristics) {
        val edge = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toSet().orEmpty()
        if (CameraMetadata.EDGE_MODE_OFF in edge || edge.isEmpty()) b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
    }

    private fun hotPixels(b: CaptureRequest.Builder, c: CameraCharacteristics) {
        val hot = c.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.toSet().orEmpty()
        if (CameraMetadata.HOT_PIXEL_MODE_FAST in hot) b.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_FAST)
    }
}
