# Dependencies and licenses

Policy: a dependency is added only when it solves a real problem better than a small in-house implementation. It must be maintained, compatible with the target toolchain, and its license must be compatible with *any* license the project owner might choose. Versions checked 2026-11-18 against Google Maven, Maven Central, GitHub and the Gradle services API.

## Toolchain (installed and verified)

| Component | Version | License | Notes |
|---|---|---|---|
| JDK (Eclipse Temurin) | 21.0.13+9 | GPLv2 + Classpath Exception | Build-time only. SHA-256 checked against the Adoptium API |
| Gradle (wrapper) | 9.9.1 | Apache-2.0 | `distributionSha256Sum` pinned in `gradle-wrapper.properties` |
| Android Gradle Plugin | 9.5.1 | Apache-2.0 | Built-in Kotlin. Requires Gradle ≥ 9.6.0 and JDK 17+ |
| Kotlin (Gradle plugin, Compose compiler plugin) | 2.4.21 | Apache-2.0 | |
| Android SDK Platform | 37.0 (compile/target), 36 | Android SDK License | |
| Build-Tools | 36.0.0 | Android SDK License | AGP 9.5 default |
| NDK | 28.2.13676358 | Apache-2.0 (LLVM: Apache-2.0 with LLVM exception) | AGP 9.5 default NDK. 16 KB page-aligned output by default |
| CMake (SDK package) | 3.31.6 | BSD-3-Clause | |

## Runtime dependencies (adopted)

| Dependency | Version | License | Why |
|---|---|---|---|
| Compose BOM | 2026.09.00 (ui 1.12.1, material3 1.4.0) | Apache-2.0 | UI |
| androidx.activity:activity-compose | 1.13.0 | Apache-2.0 | Compose host activity, permissions |
| androidx.core:core-ktx | 1.19.1 | Apache-2.0 | FileProvider, compat helpers |
| androidx.lifecycle:lifecycle-runtime-compose | 2.11.0 | Apache-2.0 | Lifecycle-aware effects |
| kotlinx-coroutines-android | 1.11.0 | Apache-2.0 | Background work |
| androidx.heifwriter:heifwriter | 1.0.0 | Apache-2.0 | HEIF stills |
| com.google.mediapipe:tasks-vision | 1.2.3 | Apache-2.0 | Portrait person segmentation (on-device). Its `INTERNET` / `ACCESS_NETWORK_STATE` permissions are removed in the app manifest |
| MediaPipe selfie multiclass segmentation model (256x256, bundled asset) | — | Apache-2.0 | Hair, skin, clothes and background classes for Portrait masks |
| libc++ (NDK, static) | NDK r28 | Apache-2.0 with LLVM exception | The exception waives attribution for embedded compiled code. Switch to `c++_shared` once more than one native library exists |

All adopted runtime dependencies are Apache-2.0. They impose notice obligations (include license and NOTICE files in distributions) and are compatible with permissive, copyleft and proprietary project licenses.

## Evaluated and not adopted

| Candidate | Version / status | License | Decision | Reason |
|---|---|---|---|---|
| **CameraX** | 1.6.2 | Apache-2.0 | Not adopted | Hides the controls Luma depends on (pixel mode, per-frame burst control, physical streams, RAW ring buffer). See TD-02 |
| **androidx.camera.viewfinder:viewfinder-compose** | 1.6.2 stable | Apache-2.0 | **Candidate, Milestone 3** | Works with Camera2 and handles surface lifecycle and transforms in Compose. Adopt if it removes real code |
| **OpenCV** | 5.1.0 (2026-11-06). Official Android AAR on Maven Central (`org.opencv:opencv`) | Apache-2.0 (since 4.5.0) | **Not in the app. Desktop benchmarking tools only** | Large native binaries per ABI. Luma's core algorithms (Bayer alignment, frequency merge, demosaic, tone mapping) are domain-specific and are not OpenCV's strengths. Generic utilities (feature matching for benchmark registration) are needed only offline |
| **LibRaw** | 0.22.3 (2026-10-26), actively maintained | LGPL-2.1 **or** CDDL-1.0 | **Not adopted** | Decodes RAW files. Camera2 already provides `RAW_SENSOR` buffers and metadata, and Luma writes DNG itself. LGPL static linking on Android brings relinking obligations, and CDDL is file-level copyleft. Offline DNG cross-checking uses existing tools (darktable, RawTherapee, Adobe DNG validator) |
| Adobe DNG SDK | — | Adobe DNG SDK license (permissive) | Not linked | Large C++ code base, hard to integrate. `dng_validate` used offline to validate Luma's DNGs |
| libultrahdr | v2.0.2 | Apache-2.0 | Not adopted | Platform `Bitmap.setGainmap` + compress (API 34) and Camera2 `JPEG_R` cover Ultra HDR |
| Halide | v22.1.0 | MIT | Deferred | Excellent for image pipelines, but brings an LLVM-based generator toolchain into the build. Revisit if hand-written Vulkan compute becomes the bottleneck |
| libjpeg-turbo | 3.2.1 | BSD-style (IJG + BSD-3 + zlib) | Deferred | Platform encoder first. Adopt only if measurements show quality or speed problems |
| Vulkan wrappers / VMA | — | MIT | Deferred | Direct Vulkan is small enough for compute-only use. Reconsider VMA if allocation code grows |
| Hilt / Dagger | — | Apache-2.0 | Not adopted | Manual constructor injection is sufficient for this app's size |
| Rust | — | — | Not adopted | No problem C++ cannot solve here. Adds a third language and toolchain |

## Desktop tooling (proposed, not part of the app)

For benchmarking and engine development on a desktop (Milestone 12):

| Tool | License | Use |
|---|---|---|
| Python 3 + NumPy | BSD-3 | Metrics |
| colour-science | BSD-3 | ΔE2000, color space conversions |
| OpenCV (Python) | Apache-2.0 | Registration of comparison images |
| rawpy (LibRaw wrapper) | MIT + LibRaw license | Reading stock-app DNGs for analysis only |
| MTF Mapper | BSD-3 | Slanted-edge MTF |

## Project license

Luma is licensed under the **MIT License** ([LICENSE](../LICENSE), owner decision 2026-11-19). Every adopted dependency is Apache-2.0, which is compatible with MIT; distributions of the APK must keep the Apache-2.0 license and NOTICE texts of those dependencies. Adopting an LGPL/GPL library (for example LibRaw) would add obligations, which is one more reason it is not used.

## Supply chain

- Gradle distribution checksum pinned. Repository content filters restrict `google()` to Android/Google groups.
- **Proposed:** Gradle dependency verification (`gradle/verification-metadata.xml`) once the dependency set is stable. GitHub Actions pinned to commit SHAs.
