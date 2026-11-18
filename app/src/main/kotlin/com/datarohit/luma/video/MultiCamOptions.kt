package com.datarohit.luma.video

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.util.Log
import android.util.Size

/** One camera stream a multi-camera recording can use. */
data class CameraSource(
    val label: String,
    /** Camera device to open. */
    val deviceId: String,
    /** Physical camera of [deviceId] to bind the stream to (null = the device itself). */
    val physicalId: String?,
    val facingBack: Boolean,
)

enum class ComboKind(val label: String) {
    CONCURRENT("Separate cameras"),
    PHYSICAL("Lens streams"),
}

/** A hardware-backed set of streams that can record together. */
data class MultiCombo(val sources: List<CameraSource>, val kind: ComboKind, val verified: Boolean, val note: String) {
    val key: String get() = sources.joinToString("+") { it.deviceId + (it.physicalId?.let { p -> "/$p" } ?: "") }
    val title: String get() = sources.joinToString(" + ") { it.label }
}

/**
 * Enumerates multi-camera combinations from what the phone reports at runtime:
 * `getConcurrentCameraIds()` sets (independent devices, full 3A each) and physical-camera stream sets of
 * the logical back camera checked with `CameraDeviceSetup.isSessionConfigurationSupported` (API 35+).
 */
object MultiCamOptions {

    fun enumerate(manager: CameraManager, labelFor: (deviceId: String, physicalId: String?) -> String): List<MultiCombo> {
        val out = mutableListOf<MultiCombo>()
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        fun facingBack(id: String) = runCatching {
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }.getOrDefault(true)

        // Independent devices that the HAL guarantees can stream together.
        runCatching { manager.concurrentCameraIds }.getOrDefault(emptySet()).forEach { set ->
            if (set.size < 2) return@forEach
            val sources = set.sorted().map { CameraSource(labelFor(it, null), it, null, facingBack(it)) }
            out += MultiCombo(sources, ComboKind.CONCURRENT, verified = true,
                note = "Concurrent camera set reported by the phone · full autofocus, exposure and white balance on each")
        }

        // Physical streams of each logical camera.
        for (id in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val physical = chars.physicalCameraIds.sorted()
            if (physical.size < 2) continue
            val combos = subsets(physical, 2, minOf(4, physical.size))
            for (combo in combos) {
                val verified = checkPhysical(manager, id, combo)
                if (verified == false) continue
                val sources = combo.map { p -> CameraSource(labelFor(id, p), id, p, facingBack(id)) }
                out += MultiCombo(sources, ComboKind.PHYSICAL, verified = verified == true,
                    note = "Physical lens streams of camera $id · autofocus and white balance follow the active lens; " +
                        "other lenses may be soft or tinted (measured on this phone)")
            }
        }
        return out
    }

    /** true = supported, false = rejected, null = cannot be checked before opening. */
    private fun checkPhysical(manager: CameraManager, logicalId: String, physical: List<String>): Boolean? {
        if (Build.VERSION.SDK_INT < 35) return null
        return try {
            if (!manager.isCameraDeviceSetupSupported(logicalId)) return null
            val setup = manager.getCameraDeviceSetup(logicalId)
            val outputs = physical.map { p ->
                OutputConfiguration(Size(1920, 1080), SurfaceTexture::class.java).apply { setPhysicalCameraId(p) }
            }
            setup.isSessionConfigurationSupported(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs))
        } catch (e: Exception) {
            Log.w("LumaMultiCam", "check $logicalId $physical", e)
            null
        }
    }

    private fun subsets(items: List<String>, min: Int, max: Int): List<List<String>> {
        val result = mutableListOf<List<String>>()
        fun go(start: Int, acc: MutableList<String>) {
            if (acc.size in min..max) result += acc.toList()
            if (acc.size == max) return
            for (i in start until items.size) {
                acc += items[i]
                go(i + 1, acc)
                acc.removeAt(acc.lastIndex)
            }
        }
        go(0, mutableListOf())
        return result
    }
}
