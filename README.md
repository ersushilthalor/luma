<div align="center">

# Luma

**Capture Naturally. Render Beautifully.**

An Android computational-photography camera with its own RAW pipeline: burst merging, multi-frame super-resolution, GPU colour science and 4K60 video. Built with Kotlin, Jetpack Compose and a C++20 imaging engine.

[![Android CI](https://github.com/DataRohit/luma/actions/workflows/android.yml/badge.svg)](https://github.com/DataRohit/luma/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Android-14%2B%20(API%2034)-3DDC84?logo=android&logoColor=white)
![Target SDK](https://img.shields.io/badge/targetSdk-37-3DDC84?logo=android&logoColor=white)

![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![Coroutines](https://img.shields.io/badge/Kotlin%20Coroutines-1.11-7F52FF?logo=kotlin&logoColor=white)
![C++20](https://img.shields.io/badge/C%2B%2B-20-00599C?logo=cplusplus&logoColor=white)
![Android NDK](https://img.shields.io/badge/NDK-r28%20%2B%20JNI-3DDC84?logo=android&logoColor=white)
![CMake](https://img.shields.io/badge/CMake-3.31-064F8C?logo=cmake&logoColor=white)
![OpenGL ES](https://img.shields.io/badge/OpenGL%20ES-3.0%20%2B%20EGL-5586A4?logo=opengl&logoColor=white)
![Camera2](https://img.shields.io/badge/Camera2-RAW%20%C2%B7%20HLG10%20%C2%B7%204K60-FF6F00)
![MediaPipe](https://img.shields.io/badge/MediaPipe-Tasks%20Vision-0097A7?logo=google&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-9.9%20(Kotlin%20DSL)-02303A?logo=gradle&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/CI-GitHub%20Actions-2088FF?logo=githubactions&logoColor=white)

</div>

---

Luma replaces the phone's "processed look" with a pipeline it controls end to end. Photos start as RAW bursts merged by a native engine. Video is rendered per frame by a GPU colour engine. Every output says honestly what produced it. It was developed and verified on a **Samsung Galaxy S26 Ultra (SM-S948B)**. It uses only public Android APIs and adapts to the cameras each device exposes.

## Highlights

**Photo (RAW pipeline, `engine/`)**
- RAW burst capture (1 to 16 frames) planned from the scene: light level, highlight clipping and motion.
- Tile alignment and robust merge in the Bayer domain, plus defect correction.
- Edge-directed demosaic, DNG colour calibration, local tone mapping, highlight handling in Oklch, noise reduction and sharpening.
- **Night**: long handheld bursts (up to 15 frames) with highlight protection.
- **Multi-frame super-resolution** for 24 and 50MP:
  - Frames are aligned globally to about 0.02 px.
  - Raw samples from 10 to 14 hand-held frames are gathered onto a grid up to 2× denser per side. This adds real detail; it is not interpolation.
  - A sample-coverage check notices when the camera barely moved. The burst is then merged and enlarged instead, and the photo says so.
- **200MP** is the 50MP super-resolution image enlarged with Lanczos-3, and is labelled as such.
- Optional merged **DNG**, HEIF or JPEG output, Display P3 or sRGB.

**Video (GPU engine, `app/.../gl`)**
- 4K60 on the main and ultra-wide cameras. The frame rate is measured on the device, not taken from the stream map.
- The camera stream is neutralised (edge enhancement off, minimal NR, scene-log tone curve) and then rendered by Luma:
  - filmic tone curve
  - OKLab highlight roll-off and gamut mapping
  - 33³ looks (Natural, Cinema, Vivid)
  - local tone mapping
  - motion-adaptive temporal denoising
  - grain
- HLG and HDR10 rendered into 10-bit BT.2020 encoder surfaces, plus a Luma Log profile.
- Slow motion, Hyperlapse, Dual Recording, Pro Video and Portrait Video. Audio and video start in sync.

**Lenses and zoom**
- Seamless lens switching with per-frame colour matching between lenses.
- An eased zoom glide, and a curved disk zoomer from 0.6× to 100× with an aiming inset at long zoom.
- Every route is labelled in the viewfinder: Luma RAW, HAL-processed or digital.

**Portrait**
- On-device person segmentation (MediaPipe multiclass selfie model, bundled, works offline).
- Bokeh in linear light with specular bloom, in five styles: Natural, Bubble, Starry, Cinematic and Swirl.

**Pro and UI**
- Manual ISO, shutter, white balance and focus, with scopes and LUTs.
- A 120 Hz Compose UI with glass controls and a landscape layout.
- Food and Panorama modes.

**Honest by design**
- Every photo's description records the frames merged, the source lens, the real resolution, and whether it was enlarged.
- Nothing is faked. Unsupported features are shown as unavailable, with the reason.

## Architecture

```
app/                 Compose UI, view models, capture planner/processor, GPU engine (gl/), video, portrait
core/camera/         Camera2 sessions, device pool, lens catalog and router, ISP tuning
core/imaging/        Kotlin API + JNI bridge to the engine (RawBurst, Camera2Raw, resampler)
core/storage/        MediaStore writer, private sinks, media repository
engine/              C++20 imaging engine, no Android dependencies, with desktop tests
tools/               Read-only hardware discovery scripts
docs/                Research, design, decisions, measurements
```

```
Camera2 RAW burst ──► engine (align · merge · super-res · demosaic · colour · tone · finish) ──► JPEG / HEIF / DNG
Camera2 YUV/PRIV  ──► OpenGL ES (scene-log decode · filmic · OKLab · LUT · TNR · LTM) ──► MediaCodec HEVC (8/10-bit)
```

## Tech stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.4 (app), C++20 (engine) |
| UI | Jetpack Compose (BOM 2026.09), Material 3 |
| Camera | Camera2: logical and physical cameras, RAW_SENSOR, HLG10, high-speed sessions |
| Imaging engine | C++20, NDK r28, CMake 3.31, multithreaded, JNI |
| GPU | OpenGL ES 3.0, EGL (10-bit BT.2020 surfaces), RGBA16F render targets |
| Video | MediaCodec / MediaMuxer (HEVC, HEVC Main10), AudioRecord |
| ML | MediaPipe Tasks Vision (selfie multiclass segmentation, bundled model) |
| Async | Kotlin Coroutines and Flow |
| Build | Gradle 9.9 (Kotlin DSL, version catalog), AGP 9.5 |
| Quality | Android Lint, JUnit, desktop engine tests against ground truth, GitHub Actions |

## Building

Requirements: JDK 17+ (21 used), Android SDK Platform 37.0, Build-Tools 36.0.0, NDK 28.2.13676358 and CMake 3.31.6.

```bash
# Point Gradle at the SDK via local.properties (sdk.dir=...) or ANDROID_HOME
./gradlew assembleDebug                  # debug APK (includes on-device diagnostics)
./gradlew assembleRelease lint           # minified release build + Android Lint
./gradlew testDebugUnitTest              # JVM unit tests
./gradlew installDebug                   # install on a connected device
```

Engine tests run on the desktop. They use synthetic RAW bursts with known ground truth: alignment, merge, super-resolution, ghosting, tone and resampling.

```bash
cmake -S engine -B build/engine-desktop -DCMAKE_BUILD_TYPE=Release -DLUMA_ENGINE_TESTS=ON
cmake --build build/engine-desktop --parallel
./build/engine-desktop/luma_engine_tests
```

## Device notes (Galaxy S26 Ultra, measured)

- Apps get 12.5MP RAW from every lens. No rear camera exposes `ULTRA_HIGH_RESOLUTION_SENSOR`, and the 50/200MP readouts are Samsung-private. This is why Luma uses multi-frame super-resolution.
- RAW from the logical camera pins the main lens. Telephoto RAW has no AF/AWB, so the telephoto lenses use HAL-processed stills, labelled as such.
- 4K60 is sustained on the main and ultra-wide cameras, although the stream map advertises 30 fps.
- Android caps an app with an active camera to 60 Hz. Luma pauses the stream under full-screen sheets so they render at 120 Hz.

Details are in [docs/DEVICE_DISCOVERY_S26_ULTRA.md](docs/DEVICE_DISCOVERY_S26_ULTRA.md) and [docs/IMAGING_PIPELINE_V2.md](docs/IMAGING_PIPELINE_V2.md).

## Documentation

| Document | Contents |
|---|---|
| [spec.md](spec.md) | Product and technical specification with verified status per capability |
| [docs/IMAGING_PIPELINE_V2.md](docs/IMAGING_PIPELINE_V2.md) | ISP neutralisation, GPU colour engine, super-resolution: design and measurements |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) / [docs/TECHNICAL_DECISIONS.md](docs/TECHNICAL_DECISIONS.md) | Structure and decision records |
| [docs/IMAGE_PIPELINE.md](docs/IMAGE_PIPELINE.md) / [docs/COLOR_SCIENCE.md](docs/COLOR_SCIENCE.md) | Engine stages, colour pipeline |
| [docs/COMPUTATIONAL_PHOTOGRAPHY.md](docs/COMPUTATIONAL_PHOTOGRAPHY.md) / [docs/EXPOSURE_SYSTEM.md](docs/EXPOSURE_SYSTEM.md) | Burst, HDR, super-resolution, exposure |
| [docs/CAMERA_HARDWARE.md](docs/CAMERA_HARDWARE.md) / [docs/CAMERA2_CAPABILITIES.md](docs/CAMERA2_CAPABILITIES.md) | S26 Ultra modules, Camera2 rules |
| [docs/DEVICE_DISCOVERY_S26_ULTRA.md](docs/DEVICE_DISCOVERY_S26_ULTRA.md) / [docs/HARDWARE_VALIDATION_PLAN.md](docs/HARDWARE_VALIDATION_PLAN.md) | Device discovery and validation |
| [docs/RAW_AND_DNG.md](docs/RAW_AND_DNG.md) | RAW resolutions, output classes, DNG |
| [docs/TELEPHOTO_INVESTIGATION.md](docs/TELEPHOTO_INVESTIGATION.md) / [docs/MACRO_INVESTIGATION.md](docs/MACRO_INVESTIGATION.md) | Lens investigations |
| [docs/PERFORMANCE.md](docs/PERFORMANCE.md) / [docs/BENCHMARKING.md](docs/BENCHMARKING.md) | Performance targets, comparison method |
| [docs/SECURITY_AND_PRIVACY.md](docs/SECURITY_AND_PRIVACY.md) / [docs/DEPENDENCIES_AND_LICENSES.md](docs/DEPENDENCIES_AND_LICENSES.md) | Permissions, privacy, dependencies |
| [docs/RESEARCH.md](docs/RESEARCH.md) / [docs/DEVELOPMENT_ROADMAP.md](docs/DEVELOPMENT_ROADMAP.md) | Sources, roadmap |
| [CHANGELOG.md](CHANGELOG.md) | Changes |

## Privacy

- Luma has **no `INTERNET` permission**. CI fails if a release APK requests it.
- It has no analytics, ads or crash SDKs.
- Segmentation runs on-device with a bundled model. MediaPipe's network permissions are removed from the merged manifest.
- Permissions: `CAMERA`, plus `RECORD_AUDIO` for video.
- Photos are processed and stored on the device.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) and, for security reports, [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE) © 2026 Rohit Vilas Ingole. Third-party components keep their own licenses (Apache-2.0); see [docs/DEPENDENCIES_AND_LICENSES.md](docs/DEPENDENCIES_AND_LICENSES.md).
