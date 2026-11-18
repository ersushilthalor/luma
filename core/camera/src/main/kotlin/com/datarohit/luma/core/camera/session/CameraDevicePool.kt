package com.datarohit.luma.core.camera.session

import android.annotation.SuppressLint
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor

/**
 * Open camera devices shared by the photo and video pipelines. Closing a session parks its device here
 * instead of closing it, so switching between photo and video modes on the same camera only builds a new
 * capture session (tens of milliseconds) rather than reopening the camera (hundreds). [closeAll] releases
 * every device when the app leaves the foreground.
 */
class CameraDevicePool(private val manager: CameraManager) {

    private val thread = HandlerThread("luma-camera-devices").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val mutex = Mutex()
    private val devices = mutableMapOf<String, CameraDevice>()
    private val listeners = mutableListOf<(String, String) -> Unit>()

    /** [listener] receives (cameraId, message) when an open device is disconnected or fails. */
    fun addLossListener(listener: (String, String) -> Unit) = synchronized(listeners) { listeners += listener }

    /**
     * Returns the open device for [id], opening it if needed. [exclusive] first closes every other parked
     * device (phones limit how many cameras one app may hold; an extra open would evict one anyway).
     */
    @SuppressLint("MissingPermission")
    suspend fun acquire(id: String, exclusive: Boolean = true, timeoutMs: Long = 5_000): CameraDevice = mutex.withLock {
        if (exclusive) closeAllExcept(setOf(id))
        synchronized(devices) { devices[id] }?.let { return@withLock it }
        val result = CompletableDeferred<CameraDevice>()
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!result.complete(camera)) camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) = lost(camera, "Camera is in use by another app")
            override fun onError(camera: CameraDevice, error: Int) = lost(camera, "Camera error $error")

            private fun lost(camera: CameraDevice, message: String) {
                camera.close()
                if (result.completeExceptionally(IllegalStateException(message))) return
                val removed = synchronized(devices) { if (devices[camera.id] === camera) devices.remove(camera.id) else null }
                if (removed != null) synchronized(listeners) { listeners.toList() }.forEach { it(camera.id, message) }
            }
        }
        try {
            manager.openCamera(id, executor, callback)
        } catch (e: CameraAccessException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        }
        val device = try {
            withTimeout(timeoutMs) { result.await() }
        } catch (e: Exception) {
            // A late onOpened closes itself (complete fails once the deferred is cancelled).
            result.cancel()
            throw e
        }
        synchronized(devices) { devices[id] = device }
        device
    }

    /** True when [id] is open and parked or in use. */
    fun isOpen(id: String): Boolean = synchronized(devices) { id in devices }

    /** Closes [id] (e.g. after an error the device cannot recover from). */
    fun close(id: String) {
        val d = synchronized(devices) { devices.remove(id) } ?: return
        runCatching { d.close() }
    }

    /** Closes every device except [keep]. */
    fun closeAllExcept(keep: Set<String>) {
        val drop = synchronized(devices) { devices.keys.filter { it !in keep }.mapNotNull { devices.remove(it) } }
        drop.forEach { runCatching { it.close() } }
    }

    fun closeAll() {
        val all = synchronized(devices) { devices.values.toList().also { devices.clear() } }
        all.forEach { d -> runCatching { d.close() }.onFailure { Log.w(TAG, "close", it) } }
    }

    private companion object {
        const val TAG = "LumaDevicePool"
    }
}
