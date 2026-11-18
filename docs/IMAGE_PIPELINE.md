# Image pipeline

The image engine is a C++20 library (`engine/`) with no Android dependencies. The Android module `core:imaging` wraps it with a JNI bridge. This split lets every stage be built, tested and benchmarked on a desktop against stored DNG bursts, independently of the camera and the UI.

## Principles

1. **Scene-referred until rendering.** Every stage before tone mapping works on linear data proportional to scene light. Display rendering happens once, at the end.
2. **One noise model throughout.** The per-frame noise profile (`SENSOR_NOISE_PROFILE`: variance = S·x + O per CFA channel) is updated after every stage that changes noise: merge, gain, shading correction. Denoise strength and merge robustness come from this model, never from a fixed constant.
3. **CPU reference first, GPU second.** Each stage first has a straightforward CPU implementation that is the correctness reference. A Vulkan version is added only when profiling on the device shows the stage dominates latency, and it is checked against the CPU reference with defined numeric tolerances.
4. **Measure before adding a stage.** A stage, or a more complex variant of it, is added only when a benchmark in [BENCHMARKING.md](BENCHMARKING.md) shows a defect it fixes.
5. **No invented detail.** No stage may synthesize texture. Sharpening is restrained and tied to output scale.

## Data domains

```
RAW16 (CFA, black-offset)          frames as delivered
   │  normalize, defects, shading
   ▼
linear CFA float (0..1 of white)    per frame, then merged
   │  white balance, demosaic (or SR accumulation)
   ▼
linear camera RGB
   │  camera → XYZ(D50) → working space
   ▼
linear working RGB (Rec.2020 primaries, D65)   scene-referred
   │  highlight recovery, residual denoise, exposure, local tone mapping
   ▼
display-referred (Luma rendering curve + look)
   │  gamut mapping, output sharpening
   ▼
Display P3 / sRGB encoded → JPEG / HEIF / Ultra HDR
```

## Stages

| # | Stage | Domain | v1 approach | Initial target | Later |
|---|---|---|---|---|---|
| 0 | Frame selection | metadata + thumbnails | Reject frames with gyro motion over threshold during exposure, or with low sharpness (Laplacian energy on a downsampled green plane). Choose the reference frame as the sharpest of the first few | CPU | — |
| 1 | Normalize | CFA | Subtract per-channel black level (`SENSOR_DYNAMIC_BLACK_LEVEL` from the result if present, else static; the S26 Ultra lists the dynamic keys). Scale by white level (`SENSOR_DYNAMIC_WHITE_LEVEL`, static 1023 = 10-bit on the S26 Ultra). The CFA phase is read per lens (GBRG, GRBG or RGGB on the S26 Ultra) | CPU (NEON) | Fused into the first GPU pass |
| 2 | Defect correction | CFA | Dynamic detection: a pixel deviating from the same-color median of its neighbors by more than k·σ(noise model) is replaced. The S26 Ultra provides **no hot-pixel map** (verified), so detection is always dynamic | CPU | GPU |
| 3 | Lens shading | CFA | The S26 Ultra's RAW is **already partly shading-corrected** (`lensShadingApplied = TRUE`, verified). Luma applies only the per-frame *residual* map (`STATISTICS_LENS_SHADING_MAP`, 17 × 13, bilinear upsampled), color residual fully and luminance residual partly (configurable, default 85 %) to avoid amplifying corner noise | CPU | GPU |
| 4 | Alignment | 2×2-averaged CFA (grayscale) | Coarse-to-fine tile alignment (HDR+): 4-level pyramid, tiles 16–32 px, L2 search at coarse levels and L1 at fine levels. Sub-pixel refinement only for the super-resolution path | CPU | **GPU** (first Vulkan stage) |
| 5 | Merge | CFA | HDR+ frequency-domain robust merge: per tile, a pairwise Wiener filter against the reference, with noise-model-driven shrinkage. Reference implementation: IPOL 2021. Output: merged CFA at native resolution (writable as Merged RAW DNG) | CPU | **GPU** |
| 5b | Super-resolution (optional path) | CFA → linear RGB | Wronski et al. 2019: kernel accumulation on a finer grid, kernel shape from local structure tensor, robustness from local statistics. Replaces stages 5 and 7 | — | **GPU only** (Experimental) |
| 6 | White balance | CFA | Gains from the AWB decision (see [COLOR_SCIENCE.md](COLOR_SCIENCE.md)), applied before demosaic | CPU | fused |
| 7 | Demosaic | CFA → RGB | Directional, edge-adaptive demosaic implemented in-house. Candidates (for example gradient-corrected linear interpolation as a baseline, then a residual-interpolation or directional method) are evaluated on Luma's own RAW test set. The choice is recorded with measurements | CPU | GPU |
| 8 | Color transform | RGB | Camera RGB → XYZ(D50) via ForwardMatrix interpolated by CCT → Bradford D50→D65 → linear Rec.2020 | CPU | fused |
| 9 | Highlight recovery | camera RGB (pre-WB ratios) | Where one or two channels are clipped, reconstruct from unclipped channels using local chromaticity ratios from the neighborhood. Blend to neutral as all channels approach clipping. No hue shift | CPU | GPU |
| 10 | Residual denoise | opponent space | Merge removes most noise. Residual chroma noise is reduced more than luma. Luma denoise is mild and edge-aware (bilateral grid or wavelet shrinkage), with strength from the post-merge noise model | CPU | GPU |
| 11 | Exposure and tone mapping | luminance | Global exposure from the exposure system. Local tone mapping with Local Laplacian filtering on log-luminance (halo-free by construction), with strength limited per scene. HDR+-style synthetic exposure fusion is the alternative to evaluate | CPU | **GPU** |
| 12 | Rendering curve and look | display | Luma rendering curve (toe, mid-contrast, soft highlight shoulder) applied to luminance with hue-preserving chroma handling. Look adjustments in Oklab | CPU | fused |
| 13 | Gamut mapping | Oklab/Oklch | Chroma compression toward the Display P3 boundary at constant hue and lightness, with soft knee | CPU | fused |
| 14 | Output sharpening | luminance | Unsharp mask with overshoot clamp (halo limit) on luminance only. Amount scales with output downscale ratio. Default low | CPU | GPU |
| 15 | Encode and metadata | — | `Bitmap` (Display P3 color space) → JPEG/HEIF. Ultra HDR via `Bitmap.setGainmap` (API 34). EXIF via `ExifInterface`. DNG via DngCreator (native) or the engine's DNG writer (computed) | Kotlin + platform | — |

## Processing graph and buffers

- Frames arrive as `Image` objects from an `ImageReader`. The capture layer passes their planes (direct `ByteBuffer`) to native code without copying, together with a metadata struct extracted in Kotlin. The `Image` is closed as soon as the engine has either copied the frame into its own pool or finished its first pass, whichever is earlier, so the camera never starves.
- The engine owns a **frame pool** sized at session start from available memory and the planned burst length. It never grows without bound during capture.
- Each processing job is a plain description (frame handles, metadata, plan, output targets) given to an engine executor on a dedicated native thread pool. The UI receives progress and the final result through a callback.
- Intermediate images use float16 storage on the GPU and float32 in the CPU reference. A 12.5MP RGB float32 image is about 150 MB, so large stages work in tiles.
- Processing continues if the user leaves the camera screen: the job runs in a foreground-safe component (see [PERFORMANCE.md](PERFORMANCE.md#background-processing)). Captured RAW frames are persisted before heavy processing starts, so a process kill does not lose the photo.

## CPU vs GPU plan

| Stage | Expected cost (12.5MP, 8 frames) | Reason for GPU |
|---|---|---|
| Alignment | High (search over 7 frames × pyramid) | Embarrassingly parallel per tile |
| Merge | High (FFT per tile per frame) | Embarrassingly parallel per tile |
| Demosaic | Medium | Per-pixel |
| Local tone mapping | Medium–high (pyramids) | Per-pixel pyramids |
| Everything else | Low | CPU is sufficient initially |

These costs are expectations, not measurements. They are replaced by profiled numbers in Milestone 7.

## Quality guards

- **Ghost guard:** merge robustness weights below threshold over a large connected area mark the region as moving. The result falls back to reference-frame-only data there, and the fraction of such pixels is recorded in diagnostics.
- **Halo guard:** local tone mapping strength is capped where gradient magnitude is high. Measured with an edge-overshoot metric on a high-contrast edge target.
- **Saturation guard:** no output pixel may exceed the chroma it had before the look step by more than the look's defined gain.
- **Sharpening guard:** edge overshoot is limited (default ≤ 5 % of local contrast). Tracked by benchmark.

## Open questions

1. Can GPU stages read the camera's RAW buffer directly? `Image.getHardwareBuffer()` exists, and the S26 Ultra's Vulkan driver exposes `VK_ANDROID_external_memory_android_hardware_buffer` (verified). Importing a `RAW16` AHardwareBuffer is still not a documented path. The fallback is one copy into a Vulkan buffer (about 25 MB per 12.5MP frame).
2. Which demosaic algorithm gives the best detail-vs-artifact trade-off on HP2 binned RAW? To be decided by measurement.
3. Is the HAL's `SENSOR_NOISE_PROFILE` accurate? This is verified by measuring noise on flat-field captures at several ISOs.
