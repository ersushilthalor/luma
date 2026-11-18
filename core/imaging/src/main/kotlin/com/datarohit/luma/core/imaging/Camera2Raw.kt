package com.datarohit.luma.core.imaging

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform

/** Converts Camera2 characteristics and capture results into engine inputs. */
object Camera2Raw {
    /** Minimum read noise (standard deviation, in raw digital numbers) assumed when the HAL reports none. */
    private const val READ_NOISE_FLOOR_DN = 1.5f


    fun metadata(chars: CameraCharacteristics, result: CaptureResult, width: Int, height: Int): RawMetadata {
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.takeIf { it.size >= 4 }
            ?: chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
                floatArrayOf(
                    p.getOffsetForIndex(0, 0).toFloat(), p.getOffsetForIndex(1, 0).toFloat(),
                    p.getOffsetForIndex(0, 1).toFloat(), p.getOffsetForIndex(1, 1).toFloat(),
                )
            } ?: floatArrayOf(64f, 64f, 64f, 64f)
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toFloat()
            ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat() ?: 1023f
        val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { n ->
            val g = n[1].toFloat().takeIf { it > 0f } ?: 1f
            floatArrayOf(n[0].toFloat() / g, 1f, n[2].toFloat() / g)
        } ?: floatArrayOf(0.5f, 1f, 0.5f)
        val noise = result.get(CaptureResult.SENSOR_NOISE_PROFILE)
        val noiseScale = noise?.map { it.first }?.average()?.toFloat() ?: 0f
        // Some modules (the S26 Ultra main camera) report a zero read-noise term. Keep a floor of about
        // READ_NOISE_FLOOR_DN digital numbers so shadow variance never collapses to quantization level.
        val range = (white - black.average().toFloat()).coerceAtLeast(1f)
        val readFloor = (READ_NOISE_FLOOR_DN / range).let { it * it }
        val noiseOffset = maxOf(noise?.map { it.second }?.average()?.toFloat() ?: 0f, readFloor)

        var columns = 0
        var rows = 0
        var gains: FloatArray? = null
        result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { map ->
            columns = map.columnCount
            rows = map.rowCount
            gains = FloatArray(columns * rows * 4).also { out ->
                for (r in 0 until rows) for (c in 0 until columns) for (ch in 0 until 4) {
                    out[(r * columns + c) * 4 + ch] = map.getGainFactor(ch, c, r)
                }
            }
        }
        return RawMetadata(width, height, cfa, black.copyOf(4), white, neutral, noiseScale, noiseOffset, columns, rows, gains)
    }

    fun calibration(chars: CameraCharacteristics): ColorCalibration {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val cm1 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.toArray()
        val cm2 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toArray()
        val fm1 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.toArray()
        val fm2 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.toArray()
        val cal1 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.toArray()
        val cal2 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.toArray()
        val ill1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 21
        val ill2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 0
        val hasSecond = cm2 != null && ill2 != 0
        return ColorCalibration(
            colorMatrix1 = cm1 ?: DEFAULT_CM,
            colorMatrix2 = cm2 ?: cm1 ?: DEFAULT_CM,
            forwardMatrix1 = fm1 ?: FloatArray(9),
            forwardMatrix2 = fm2 ?: fm1 ?: FloatArray(9),
            calibration1 = cal1 ?: identity,
            calibration2 = cal2 ?: cal1 ?: identity,
            illuminant1 = ill1,
            illuminant2 = if (hasSecond) ill2 else ill1,
            hasSecond = hasSecond,
            hasForward = fm1 != null,
        )
    }

    private fun ColorSpaceTransform.toArray(): FloatArray =
        FloatArray(9) { i -> getElement(i % 3, i / 3).toFloat() }

    /** XYZ -> sRGB-like camera fallback when a module reports no colour transform. */
    private val DEFAULT_CM = floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f, 1.9161415f, 0.0334540f,
        0.0719453f, -0.2289914f, 1.4052427f,
    )
}
