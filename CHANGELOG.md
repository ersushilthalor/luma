# Changelog

All notable changes are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Published on GitHub under the MIT License; README rewritten (features, architecture, tech stack, build); CI also builds and runs the desktop engine tests.
- Multi-frame super-resolution for 24 / 50MP (`docs/IMAGING_PIPELINE_V2.md` §5): the hand-held RAW burst (10 / 14 frames) is aligned globally to ~0.02 px and its raw samples are gathered onto a denser grid (up to 2x per side) in the engine; a sample-coverage gate declines it for a camera that barely moved (merge + enlargement, said in the photo description). 200MP = 50MP super-resolution enlarged, labelled as such. Engine tests against ground truth; render stats report scale, sub-pixel spread and coverage. JNI options take a 12th value (super-resolution).
- Debug `UhrProbe` (`--ez uhr true`): ULTRA_HIGH_RESOLUTION_SENSOR and maximum-resolution stream maps for every rear logical and physical camera, with a dedicated SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION still where a larger size exists. On SM-S948B: no camera has the capability; no size above 4080x3060. Capture test `--ez superRes true` renders all tiers with centre crops.
- Video audit fixes: HLG and HDR10 are rendered by Luma's GPU engine into 10-bit BT.2020 HLG / PQ surfaces (EGL colour-space tagged) instead of camera-to-encoder with the stock look; slow motion and HDR10+ stay on the camera path. Temporal denoiser (motion-adaptive, RGBA16F history, strength from live ISO). Local tone mapping (blurred log-luminance map, asymmetric shadow lift), black point, halo-limited detail lift scaled per source pixel. Measured against the stock rendering of the same room: tone and detail matched (detail 5.24 vs 5.36), more shadow detail (p10 42 vs 34).
- Thermal guard: optional GPU stages step down at MODERATE / SEVERE thermal status.
- Disk zoomer (curved logarithmic dial, opens on drag or pinch, haptic lens stops); zoom to 100x (GPU crop of the 5x telephoto beyond the camera's 10x) with an aiming inset and preview stabilisation at long zoom; no empty band above the mode wheel in Photo/Night.
- Portrait: on-device person segmentation (MediaPipe multiclass selfie model, bundled; INTERNET and network-state permissions merged from the library are removed), mask-driven bokeh in linear light with subject-excluded gathering, specular bloom and depth falloff; styles Natural, Bubble, Starry, Cinematic, Swirl; applied to preview, Portrait Video (1080p) and saved photos.
- Research probes (debug): Qualcomm vendor session keys, sensor mode table (200MP@11, 50MP@30 exist), JPEG/RAW tile and in-sensor-zoom studies. Result: no app-reachable path to native 50/200MP output on this firmware.

- Imaging pipeline v2 (`docs/IMAGING_PIPELINE_V2.md`). `IspTuning`: neutral HAL streams (EDGE off, minimal NR, hot-pixel correction, Luma scene-log tone curve) for preview, video, AF and RAW-burst requests; HAL stills without sharpening. Measured on SM-S948B: stock EDGE FAST doubles fine-detail energy and frame-to-frame noise.
- GPU colour engine: scene-log decode, highlight headroom (AE underexposure restored on the GPU), hue-preserving filmic tone curve (true black toe, smooth shoulder), OKLab highlight desaturation and gamut mapping, 33³ RGB16F looks (Natural, Cinema, Vivid), shadow grain; Luma Log output from the same stream. Pro Tools: engine on/off for A/B, look, headroom, grain. 4K60 with the engine: 0 frames lost.
- Debug ISP probe (`StreamProbe.isp`): available HAL modes and their measured effect.

- 4K60 video: frame rates come from the camera's fixed AE ranges plus a one-time on-device measurement (`RateStore`, `VideoEngine.verifyRate`). On SM-S948B the stream map advertises 4K30, but both the logical back camera and the ultra-wide sustain 60.0 fps; the clip records 4K60 HEVC with no frames lost in Luma's pipeline. The 640x360 analysis stream is dropped above the 4K30 pixel rate (it cost frames at 4K60).
- Lens continuity: per-frame colour measurement (asynchronous PBO readback) and a fading gain that matches each new lens to the previous one on logical-camera switches and lens restarts (measured on device: ultra-wide about 10-15 % greener than main, corrected and faded over ~1.5 s).
- Eased preset zoom (log-space glide), freeze-and-cross-fade on session changes, shared `CameraDevicePool` (photo/video switches reuse the open device), session work on a background thread.
- Photo resolution tiers 12 / 24 / 50 / 200MP (`ResolutionCatalog`): native where the public or maximum-resolution stream maps expose a size; otherwise (since superseded by multi-frame super-resolution for RAW lenses) rendered from the merged RAW and enlarged with the engine's new Lanczos-3 resampler (`engine/src/resample.cpp`, unit-tested). Tiers are labelled per lens in Settings, in the viewfinder and in each photo's description. On SM-S948B the private 50MP/200MP sizes are accepted by the HAL but answered with 1920x1440 JPEGs, so they are never treated as native.
- 120 Hz: the activity requests the panel's fastest mode and opts out of power-saving frame-rate votes; sheets pause the camera stream while they cover the viewfinder, because Android caps apps with a streaming camera to 60 Hz (`CameraServiceProxy`, `MAX_PREVIEW_FPS = 60`).
- Recording starts audio and video together (video waits for the microphone, then starts on a requested keyframe).
- Debug diagnostics: stream probe (measured 4K rates, private high-resolution sizes), media verifier.

- Product and technical specification (`spec.md`) and research, design and decision documents (`docs/`).
- Android project foundation: Gradle 9.9.1 wrapper (checksum pinned), AGP 9.5.1, Kotlin 2.4.21, Compose (BOM 2026.09.00), compileSdk/targetSdk 37, minSdk 34.
- `core:camera`: read-only camera inventory covering public and hidden physical cameras, with a text report.
- `core:imaging` + `engine/`: C++20 engine library and JNI bridge (build information only).
- Foundation screen: engine status and camera inventory scan, save and share.
- Read-only hardware discovery of the Galaxy S26 Ultra (SM-S948B, S948BXXS6BZK1) and its results (`docs/DEVICE_DISCOVERY_S26_ULTRA.md`). Phased, authorization-gated validation plan (`docs/HARDWARE_VALIDATION_PLAN.md`).
- `tools/hardware-discovery/parse_camera_dump.py`: summarizes `dumpsys media.camera` static metadata per camera and physical camera.
- `core:camera/lens`: `LensCatalog` with data-derived main lens, native zoom factors and zoom-source resolution (optical / sensor crop / digital, focus feasibility), with unit tests based on the verified S26 Ultra values.
- Decision records TD-17 (public resolution ceiling), TD-18 (logical camera 0), TD-19 (no vendor tags), TD-20 (engine input assumptions).
- CI workflow: build, lint, release permission check, 16 KB alignment check.
- Camera UI refinement: equal-slot zoom pill (active circle grows in place, labels optically centred), centred mode wheel with viewfinder swipe, Photo/Video switch for Portrait and Pro, "More Modes" sheet, two-state Pro panel with ruler dials, iOS-style focus square with exposure drag, equal-column top bar and bottom row.
- Mode catalog (`camera/CameraModes.kt`) covering Portrait, Photo, Pro Photo, Video, Pro Video, Portrait Video, Night, Food, Panorama, Slow Motion, Hyperlapse, Dual Recording and Multi Recording. Only Photo, Night and Pro Photo capture; the others are shown disabled with their reason.

### Changed
- Pro Photo: lens presets float over the bottom of the 4:3 viewfinder, so the dial row and mode wheel are never clipped. Pro Video actions are one row of compact capsules.
- The viewfinder surface no longer blocks the GL thread on vsync (swap interval 0; encoder drawn first). GL time per 4K60 frame fell from 13.1 ms to 3.4 ms.
- HEIF files use MIME `image/heic` (no more `.heic.heif` names). Capability lookups (encoder support, AE ranges) are cached.

- `tools/hardware-discovery/collect.sh` is now strictly read-only. The `adb pull` of app reports was removed, getprop is limited to an allowlist, and the camera dump is sanitized locally.
- `spec.md` statuses updated with HAL-verified device facts. 200MP/50MP RAW, max-resolution capture and settings-override zoom are marked not supported on the S26 Ultra. 2× and 10× are classified as digital zoom.
