package com.datarohit.luma.device

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/** One video encoder as reported by MediaCodecList. */
data class EncoderInfo(
    val name: String,
    val mime: String,
    val hardware: Boolean,
    val profiles: List<Int>,
    val maxWidth: Int,
    val maxHeight: Int,
    val bitrateMax: Int,
) {
    val tenBit: Boolean
        get() = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> profiles.any { it in TEN_BIT_HEVC }
            MIME_APV -> true
            else -> false
        }

    companion object {
        const val MIME_APV = "video/apv"
        val TEN_BIT_HEVC = setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        )
    }
}

data class VideoSizeOption(val size: Size, val maxFps: Int)
data class HighSpeedOption(val size: Size, val fps: Int)

/** Per-camera capabilities that decide which video and photo options Luma offers. */
data class CameraCaps(
    val id: String,
    val facingBack: Boolean,
    val physicalIds: List<String>,
    val dynamicRangeProfiles: Set<Long>,
    /** Profiles that can run together with each 10-bit profile (DynamicRangeProfiles constraints). */
    val profileConstraints: Map<Long, Set<Long>>,
    val videoSizes: List<VideoSizeOption>,
    val highSpeed: List<HighSpeedOption>,
    val videoStabilization: Set<Int>,
    val ois: Boolean,
    val tonemapModes: Set<Int>,
    val tonemapMaxPoints: Int,
    val jpegSizes: List<Size>,
    val heic: Boolean,
    val cctAndTint: Boolean,
    val extensions: List<Int>,
) {
    val supportsLog: Boolean get() = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in tonemapModes && tonemapMaxPoints >= 32
    val supportsHlg: Boolean get() = DynamicRangeProfiles.HLG10 in dynamicRangeProfiles
    val supportsHdr10: Boolean get() = DynamicRangeProfiles.HDR10 in dynamicRangeProfiles
    val supportsHdr10Plus: Boolean get() = DynamicRangeProfiles.HDR10_PLUS in dynamicRangeProfiles
    val previewStabilization: Boolean get() = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION in videoStabilization
}

/**
 * Runtime inventory of what this phone exposes to a third-party app: cameras, concurrent camera sets,
 * dynamic-range profiles, high-speed modes and encoders. Nothing is hardcoded per device.
 */
class DeviceCapabilities(private val manager: CameraManager) {

    val cameraIds: List<String> by lazy { runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList()) }

    val cameras: Map<String, CameraCaps> by lazy {
        val all = cameraIds.toMutableSet()
        cameraIds.forEach { id -> runCatching { all += manager.getCameraCharacteristics(id).physicalCameraIds } }
        all.mapNotNull { id -> runCatching { id to describe(id, manager.getCameraCharacteristics(id)) }.getOrNull() }.toMap()
    }

    val concurrentSets: List<Set<String>> by lazy {
        runCatching { manager.concurrentCameraIds.map { it.toSet() } }.getOrDefault(emptyList())
    }

    val encoders: List<EncoderInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && !it.isAlias }.flatMap { info ->
            info.supportedTypes.filter { it.startsWith("video/") }.mapNotNull { type ->
                runCatching {
                    val caps = info.getCapabilitiesForType(type)
                    val v = caps.videoCapabilities
                    EncoderInfo(
                        name = info.name,
                        mime = type,
                        hardware = info.isHardwareAccelerated,
                        profiles = caps.profileLevels.map { it.profile }.distinct(),
                        maxWidth = v?.supportedWidths?.upper ?: 0,
                        maxHeight = v?.supportedHeights?.upper ?: 0,
                        bitrateMax = v?.bitrateRange?.upper ?: 0,
                    )
                }.getOrNull()
            }
        }
    }

    fun encoder(mime: String, tenBit: Boolean = false): EncoderInfo? =
        encoders.filter { it.mime == mime && (!tenBit || it.tenBit) }.sortedByDescending { it.hardware }.firstOrNull()

    private val codecInfos by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder } }
    private val supportCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * True when [mime] can encode [size] at [fps] (checked against the codec's own capability table).
     * Memoised: UI rows ask this while composing, and building codec capability tables is expensive.
     */
    fun encoderSupports(mime: String, size: Size, fps: Int): Boolean = supportCache.getOrPut("$mime|${size.width}x${size.height}@$fps") {
        codecInfos.filter { mime in it.supportedTypes }.any { info ->
            runCatching {
                val v = info.getCapabilitiesForType(mime).videoCapabilities ?: return@runCatching false
                v.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) ||
                    v.areSizeAndRateSupported(size.height, size.width, fps.toDouble())
            }.getOrDefault(false)
        }
    }

    private fun describe(id: String, c: CameraCharacteristics): CameraCaps {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val videoSizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            .filter { it.width >= 640 && it.width * 9 == it.height * 16 }
            .map { s ->
                val d = runCatching { map!!.getOutputMinFrameDuration(SurfaceTexture::class.java, s) }.getOrDefault(0L)
                VideoSizeOption(s, if (d > 0) (1_000_000_000L / d).toInt() else 30)
            }.sortedByDescending { it.size.width }
        val highSpeed = runCatching {
            map!!.highSpeedVideoSizes.flatMap { s ->
                map.getHighSpeedVideoFpsRangesFor(s).filter { it.lower == it.upper }.map { HighSpeedOption(s, it.upper) }
            }.distinct()
        }.getOrDefault(emptyList())
        val profiles = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        val supported = profiles?.supportedProfiles.orEmpty()
        val constraints = supported.associateWith { p -> runCatching { profiles!!.getProfileCaptureRequestConstraints(p) }.getOrDefault(emptySet()) }
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val cct = Build.VERSION.SDK_INT >= 36 &&
            c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)?.contains(CameraMetadata.COLOR_CORRECTION_MODE_CCT) == true
        val ext = runCatching { manager.getCameraExtensionCharacteristics(id).supportedExtensions }.getOrDefault(emptyList())
        return CameraCaps(
            id = id,
            facingBack = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK,
            physicalIds = c.physicalCameraIds.sorted(),
            dynamicRangeProfiles = supported,
            profileConstraints = constraints,
            videoSizes = videoSizes,
            highSpeed = highSpeed,
            videoStabilization = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toSet().orEmpty(),
            ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
            tonemapModes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty(),
            tonemapMaxPoints = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0,
            jpegSizes = map?.getOutputSizes(ImageFormat.JPEG).orEmpty().sortedByDescending { it.width.toLong() * it.height },
            heic = map?.getOutputSizes(ImageFormat.HEIC)?.isNotEmpty() == true,
            cctAndTint = cct && CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps,
            extensions = ext,
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("sdk", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE)
        put("cameraIdList", JSONArray(cameraIds))
        put("concurrentCameraIds", JSONArray(concurrentSets.map { JSONArray(it.sorted()) }))
        put("cameras", JSONObject().apply {
            cameras.forEach { (id, c) ->
                put(id, JSONObject().apply {
                    put("facingBack", c.facingBack)
                    put("physicalIds", JSONArray(c.physicalIds))
                    put("dynamicRangeProfiles", JSONArray(c.dynamicRangeProfiles.map(::profileName)))
                    put("profileConstraints", JSONObject().apply {
                        c.profileConstraints.forEach { (k, v) -> put(profileName(k), JSONArray(v.map(::profileName))) }
                    })
                    put("videoSizes16x9", JSONArray(c.videoSizes.map { "${it.size.width}x${it.size.height}@${it.maxFps}" }))
                    put("highSpeed", JSONArray(c.highSpeed.map { "${it.size.width}x${it.size.height}@${it.fps}" }))
                    put("videoStabilization", JSONArray(c.videoStabilization.toList()))
                    put("ois", c.ois)
                    put("tonemapModes", JSONArray(c.tonemapModes.toList()))
                    put("tonemapMaxPoints", c.tonemapMaxPoints)
                    put("jpegMax", c.jpegSizes.firstOrNull()?.let { "${it.width}x${it.height}" })
                    put("heic", c.heic)
                    put("cctAndTint", c.cctAndTint)
                    put("extensions", JSONArray(c.extensions))
                })
            }
        })
        put("encoders", JSONArray(encoders.map { e ->
            JSONObject().apply {
                put("name", e.name); put("mime", e.mime); put("hw", e.hardware)
                put("profiles", JSONArray(e.profiles)); put("tenBit", e.tenBit)
                put("max", "${e.maxWidth}x${e.maxHeight}"); put("bitrateMax", e.bitrateMax)
            }
        }))
    }

    companion object {
        fun profileName(p: Long): String = when (p) {
            DynamicRangeProfiles.STANDARD -> "STANDARD"
            DynamicRangeProfiles.HLG10 -> "HLG10"
            DynamicRangeProfiles.HDR10 -> "HDR10"
            DynamicRangeProfiles.HDR10_PLUS -> "HDR10_PLUS"
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM -> "DOLBY_VISION_10B_OEM"
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF -> "DOLBY_VISION_10B_REF"
            else -> "0x" + p.toString(16)
        }
    }
}
