package com.datarohit.luma.foundation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.datarohit.luma.R
import com.datarohit.luma.appGraph
import com.datarohit.luma.core.camera.inventory.CameraInventory
import com.datarohit.luma.core.camera.inventory.CameraInventoryProbe
import com.datarohit.luma.core.camera.inventory.CameraInventoryReport
import com.datarohit.luma.core.camera.inventory.CameraListing
import com.datarohit.luma.core.camera.inventory.CameraSummary
import com.datarohit.luma.core.imaging.ImagingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface ScanState {
    data object Idle : ScanState
    data object Running : ScanState
    data class Done(val inventory: CameraInventory, val report: String) : ScanState
    data class Failed(val message: String) : ScanState
}

/** Diagnostics: engine build, optional live session section, and the camera inventory tool. */
@Composable
fun FoundationScreen(onBack: (() -> Unit)? = null, liveSection: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraGranted by remember { mutableStateOf(context.hasCameraPermission()) }
    var scan by remember { mutableStateOf<ScanState>(ScanState.Idle) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    val engine = remember { runCatching { ImagingEngine.buildInfo() } }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }
    LifecycleResumeEffect(Unit) {
        cameraGranted = context.hasCameraPermission()
        onPauseOrDispose { }
    }

    fun runScan() {
        scan = ScanState.Running
        savedFile = null
        scope.launch {
            scan = withContext(Dispatchers.Default) {
                runCatching {
                    val manager = context.getSystemService(CameraManager::class.java)
                    val inventory = CameraInventoryProbe(manager).collect()
                    ScanState.Done(inventory, CameraInventoryReport.render(inventory))
                }.getOrElse { ScanState.Failed(it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    fun save(report: String, then: (File) -> Unit = {}) {
        scope.launch {
            val file = withContext(Dispatchers.IO) { context.writeDiagnosticsReport(report) }
            savedFile = file
            if (file != null) then(file)
        }
    }

    val caps = remember { context.appGraph.caps }
    com.datarohit.luma.ui.design.IosScreen(title = "Diagnostics", backLabel = "Settings", onBack = onBack) {
        com.datarohit.luma.ui.design.IosSection("Engine") {
            engine.fold(
                onSuccess = {
                    com.datarohit.luma.ui.design.IosRow("Version", value = it.version)
                    com.datarohit.luma.ui.design.IosDivider()
                    com.datarohit.luma.ui.design.IosRow("Architecture", value = "${it.architecture} · ${it.simd}")
                    com.datarohit.luma.ui.design.IosDivider()
                    com.datarohit.luma.ui.design.IosRow("Compiler", value = it.compiler)
                },
                onFailure = { com.datarohit.luma.ui.design.IosRow(stringResource(R.string.engine_unavailable, it.message.orEmpty())) },
            )
        }
        if (liveSection != null) liveSection()
        com.datarohit.luma.ui.design.IosSection("This phone", footer = "Read from Camera2 and MediaCodecList at runtime. Luma builds its modes from these values.") {
            com.datarohit.luma.ui.design.IosRow("Cameras", value = caps.cameraIds.joinToString(" · "))
            com.datarohit.luma.ui.design.IosDivider()
            com.datarohit.luma.ui.design.IosRow("Concurrent sets", value = caps.concurrentSets.joinToString("  ") { it.sorted().joinToString("+") }.ifEmpty { "None" })
            caps.cameras[caps.cameraIds.firstOrNull()]?.let { c ->
                com.datarohit.luma.ui.design.IosDivider()
                com.datarohit.luma.ui.design.IosRow("Dynamic range", value = c.dynamicRangeProfiles.joinToString(" · ") { com.datarohit.luma.device.DeviceCapabilities.profileName(it) })
                com.datarohit.luma.ui.design.IosDivider()
                com.datarohit.luma.ui.design.IosRow("High speed", value = c.highSpeed.map { it.fps }.distinct().sorted().joinToString(" · ") { "$it fps" }.ifEmpty { "None" })
                com.datarohit.luma.ui.design.IosDivider()
                com.datarohit.luma.ui.design.IosRow("Video sizes", value = c.videoSizes.take(3).joinToString(" · ") { "${it.size.height}p${it.maxFps}" })
            }
            com.datarohit.luma.ui.design.IosDivider()
            com.datarohit.luma.ui.design.IosRow("Hardware encoders", value = caps.encoders.filter { it.hardware }.map { it.mime.substringAfter('/').uppercase(Locale.ROOT) }.distinct().joinToString(" · "))
        }
        com.datarohit.luma.ui.design.IosSection(stringResource(R.string.inventory_title), footer = savedFile?.let { stringResource(R.string.inventory_saved, it.absolutePath) }
            ?: (scan as? ScanState.Failed)?.let { stringResource(R.string.inventory_failed, it.message) }) {
            if (!cameraGranted) {
                com.datarohit.luma.ui.design.IosRow(stringResource(R.string.inventory_permission_rationale))
                com.datarohit.luma.ui.design.IosDivider()
                com.datarohit.luma.ui.design.IosButtonRow(stringResource(R.string.inventory_grant)) { permissionLauncher.launch(Manifest.permission.CAMERA) }
                com.datarohit.luma.ui.design.IosDivider()
            }
            com.datarohit.luma.ui.design.IosButtonRow(stringResource(if (scan == ScanState.Running) R.string.inventory_scanning else R.string.inventory_scan)) {
                if (scan != ScanState.Running) runScan()
            }
            val done = scan as? ScanState.Done
            if (done != null) {
                com.datarohit.luma.ui.design.IosDivider()
                com.datarohit.luma.ui.design.IosButtonRow(stringResource(R.string.inventory_save)) { save(done.report) }
                com.datarohit.luma.ui.design.IosDivider()
                com.datarohit.luma.ui.design.IosButtonRow(stringResource(R.string.inventory_share)) { save(done.report) { context.shareDiagnosticsReport(it) } }
            }
        }
        val cameras = (scan as? ScanState.Done)?.inventory?.cameras.orEmpty()
        cameras.forEach { CameraCard(it) }
    }
}

@Composable
private fun CameraCard(camera: CameraSummary) {
    val title = buildString {
        append("Camera ").append(camera.id).append(" · ").append(camera.facing.name.lowercase())
        if (camera.listing == CameraListing.HIDDEN_PHYSICAL) append(" · hidden physical")
        if (camera.isLogicalMultiCamera) append(" · logical ").append(camera.physicalCameraIds.joinToString(","))
    }
    com.datarohit.luma.ui.design.IosSection(title, footer = camera.capabilities.joinToString()) {
        com.datarohit.luma.ui.design.IosRow("Hardware level", value = camera.hardwareLevel.toString())
        com.datarohit.luma.ui.design.IosDivider()
        com.datarohit.luma.ui.design.IosRow("Optics", subtitle = cameraOpticsLine(camera))
        com.datarohit.luma.ui.design.IosDivider()
        com.datarohit.luma.ui.design.IosRow("RAW", value = cameraRawLine(camera))
        if (camera.extensions.isNotEmpty()) {
            com.datarohit.luma.ui.design.IosDivider()
            com.datarohit.luma.ui.design.IosRow("Extensions", value = camera.extensions.joinToString())
        }
    }
}

private fun cameraOpticsLine(camera: CameraSummary): String = listOfNotNull(
    camera.focalLengthsMm.firstOrNull()?.let { "%.2f mm".format(Locale.ROOT, it) },
    camera.equivalentFocalLengthMm?.let { "%.0f mm eq.".format(Locale.ROOT, it) },
    camera.apertures.firstOrNull()?.let { "f/%.1f".format(Locale.ROOT, it) },
    camera.minimumFocusDistanceMeters?.let { "min focus %.2f m".format(Locale.ROOT, it) },
    camera.zoomRatioRange?.let { "zoom ${it.lower}–${it.upper}×" },
    "OIS".takeIf { camera.opticalStabilization },
).joinToString(" · ")

private fun cameraRawLine(camera: CameraSummary): String {
    val raw = camera.streams.rawSensor.firstOrNull()?.let { "${it.width}×${it.height}" } ?: "None"
    val maxRes = camera.streams.rawSensorMaximumResolution.firstOrNull()?.let { " · max-res ${it.width}×${it.height}" }.orEmpty()
    return raw + maxRes
}

private fun Context.hasCameraPermission(): Boolean =
    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.writeDiagnosticsReport(report: String): File? {
    val directory = getExternalFilesDir("diagnostics") ?: return null
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))
    return File(directory, "camera-inventory-$stamp.txt").apply { writeText(report) }
}

private fun Context.shareDiagnosticsReport(file: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.diagnostics", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null))
}
