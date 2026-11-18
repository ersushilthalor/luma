# Camera2 capabilities and constraints

What the Android platform exposes, at which API level, and what Luma depends on. API levels were checked against `platforms/android-37.0/data/api-versions.xml` from the installed SDK. Definitions were checked against AOSP `metadata_definitions.xml`.

## Camera2 vs CameraX

| Requirement | Camera2 | CameraX (1.6.2) |
|---|---|---|
| `RAW_SENSOR` + DngCreator | Full | Supported since 1.5 through `ImageCapture` output formats, without per-frame control |
| Bursts with per-frame exposure (bracketing) | `captureBurst` with independent requests | Not exposed |
| `SENSOR_PIXEL_MODE` (200MP) | Yes | Not exposed |
| Physical camera streams (`OutputConfiguration.setPhysicalCameraId`) | Yes | Limited (physical camera selection through Camera2 interop) |
| Manual ISO / shutter / focus / white balance | Yes | Only through Camera2 interop, without lifecycle guarantees |
| Repeating RAW ring buffer (own ZSL) | Yes | No |
| Camera extensions | `CameraExtensionSession` | `ExtensionsManager` |
| Preview and orientation handling | Manual (or `androidx.camera.viewfinder`) | `PreviewView` |

**Decision:** Camera2 is the camera implementation (see [TECHNICAL_DECISIONS.md](TECHNICAL_DECISIONS.md#td-02-camera2-as-the-camera-api)). CameraX is not a dependency. The Camera2-agnostic `androidx.camera.viewfinder:viewfinder-compose` (1.6.2 stable) is a candidate for preview, because it handles surface lifecycle and transforms without hiding Camera2.

## API level map for keys Luma relies on

| Key / API | Purpose in Luma | API |
|---|---|---|
| `RAW_SENSOR`, `DngCreator`, `SENSOR_NOISE_PROFILE`, `STATISTICS_LENS_SHADING_MAP_MODE` | Native RAW, DNG, noise model, shading | 21 |
| `getPhysicalCameraIds`, `getAvailablePhysicalCameraRequestKeys`, `LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE` | Physical lens discovery | 28 |
| `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` (result) | Which lens produced a frame | 29 |
| `CONTROL_ZOOM_RATIO`, `CONTROL_ZOOM_RATIO_RANGE` | Zoom on logical cameras, including <1× | 30 |
| `SENSOR_PIXEL_MODE`, `SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION`, `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION`, `SENSOR_INFO_BINNING_FACTOR`, `SENSOR_RAW_BINNING_FACTOR_USED`, `ULTRA_HIGH_RESOLUTION_SENSOR`, `REMOSAIC_REPROCESSING`, `MultiResolutionImageReader`, `CameraExtensionCharacteristics` | High-resolution capture, extensions | 31 |
| `REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES` | 10-bit preview (optional) | 33 |
| `CONTROL_SETTINGS_OVERRIDE_ZOOM`, `JPEG_R`, `SENSOR_READOUT_TIMESTAMP`, `REQUEST_AVAILABLE_COLOR_SPACE_PROFILES`, `CONTROL_AUTOFRAMING`, extension postview | Low-latency zoom, Ultra HDR output, rolling-shutter timing | 34 |
| `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION`, `CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY`, `FLASH_STRENGTH_LEVEL`, `CameraManager.getCameraDeviceSetup` | Lens-switch diagnostics, faster session setup | 35 |
| `CONTROL_ZOOM_METHOD`, `CONTROL_AE_PRIORITY_MODE`, `COLOR_CORRECTION_MODE_CCT` + `COLOR_CORRECTION_COLOR_TEMPERATURE`, `HEIC_ULTRAHDR`, `EXTENSION_NIGHT_MODE_INDICATOR` | Explicit zoom method, shutter/ISO priority, Kelvin WB, HDR HEIF | 36 |
| `LOGICAL_MULTI_CAMERA_ADDITIONAL_RESULTS`, `INFO_DEVICE_TYPE`, `MultiResolutionImageReader.OnActiveOutputSurfacesListener`, `OutputConfiguration.makeDeferredAndRemoveSurfaces` | Metadata from non-active physical lenses, multi-res routing | 37 |

Luma's `minSdk` is 34, so features from API 35–37 need runtime version checks. All of them are available on the target device (API 37).

## Platform rules that shape the design

1. **Default RAW is binned.** With the RAW capability, the maximum `RAW_SENSOR` size equals the default-mode pixel array or pre-correction active array.
2. **Two pixel modes, mutually exclusive outputs.** Default-mode and max-resolution-mode outputs must not be mixed in one request unless both stream maps list them. A max-resolution still therefore needs either a separate session, or a request that targets only max-resolution outputs, with the session configured for both.
3. **Remosaic semantics.** With `ULTRA_HIGH_RESOLUTION_SENSOR` but without `REMOSAIC_REPROCESSING`, max-resolution `RAW_SENSOR` is regular Bayer: the HAL has already remosaiced it. With `REMOSAIC_REPROCESSING`, RAW may arrive in the binning-factor pattern. `SENSOR_RAW_BINNING_FACTOR_USED` in the result says which. Luma's DNG writer and demosaic must honor this per frame.
4. **Ultra-high-resolution guarantee.** A camera with this capability has a max-resolution pixel array of at least 24MP.
5. **Logical cameras switch lenses on their own.** `exposureTime`, `sensitivity`, lens properties, tonemap, white balance and shading map may change at a lens switch even when AE/AF/AWB are locked. Luma must treat `ACTIVE_PHYSICAL_ID` changes as a discontinuity in its exposure and color state.
6. **Hidden physical cameras.** Physical cameras can be absent from `getCameraIdList()` but are still queryable, and streamable as physical streams of their logical camera. Combinations of physical streams from different physical cameras are not guaranteed. Query them with `isSessionConfigurationSupported`.
7. **Metering coordinates.** In max-resolution mode, AE/AF/AWB regions use the max-resolution active array coordinate system.
8. **Shading map for DNG.** DngCreator embeds a lens-shading GainMap only if the result contains `STATISTICS_LENS_SHADING_MAP`, so RAW captures must set `STATISTICS_LENS_SHADING_MAP_MODE_ON`. This is always supported on RAW-capable devices.
9. **Minimum focus distance is in diopters.** With `UNCALIBRATED`, units are not physical. Luma converts to meters only for `APPROXIMATE` or `CALIBRATED`.
10. **Permission-gated characteristics.** Some keys (for example `LENS_INFO_MINIMUM_FOCUS_DISTANCE`) are withheld without CAMERA permission (`getKeysNeedingPermission`).
11. **Orientation on Android 17.** For displays with sw ≥ 600 dp, apps can no longer opt out of resizability and orientation changes. A phone is below that threshold, but the preview transform must still be computed from window metrics and sensor orientation, never assumed.

## S26 Ultra: device-specific constraints (verified at HAL level, 2026-11-18)

From read-only discovery ([DEVICE_DISCOVERY_S26_ULTRA.md](DEVICE_DISCOVERY_S26_ULTRA.md)):

| Constraint | Value | Design consequence |
|---|---|---|
| Primary camera | Logical camera **0** (LEVEL_3) with physical cameras 2 (UW), 5 (main), 6 (3x), 7 (5x) | Luma opens camera 0 for all back photography |
| Zoom range on camera 0 | 0.6–10.0 | 10× is the maximum preset |
| Max-resolution mode | `ULTRA_HIGH_RESOLUTION_SENSOR` absent. `sensor.pixelMode` not a request key | All public RAW is 12.5MP (10MP on 3x). 2× and 10× are digital crops |
| Physical request keys | None | Physical streams cannot get per-lens settings |
| RAW streams per session | 1 | One RAW source at a time |
| Multi-resolution streams | Not supported | No `MultiResolutionImageReader` |
| Settings override | OFF only | `SETTINGS_OVERRIDE_ZOOM` unavailable. Zoom latency follows normal pipeline depth (max 8) |
| Per-frame control | `PER_FRAME_CONTROL` | Exposure brackets are reliable |
| AE priority, CCT white balance | Available (API 36 keys present) | Pro mode uses native priority modes and Kelvin WB |
| Tonemap modes | CONTRAST_CURVE, FAST, HIGH_QUALITY | Analysis streams can use a known (linear) curve |
| Metering regions | AE 1, AWB 0, AF 1 | One AE region (face or tap). No AWB region |
| Result metadata | `activePhysicalId`, `activePhysicalSensorCropRegion`, `noiseProfile`, dynamic black/white level | Lens tracking and DNG metadata are complete |
| Logical OIS | OFF only (physical modules list ON) | Stabilization behavior to be measured |

## Zero shutter lag

| Approach | Mechanism | Feasibility |
|---|---|---|
| Platform ZSL | `CONTROL_ENABLE_ZSL` (request key present), `PRIVATE_REPROCESSING` (capability present) | Output is processed by the ISP, not Luma |
| Own RAW ring buffer | Repeating `RAW_SENSOR` stream into a small `ImageReader` ring buffer, alongside preview. On shutter, the last N frames are already captured | Preferred. The S26 Ultra advertises RAW16 4080 × 3060 at a 33 ms minimum frame duration (30 fps) on camera 0 and every back lens. **Sustained rate with preview still to verify** |
| Capture after shutter | Burst requested at shutter press | Always works. Adds latency equal to the burst duration |

## Camera extensions

`CameraExtensionCharacteristics.getSupportedExtensions()` lists the vendor modes (Automatic, Bokeh, Face Retouch, HDR, Night) per camera. Extension sessions output processed JPEG/YUV (and JPEG_R where supported), not RAW, and give limited manual control. From Android 17, vendors can define custom extension modes. Luma may offer a vendor extension only as a clearly labeled alternative ("Samsung Night processing"). It never presents one as Luma processing.

On the S26 Ultra, `ro.camerax.extensions.enabled=true` and the `com.samsung.android.cameraxservice` feature are present (verified), so a vendor extensions implementation exists. Press reports say Samsung exposed all five extension modes on its Android 14 flagships. Which modes the S26 Ultra exposes to third-party apps is **to be verified** with the diagnostic app (extension support is served by a vendor service and does not appear in the HAL dump).

## Hardware discovery procedure

The owner's phone is a **read-only inspection target**. Installing anything, pushing or pulling files, or changing settings requires the owner's explicit authorization for that phase.

### A. Read-only adb discovery (done 2026-11-18)

1. USB debugging must already be enabled and the laptop authorized by the owner. Luma never changes these settings.
2. From the repository root, run `bash tools/hardware-discovery/collect.sh`. Set `ADB=/path/to/adb` if adb is not on `PATH` (on this laptop: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`). The script only runs read-only queries (allowlisted `getprop` keys, `dumpsys media.camera` without arguments, `pm list features`, `cmd gpu vkjson`, memory, CPU and storage totals). It installs, pushes and pulls nothing.
3. Output goes to `hardware-reports/<model>-<build>-<date>/` (git-ignored). The camera dump is sanitized: the camera service event log and client history are dropped. `parse_camera_dump.py` writes `camera-summary.txt/json`.

Results: [DEVICE_DISCOVERY_S26_ULTRA.md](DEVICE_DISCOVERY_S26_ULTRA.md).

### B. App-level view and capture tests (require authorization)

The dumpsys output shows HAL metadata. It cannot show what `CameraManager` returns to a third-party app, extension availability or capture behavior. These are covered by the phased plan in [HARDWARE_VALIDATION_PLAN.md](HARDWARE_VALIDATION_PLAN.md): Phase A is a read-only diagnostic app with no capture, Phase B covers capture tests. Each phase starts only after the owner approves it.
