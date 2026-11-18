package com.datarohit.luma

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraManager
import com.datarohit.luma.capture.CaptureProcessor
import com.datarohit.luma.core.camera.session.CameraCatalog
import com.datarohit.luma.core.camera.session.CameraController
import com.datarohit.luma.core.storage.LumaMediaRepository
import com.datarohit.luma.core.storage.MediaStoreWriter
import com.datarohit.luma.settings.SettingsRepository

/** Manual dependency graph (docs/ARCHITECTURE.md: no DI framework). */
class AppGraph(context: Context) {
    val cameraManager: CameraManager = context.getSystemService(CameraManager::class.java)
    val catalog = CameraCatalog(cameraManager)
    val controller = CameraController(cameraManager)
    val settings = SettingsRepository(context)
    val media = LumaMediaRepository(context)
    val writer = MediaStoreWriter(context)
    val segmenter by lazy { com.datarohit.luma.portrait.PortraitSegmenter(context.applicationContext) }
    val processor = CaptureProcessor(controller, { id -> catalog.characteristics(id) }, writer, { b -> segmenter.segment(b) })
    val caps = com.datarohit.luma.device.DeviceCapabilities(cameraManager)
    val resolutions = com.datarohit.luma.capture.ResolutionCatalog { id -> catalog.characteristics(id) }
    val renderer by lazy { com.datarohit.luma.gl.GlRenderer() }
    val videoCamera = com.datarohit.luma.video.VideoCamera(cameraManager, controller.devices)
    val videoEngine by lazy { com.datarohit.luma.video.VideoEngine(context.applicationContext, caps, renderer, videoCamera, com.datarohit.luma.video.RateStore(context)) }
    val proSettings = com.datarohit.luma.video.ProSettingsStore(context)
}

class LumaApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

val Context.appGraph: AppGraph get() = (applicationContext as LumaApplication).graph
