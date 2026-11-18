# Technical decision records

Each record states the context, the options, the decision, the reasoning, the trade-offs and the unresolved questions. Status is **Accepted** (in effect), **Proposed** (direction set, to be confirmed by evidence) or **Superseded**. Decisions dated 2026-11-18 unless noted.

---

## TD-01: Kotlin for the Android application

- **Context:** The application layer needs Camera2, Compose and long-term maintainability for one developer.
- **Options:** Kotlin. Java. Flutter or React Native. Kotlin Multiplatform.
- **Decision:** Kotlin (2.4.21, through AGP 9 built-in Kotlin). **Accepted.**
- **Reasoning:** Compose is Kotlin-only. Coroutines and Flow fit camera callbacks and state. Camera2 is a platform API, so cross-platform frameworks would need native camera plugins for everything that matters, while adding a runtime. KMP brings no benefit to an Android-only product.
- **Trade-offs:** None significant. Java interop is available if ever needed.
- **Unresolved:** None.

## TD-02: Camera2 as the camera API

- **Context:** Luma needs per-frame exposure control in bursts, RAW ring buffers, `SENSOR_PIXEL_MODE`, physical camera streams, manual controls and capture-result inspection (`ACTIVE_PHYSICAL_ID`).
- **Options:** CameraX 1.6.2. Camera2. A hybrid (CameraX for preview, Camera2 interop for the rest). NDK Camera (`ACameraManager`).
- **Decision:** Camera2 (Java/Kotlin API) for everything. CameraX is not a dependency. **Accepted.**
- **Reasoning:** CameraX's value is device-compatibility handling and lifecycle convenience. Luma targets one known device class and needs exactly the controls CameraX abstracts away. A hybrid splits ownership of a single `CameraDevice`, which makes session reconfiguration (pixel-mode switches, physical streams) fragile. The NDK camera API lags the Java API in features (for example extensions), and gains nothing, because image buffers already reach native code through direct `ByteBuffer`s.
- **Trade-offs:** More code: session state machine, orientation, preview transforms, device quirks. Mitigated by a small, well-tested `CameraSession`, and possibly by `androidx.camera.viewfinder` for preview surfaces (Camera2-agnostic).
- **Unresolved:** Whether `viewfinder-compose` reduces code enough to adopt it (Milestone 3).

## TD-03: Compose UI architecture

- **Context:** A polished, Samsung-familiar camera UI with fast state updates (zoom, exposure, focus) over a live preview.
- **Options:** Views/XML. Compose with ViewModel + StateFlow (unidirectional). Compose with an MVI framework.
- **Decision:** Compose + Material 3. Single activity. Screen state holders (ViewModel) exposing one immutable UI state per screen. Intents as plain functions. No MVI framework. `navigation-compose` added when a second screen exists. **Accepted.**
- **Reasoning:** Compose is the platform direction and suits overlay-heavy camera UIs. A framework adds ceremony without benefit at this scale.
- **Trade-offs:** High-frequency camera state (histogram, focus peaking) must avoid recomposition storms: keep it in dedicated state objects read in drawing-phase lambdas.
- **Unresolved:** Preview surface integration (`SurfaceView` through `AndroidExternalSurface` vs viewfinder-compose). Decided in Milestone 3 by measured latency and correctness.

## TD-04: Native C++ integration

- **Context:** Image processing needs predictable performance, SIMD, GPU compute and desktop testability.
- **Options:** Kotlin only. C++ via JNI. Rust via JNI. Halide-generated C++.
- **Decision:** C++20 engine, built with CMake through AGP `externalNativeBuild`, behind a coarse-grained JNI bridge. Static libc++ while there is one native library. **Accepted.**
- **Reasoning:** Kotlin/ART cannot match native SIMD and memory control for multi-hundred-MB bursts. C++ has the most mature Vulkan, NEON and profiling tooling on Android and on the desktop. Rust adds a third language and toolchain without a compelling benefit here. The engine's correctness risk is numeric, not memory-safety-dominated, and sanitizers cover the memory side.
- **Trade-offs:** Memory-safety discipline required: validated sizes, sanitizer CI, a narrow API.
- **Unresolved:** Switch to `c++_shared` if a second native library appears.

## TD-05: RAW processing strategy

- **Context:** Native RAW from Camera2. Computed RAW (merged, reconstructed). Processed outputs.
- **Options:** LibRaw-based pipeline. Adobe DNG SDK. Own pipeline on Camera2 buffers, with DngCreator for native DNG and an own writer for computed DNG.
- **Decision:** Own pipeline on `RAW_SENSOR` buffers. Platform `DngCreator` for native RAW. Engine-side DNG writer (CFA and LinearRaw subsets) for computed RAW. **Accepted.**
- **Reasoning:** No RAW file decoding is needed in the capture path (see F17 in [RESEARCH.md](RESEARCH.md)). DngCreator writes complete color, noise, shading and distortion metadata (verified in AOSP source). A DNG *writer* for a fixed subset is small.
- **Trade-offs:** Luma owns DNG conformance for computed files. Mitigated by offline `dng_validate` and multi-converter checks.
- **Unresolved:** Lossless compression for LinearRaw (DNG 1.7 JPEG XL vs deflate). Decided by size and compatibility measurements.

## TD-06: Image engine boundaries

- **Context:** The engine must be benchmarkable independently of the UI and camera.
- **Options:** Engine inside the Android module. Standalone C++ library consumed by the Android module.
- **Decision:** `engine/` is a standalone CMake project with no Android dependencies. `core:imaging` adds it with `add_subdirectory` and owns only the JNI bridge. **Accepted.**
- **Reasoning:** Desktop builds give fast iteration, sanitizers, a deterministic regression corpus and profiling without deploying to the device. It also prevents Android types from leaking into algorithms.
- **Trade-offs:** Platform-specific pieces (AHardwareBuffer import, ATrace, ADPF) live behind small interfaces, with desktop stubs.
- **Unresolved:** None.

## TD-07: CPU vs GPU processing

- **Context:** Burst alignment and merge on 8–15 frames of 12.5MP must finish in about 1–2 s.
- **Options:** All-CPU. All-GPU from day one. CPU reference first, GPU for stages that profiling selects.
- **Decision:** CPU reference first. A stage moves to the GPU only when it misses its latency budget, the GPU version is ≥ 3× faster end-to-end, and it matches the CPU reference within tolerance. **Accepted.**
- **Reasoning:** Correctness before speed. The CPU path is the oracle for GPU tests and runs on the desktop. Avoids GPU work for stages that do not need it.
- **Trade-offs:** Two implementations of hot stages. Accepted cost for testability.
- **Unresolved:** Real latency numbers (Milestone 7).

## TD-08: Vulkan compute as the GPU API

- **Context:** Choosing a GPU compute API on Android.
- **Options:** Vulkan compute. OpenGL ES 3.1 compute. OpenCL (not part of the public Android platform; vendor libraries only). RenderScript (deprecated since API 31). Halide (generates Vulkan, OpenCL or CPU code). Android NNAPI (deprecated in favor of LiteRT, intended for ML).
- **Decision:** Vulkan compute (1.3 core baseline), GLSL → SPIR-V with NDK `glslc`, direct API use without wrappers. **Proposed**: activated by TD-07's criteria in Milestone 10.
- **Reasoning:** Vulkan is the only modern, supported, cross-vendor compute API in the Android platform, and Vulkan 1.4 is required on the target's launch OS. It gives explicit memory control (important for large bursts) and possible AHardwareBuffer interop.
- **Trade-offs:** Verbose setup code. Hand-written kernels. Halide could cut kernel-authoring effort and remains the fallback if kernel count grows (TD-10).
- **Unresolved:** Whether RAW16 `AHardwareBuffer`s can be imported zero-copy.

## TD-09: Image format strategy

- **Context:** What Luma saves, by default and optionally.
- **Options:** JPEG sRGB. JPEG Display P3. HEIF 10-bit. Ultra HDR JPEG (gain map). DNG variants.
- **Decision:**
  - Default: JPEG, Display P3, embedded ICC.
  - Options: sRGB JPEG; HEIF; Ultra HDR (after SDR rendering is validated).
  - RAW: native DNG (DngCreator), merged CFA DNG, reconstructed LinearRaw DNG. Always labeled by output class.
  - **Accepted** (defaults), **Proposed** (Ultra HDR and HEIF timing).
- **Reasoning:** JPEG is universally compatible. P3 matches the device display and modern viewers. Ultra HDR and HEIF add value but each adds a validation burden.
- **Trade-offs:** P3 JPEGs viewed in non-color-managed software look slightly desaturated. The sRGB option exists for that case.
- **Unresolved:** None.

## TD-10: Dependency choices

- **Context:** Minimal, well-licensed dependencies.
- **Decision:** AndroidX/Compose/Coroutines only. No CameraX, OpenCV, LibRaw, DI framework, Halide or Rust (details and reasons in [DEPENDENCIES_AND_LICENSES.md](DEPENDENCIES_AND_LICENSES.md)). **Accepted.**
- **Reasoning:** Each rejected library either hides needed control, solves a problem Luma does not have, or adds licensing and size cost.
- **Trade-offs:** In-house implementations of demosaic, alignment, merge, tone mapping and the DNG writer. These are the product's core competence anyway.
- **Unresolved:** Halide reconsidered at Milestone 10 if Vulkan kernel effort dominates.

## TD-11: Module structure

- **Context:** Separation without over-modularization.
- **Options:** Single module. Proposed layout (`core/{camera,image,processing,color,common,storage}`, `native/{image-engine,raw,processing,gpu}`). A reduced set.
- **Decision:** `app`, `core:camera`, `core:imaging`, `engine/` now. `core:storage` when MediaStore writing starts. Processing and color live inside the engine, not as Kotlin modules. No `core:common` until there is shared code. **Accepted.**
- **Reasoning:** Each module has a concrete purpose: a camera test seam, a native build boundary, a platform-free engine. Color and processing are C++ concerns. Kotlin wrappers for them would be empty shells.
- **Trade-offs:** Shared build configuration is repeated in three small build files. A `build-logic` convention plugin is introduced when module count exceeds about five.
- **Unresolved:** Whether UI features become separate modules (only if build times demand it).

## TD-12: Camera abstraction

- **Context:** Representing heterogeneous lenses and preventing unsupported controls in the UI.
- **Decision:** Typed `LensCatalog` built from measured capabilities. Zoom expressed as field of view and resolved to `Source(lens, pixelMode, crop, kind)`. Selection by scored policy with hysteresis. Logical camera by default, physical streams where the logical camera's choice is wrong and the device allows it (see [ARCHITECTURE.md](ARCHITECTURE.md#camera-abstraction)). **Accepted** (2026-11-18). The `LensCatalog` and zoom-source resolution are implemented in `core:camera/lens` as pure Kotlin and tested with the verified S26 Ultra values. The scoring policy is still to come.
- **Reasoning:** Hardcoded camera IDs and zoom thresholds break across firmware and devices. Data-driven selection can be measured.
- **Trade-offs:** More up-front modeling than thresholds.
- **Unresolved:** Physical stream availability on the S26 Ultra.

## TD-13: Color management

- **Context:** Accurate, natural, sensor-aware color.
- **Decision:** DNG-style matrix pipeline (per-module ForwardMatrix interpolated by CCT). Scene-referred linear Rec.2020 working space. Parametric rendering curve. Look adjustments in Oklab. Hue-preserving gamut mapping to Display P3. No LUT-only rendering. **Accepted** (approach), **Proposed** (parameters).
- **Reasoning:** Matrix pipelines are transparent and per-sensor calibratable. Oklab gives perceptual hue stability for saturation and gamut operations. A parametric curve is tunable and documented.
- **Trade-offs:** Matrix-only characterization limits accuracy for highly saturated colors. A LUT refinement may be added later, *on top of* the calibrated base, if measurements justify it.
- **Unresolved:** Quality of Samsung's per-module matrices (to measure).

## TD-14: SDK levels

- **Context:** Target device on Android 17 (API 37). Development and test devices may be older.
- **Decision:** `compileSdk` 37, `targetSdk` 37, `minSdk` 34. **Accepted.**
- **Reasoning:** API 34 brings `SETTINGS_OVERRIDE_ZOOM`, `JPEG_R`, readout timestamps, color-space profiles, Bitmap gain maps and partial media access. That covers most of what Luma uses without version checks. API 35–37 features are runtime-gated. Older devices are not targets.
- **Trade-offs:** Excludes Android 13 and older devices.
- **Unresolved:** None.

## TD-15: Toolchain versions

- **Context:** Stable, mutually compatible toolchain as of 2026-11-18.
- **Decision:** JDK 21 (Temurin 21.0.13), Gradle 9.9.1 (checksum pinned), AGP 9.5.1, Kotlin 2.4.21, NDK 28.2.13676358 (AGP default), CMake 3.31.6, Build-Tools 36.0.0 (AGP default). **Accepted.**
- **Reasoning:** AGP 9.5.1 is the latest stable AGP (needs Gradle ≥ 9.6.0 and JDK 17+). Using AGP's default NDK and Build-Tools avoids untested combinations. JDK 21 is LTS and matches current Android Studio runtimes. CMake 3.31 avoids CMake 4's removal of old compatibility policies in third-party code that may be added later.
- **Trade-offs:** NDK r29/r30 are available but not AGP's default. Upgrading is a deliberate step.
- **Unresolved:** None.

## TD-16: ABIs

- **Decision:** `arm64-v8a` (device) and `x86_64` (emulator, for UI work only). **Accepted.**
- **Reasoning:** The target is arm64. Verified 2026-11-18: the S26 Ultra lists `arm64-v8a` only, with no 32-bit ABIs. The emulator is useful for UI iteration but not for camera or processing validation.
- **Trade-offs:** About 2× native build time. `x86_64` may be dropped from release builds later.

## TD-17: Public resolution ceiling on the S26 Ultra (2026-11-18)

- **Context:** Read-only discovery showed that no camera exposes `ULTRA_HIGH_RESOLUTION_SENSOR` or a `sensor.pixelMode` request key. Native public RAW is 12.5MP (10MP on the 3x). The 200MP and 50MP readouts exist only as Samsung-private configurations.
- **Options:** (a) Rely on Samsung vendor tags to try to reach private readouts. (b) Accept the public ceiling and offer higher resolution only through multi-frame reconstruction. (c) Upscale.
- **Decision:** (b). **Accepted.**
  - Native output is 12.5MP (10MP on the 3x).
  - 24MP comes only from multi-frame super-resolution (labeled "Reconstructed").
  - 48MP stays Experimental.
  - 2× and 10× are labeled **digital** unless a future firmware exposes max-resolution readouts.
- **Reasoning:** (a) is undocumented, unsupported, and likely blocked by the platform's stream validation. It could also break with any update. (c) violates the product rules.
- **Trade-offs:** Luma cannot match the stock app's detail at 2× and 10× in bright light. This is stated openly in the UI and in benchmark reports.
- **Unresolved:** Re-checked by the discovery script after every firmware update.

## TD-18: Logical camera 0 as the back camera; physical streams as an exception

- **Context:** Camera 0 groups all four back lenses (zoom 0.6–10), advertises `activePhysicalId` and the active crop region, but lists **no** physical-camera request keys and allows one RAW stream.
- **Decision:** All back-camera photography runs on logical camera 0, with zoom through `CONTROL_ZOOM_RATIO`. Luma tracks the HAL's lens choice through results. Physical streams are used only where measurements show they solve a specific problem (for example forcing the 3x module or keeping a macro framing), and only if session queries show they are supported. Settings for physical streams are inherited from the logical request. **Accepted.**
- **Reasoning:** The logical camera gives seamless switching and the full LEVEL_3 feature set. Without physical request keys, per-lens control through physical streams is limited.
- **Unresolved:** Whether physical RAW streams can be configured (validation plan, Phase A/B).

## TD-19: No dependence on undocumented vendor metadata

- **Context:** The HAL exposes about 90 `samsung.android.*` vendor tags on camera 0, plus many Qualcomm session keys (for example `EnableInsensorZoom`, `EnableHDRDCGMode`).
- **Decision:** Luma does not set or depend on undocumented vendor tags or vendor session keys. They may be *displayed* in diagnostics for investigation. **Accepted.**
- **Reasoning:** No documentation, no stability guarantees, possible package-based restrictions, and risk of undefined HAL behavior.
- **Trade-offs:** Forgoes potential access to sensor HDR (DCG) or in-sensor zoom modes.

## TD-20: Engine input assumptions from verified RAW properties

- **Decision:** The engine treats the CFA phase as per-lens data (GBRG / GRBG / RGGB), never as a constant. It expects 10-bit RAW in RAW16 containers, uses per-frame dynamic black and white levels when present, applies only the *residual* shading map (shading is pre-applied in RAW), and always runs dynamic defect correction (no hot-pixel map exists). **Accepted.**
- **Reasoning:** Verified at HAL level on all lenses (see [DEVICE_DISCOVERY_S26_ULTRA.md](DEVICE_DISCOVERY_S26_ULTRA.md#4-raw-and-dng-findings)).

## TD-21: Lens routing from measured app-level behavior (2026-11-18)

- **Context:** App-level tests ([DEVICE_DISCOVERY_S26_ULTRA.md §7](DEVICE_DISCOVERY_S26_ULTRA.md#7-app-level-findings-luma-debug-build-2026-11-18)) showed:
  - any RAW stream on logical camera 0 pins the active lens to the main camera;
  - physical RAW streams of inactive telephoto lenses have no autofocus and no AWB;
  - the hidden telephoto IDs cannot be opened;
  - camera 2 (ultra-wide) opens directly with full 3A.
- **Options:**
  - (a) Use physical telephoto RAW streams anyway.
  - (b) Upscale main-camera crops at telephoto zoom.
  - (c) Route each zoom range to the best source an app can actually get, and label the HAL-processed one.
- **Decision:** (c). **Accepted.**

  | Zoom | Route | Output |
  |---|---|---|
  | Below 1× | Camera 2, RAW | Luma RAW pipeline |
  | 1× to 95 % of the first telephoto factor (≈ 2.75×) | Camera 0 with RAW (main sensor) | Luma RAW pipeline, digital zoom above 1× |
  | Above that | Camera 0 without RAW, plus a JPEG stream | "Processed by the camera HAL" |

  The decision is implemented in `core:camera/lens/LensRouter`, with unit tests. The router is driven by characteristics, not camera IDs. A route change reconfigures the session after the zoom gesture settles (350 ms).
- **Reasoning:**
  - (a) produces out-of-focus, green images.
  - (b) breaks the "no upscaling" product rule (NG-6).
  - The HAL's telephoto output uses real optics, which beats any crop of the main sensor.
- **Trade-offs:**
  - At 3× and above there is no DNG and no Luma rendering. The UI says so: the RAW toggle is disabled with an explanation, and the source chip shows the HAL.
  - At close range the HAL may serve a crop of a wider lens. The source chip reports the actual lens from `activePhysicalId`.
- **Unresolved:** Re-test after each firmware update. If physical telephoto streams gain AF and AWB, revisit (a).

## TD-22: Settings stored in SharedPreferences

- **Context:** The app has about 16 user settings (look, output space, DNG, toggles, strength sliders, timer).
- **Options:** DataStore (Preferences), SharedPreferences, or a custom file.
- **Decision:** SharedPreferences behind `SettingsRepository`, exposed as a `StateFlow<AppSettings>`. Writes use `apply()`. **Accepted.**
- **Reasoning:**
  - No new dependency: the platform already provides it.
  - Synchronous first read, so the first frame of the camera UI already has the user's settings.
  - The data is a small, flat key-value set.
- **Trade-offs:** No typed schema migration. Keys are versioned by name if their meaning changes.

## TD-23: Read-noise floor when the HAL reports none

- **Context:** On the main camera, `SENSOR_NOISE_PROFILE` reports a zero read-noise (offset) term. With a zero offset, the variance in deep shadows collapses, and the merge robustness and denoise thresholds become too strict there.
- **Decision:** `Camera2Raw.metadata` raises the offset to at least (1.5 DN / (white − black))². **Accepted.**
- **Reasoning:**
  - Real sensors always have a read-noise floor.
  - 1.5 DN is a conservative value for a 10-bit binned readout.
  - Reported values above the floor are used unchanged.
- **Unresolved:** Replace the floor with values measured from dark frames during noise characterization (M8).
