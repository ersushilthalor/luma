package com.datarohit.luma

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.datarohit.luma.camera.CameraScreen
import com.datarohit.luma.camera.CameraViewModel
import com.datarohit.luma.camera.formatShutter
import com.datarohit.luma.foundation.FoundationScreen
import com.datarohit.luma.gallery.GalleryScreen
import com.datarohit.luma.settings.SettingsScreen
import com.datarohit.luma.ui.theme.LumaTheme
import java.util.Locale
import kotlin.math.abs

private enum class Destination { CAMERA, GALLERY, SETTINGS, DIAGNOSTICS }

/** Fastest refresh rate of the panel (set at startup from Display.supportedModes). */
@Volatile var maxRefreshRate: Float = 60f

class LumaActivity : ComponentActivity() {

    private val vm: CameraViewModel by lazy { androidx.lifecycle.ViewModelProvider(this)[CameraViewModel::class.java] }
    private val orientation = mutableStateOf(0)
    private val tilt = mutableStateOf<Float?>(null)
    private var shutterKeys: (() -> Unit)? = null

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) {
                    tilt.value = null // Lying flat: no meaningful horizon.
                    return
                }
                vm.onRawOrientation(degrees.toFloat())
                val current = orientation.value
                // Hysteresis: only switch quadrant once the phone is clearly past the 45° boundary.
                val distance = angularDistance(degrees, current)
                if (distance > 55) orientation.value = ((degrees + 45) / 90 * 90) % 360
                tilt.value = signedAngle(degrees, orientation.value)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighestRefreshRate()
        // Phones run the camera UI in portrait and rotate icons instead; tablets and foldables may rotate.
        if (resources.configuration.smallestScreenWidthDp < 600) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setContent {
            LumaTheme {
                LumaApp(
                    orientation = orientation.value,
                    tilt = tilt.value,
                    registerShutterKeys = { shutterKeys = it },
                    keepScreenOn = { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    },
                    openAppInfo = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    },
                )
            }
        }
    }

    /**
     * Asks for the panel's fastest mode at the current resolution (120 Hz on the S26 Ultra), read from
     * Display.supportedModes rather than assumed. Variable-refresh panels still idle down when nothing
     * changes; while Luma animates, scrolls or streams the viewfinder it runs at the full rate.
     */
    private fun requestHighestRefreshRate() {
        val display = display ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
            preferredRefreshRate = best.refreshRate
        }
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            // Adaptive refresh votes "normal" UI content down to 60 Hz on this panel; Luma's animations,
            // dial scrubbing and carousel are motion content and ask for the panel's full rate instead.
            window.setFrameRatePowerSavingsBalanced(false)
            window.decorView.post {
                val content = window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)
                content.requestedFrameRate = best.refreshRate
                for (i in 0 until content.childCount) content.getChildAt(i).requestedFrameRate = best.refreshRate
            }
        }
        maxRefreshRate = best.refreshRate
        android.util.Log.i("LumaActivity", "display mode ${best.modeId}: ${best.physicalWidth}x${best.physicalHeight} @ ${best.refreshRate} Hz")
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        orientationListener.disable()
        super.onPause()
    }

    /** Volume keys act as a shutter while the camera is on screen (common camera convention). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val shutter = shutterKeys
        if (shutter != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_CAMERA)
        ) {
            if (event.repeatCount == 0) shutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (shutterKeys != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_CAMERA)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    private companion object {
        fun angularDistance(a: Int, b: Int): Int {
            val d = abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }

        fun signedAngle(degrees: Int, reference: Int): Float {
            var d = (degrees - reference) % 360
            if (d > 180) d -= 360
            if (d < -180) d += 360
            return d.toFloat()
        }
    }
}

@Composable
private fun LumaApp(
    orientation: Int,
    tilt: Float?,
    registerShutterKeys: ((() -> Unit)?) -> Unit,
    keepScreenOn: (Boolean) -> Unit,
    openAppInfo: () -> Unit,
) {
    val vm: CameraViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.CAMERA) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedOnce = true
        vm.onPermission(granted)
    }
    fun granted() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // Lifecycle: the camera runs only while the activity is resumed and the camera screen is shown.
    val lifecycleOwner = LocalLifecycleOwner.current
    val onCamera = destination == Destination.CAMERA
    DisposableEffect(lifecycleOwner, onCamera) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val g = granted()
                    if (g != ui.hasPermission) vm.onPermission(g)
                    if (onCamera) vm.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> vm.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (!onCamera) vm.onPause()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (granted()) vm.onPermission(true) else permission.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(orientation) { vm.onDeviceOrientation(orientation) }
    DisposableEffect(onCamera, ui.hasPermission) {
        registerShutterKeys(if (onCamera && ui.hasPermission) vm::shutter else null)
        keepScreenOn(onCamera)
        onDispose { registerShutterKeys(null) }
    }

    when (destination) {
        Destination.CAMERA -> {
            if (!ui.hasPermission && askedOnce) {
                PermissionScreen(
                    onGrant = { permission.launch(Manifest.permission.CAMERA) },
                    onOpenAppInfo = openAppInfo,
                    onGallery = { destination = Destination.GALLERY },
                )
            } else {
                CameraScreen(
                    vm = vm,
                    tilt = tilt,
                    onOpenGallery = { destination = Destination.GALLERY },
                    onOpenSettings = { destination = Destination.SETTINGS },
                )
            }
        }
        Destination.GALLERY -> GalleryScreen(onBack = {
            vm.refreshThumbnail()
            destination = Destination.CAMERA
        })
        Destination.SETTINGS -> SettingsScreen(
            onBack = { destination = Destination.CAMERA },
            onOpenDiagnostics = { destination = Destination.DIAGNOSTICS },
        )
        Destination.DIAGNOSTICS -> {
            BackHandler { destination = Destination.SETTINGS }
            FoundationScreen(
                onBack = { destination = Destination.SETTINGS },
                liveSection = { LiveSection(vm) },
            )
        }
    }
}

@Composable
private fun LiveSection(vm: CameraViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val info by vm.frameInfo.collectAsStateWithLifecycle()
    val state by vm.cameraState.collectAsStateWithLifecycle()
    val focus by vm.focusReadout.collectAsStateWithLifecycle()
    com.datarohit.luma.ui.design.IosSection("Live session") {
        com.datarohit.luma.ui.design.IosRow("State", value = state::class.simpleName ?: "–")
        com.datarohit.luma.ui.design.IosDivider()
        com.datarohit.luma.ui.design.IosRow("Mode", value = ui.modeId.label)
        com.datarohit.luma.ui.design.IosDivider()
        com.datarohit.luma.ui.design.IosRow("Route", value = "${ui.route} · ${"%.2f".format(Locale.ROOT, ui.zoom)}× · camera ${ui.descriptor?.cameraId ?: "–"}")
        info?.let {
            com.datarohit.luma.ui.design.IosDivider()
            com.datarohit.luma.ui.design.IosRow("Active lens", value = it.activePhysicalId ?: "–")
            com.datarohit.luma.ui.design.IosDivider()
            com.datarohit.luma.ui.design.IosRow("Exposure", value = "ISO ${it.iso} · ${formatShutter(it.exposureNs)}")
            com.datarohit.luma.ui.design.IosDivider()
            com.datarohit.luma.ui.design.IosRow("Focus", value = "${"%.2f".format(Locale.ROOT, it.focusDiopters)} dpt · " +
                com.datarohit.luma.core.camera.lens.FocusMath.format(focus?.distanceM))
        }
        ui.lastSummary?.let {
            com.datarohit.luma.ui.design.IosDivider()
            com.datarohit.luma.ui.design.IosRow("Last capture", subtitle = it)
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit, onOpenAppInfo: () -> Unit, onGallery: () -> Unit) {
    val ios = com.datarohit.luma.ui.design.Ios
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.mipmap.ic_launcher_legacy), contentDescription = null,
            modifier = Modifier.padding(bottom = 24.dp).size(96.dp),
        )
        Text("Allow Luma to use the camera", style = ios.Title2, color = ios.Label, textAlign = TextAlign.Center)
        Text(
            "Luma uses the camera only to take photos and videos on this phone. It has no internet access and never uploads anything.",
            style = ios.Subhead, color = ios.Label2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 32.dp),
        )
        com.datarohit.luma.ui.design.IosButton("Continue", primary = true, onClick = onGrant)
        com.datarohit.luma.ui.design.IosButton("Open App Settings", primary = false, onClick = onOpenAppInfo)
        com.datarohit.luma.ui.design.IosButton("View Library", primary = false, onClick = onGallery)
    }
}
