# Architecture

## Goals

1. The image engine can be developed, tested and benchmarked **without Android**.
2. The camera layer exposes what the hardware can really do, as typed data. The UI cannot show a control the active camera does not support.
3. Few modules, few abstractions. A boundary exists only where it pays for itself: build isolation, a test seam, or a language boundary.

## Repository layout

```
luma/
├── app/                    Android application: Compose UI, navigation, wiring
├── core/
│   ├── camera/             Camera2 integration (Kotlin, Android library)
│   └── imaging/            Kotlin API + JNI bridge to the engine (Android library, CMake)
├── engine/                 Image engine (C++20, CMake, no Android dependencies)
├── tools/
│   └── hardware-discovery/ read-only adb capability collection + dump parser
├── docs/                   Research, design and decision records
├── gradle/                 Version catalog, wrapper
└── .github/                CI, templates
```

### Present vs planned

| Path | State | Contents |
|---|---|---|
| `app` | **Exists** | Foundation screen: identity, engine build info, camera inventory tool |
| `core/camera` | **Exists** | `inventory/`: read-only enumeration of logical and hidden physical cameras, typed summary, full characteristic dump. `lens/`: `LensCatalog` (main-lens detection, native zoom factors, zoom-source resolution with focus feasibility), unit-tested with verified S26 Ultra values |
| `core/imaging` | **Exists** | `ImagingEngine` (Kotlin) + `jni_bridge.cpp`. Builds `engine/` through CMake `add_subdirectory` |
| `engine` | **Exists** | Static library `luma_engine` with build information only |
| `core/storage` | Planned (Milestone 5) | MediaStore writes, EXIF/XMP, DNG output orchestration, job persistence |
| `engine/src/{raw,align,merge,demosaic,color,tone,io}` | Planned (Milestones 6–10) | Pipeline stages |
| `engine/gpu` | Planned (Milestone 10) | Vulkan compute backend. GLSL → SPIR-V via NDK `glslc` |
| `engine/tests`, `engine/tools` | Planned (Milestone 7) | Desktop tests (sanitizers) and a CLI that processes stored DNG bursts |
| `benchmarks/` | Planned (Milestone 12) | Desktop metric scripts and committed reports |
| `macrobenchmark/` | Planned (Milestone 15) | Startup and capture-flow benchmarks |

The proposed `native/{image-engine,raw,processing,gpu}` split was replaced by a single `engine/` CMake project with internal directories. The stages share types (image buffers, metadata, noise model) and are built and tested together, so separate top-level native projects would add CMake plumbing without adding isolation.

## Module dependencies

```
app ──▶ core:camera
 │
 └───▶ core:imaging ──(CMake)──▶ engine
```

- `core:camera` does not depend on `core:imaging`. The capture pipeline passes frames to the engine through the app-level coordinator (Milestone 5+), so the camera layer can be tested without native code, and the engine without a camera.
- `core:storage` (planned) depends on neither. It writes byte streams and metadata it is given.

## Layers inside the app

```
Compose screens (stateless UI)                                 app
  ▲ immutable UI state        │ user intents
  │                           ▼
Screen state holders (ViewModel, StateFlow)                    app
  │
  ├─▶ CameraController ───────────── core:camera
  │     LensCatalog (typed capabilities)
  │     CameraSelector (zoom → lens + crop policy)
  │     CameraSession (Camera2 device/session/request lifecycle)
  │     SceneAnalyzer / ExposureController / CapturePlanner
  │
  ├─▶ ProcessingCoordinator ─────── app (wires camera → engine → storage)
  │     ImagingEngine (JNI) ──────── core:imaging → engine
  │     MediaWriter / DngWriter ──── core:storage
  │
  └─▶ Settings (DataStore, planned)
```

- **Unidirectional data flow.** Screens render a single immutable state and send intents. There is no business logic in composables.
- **Manual dependency injection.** An `AppGraph` created in `Application` constructs the controllers. No DI framework.
- **Interfaces only at real seams.** `CameraSession` is a concrete Camera2 class. Tests target `CameraSelector` and `ExposureController`, which are pure Kotlin functions over data and need no fakes.

## Threading

| Work | Thread |
|---|---|
| Camera2 callbacks, requests | One dedicated camera executor (single thread) |
| Preview analysis (YUV) | Analysis executor. Drops frames under load (keeps only the latest) |
| Engine processing | Native thread pool owned by the engine. Sized from CPU topology. ADPF hint session |
| GPU submission | Engine-owned queue thread |
| Storage I/O | `Dispatchers.IO` |
| UI | Main thread only. Never touches image buffers |

## Camera abstraction

### LensCatalog

Built once per process from the camera inventory (`core:camera/inventory`), then refined by capture tests:

```
Lens(
  cameraId, physicalId?, logicalParentId?,
  facing, equivalentFocalLengthMm, nativeZoomFactor,      // relative to the main 1× lens
  aperture, minimumFocusDistanceM?, ois,
  rawSizes: Map<PixelMode, Size>, processedSizes,
  manual: ManualSupport(iso, shutter, focus, wb),
  extensions, isUltraHighResolution, hasRemosaicReprocessing,
)
```

The **main lens** (1×) is the back camera whose equivalent focal length is closest to 24 mm and which has the largest sensor. It is identified from data, never hardcoded as camera "0".

### Zoom model

The user asks for a **field of view** (zoom factor relative to the main lens). The selector resolves it into a **source**:

```
Source(lens, pixelMode, cropRect, kind)   kind ∈ {OPTICAL, SENSOR_CROP, DIGITAL}
```

- `OPTICAL`: a lens whose native factor matches the request (±5 %).
- `SENSOR_CROP`: a crop from a max-resolution readout that still gives at least the output resolution in real pixels.
- `DIGITAL`: a crop that requires upsampling.

The zoom control shows the kind. Preset buttons (0.6×, 1×, 2×, 3×, 5×, 10×) are requests, not promises. For example, 2× is shown as `SENSOR_CROP` only if a max-resolution main readout exists, otherwise as `DIGITAL`.

### Camera selection

Selection is a scoring problem, evaluated when zoom, light, focus distance or motion change significantly:

```
score(source) = w_res · effectiveResolution(source, outputSize)
              + w_noise · −noisePerOutputPixel(source, sceneLux)
              + w_focus · focusFeasible(source, subjectDistance)        // hard gate
              + w_motion · −motionRisk(source, exposureNeeded, ois)
              − w_switch · switchCost(current → source)                 // hysteresis
```

- Weights and thresholds come from measurements in Milestone 4, not from guesses. The model starts with focus feasibility as a hard gate and effective resolution as the main term.
- Hysteresis: a candidate must beat the current source by a margin, for a minimum dwell time, before switching. This prevents visible flapping near a threshold.
- When Luma uses the **logical camera**, the HAL chooses the lens. Luma detects the choice through `ACTIVE_PHYSICAL_ID` and the active crop region, and reports it. When Luma needs a specific lens (telephoto close-focus, macro), it uses **physical streams** if the device supports them for that lens. Otherwise the limitation is shown in the UI.

## Engine interface

The JNI boundary is narrow and coarse-grained:

- `ImagingEngine.submit(job)`: frame handles (direct `ByteBuffer`s + strides), per-frame metadata (a flat struct encoded once in Kotlin), the capture plan and output targets. Returns a job ID.
- Progress and completion arrive through a single callback interface.
- No per-pixel or per-tile JNI calls. No Java objects held by native code beyond the job's lifetime.
- Engine C++ API: `luma::engine::Pipeline` takes a `Burst` (frames + `FrameMetadata` + `NoiseModel`) and a `RenderSettings`, and produces `LinearImage` / `DisplayImage` / DNG bytes. It is identical on the desktop and on Android.

## UI structure (target for the first usable build)

| Screen | Contents |
|---|---|
| Camera | Full-screen preview, mode selector (Photo, Portrait, Night, Pro, Panorama: unavailable modes are visible but disabled, with the reason), zoom presets + pinch, lens/source badge, shutter, gallery thumbnail, flash, timer, aspect ratio, resolution, quick settings, exposure compensation, tap-to-focus/meter |
| Pro controls | ISO, shutter, white balance (Kelvin), manual focus, EV, lens, RAW toggle, resolution. Each control appears only if the active lens supports it |
| Gallery / viewer | Luma captures from MediaStore, full-screen viewer, metadata and capture info, output class badge (Native RAW / Merged RAW / Reconstructed / Processed), share, delete |
| Settings | Image quality, default format, RAW, resolution, processing, save location, storage, camera behavior, performance, diagnostics |
| Diagnostics | Camera inventory (exists now), active camera/source, capture configuration, last capture plans, engine build and timings, thermal state, crash records |

Camera UI orientation: the manifest declares no orientation lock. On phones (smallest width < 600 dp), the camera screen requests portrait at runtime and rotates its icons and labels with device orientation, as camera apps conventionally do. On large screens, Android 16+ ignores orientation requests, so the camera layout must adapt to landscape and resizable windows. The preview transform is always computed from window metrics and sensor orientation.
