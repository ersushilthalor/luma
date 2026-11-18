# Hardware validation plan

How Luma will validate the Galaxy S26 Ultra from the point of view of an ordinary third-party app. **Nothing here may be installed or run on the owner's phone until the owner explicitly authorizes it.**

**Status (2026-11-18):** the owner authorized installing, updating, deleting and testing Luma itself, and nothing else on the phone. Phase A and Phase B ran that day. See [Execution record](#execution-record-2026-11-18) and [DEVICE_DISCOVERY_S26_ULTRA.md §7](DEVICE_DISCOVERY_S26_ULTRA.md#7-app-level-findings-luma-debug-build-2026-11-18).

The read-only ADB discovery ([DEVICE_DISCOVERY_S26_ULTRA.md](DEVICE_DISCOVERY_S26_ULTRA.md)) established what the camera HAL advertises. What remains is what `CameraManager` returns to an untrusted app, and how the hardware behaves under capture.

## Ground rules (all phases)

| Rule | Detail |
|---|---|
| Separate authorization per phase | Phase A (read-only app) and Phase B (capture tests) each need the owner's explicit approval before anything is installed |
| Debug build only | Installed with `adb install` of a locally built debug APK, only after approval. Uninstalled after the phase with `adb uninstall com.datarohit.luma`, if the owner wants it removed |
| Permissions | Only `CAMERA`, granted by the owner on the phone through the normal runtime dialog. Never granted through `pm grant`. No storage, location, media or network permissions |
| No personal data | The app never reads MediaStore, the gallery, accounts or other apps' data. It writes only to its own app-specific directory |
| No settings changes | The app changes no system, camera-app or developer settings |
| Getting results off the phone | The owner uses the in-app **Share report** action, or separately authorizes an `adb pull` limited to `Android/data/com.datarohit.luma/files/diagnostics/` |
| Clean state | Uninstalling removes all app data, including diagnostics reports |

## Phase A: read-only diagnostic app

**Goal:** answer "what does a normal app see?" without opening a capture session or producing any image.

The current foundation build already contains the core of this: the camera inventory screen (`core:camera/inventory`). It enumerates IDs, reads characteristics and lists extensions, without opening a camera. Phase A extends it as follows (to be implemented only when the phase is approved).

| Item | API | Opens a camera? |
|---|---|---|
| Camera IDs visible to apps | `CameraManager.getCameraIdList()` | No |
| Logical / physical relationships | `CameraCharacteristics.getPhysicalCameraIds()`. Hidden physical IDs queried with `getCameraCharacteristics(id)` | No |
| Full `CameraCharacteristics` per camera, including permission-gated keys and any vendor keys exposed to apps | `getKeys()`, `getKeysNeedingPermission()` | No |
| Output formats and sizes: RAW_SENSOR, RAW10/12, JPEG, YUV, P010, JPEG_R, HEIC; high-resolution sizes; min frame and stall durations | `StreamConfigurationMap` (default and `…_MAXIMUM_RESOLUTION`) | No |
| Focal lengths, apertures, minimum focus distance, calibration, hyperfocal distance | `LENS_INFO_*` | No |
| Pixel array, active array, pre-correction array, binning factor, CFA, white level, black level pattern | `SENSOR_INFO_*` | No |
| Manual sensor support, ISO range, max analog ISO, exposure-time range, max frame duration | Capabilities, `SENSOR_INFO_*`, `SENSOR_MAX_ANALOG_SENSITIVITY` | No |
| AF modes, OIS modes, AE/AWB modes, AE priority modes, CCT range, tonemap modes, max regions | `CONTROL_*`, `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`, `COLOR_CORRECTION_*` | No |
| Request, result, session and physical-request key lists | `getAvailableCaptureRequestKeys()`, `getAvailableCaptureResultKeys()`, `getAvailableSessionKeys()`, `getAvailablePhysicalCameraRequestKeys()` | No |
| Camera extensions per camera, supported sizes, postview, capture-progress support | `CameraExtensionCharacteristics` | No |
| Concurrency | `CameraManager.getConcurrentCameraIds()`, `isConcurrentSessionConfigurationSupported()` | No |
| Camera availability | `CameraManager.AvailabilityCallback` (logged while the screen is open; never forces access) | No |
| Stream-combination support (preview + RAW, preview + RAW + YUV, physical streams of camera 0, JPEG_R) | `CameraManager.getCameraDeviceSetup(id).isSessionConfigurationSupported(...)` (API 35). Requires `isCameraDeviceSetupSupported(id)` | **No.** Queries without opening the device. If `CameraDeviceSetup` is unsupported, the query is skipped and recorded as "requires opening the device", which waits for Phase B |

**Output:** a versioned text and JSON report in the app's diagnostics directory, containing the device model and build only (no serials). It is compared field by field with the HAL dump from read-only discovery, and every difference is recorded as a finding.

**Acceptance:** every "Unverified (app-level)" item in [DEVICE_DISCOVERY_S26_ULTRA.md](DEVICE_DISCOVERY_S26_ULTRA.md#6-what-remains-unverified) that can be answered without opening a camera has an answer.

## Phase B: capture tests (separate authorization)

**Goal:** measure behavior that only shows during capture. Captured frames stay in app-internal storage, are used only for measurement, and are deleted at the end of each test unless the owner chooses to keep a test set. Nothing is written to the gallery.

| Test | Measures |
|---|---|
| Open/close cycle on each public camera | Session setup time. Failures |
| Preview + repeating RAW_SENSOR on camera 0 at 30 fps for 60 s | Delivered frame rate, dropped frames, thermal status (decides the zero-shutter-lag ring buffer) |
| Physical stream sessions on camera 0 (RAW from 5, 6, 7, 2) | Which physical RAW streams work. Delivered size |
| Zoom sweep 0.6 → 10 with `activePhysicalId` and `activePhysicalSensorCropRegion` logging | Lens switch points, hysteresis, crop behavior |
| 3× distance sweep (0.2–5 m against a chart) | Which physical camera serves 3× at each distance. Close-focus fallback distance |
| Manual exposure and ISO requests vs results | Exactness of manual control per lens, including above max analog ISO |
| Burst with per-frame exposure changes | Per-frame control latency (expected: exact, per `PER_FRAME_CONTROL`) |
| OIS behavior on camera 0 vs physical streams | Frame-to-frame motion against gyro |
| Minimum focus distance check per lens | Measured against APPROXIMATE metadata |
| DNG round-trip | DngCreator output for each lens opens correctly in desktop RAW converters |
| JPEG_R capture | Whether Camera2 Ultra HDR output works for apps |
| Extensions (if listed) | Night/Bokeh capture latency and output, for reference only |

**Acceptance:** results recorded in [CAMERA_HARDWARE.md](CAMERA_HARDWARE.md), [DEVICE_DISCOVERY_S26_ULTRA.md](DEVICE_DISCOVERY_S26_ULTRA.md) and the `spec.md` statuses. Test artifacts deleted from the phone, or the app uninstalled, as the owner chooses.

## Phase C: stock comparison

Benchmark captures with the stock Camera app follow [BENCHMARKING.md](BENCHMARKING.md). They create photos in the owner's gallery, so they need the owner's explicit approval and are done by the owner or under their direction.

## Execution record (2026-11-18)

| Step | What ran | Phone-side effect |
|---|---|---|
| Install | `adb install --user 0 -r -g app-debug.apk` (Luma only, primary user) | Luma installed. The `-g` flag granted Luma's own `CAMERA` permission at install time instead of through the runtime dialog. **This deviates from the ground rule above.** It affected only Luma, and the grant disappears on uninstall |
| Phase A | Debug-only `DiagnosticsActivity` (`am start` of Luma's own activity with an extra) | None outside Luma's private storage |
| Phase B and capture tests | The same activity: repeating RAW, zoom sweep, lens probe, 8 capture cases through the Luma engine | Test images were written to Luma's private storage only, never to the gallery |
| Reports | `adb pull` of Luma's own private report files to `hardware-reports/app-level/` (git-ignored) | Read only |
| Logs | `adb logcat -T <time>` filtered to Luma | Read only. **Once**, early on, `logcat -c` was run. It clears the phone's log buffer, a small change to system state outside Luma. It has not been used since |
| Interruptions | One run was cancelled when the phone left Luma (Home was pressed). It was retried once; test runs stop if the phone is in active use | None |
| Cleanup | `adb uninstall --user 0 com.datarohit.luma`, then a read-only check | No package, no `Android/data`, `media` or `obb` folders, no `DCIM/Luma` or `Pictures/Luma`, no files in `/data/local/tmp`, no MediaStore rows owned by Luma |

**Later the same day (full app):** the complete app was reinstalled the same way. A shutter press from the real UI (`adb shell input tap`, sent only while Luma was in the foreground) saved photos to `DCIM/Luma` through MediaStore. That is the app's intended behavior, and these are files owned by Luma. Test photos are deleted again at the end of each test session, unless the owner chooses to keep them.
