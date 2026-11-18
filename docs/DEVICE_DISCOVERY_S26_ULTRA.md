# Device discovery: Galaxy S26 Ultra (SM-S948B)

Read-only hardware discovery over ADB. **Nothing was installed, pushed, pulled, changed or started on the phone during this discovery.** See [Commands used](#commands-used) for the complete list.

Later the same day the owner authorized installing and testing Luma itself (and nothing else). The app-level results are in [§7](#7-app-level-findings-luma-debug-build-2026-11-18). Sections 1–6 describe the read-only HAL discovery only.

| | |
|---|---|
| Date / time | 2026-11-18, 00:55–01:05 IST (2026-11-17 19:25–19:35 UTC) |
| Method | ADB shell, read-only commands only (system properties, `dumpsys media.camera`, `pm list features`, `cmd gpu vkjson`, `/proc` and `/sys` reads, `df`, `wm size`/`wm density` without arguments) |
| Laptop tools | adb 1.0.41 (platform-tools 37.0.1-15733141), Windows 11 |
| Raw outputs | `hardware-reports/SM-S948B-S948BXXS6BZK1-20261118/` (git-ignored, sanitized: runtime event log and client history removed) |
| Parser | [`tools/hardware-discovery/parse_camera_dump.py`](../tools/hardware-discovery/parse_camera_dump.py) |

## Evidence classes

| Class | Meaning |
|---|---|
| **Verified on device** | Directly observed through a read-only device interface. For camera data this means the **camera service's copy of the HAL static metadata** (`dumpsys media.camera`). |
| **Verified through official documentation** | AOSP source or Android documentation. |
| **Reported** | Credible technical reporting (reviews, lab tests, press). |
| **Unverified** | Needs a Camera2 app on the device, or a capture test. |
| **Unavailable** | Not exposed through the inspected interfaces. |

**Main limitation of this method.** `dumpsys media.camera` shows the static metadata the camera service holds for every HAL device. It does **not** show what `CameraManager` returns to an untrusted third-party app. The framework can hide devices (system cameras, hidden physical cameras), withhold permission-gated keys, filter some sizes (performance-class JPEG filtering), and the vendor framework may apply per-package policies. Device-level values below are therefore "verified on device" as **HAL-advertised capabilities**. Whether Luma, as an ordinary app, sees exactly these values stays **Unverified** until the diagnostic app in [HARDWARE_VALIDATION_PLAN.md](HARDWARE_VALIDATION_PLAN.md) runs.

## 1. Connection

| Check | Result |
|---|---|
| USB detection (Windows PnP) | **Detected**: "SAMSUNG Mobile USB Composite Device", "SAMSUNG Android ADB Interface", MTP device, modem interfaces |
| ADB availability (laptop) | **Available**: adb 1.0.41 / 37.0.1 |
| First `adb devices` | Empty. The adb server had started before the phone was connected and had not enumerated it |
| After restarting the **laptop** adb server | Briefly `offline` (USB descriptor read errors in the adb trace), then `device` about 20 s later |
| Authorization | **Authorized** (state `device`). No authorization bypass was attempted |
| Read-only shell | **Successful** |

## 2. Device identification (Verified on device)

| Property | Value | Source |
|---|---|---|
| Manufacturer / brand | samsung | `ro.product.manufacturer` |
| Model | **SM-S948B** | `ro.product.model` |
| Device codename / product | m3q / m3qxins | `ro.product.device`, `ro.product.name` |
| Android | **17 (API 37)**, codename REL | `ro.build.version.release`, `.sdk` |
| One UI | 9.0 (`ro.build.version.oneui` = 90000) | `ro.build.version.oneui` |
| Build | CP2A.260605.016 / S948BXXS6BZK1 | `ro.build.display.id` |
| Fingerprint | `samsung/m3qxins/m3q:17/CP2A.260605.016/S948BXXS6BZK1_OXM6BZK1:user/release-keys` | `ro.build.fingerprint` |
| Vendor image | Android 16 base (`BP2A.250605.031.A3`), vendor API level 202504 | `ro.vendor.build.fingerprint`, `ro.vendor.api_level` |
| Security patch | 2026-11-05 | `ro.build.version.security_patch` |
| First API level | 36 (launched on Android 16) | `ro.product.first_api_level` |
| Build type | user / release-keys | `ro.build.type`, `ro.build.tags` |
| SoC | Qualcomm **SM8850** (platform `canoe`). Marketed as Snapdragon 8 Elite Gen 5 for Galaxy (*Reported*) | `ro.soc.manufacturer`, `ro.soc.model`, `ro.board.platform` |
| CPU | 8 cores: 6 × 3.63 GHz + 2 × 4.74 GHz | `/sys/devices/system/cpu/*/cpufreq/cpuinfo_max_freq` |
| ABIs | **arm64-v8a only** (no 32-bit ABIs) | `ro.product.cpu.abilist*` |
| RAM | 11.35 GB visible to the kernel (12 GB class). 16 GB swap (RAM Plus) | `/proc/meminfo` |
| Storage | `/data` 459 GB (512 GB class) | `df -h /data` (totals only) |
| Display (current mode) | 1080 × 2340, physical density 450 dpi, override 420 dpi (user display-size setting). This is the current display mode, not necessarily the panel maximum | `wm size`, `wm density` (no arguments = read-only) |
| GPU | Adreno 840. **Vulkan 1.4.295**. OpenGL ES 3.2 (+AEP) | `cmd gpu vkjson`, `pm list features`, `ro.opengles.version` |
| Updatable GPU drivers | `com.samsung.gamedriver.sm8850`, `com.qualcomm.qti.gpudrivers.canoe.api36` | `ro.gfx.driver.*` |

### Vulkan (Verified on device)

| Item | Value |
|---|---|
| Device API version | 1.4.295 |
| `android.hardware.vulkan.version` | 4210688 = 0x404000 → 1.4.0. `vulkan.level=1`, `vulkan.compute` |
| Extensions relevant to Luma | `VK_ANDROID_external_memory_android_hardware_buffer`, `VK_KHR_16bit_storage`, `VK_KHR_shader_float16_int8`, `VK_KHR_timeline_semaphore`, `VK_EXT_host_image_copy`, `VK_EXT_subgroup_size_control`, `VK_KHR_cooperative_matrix`, `VK_KHR_shader_integer_dot_product`: all present |
| Compute limits | maxComputeSharedMemorySize 32 KiB, maxComputeWorkGroupInvocations 1024, maxImageDimension2D 16384 |

### Platform features (Verified on device)

`camera`, `camera.any`, `camera.autofocus`, `camera.flash`, `camera.front`, `camera.concurrent`, `camera.level.full`, `camera.capability.raw`, `camera.capability.manual_sensor`, `camera.capability.manual_post_processing`, `sensor.gyroscope`, `vulkan.compute`. Samsung-specific: `com.samsung.android.camerasdkservice`, `com.samsung.android.cameraxservice`, `com.samsung.android.sdk.camera.processor`, `com.samsung.android.camera.deviceinjector=4`.

### Camera-related system properties (Verified on device)

| Property | Value | Interpretation |
|---|---|---|
| `ro.camerax.extensions.enabled` | true | The device ships a CameraX/Camera2 vendor extensions implementation. Which modes are exposed to apps: **Unverified** |
| `ro.camera.disableJpegR` | true | Disables only the *framework-derived* JPEG_R support (AOSP `CameraProviderManager::deriveJpegRTags`). The HAL itself advertises JPEG_R configurations on logical camera 0 (below) |
| `ro.camera.disableHeicUltraHDR` | true | Framework HEIC Ultra HDR derivation disabled. No HEIC stream configurations are advertised |
| `persist.vendor.camera.adaptivelensmode3p` | 1 | Vendor flag whose name suggests adaptive lens switching for third-party apps. No documentation found (**interpretation, Unverified**) |
| `vendor.camera.aux.packagelist` | org.codeaurora.snapcam | Qualcomm CAF auxiliary-camera allowlist. Whether Samsung's framework enforces it for third-party apps: **Unverified** |
| `vendor.camera.unihal.enable` | true | Samsung "UniHAL" camera HAL |

## 3. Camera devices

### 3.1 Camera service overview (Verified on device)

| Item | Value |
|---|---|
| HAL provider | `android.hardware.camera.provider.ICameraProvider/internal/0-1` (AIDL v2), 14 devices. An external provider exists with 0 devices |
| Camera service counts | "Number of camera devices: 4" (public + system-only, excluding hidden secure cameras). "Number of normal camera devices: 3". API1 IDs: 0, 1, 3 |
| Concurrent combinations | {1, 0} and {3, 0} (front + back) |

Interpretation, from the AOSP `CameraService::updateCameraNumAndIds` source: four IDs are public or system cameras. API1 excludes physical members of logical cameras. The most consistent reading is that third-party Camera2 apps see **IDs 0, 1, 2 and 3**, with 2 (the ultra-wide) visible to Camera2 but not API1. **Unverified until the app runs.**

### 3.2 Device map (Verified on device, HAL level)

| ID | Kind | Facing | Level | Role | Notes |
|---|---|---|---|---|---|
| **0** | Logical | Back | LEVEL_3 | Back logical camera | Physical IDs **2, 5, 6, 7**. Zoom ratio 0.6–10.0. Sync APPROXIMATE |
| **1** | Single | Front | FULL | Front camera | 12MP |
| **2** | Single, also physical member of 0 | Back | LIMITED | **Ultra-wide** | |
| **3** | Single | Front | FULL | Front, cropped (~30 mm eq.) | 7MP |
| 4 | Single | Front | FULL | Secure-only (`SECURE_IMAGE_DATA` only) | Hidden secure camera. Not usable |
| 5 | Physical of 0 only | Back | LEVEL_3 | **Main (wide)** | Not a standalone device |
| 6 | Physical of 0 only | Back | LIMITED | **Telephoto 3x** | Not a standalone device |
| 7 | Physical of 0 only | Back | LIMITED | **Telephoto 5x** | Not a standalone device |
| 20 | Logical | Back | LEVEL_3 | Vendor logical (58, 56, 52, 54) | Not counted as public |
| 21 / 23 / 24 | Logical | Back | FULL | Vendor logical pairs (56+52, 58+56, 52+54) | Not counted as public |
| 52 / 54 / 56 / 58 | Single | Back | LIMITED / LIMITED / LEVEL_3 / LIMITED | Standalone copies of 3x / 5x / main / ultra-wide | Not counted as public |
| 90 | Single | Front | FULL | Secure-only | Hidden secure camera |

### 3.3 Per-lens characteristics (Verified on device, HAL level)

The 35 mm equivalent focal length is computed from focal length, sensor physical size and active array.

| | Main (5) | Ultra-wide (2) | Tele 3x (6) | Tele 5x (7) | Front (1) |
|---|---|---|---|---|---|
| Focal length | 6.5 mm | 2.2 mm | 7.0 mm | 18.6 mm | 3.3 mm |
| 35 mm equivalent | **23.0 mm** | **13.3 mm** (0.58×) | **66.4 mm** (2.89×) | **112.7 mm** (4.90×) | 23.8 mm |
| Aperture | f/1.4 | f/1.9 | f/2.4 | f/2.9 | f/2.2 |
| Sensor physical size | 9.792 × 7.344 mm | 5.712 × 4.284 mm | 3.648 × 2.736 mm | 5.712 × 4.284 mm | 4.80 × 3.60 mm |
| Pixel array (default) | 4080 × 3060 (12.5MP) | 4080 × 3060 | 3648 × 2736 (10.0MP) | 4080 × 3060 | 4000 × 3000 |
| Pixel array (max-res key) | 4080 × 3060 (same) | 4080 × 3060 (same) | — | 4080 × 3060 (same) | — |
| Binning factor key | 2 × 2 | 2 × 2 | — | 2 × 2 | — |
| CFA | GBRG | GRBG | GBRG | GRBG | RGGB |
| White level | 1023 (10-bit) | 1023 | 1023 | 1023 | 1023 |
| ISO range / max analog | 9–3200 / 1200 | 15–3200 / 950 | 23–3200 / **371** | 25–3200 / **203** | 43–3200 / 345 |
| Exposure range | 43 µs – 255 ms | 84 µs – 176 ms | 33 µs – 214 ms | 48 µs – 274 ms | 81 µs – 203 ms |
| Min focus distance | 10 dpt = **0.10 m** | 20 dpt = **0.05 m** | 2.5 dpt = **0.40 m** | 1.25 dpt = **0.80 m** | 5 dpt = 0.20 m |
| Focus calibration | APPROXIMATE | APPROXIMATE | APPROXIMATE | APPROXIMATE | APPROXIMATE |
| AF modes | OFF, AUTO, MACRO, C-VIDEO, C-PICTURE, EDOF | same | same | same | same |
| OIS modes | OFF, ON | OFF only | OFF, ON | OFF, ON | OFF only |
| Capabilities (beyond BACKWARD_COMPATIBLE) | RAW, MANUAL_SENSOR, MANUAL_POST_PROCESSING, READ_SENSOR_SETTINGS, BURST_CAPTURE, PRIVATE + YUV_REPROCESSING, DYNAMIC_RANGE_TEN_BIT, STREAM_USE_CASE, COLOR_SPACE_PROFILES, CONSTRAINED_HIGH_SPEED_VIDEO | same, with YUV_REPROCESSING | same, without YUV_REPROCESSING | same, without YUV_REPROCESSING | same, without YUV_REPROCESSING |
| `ULTRA_HIGH_RESOLUTION_SENSOR` / `REMOSAIC_REPROCESSING` | **No / No** | No / No | No / No | No / No | No / No |
| RAW_SENSOR (RAW16) | 4080 × 3060 @ 30 fps | 4080 × 3060 @ 60 fps | 3648 × 2736 @ 30 fps | 4080 × 3060 @ 30 fps | 4000 × 3000 @ 30 fps |
| Other RAW formats | RAW10, RAW_PRIVATE | RAW10 | RAW12, RAW_PRIVATE | RAW10, RAW_PRIVATE | RAW10 |
| Largest JPEG / YUV / P010 | 4080 × 3060 | 4080 × 3060 | 3648 × 2736 | 4080 × 3060 | 4000 × 3000 |
| Max-resolution stream map | 1920 × 1080 JPEG/YUV/P010 only. **No RAW** | — | — | 1920 × 1080 only. **No RAW** | — |
| Lens shading map | 17 × 13, mode ON available, **`lensShadingApplied = TRUE`** | same | same | same | same |
| Hot pixel map | **OFF only** | OFF only | OFF only | OFF only | OFF only |
| Color calibration | ColorTransform/ForwardMatrix 1 & 2. Reference illuminants **D65** and **17 (Standard A)** | same | same | same | same |

### 3.4 Logical camera 0 (Verified on device, HAL level)

| Item | Value | Consequence for Luma |
|---|---|---|
| Physical IDs | 2 (UW), 5 (main), 6 (3x), 7 (5x) | Every back lens is reachable through camera 0 |
| `zoomRatioRange` | **0.6 – 10.0** | Third-party zoom through the logical camera stops at 10× |
| `availablePhysicalCameraRequestKeys` | **None listed** | No per-physical-camera overrides in a request. Physical streams take the logical request's settings. Physical stream configuration itself: **Unverified** |
| `maxNumOutputStreams` (raw, processed, stalling) | 1, 3, 2 | Exactly one RAW stream per session |
| `multiResolutionStreamSupported` | FALSE | No `MultiResolutionImageReader` |
| `sync.maxLatency` | PER_FRAME_CONTROL | Per-frame settings in bursts are applied exactly. Bracketing is feasible |
| `pipelineMaxDepth` | 8 | |
| `settingsOverride` | OFF only | `SETTINGS_OVERRIDE_ZOOM` **not supported** |
| `aeAvailablePriorityModes` | OFF, sensitivity priority, exposure-time priority | Native ISO and shutter priority (API 36) available |
| `colorCorrection.availableModes` | 0–3 (includes **CCT**). `colorTemperatureRange` 2300–10000 K | Kelvin + tint white balance available (API 36) |
| Tonemap modes | CONTRAST_CURVE, FAST, HIGH_QUALITY (no GAMMA_VALUE, no PRESET_CURVE) | A custom linear curve is possible for analysis streams |
| Max metering regions (AE, AWB, AF) | 1, 0, 1 | One AE and one AF region. No AWB regions |
| Face detection | OFF, SIMPLE, FULL | Face-aware exposure possible |
| Distortion correction | Distortion coefficients present per lens | |
| Readout timestamps | HARDWARE | Rolling-shutter timing available |
| Request keys present | `zoomRatio`, `zoomMethod`, `aePriorityMode`, `colorTemperature`, `colorTint`, `lensShadingMapMode`, `opticalStabilizationMode`, `enableZsl`, manual exposure/ISO/frame duration/focus | |
| Request keys absent | **`sensor.pixelMode`** | No max-resolution mode for apps |
| Result keys present | `activePhysicalId`, `activePhysicalSensorCropRegion`, `noiseProfile`, `dynamicBlackLevel`, `dynamicWhiteLevel`, `neutralColorPoint`, `greenSplit`, `rawBinningFactorUsed`, focus distance and range | Everything Luma needs for lens tracking and DNG |
| Logical OIS modes | **OFF only** | OIS exists on physical modules 5, 6 and 7, but the logical camera does not advertise OIS control. Actual stabilization behavior of logical-camera frames: **Unverified** |
| JPEG_R stream configurations | Advertised by the HAL (up to 4080 × 3060) | Camera2 Ultra HDR output plausible. App-level availability **Unverified** |
| HEIC stream configurations | None | **Unavailable** through Camera2 |

### 3.5 Samsung-private capabilities (Verified on device as vendor metadata, not public API)

Samsung vendor tags in the same static metadata show the readout modes that the stock app and Expert RAW use:

| Vendor tag | Content |
|---|---|
| `samsung.android.scaler.availableHighresRawStreamConfigurations` | Main: **16320 × 12240** and **8160 × 6120** RAW10. 5x: 8160 × 6120 RAW10 |
| `…ExpertRawHighresRawStreamConfigurations` | Main 16320 × 12240 / 8160 × 6120. 5x and ultra-wide 8160 × 6120 |
| `…ProRawStreamConfigurations` | Main, 5x, ultra-wide: 8160 × 6120 and 4000 × 3000 RAW16. 3x: 3648 × 2736 |
| `…SuperResolutionRaw`, `…AiFusionHighresRaw`, `…SuperNightRaw`, `…VirtualAperture…` | Other private capture paths |
| `samsung.android.control.highresModeInfo` | 200MP and 50MP configurations paired with numbers that look like lux thresholds (1000 and 600). **Interpretation only** |
| `samsung.android.scaler.rawSensorInfo` | `[10 0 0]`: 10-bit sensor RAW |
| Internal camera IDs | 52 (3x), 54 (5x), 56 (main), 58 (UW), and logical 20/21/23/24 |

These sizes are **not** in the standard `availableStreamConfigurations` or the max-resolution maps, and `sensor.pixelMode` is not a request key. Under AOSP stream validation, an app cannot configure an output size the public stream maps don't list. So 200MP and 50MP RAW are **Unavailable through the public Camera2 API** on this firmware. That Samsung's own apps can use them through private configuration is consistent with what the stock app offers (50MP/200MP, Expert RAW). Whether an app can even *read* these vendor keys through `CameraCharacteristics.getKeys()` is **Unverified**. Luma will not depend on undocumented vendor tags.

## 4. RAW and DNG findings

| Question | Answer | Class |
|---|---|---|
| Native RAW, main | **12.5MP (4080 × 3060)**, RAW16/RAW10, 30 fps stream config | Verified on device (HAL) |
| Native RAW, ultra-wide | 12.5MP (4080 × 3060), up to 60 fps | Verified on device (HAL) |
| Native RAW, 3x | 10MP (3648 × 2736), RAW16/RAW12 | Verified on device (HAL) |
| Native RAW, 5x | 12.5MP (4080 × 3060) binned | Verified on device (HAL) |
| Native RAW, front | 12MP (4000 × 3000). Camera 3: 7MP crop | Verified on device (HAL) |
| 200MP RAW | **Not available to third-party apps** (no UHR capability, no `pixelMode` request key, no max-res RAW stream). Exists only as a Samsung-private configuration | Verified on device (HAL) + AOSP rules |
| 50MP RAW (main, 5x, UW) | **Not available to third-party apps**. Samsung-private only | Verified on device (HAL) |
| 48MP / 24MP RAW | No such sensor mode. Achievable only as *reconstructed* output | Verified (absence) |
| RAW bit depth | 10-bit (white level 1023) | Verified on device (HAL) |
| Lens shading in RAW | Already partly applied (`lensShadingApplied = TRUE`). The result shading map is the residual | Verified on device (HAL) + AOSP definition |
| Hot pixel map | Not available | Verified on device (HAL) |
| DNG color metadata | Dual-illuminant matrices (D65 + Standard A) present on all lenses | Verified on device (HAL) |
| RAW from physical streams (e.g. 3x RAW while the logical camera is at 1×) | Physical streams are part of the logical camera. Only one RAW stream per session | **Unverified** |
| Sustained repeating RAW at 30 fps with preview | Stream config allows 30 fps per stream. A combined session is not tested | **Unverified** |

## 5. Telephoto and macro findings

- **3x close-focus limit:** the 3x module cannot focus closer than about **0.40 m** (2.5 dpt, APPROXIMATE). Any 3× subject closer than that must come from another module, almost certainly a crop of the main camera. Publicly, that means a crop of the 12.5MP binned output: about 1.5MP of real pixels at 2.9×. This is a **hardware-backed mechanism** that matches the reported "blurry 3x in close-ups". Whether Samsung's stock pipeline also has a processing defect is a separate, unverified question.
- **5x close-focus limit:** about **0.80 m**. Subjects closer than that at 5× fall back to the 3x module or the main camera.
- **Telephoto sensitivity:** max analog ISO is **371** (3x) and **203** (5x), against 1200 on the main camera. Above these, gain is digital, which raises noise without adding information. Telephoto low-light quality depends on multi-frame merging at analog-only ISO.
- **10× zoom:** logical zoom ends at 10.0. Without the 50MP 5x readout, Luma's 10× is a ~2.04× crop of the 12.5MP 5x frame: about 3.0MP of real pixels, which is **digital zoom**.
- **2× zoom:** without the 50MP main readout, 2× is a 2× crop of the 12.5MP main frame: about 3.1MP, which is **digital zoom**. The stock app's "optical-quality" 2× and 10× rely on Samsung-private readouts.
- **Macro:** the ultra-wide focuses to about **0.05 m** (20 dpt) but has no OIS. The main camera focuses to 0.10 m. Luma's close-focus path is ultra-wide below about 10 cm, and main camera otherwise.
- **Lens switching:** happens inside logical camera 0, and is visible through `activePhysicalId` and `activePhysicalSensorCropRegion` results. The 0.6×, 1×, 2.89× and 4.90× native points are where the optical sources sit.

## 6. What remains unverified

| Item | Why | How it will be verified |
|---|---|---|
| Exact `getCameraIdList()` returned to an app | Framework or vendor filtering is not visible in dumpsys | Diagnostic app: `CameraManager.cameraIdList` |
| App-visible characteristics vs HAL metadata (sizes, keys, vendor keys) | Framework filtering, permission-gated keys | Diagnostic app full characteristic dump |
| Camera extensions available to apps (Night, Bokeh, HDR, Auto, Face Retouch, vendor-defined) | Extensions are served by a vendor service, not the HAL dump | `CameraExtensionCharacteristics` in the diagnostic app |
| Physical-stream sessions (e.g. preview on 0 + RAW from physical 6) | Session support is decided at configuration time | `isSessionConfigurationSupported` (query only, no capture) |
| Repeating RAW at 30 fps + preview, sustained | Needs a running session | Capture phase (requires separate authorization) |
| JPEG_R output through Camera2 | Framework path not exercised | Session query, then capture phase |
| Real OIS behavior on logical-camera frames | Logical camera advertises OFF only | Capture phase (gyro vs frame motion) |
| Accuracy of min focus distances (APPROXIMATE) | Calibration class only | Capture phase with a target |
| Whether the 3x close-up blur reproduces on this firmware | Requires capture | Capture phase, stock app and Luma |

## 7. App-level findings (Luma debug build, 2026-11-18)

Measured with Luma's own debug build after the owner authorized installing and testing Luma (only Luma). Raw reports are in `hardware-reports/app-level/` (git-ignored). Every item below is **Verified on device (app level)**.

### 7.1 What an app sees (Phase A, no camera opened)

| Item | Result |
|---|---|
| `getCameraIdList()` | `0, 1, 2, 3`, identical to the HAL. Hidden physical IDs `5, 6, 7` are readable through `getCameraCharacteristics`, but are not listed |
| Concurrent camera sets | `[0, 1]` and `[0, 3]` |
| `CameraDeviceSetup` (session queries without opening) | Supported on all four public cameras |
| Session support (query) | Preview + YUV + RAW + JPEG, and preview + JPEG_R, supported on 0–3. Camera 0 also accepts physical RAW or YUV streams from 2, 5, 6 and 7 |
| Camera extensions | Bokeh, Night and Face Retouch on 0–3 (and on 5 and 6; 7 has no Night). No HDR or Auto extension |
| `getAvailablePhysicalCameraRequestKeys()` | Returns **null** on camera 0, although the API documents a non-null list. Code must handle null |
| Characteristics vs HAL dump | Matched for the values Luma uses (sizes, CFA, ISO and exposure ranges, focus distances, calibration) |

### 7.2 Capture behavior (Phase B and capture tests)

| Item | Result | Consequence for Luma |
|---|---|---|
| Repeating RAW 4080×3060 + YUV 640×480 + preview on camera 0 | 24.97 fps for 5 s, no failures, max gap 40 ms | A RAW ring buffer (ZSL) is feasible at about 25 fps |
| A logical-camera-0 session that contains **any** RAW stream | The active camera is pinned to **main (5)** at every zoom from 0.6× to 10× (`activePhysicalId` = 5, crop grows with zoom) | RAW on camera 0 always means main-sensor RAW |
| The same session **without** RAW | The HAL switches lenses: 0.6× → 2, 3× → 6, 5× → 7. Close or dim subjects get crops of wider lenses; at about 20 cm the ultra-wide serves even 1× | Telephoto output exists only as HAL-processed images |
| Physical RAW streams of inactive lenses (6, 7) on camera 0 | Frames arrive, but AF is inactive (0 dpt) and there is no AWB (strong green cast) | Unusable as photos. Not used |
| Opening hidden IDs 5, 6, 7 directly | `CameraAccessException` (reason 2, CAMERA_DISABLED-type refusal) | No direct telephoto access |
| Opening camera 2 (ultra-wide) directly | Full 3A, RAW works with the Luma engine | Ultra-wide RAW route |
| Manual exposure and ISO, per frame | Exact (requested = reported) | Burst plans can rely on per-frame control |
| JPEG_R (Ultra HDR) | Works | Possible HDR output format later |
| `SENSOR_NOISE_PROFILE` on the main camera | Read-noise term reported as 0 (only the shot-noise term) | Engine adds a floor estimated from the frames |
| Main-camera RAW | 10-bit in RAW16, dynamic black 64, white 1023, shading map 17×13 (max gain ≈ 1.7–1.9) | Matches TD-20 |

### 7.3 Engine on the device

10 × 12.5MP RAW frames of a static indoor scene, main camera:

| Stage | Time |
|---|---|
| Burst capture (10 × 40 ms) | ≈ 0.8 s |
| Align | ≈ 0.5–0.6 s |
| Merge | ≈ 0.33 s |
| Demosaic, colour, tone, finish | ≈ 0.8 s |
| **Total processing** | **≈ 1.6–1.9 s** |

Mean merge weight 0.96 (static scene), noise reduction ≈ +9.8 dB. A 10 × 125 ms Night burst takes ≈ 1.7 s to capture.

### 7.4 Resulting lens routing (TD-21)

| Zoom | Route | Output class |
|---|---|---|
| Below 1× | Camera 2 (ultra-wide) opened directly, RAW | Luma RAW pipeline |
| 1× up to ≈ 2.75× (95 % of the 3x lens's native factor) | Camera 0 with RAW (main sensor, crop) | Luma RAW pipeline; digital zoom above 1× |
| ≈ 2.75× and above | Camera 0 without RAW, HAL JPEG | **Processed by the camera HAL**, labeled. No DNG |

## Commands used

All commands ran from the laptop. None changes device state. Git Bash path conversion was disabled (`MSYS_NO_PATHCONV=1`) for commands with absolute device paths, after an initial attempt passed a converted Windows path that the phone rejected as nonexistent (`df`, `cat`). That attempt was harmless.

```
# Laptop-side
adb version
adb devices -l
adb kill-server ; adb start-server          # restarts the laptop adb server only
Get-PnpDevice -PresentOnly                   # Windows USB inventory (PowerShell)

# Read-only device queries
adb get-state
adb shell getprop                            # read; filtered to an allowlist on the laptop, not stored
adb shell dumpsys media.camera               # camera service dump, no arguments
adb shell pm list features
adb shell cmd gpu vkjson                     # Vulkan device description (JSON)
adb shell cat /proc/meminfo                  # MemTotal / SwapTotal only
adb shell cat /sys/devices/system/cpu/possible
adb shell cat /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq
adb shell df -h /data                        # filesystem totals only
adb shell wm size                            # no arguments: prints only
adb shell wm density                         # no arguments: prints only
```

Not used: `install`, `uninstall`, `push`, `pull`, `reboot`, `settings`, `am`, `content`, `pm` (except `list features`), `cmd media.camera`, `dumpsys` with arguments, `fastboot`, or any file access outside `/proc` and `/sys` reads of CPU and memory totals. No photos, media, accounts, messages or app data were accessed.

## Recommended next validation steps

1. Done 2026-11-18: Phase A and Phase B (see §7).
2. Still open: the 3× distance sweep against a chart, OIS behavior against gyro data, and minimum-focus checks with a target.
3. Re-run this discovery after each firmware update (`tools/hardware-discovery/collect.sh`). Samsung has changed third-party camera access between firmware versions before.
