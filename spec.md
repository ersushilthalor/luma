# Luma: Product and Technical Specification

| | |
|---|---|
| Application | Luma |
| Package | `com.datarohit.luma` |
| Tagline | Capture Naturally. Render Beautifully. |
| Spec version | 0.2 (foundation + read-only device discovery) |
| Last updated | 2026-11-18 |
| Primary target | Samsung Galaxy S26 Ultra |

This document is the authoritative source of truth for Luma. The documents in `docs/` hold the research, evidence and design detail behind it. Where they disagree, this document wins, and the disagreement is a bug to fix.

## 0. Status labels

Every requirement and capability carries exactly one label:

| Label | Meaning |
|---|---|
| **Confirmed** | Established by a primary source (AOSP, Android SDK, official docs) or verified in this repository (build, lint, manifest inspection). For product rules: decided and in force |
| **Proposed** | Intended design. Feasible according to research, not yet implemented or measured |
| **To be verified** | Depends on facts about the S26 Ultra not yet measured on the device |
| **Experimental** | May be built, but ships only behind an explicit label if measurements justify it |
| **Not supported** | Will not be built, or cannot be built with public APIs |
| **Deferred** | Out of scope for the current roadmap phase |

**Device verification status (2026-11-18):** Read-only ADB discovery was performed on the owner's SM-S948B (firmware S948BXXS6BZK1, Android 17), see [docs/DEVICE_DISCOVERY_S26_ULTRA.md](docs/DEVICE_DISCOVERY_S26_ULTRA.md). Values marked **Confirmed (HAL)** were observed in the camera service's HAL static metadata. What an ordinary app receives from `CameraManager`, and all capture behavior, remain **To be verified** under the phased plan in [docs/HARDWARE_VALIDATION_PLAN.md](docs/HARDWARE_VALIDATION_PLAN.md). Each phase needs the owner's authorization. The owner's phone is a read-only inspection target until then.

---

## 1. Product identity and vision

Luma is an Android computational photography camera for still photography. Its goal is photographs that are consistently better than the Samsung stock camera in selected scenarios: exposure decisions, highlight control, dynamic range, natural color, shadow detail, skin tones, restrained sharpening, telephoto consistency. It gets there through a carefully engineered, measurable RAW-based pipeline, not through saturation, heavy HDR or synthetic detail.

The interface is premium and familiar to Samsung users, with its own visual identity. It does not copy Samsung's design pixel-for-pixel.

## 2. Objectives

| ID | Objective | Status |
|---|---|---|
| OBJ-1 | Exposure decisions that preserve highlights and shadows, per scene, and are consistent shot to shot | Proposed |
| OBJ-2 | Controlled highlight roll-off without flat or hue-shifted highlights | Proposed |
| OBJ-3 | Natural, accurate color with refined skin tones, sensor-aware per lens | Proposed |
| OBJ-4 | Detail retention without artificial sharpening or texture synthesis | Proposed |
| OBJ-5 | Honest, consistent telephoto: the real source lens, crop and quality shown to the user | Proposed |
| OBJ-6 | RAW-centric workflow: processed images and DNGs from the same captured data | Proposed |
| OBJ-7 | Every quality claim backed by controlled comparison ([docs/BENCHMARKING.md](docs/BENCHMARKING.md)) | Confirmed (rule) |

## 3. Non-goals

| ID | Non-goal | Status |
|---|---|---|
| NG-1 | Video recording (the owner uses Blackmagic Camera for video) | Not supported |
| NG-2 | Cloud upload, accounts, analytics, ads, network features | Not supported |
| NG-3 | Mandatory AI in the core pipeline | Not supported |
| NG-4 | Beauty filters, skin smoothing, "AI enhance", texture synthesis | Not supported |
| NG-5 | Pixel-for-pixel imitation of Samsung's UI or branding | Not supported |
| NG-6 | Single-frame upscaling presented as higher resolution | Not supported |
| NG-7 | Broad device compatibility before the S26 Ultra experience is right | Deferred |

## 4. Product rules (apply everywhere)

| ID | Rule | Status |
|---|---|---|
| RULE-1 | No fake functionality. An unimplemented mode or control is visible only as disabled, with the reason | Confirmed |
| RULE-2 | No control is shown that the active camera cannot honor | Confirmed |
| RULE-3 | Every output carries its class: Native RAW, Merged RAW, Reconstructed RAW or Processed. Resolution labels always include the class | Confirmed |
| RULE-4 | Zoom labels are requests. The UI shows whether the result is optical, sensor crop or digital | Confirmed |
| RULE-5 | No claim of superiority over the stock camera without a controlled comparison | Confirmed |
| RULE-6 | Unverified targets are labeled "proposed". Benchmarks are never invented | Confirmed |

## 5. Target hardware

### 5.1 Galaxy S26 Ultra (SM-S948B)

Detail: [docs/CAMERA_HARDWARE.md](docs/CAMERA_HARDWARE.md), [docs/DEVICE_DISCOVERY_S26_ULTRA.md](docs/DEVICE_DISCOVERY_S26_ULTRA.md).

| Module | Camera2 IDs | Verified values (HAL) | Public RAW | Status |
|---|---|---|---|---|
| Main (HP2, reported) | physical 5 of logical 0 | 6.5 mm (23.0 mm eq.), f/1.4, 9.79 × 7.34 mm, OIS, min focus 0.10 m, ISO 9–3200 (analog ≤1200), 43 µs–255 ms | 4080 × 3060 (12.5MP) | Confirmed (HAL) |
| Ultra-wide | 2 (public), physical of 0 | 2.2 mm (13.3 mm eq., 0.58×), f/1.9, no OIS, min focus 0.05 m | 4080 × 3060 | Confirmed (HAL) |
| Tele 3x (S5K3LD, reported) | physical 6 of 0 | 7.0 mm (66.4 mm eq., 2.89×), f/2.4, OIS, min focus **0.40 m**, analog ISO ≤371 | 3648 × 2736 (10MP) | Confirmed (HAL) |
| Tele 5x (IMX854, reported) | physical 7 of 0 | 18.6 mm (112.7 mm eq., 4.90×), f/2.9, OIS, min focus **0.80 m**, analog ISO ≤203 | 4080 × 3060 (12.5MP binned) | Confirmed (HAL) |
| Front | 1 (12MP), 3 (7MP crop) | 3.3 mm (23.8 mm eq.), f/2.2, AF, min focus 0.20 m | 4000 × 3000 | Confirmed (HAL) |
| 200MP / 50MP readouts | Samsung-private vendor configurations only | — | **None** | Not supported (public API) |
| SoC / GPU / RAM | SM8850, 6 × 3.63 + 2 × 4.74 GHz / Adreno 840, Vulkan 1.4.295 / 11.35 GB | — | — | Confirmed (device) |
| OS | Android 17 (API 37), One UI 9.0, patch 2026-11-05, arm64-only | — | — | Confirmed (device) |
| App-visible camera ID list | Expected 0, 1, 2, 3 | — | — | To be verified (app) |

### 5.2 Hardware requirements for Luma

| ID | Requirement | Status |
|---|---|---|
| HW-1 | A back camera with the `RAW` capability (required for Luma processing) | Confirmed (HAL): all lenses |
| HW-2 | `MANUAL_SENSOR` on the main camera (required for Pro mode and bracketing) | Confirmed (HAL): all lenses |
| HW-3 | Logical multi-camera with `CONTROL_ZOOM_RATIO` (required for seamless zoom) | Confirmed (HAL): camera 0, 0.6–10× |
| HW-4 | Vulkan 1.3 compute (required for GPU stages) | Confirmed (device): Vulkan 1.4.295 |
| HW-5 | Devices without HW-1: Luma runs with HAL-processed output only, clearly labeled | Proposed |

## 6. Android compatibility

| ID | Item | Value | Status |
|---|---|---|---|
| AND-1 | minSdk / targetSdk / compileSdk | 34 / 37 / 37 | Confirmed (builds) |
| AND-2 | ABIs | arm64-v8a (device), x86_64 (emulator UI work only) | Confirmed |
| AND-3 | 16 KB page size compatibility | Native libraries 16 KB aligned (ELF LOAD align 0x4000, `zipalign -P 16` verified) | Confirmed |
| AND-4 | API 35–37 camera features | Runtime-gated by `SDK_INT` | Confirmed (rule) |
| AND-5 | Orientation | No manifest lock. Runtime portrait request on phones. Adaptive layout on large screens | Proposed |
| AND-6 | Toolchain | JDK 21, Gradle 9.9.1, AGP 9.5.1, Kotlin 2.4.21, NDK 28.2.13676358, CMake 3.31.6 | Confirmed |

## 7. Camera2 architecture

Detail: [docs/CAMERA2_CAPABILITIES.md](docs/CAMERA2_CAPABILITIES.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

| ID | Requirement | Status |
|---|---|---|
| CAM-1 | Camera2 is the only camera API. CameraX is not used | Confirmed (decision TD-02) |
| CAM-2 | The camera inventory enumerates public cameras **and** hidden physical cameras (`getPhysicalCameraIds`) with full characteristics | Confirmed (implemented in `core:camera`, not yet run on the device) |
| CAM-3 | `LensCatalog`: typed per-lens model (equivalent focal length, aperture, min focus, OIS, RAW sizes per pixel mode, manual-control support, extensions), built from measured characteristics. Never hardcoded IDs | Proposed |
| CAM-4 | `CameraSession` state machine with error recovery, reconfiguration for pixel-mode and lens changes, prompt release on `onStop` | Proposed |
| CAM-5 | Every frame's `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` and (API 35+) active physical crop region are tracked and shown in diagnostics | Proposed |
| CAM-6 | Max-resolution capture via `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` | Not supported on the S26 Ultra (no UHR capability, `pixelMode` not a request key: Confirmed (HAL)). Code path kept for devices that expose it |
| CAM-7 | Stream combinations involving physical streams are checked with `isSessionConfigurationSupported` (or `CameraDeviceSetup`, API 35) before use | Proposed |
| CAM-8 | Zero-shutter-lag through Luma's own repeating RAW ring buffer | RAW16 12.5MP at 30 fps advertised: Confirmed (HAL). Sustained with preview: To be verified |
| CAM-9 | Vendor camera extensions (Night, Bokeh, …) may be offered only as clearly labeled vendor processing | Proposed. Extensions implementation present (`ro.camerax.extensions.enabled`); modes To be verified |
| CAM-10 | AE/AF/AWB state is treated as discontinuous on physical camera switches | Confirmed (platform rule) |
| CAM-11 | Back photography runs on logical camera 0. Lens choice is tracked through `activePhysicalId` / `activePhysicalSensorCropRegion`. Physical streams only where measured to help (camera 0 has no physical request keys and one RAW stream) | Confirmed (decision TD-18) |
| CAM-12 | No dependence on undocumented vendor tags or vendor session keys | Confirmed (decision TD-19) |

## 8. Camera modes

| Mode | Scope | Status |
|---|---|---|
| **Photo** | Default mode: adaptive exposure, single-frame or multi-frame by scene, JPEG/HEIF (+ optional DNG) | Proposed (Milestones 5–10) |
| **Pro** | ISO, shutter, Kelvin WB, manual focus with peaking, EV, lens, RAW, resolution. Priority modes via `CONTROL_AE_PRIORITY_MODE` (API 36). Shutter limited to about 1/4 s maximum (176–274 ms depending on lens) | Proposed; manual sensor, AE priority and CCT support Confirmed (HAL) on every lens |
| **Night** | Motion-metered long burst merge, progress UI. Optional labeled vendor Night extension | Proposed (Milestone 13) |
| **Portrait** | Needs depth or segmentation: Bokeh extension, depth output (unlikely) or optional ML segmentation | Requires research (Milestone 14). Shown **disabled** until then |
| **Panorama** | Gyro-guided capture + stitching | Deferred. Shown **disabled** |
| Video, Pro Video, Portrait Video, Slow Motion, Hyperlapse | Listed in the mode selector (owner request 2026-11-18) and shown **disabled** with the reason. No video pipeline exists; NG-1 stays in force until the owner decides otherwise | Not supported (UI entry only) |
| Food | Close-up colour profile, measured against the stock camera before release | Proposed. Shown **disabled** |
| Dual Recording | Front + back together. Concurrent sets `[0, 1]` and `[0, 3]` exist: Confirmed (HAL). Needs the video pipeline | Not supported (UI entry only). Shown **disabled** |
| Multi Recording | Record from several back lenses at once. Only one back lens runs with full 3A for apps; inactive physical streams have no AF/AWB: Confirmed (app) | Not supported on the S26 Ultra. Shown **disabled** with the lens picker greyed out |

Mode selector layout (implemented 2026-11-18): wheel `NIGHT · PORTRAIT · PHOTO · VIDEO · PRO · MORE`, selected mode centred; swipe on the viewfinder or wheel to step modes. Portrait and Pro carry a Photo/Video segmented switch. `MORE` opens a sheet with Pro Video, Portrait Video, Food, Panorama, Slow Motion, Hyperlapse, Dual Recording, Multi Recording. A mode that cannot capture keeps the live preview, shows a status card over the viewfinder and a dimmed shutter (RULE-1).

## 9. Zoom behavior

Zoom factors are relative to the main lens's equivalent focal length (≈23 mm).

Native factors computed from verified HAL metadata. Presets within 5 % of a native factor snap to it (no upsampling crop).

| Preset | Source on the S26 Ultra | Kind | Status |
|---|---|---|---|
| 0.6× | Ultra-wide native (0.58×) | Optical | Confirmed (HAL) |
| 1× | Main | Optical | Confirmed (HAL) |
| 2× | Main, 2× crop of 12.5MP (≈3.1MP captured) | **Digital** (no public max-resolution readout) | Confirmed (HAL) |
| 3× | Tele 3x native (2.89×). Subjects closer than **0.40 m**: main-camera crop (≈1.4MP captured) | Optical / Digital at close range | Confirmed (HAL); fallback behavior of the logical camera To be verified |
| 5× | Tele 5x native (4.90×). Subjects closer than **0.80 m**: 3x crop | Optical / Digital at close range | Confirmed (HAL) |
| 10× | Tele 5x, 2.04× crop of 12.5MP (≈3.0MP captured). Also the logical camera's maximum zoom | **Digital** | Confirmed (HAL) |

| ID | Requirement | Status |
|---|---|---|
| ZOOM-1 | Continuous pinch-to-zoom across the full `CONTROL_ZOOM_RATIO_RANGE` (0.6–10 on the S26 Ultra) | Proposed |
| ZOOM-2 | The source badge shows optical / sensor crop / digital, derived from the actual source, never from the preset label | Proposed (classification implemented in `LensCatalog.resolve`) |
| ZOOM-3 | Lens selection by scored policy (effective resolution, noise at scene light, focus feasibility, motion) with hysteresis. No hardcoded thresholds | Partially implemented: `LensCatalog` (data-derived main lens, native factors, focus-feasibility gate, kind classification, unit-tested with S26 Ultra values). Scoring and hysteresis Proposed |
| ZOOM-4 | Preview and capture use one crop definition. Capture crop is checked against request (macro framing bug class) | Proposed |
| ZOOM-5 | Close-focus: Luma suggests the ultra-wide when focus is unreachable. Automatic switching is a setting, off until framing accuracy is proven | Proposed |
| ZOOM-6 | `CONTROL_SETTINGS_OVERRIDE_ZOOM` used for responsive zoom where listed | Not supported on the S26 Ultra (override OFF only: Confirmed (HAL)) |

## 10. RAW and DNG

Detail: [docs/RAW_AND_DNG.md](docs/RAW_AND_DNG.md).

| ID | Requirement | Status |
|---|---|---|
| RAW-1 | Native RAW via `RAW_SENSOR` + `DngCreator` with `STATISTICS_LENS_SHADING_MAP_MODE_ON` (so the residual GainMap opcode is embedded) | Confirmed (platform behavior; shading map mode ON available: HAL) |
| RAW-2 | Native RAW sizes: main / ultra-wide / 5x 4080 × 3060 (12.5MP), 3x 3648 × 2736 (10MP), front 4000 × 3000. 10-bit data | Confirmed (HAL) |
| RAW-3 | 200MP native RAW | Not supported on the S26 Ultra (Samsung-private only: Confirmed (HAL)) |
| RAW-4 | 50MP RAW (main, 5x, ultra-wide) | Not supported on the S26 Ultra (Samsung-private only: Confirmed (HAL)) |
| RAW-5 | 24MP / 48MP RAW are never native on this sensor. Produced only as Reconstructed RAW via multi-frame super-resolution | Experimental |
| RAW-6 | Merged RAW (multi-frame, native resolution) written as CFA DNG by the engine's DNG writer | Proposed |
| RAW-7 | Reconstructed RAW written as LinearRaw DNG with provenance XMP | Experimental |
| RAW-8 | If a requested resolution cannot be produced honestly, Luma saves the native resolution and explains why | Confirmed (rule) |
| RAW-9 | DNG conformance: opens correctly in Lightroom/ACR, darktable and RawTherapee. Passes `dng_validate` | Proposed (acceptance criterion) |
| RAW-10 | RAW from each physical lens | Advertised on every lens: Confirmed (HAL). Selecting a specific lens's RAW (lens switch or physical stream) in an app: To be verified |
| RAW-11 | Engine input handling: per-lens CFA phase (GBRG / GRBG / RGGB), residual-only shading (RAW is pre-shaded), dynamic defect correction (no hot-pixel map), dynamic black/white levels | Confirmed (HAL facts, decision TD-20) |

## 11. Image-processing architecture

Detail: [docs/IMAGE_PIPELINE.md](docs/IMAGE_PIPELINE.md).

| ID | Requirement | Status |
|---|---|---|
| IMG-1 | The engine is a standalone C++20 library with no Android dependencies, buildable and testable on a desktop | Confirmed (structure exists); stages Proposed |
| IMG-2 | Scene-referred linear processing until a single final rendering step | Proposed |
| IMG-3 | Stages: normalize → defects → shading → align → merge → WB → demosaic → color → highlight recovery → residual denoise → exposure/local tone mapping → rendering curve/look → gamut map → output sharpening → encode | Proposed |
| IMG-4 | One noise model (from `SENSOR_NOISE_PROFILE`) propagated through all stages and driving merge and denoise | Proposed |
| IMG-5 | CPU reference implementation for every stage. GPU only per TD-07 criteria | Confirmed (decision) |
| IMG-6 | Guards: ghost, halo, saturation, sharpening, each with a metric and a threshold | Proposed |
| IMG-7 | Coarse JNI API (job submission + callback). No per-pixel JNI | Proposed |
| IMG-8 | Captured frames needed for processing are persisted before heavy processing, so photos survive process death | Proposed |

## 12. Computational photography

Detail: [docs/COMPUTATIONAL_PHOTOGRAPHY.md](docs/COMPUTATIONAL_PHOTOGRAPHY.md).

| ID | Requirement | Status |
|---|---|---|
| CP-1 | Default: a good single frame. Multi-frame only when scene DR, light or noise justify it | Proposed |
| CP-2 | HDR+-style same-exposure burst merge for dynamic range and noise | Proposed |
| CP-3 | Bracketing (added long frames) only when shadows are noise-limited and motion allows | Proposed |
| CP-4 | Motion-aware scheduling (gyro + frame difference). With moving subjects, motion consistency takes priority over noise | Proposed |
| CP-5 | Local tone mapping that is halo-free by construction (Local Laplacian) with strength limits | Proposed |
| CP-6 | Multi-frame super-resolution | Experimental |
| CP-7 | Face-aware exposure and tone protection via platform face detection | Proposed |
| CP-8 | Learned components (AWB, segmentation, denoise) optional and never mandatory | Deferred |

## 13. Exposure system

Detail: [docs/EXPOSURE_SYSTEM.md](docs/EXPOSURE_SYSTEM.md).

| ID | Requirement | Status |
|---|---|---|
| EXP-1 | Scene analyzer: histogram, region-weighted clipping, scene brightness, faces, subject region, motion, focal length/OIS, capability limits | Proposed |
| EXP-2 | Preview: HAL AE with a smoothed Luma bias (no pumping) | Proposed |
| EXP-3 | Capture: highlight-protecting exposure and subject exposure computed per scene. Scene DR picks single / burst / bracket. No fixed negative EV | Proposed |
| EXP-4 | Deterministic decisions, logged per capture (inputs, class, plan) | Proposed |
| EXP-5 | Repeatability: subject exposure spread ≤ 0.1 EV over 10 tripod captures | Proposed (target) |
| EXP-6 | Linearization of the preview analysis stream (HAL tonemap control or calibrated inverse) | `TONEMAP_MODE_CONTRAST_CURVE` advertised: Confirmed (HAL). Honored on a preview + analysis session: To be verified |
| EXP-7 | Metering uses one AE region and one AF region (the device advertises AE 1, AWB 0, AF 1). No AWB regions | Confirmed (HAL) |
| EXP-8 | Telephoto exposure planning keeps ISO at or below the analog limit (3x: 371, 5x: 203) and merges more frames instead of using digital gain | Proposed (limits Confirmed (HAL)) |

## 14. Color science

Detail: [docs/COLOR_SCIENCE.md](docs/COLOR_SCIENCE.md).

| ID | Requirement | Status |
|---|---|---|
| COL-1 | Per-physical-lens color matrices (ForwardMatrix interpolated by CCT). Samsung matrices first, Luma calibration replaces them when measurably better | Proposed |
| COL-2 | Working space: linear Rec.2020 (D65), scene-referred | Proposed |
| COL-3 | Parametric rendering curve with soft highlight shoulder. Versioned. Version recorded in output metadata | Proposed |
| COL-4 | Hue-preserving highlight desaturation and gamut mapping (Oklch) | Proposed |
| COL-5 | Skin protection band. No beautification | Proposed |
| COL-6 | Default output Display P3 with ICC. sRGB option | Proposed |
| COL-7 | White balance v1 starts from the HAL estimate (a stock strength). Luma estimator v2 replaces it only when measurably better | Proposed |
| COL-8 | Targets: ColorChecker mean ΔE2000 ≤ 3.0. Cross-module neutral ≤ 1.5 ΔE2000 | Proposed (targets) |
| COL-9 | Kelvin WB in Pro via `COLOR_CORRECTION_MODE_CCT` (API 36), range 2300–10000 K | Confirmed (HAL); app-level To be verified |
| COL-10 | Samsung calibration: dual-illuminant (D65 + Standard A) ColorMatrix/ForwardMatrix on every lens | Confirmed (HAL) |

## 15. UI and UX

The first usable build (end of Milestone 6) has a complete, coherent interface. No placeholder screens.

| ID | Requirement | Status |
|---|---|---|
| UI-1 | Main camera: full-screen preview, camera switch, zoom presets + pinch + source badge, shutter, gallery thumbnail, mode selector, flash, timer, aspect ratio, resolution, quick settings, EV, tap focus/meter | Proposed |
| UI-2 | Pro controls: ISO, shutter, WB, manual focus, EV, lens, RAW toggle, resolution, each shown only if supported | Proposed |
| UI-3 | Gallery and viewer: Luma captures, full-screen, metadata and capture info, output-class badge, share (location removal default on), delete, navigation | Proposed |
| UI-4 | Settings: image quality, default format, RAW, resolution, processing, save location, storage info, camera behavior, performance, diagnostics | Proposed |
| UI-5 | Diagnostics: camera IDs, physical IDs, capabilities, resolutions, RAW support, formats, manual controls, active camera, active zoom source, capture configuration, processing info, thermal, crash records | Partially implemented (camera inventory: Confirmed in code, not yet run on the device); rest Proposed |
| UI-6 | Unavailable features are visible and explained, never faked | Confirmed (rule) |
| UI-7 | Compose + Material 3, single activity, unidirectional state | Confirmed (foundation) |
| UI-8 | Visual identity: dark, restrained, warm amber accent. Samsung-familiar layout conventions (mode strip above shutter, zoom pills above modes) without copying assets. iOS-inspired finish: glass capsules, equal-slot zoom pill with optically centred labels, centred mode wheel, ruler dials for Pro values, focus square with exposure sun | Implemented 2026-11-18 (on-device screenshots reviewed) |

## 16. Gallery and storage

| ID | Requirement | Status |
|---|---|---|
| STO-1 | Save through MediaStore to `DCIM/Luma/` with `IS_PENDING`. No storage permission | Proposed |
| STO-2 | MIME types: `image/jpeg`, `image/heic`, `image/x-adobe-dng` | Proposed |
| STO-3 | Temporary and job data only in app-internal `noBackupFilesDir`, deleted on completion, retention limit 7 days | Proposed |
| STO-4 | Gallery reads Luma's own MediaStore items. After reinstall, photo picker or partial access, never broad media access by default | Proposed |
| STO-5 | Storage information in settings (space used by Luma, free space) | Proposed |

## 17. Diagnostics

| ID | Requirement | Status |
|---|---|---|
| DIA-1 | Camera inventory with full characteristic dump, save and share | Confirmed (implemented, not yet run on the device) |
| DIA-2 | Read-only adb hardware discovery (`tools/hardware-discovery/collect.sh`, `parse_camera_dump.py`): no install, push, pull or settings changes. Sanitizes the camera dump | Confirmed (procedure run manually on 2026-11-18; script reproduces it) |
| DIA-3a | Phase A diagnostic app (read-only app-level view) and Phase B capture tests, each only after owner authorization | Proposed ([plan](docs/HARDWARE_VALIDATION_PLAN.md)) |
| DIA-3 | Live: active camera/physical ID, crop, source kind, AE/AF/AWB state, exposure/ISO, frame rate | Proposed |
| DIA-4 | Per-capture plan and timing log. Engine build and stage timings | Proposed |
| DIA-5 | Thermal status, memory, crash/exit records (`ApplicationExitInfo`) | Proposed |

## 18. Performance

Detail: [docs/PERFORMANCE.md](docs/PERFORMANCE.md). All targets **Proposed** until measured.

| ID | Target |
|---|---|
| PERF-1 | Cold start → first preview: p50 ≤ 700 ms |
| PERF-2 | Preview steady 30 fps. < 1 % dropped frames during processing |
| PERF-3 | Shutter lag ≤ 50 ms (ring buffer) / ≤ 150 ms (without) |
| PERF-4 | 8-frame 12.5MP merge → JPEG p50 ≤ 1.5 s |
| PERF-5 | Peak native memory ≤ 1.2 GB |
| PERF-6 | 10 min sustained shooting without failures. Processing slowdown ≤ 50 % |
| PERF-7 | No image data copies through the Java heap |

## 19. Privacy and security

Detail: [docs/SECURITY_AND_PRIVACY.md](docs/SECURITY_AND_PRIVACY.md).

| ID | Requirement | Status |
|---|---|---|
| SEC-1 | No `INTERNET` permission. No analytics, ads or crash SDKs | Confirmed (release APK permissions checked in CI; MediaPipe's network permissions removed with `tools:node="remove"`) |
| SEC-2 | Location tagging off by default. Location permission requested only when the user enables it. Approximate stays approximate | Proposed |
| SEC-3 | No device identifiers in any output or report | Proposed |
| SEC-4 | App data excluded from cloud backup and device transfer | Confirmed (`data_extraction_rules.xml`) |
| SEC-5 | Diagnostics leave the device only through explicit user share | Confirmed (foundation behavior) |
| SEC-6 | Native code: validated metadata, sanitizer-tested engine, fuzzed parsers (if any) | Proposed |

## 20. Benchmarking

Detail: [docs/BENCHMARKING.md](docs/BENCHMARKING.md).

| ID | Requirement | Status |
|---|---|---|
| BEN-1 | Controlled A/B against stock: same device, alternating order, equal framing, equal output resolution, registered for pixel metrics | Confirmed (method) |
| BEN-2 | Scene matrix covering daylight, high contrast, indoor, low light, night, portraits, skin, landscape, architecture, text, fine detail, motion, 3×/5×/10×, macro | Confirmed (method) |
| BEN-3 | Objective metrics + blind subjective review. Losses reported like wins | Confirmed (method) |
| BEN-4 | Engine regression corpus run on every engine change (desktop) | Proposed |
| BEN-5 | Firmware versions of both apps recorded in every report | Confirmed (rule) |

## 21. Dependencies

Detail: [docs/DEPENDENCIES_AND_LICENSES.md](docs/DEPENDENCIES_AND_LICENSES.md).

| Item | Status |
|---|---|
| AndroidX Activity/Core/Lifecycle, Compose BOM 2026.09.00 (Material 3 1.4.0), kotlinx-coroutines 1.11.0, all Apache-2.0 | Confirmed (resolved and built) |
| CameraX, OpenCV (in app), LibRaw, Adobe DNG SDK (linked), DI frameworks, Rust | Not supported (rejected with reasons) |
| `viewfinder-compose` 1.6.2 | Proposed (evaluate in Milestone 3) |
| Halide, libjpeg-turbo, VMA | Deferred |
| MediaPipe Tasks Vision 1.2.3 + selfie multiclass model (bundled) | Confirmed (Portrait segmentation, Apache-2.0) |
| Project LICENSE | MIT (owner decision 2026-11-19) |

## 22. Architecture

Detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), decisions in [docs/TECHNICAL_DECISIONS.md](docs/TECHNICAL_DECISIONS.md).

| ID | Item | Status |
|---|---|---|
| ARC-1 | Modules: `app`, `core:camera`, `core:imaging`. Standalone `engine/` (C++). `core:storage` planned | Confirmed (exists) / Proposed (storage) |
| ARC-2 | `core:camera` and `core:imaging` independent of each other. App-level coordinator wires them | Confirmed (structure) |
| ARC-3 | Manual DI, unidirectional UI state, interfaces only at real seams | Confirmed (decision) |
| ARC-4 | Threading: dedicated camera executor, analysis executor, engine-owned native pool, I/O dispatcher, UI on main | Proposed |

## 23. Development milestones

Detail: [docs/DEVELOPMENT_ROADMAP.md](docs/DEVELOPMENT_ROADMAP.md).

| # | Milestone | Status |
|---|---|---|
| M0 | Foundation: research, spec, buildable project, inventory tool | **Done** |
| M1 | Hardware capability verification on the S26 Ultra | **In progress**: read-only HAL discovery done 2026-11-18. App-level Phase A awaits owner authorization |
| M2 | Capture lab + baselines + RAW corpus | Proposed |
| M3 | Camera core + live preview | Proposed |
| M4 | Lens selection + zoom | Proposed |
| M5 | Still capture + storage | Proposed |
| M6 | RAW/DNG + first usable build (0.2) | Proposed |
| M7 | Engine v1 (CPU reference), in parallel with M3–M6 | Proposed |
| M8 | Color & rendering v1 | Proposed |
| M9 | Exposure intelligence | Proposed |
| M10 | Computational photography + Vulkan | Proposed |
| M11 | Pro mode | Proposed |
| M12 | Gallery, settings, diagnostics complete | Proposed |
| M13 | Night mode | Proposed |
| M14 | Portrait / Panorama research | Deferred |
| M15 | Performance & thermal | Proposed |
| M16 | Benchmark & refine (continuous) | Proposed |

## 24. Acceptance criteria

### Foundation (M0): verified 2026-11-18
- [x] `./gradlew assembleDebug assembleRelease lint` succeeds. Lint: no issues in `app`, `core:camera`, `core:imaging`.
- [x] Native library built for arm64-v8a and x86_64, 16 KB aligned. Only the JNI entry point exported.
- [x] Release APK permissions: `CAMERA` only (plus an AndroidX-internal broadcast permission). No `INTERNET`. (Video later added `RECORD_AUDIO`.)
- [x] Package `com.datarohit.luma`, targetSdk 37, minSdk 34.
- [x] Read-only hardware discovery on the S26 Ultra (2026-11-18). Nothing installed or changed on the phone.
- [x] `LensCatalog` unit tests pass with verified S26 Ultra values (`./gradlew :core:camera:testDebugUnitTest`).
- [x] App run on a device (owner-authorized, 2026-11-18 onwards).
- [x] Published to GitHub with CI (2026-11-19).

### First usable build (M6)
- Complete camera UI per UI-1 with honest disabled states. Photo capture (HAL-processed, labeled) and native DNG working on every lens the device exposes. Gallery viewer with metadata and output-class badges. Settings and Diagnostics present. 1000-capture reliability run without failure.

### Quality milestones (M8–M10)
- Each claim in OBJ-1…OBJ-5 is evaluated with the [benchmark method](docs/BENCHMARKING.md) and reported against stock, whatever the outcome.

## 25. Known limitations

| ID | Limitation | Status |
|---|---|---|
| LIM-1 | No access to Samsung's private processing, sensor-internal HDR readouts (DSG, Smart-ISO Pro) or the 50MP binning mode | Not supported (platform limit) |
| LIM-2 | No public 200MP or 50MP readout on the S26 Ultra (firmware S948BXXS6BZK1). 2× and 10× are digital. 24MP only by reconstruction | Confirmed (HAL) |
| LIM-7 | Logical-camera zoom ends at 10× | Confirmed (HAL) |
| LIM-8 | Longest single exposure about 0.18–0.27 s depending on lens. Longer night exposures need multi-frame | Confirmed (HAL) |
| LIM-9 | Telephoto analog gain is low (3x ≤ ISO 371, 5x ≤ ISO 203). Higher ISO is digital | Confirmed (HAL) |
| LIM-10 | 3x cannot focus closer than about 0.40 m, 5x closer than about 0.80 m (APPROXIMATE calibration) | Confirmed (HAL) |
| LIM-3 | The 3x module's resolution and optics cannot be improved by software beyond multi-frame noise reduction. Possible firmware issues cannot be fixed by Luma | Confirmed |
| LIM-4 | Lens availability for third-party apps may change with firmware updates | Confirmed (historical reports); re-verify per firmware |
| LIM-5 | Fast-moving low-light scenes and portrait segmentation are expected stock strengths | Proposed (to be measured) |
| LIM-6 | Parallax at macro distances: a lens switch can match field of view but not perspective | Confirmed (physics) |

## 26. Open technical questions

Answered on 2026-11-18 at HAL level: camera IDs and topology, RAW sizes, absence of `ULTRA_HIGH_RESOLUTION_SENSOR`, manual-control support, minimum focus distances (see §5.1).

1. Does the app-visible `getCameraIdList()` equal 0, 1, 2, 3? Do app-visible characteristics match the HAL metadata? (Phase A)
2. Can repeating `RAW_SENSOR` sustain 30 fps with preview (decides ZSL)? (Phase B)
3. Can physical RAW streams of camera 0 (for example 3x at 1× logical zoom) be configured? (Phase A query / Phase B)
4. What does the logical camera do at 3× for subjects under 0.40 m, and does the stock 3× close-up blur reproduce on S948BXXS6BZK1? (Phase B)
5. Which camera extensions (including Android 17 vendor-defined ones) are exposed to third-party apps? (Phase A)
6. Is the tonemap contrast curve honored on a preview + analysis session? (Phase B)
7. How do logical-camera frames behave with OIS, given that camera 0 advertises OIS OFF only? (Phase B)
8. Are Samsung's per-module color matrices and noise profiles accurate enough, or does Luma need its own characterization?
9. Can RAW16 `AHardwareBuffer`s be imported into Vulkan without a copy? (`VK_ANDROID_external_memory_android_hardware_buffer` is present)
10. Which demosaic algorithm performs best on HP2 binned RAW (measured)?
11. Project license (owner decision).
