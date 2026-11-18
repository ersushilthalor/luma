# RAW and DNG

## Output classes

Every file Luma writes belongs to exactly one class. The class is stored in the file's metadata and shown in the gallery. The vocabulary is fixed:

| Class | Definition | Example |
|---|---|---|
| **Native RAW** | One `RAW_SENSOR` frame as the sensor and HAL delivered it, written without resampling | 12.5MP binned RAW from the main camera |
| **Merged RAW** | Several native RAW frames aligned and merged in the Bayer domain, **at native resolution**. Still a CFA image | 8-frame burst merged to one DNG, HDR+ style |
| **Reconstructed RAW** | Linear scene-referred RGB at a **higher** resolution than any single frame, produced by multi-frame super-resolution | 24MP linear DNG from a 12.5MP burst |
| **Processed image** | Rendered, display-referred JPEG / HEIF / Ultra HDR | Default photo output |
| **Upscaled** | Any output whose pixel count exceeds the information captured, with no multi-frame reconstruction | Luma does **not** produce this class. Requests that would need it are refused with an explanation |

Rules:

- A resolution label (for example "24MP RAW") always carries its class. Luma never shows "24MP RAW" without "reconstructed".
- A reconstructed output that fails the quality gate (alignment confidence too low, or too little sub-pixel coverage) is not upscaled to the requested size. Luma saves it at native resolution and tells the user.

## What can be native on the S26 Ultra

Verified 2026-11-18 from the camera service's HAL static metadata (firmware S948BXXS6BZK1). App-level visibility is still to be confirmed ([HARDWARE_VALIDATION_PLAN.md](HARDWARE_VALIDATION_PLAN.md)).

| Requested output | Public Camera2 path | Status |
|---|---|---|
| Main RAW | `RAW_SENSOR` **4080 × 3060 (12.5MP)**, RAW16/RAW10, 30 fps stream config | **Verified (HAL)** |
| Ultra-wide RAW | 4080 × 3060 (12.5MP), up to 60 fps | **Verified (HAL)** |
| 3x RAW | 3648 × 2736 (10MP), RAW16/RAW12 | **Verified (HAL)** |
| 5x RAW | 4080 × 3060 (12.5MP binned) | **Verified (HAL)** |
| Front RAW | 4000 × 3000 (12MP). Camera 3: 3056 × 2296 | **Verified (HAL)** |
| 200MP main RAW | None. No `ULTRA_HIGH_RESOLUTION_SENSOR`, `sensor.pixelMode` is not a request key, no RAW in the max-resolution map | **Not supported** (Samsung-private configuration only) |
| 50MP RAW (main, 5x, ultra-wide) | None | **Not supported** (Samsung-private configuration only) |
| 48MP / 24MP RAW | No sensor mode at either resolution | **Not native.** Reconstructed only |

RAW facts that affect processing (verified, HAL):

- **10-bit data** (white level 1023) in RAW16 containers. RAW10 is also available.
- **Lens shading partly applied** to RAW (`lensShadingApplied = TRUE`). The per-frame `STATISTICS_LENS_SHADING_MAP` (17 × 13) is the *residual* gain needed to match processed output. DngCreator writes it as a GainMap, which is correct.
- **No hot-pixel map** (mode OFF only). DngCreator writes no bad-pixel list, so defect correction in Luma's engine must be dynamic.
- **CFA phase differs per lens:** main and 3x are GBRG, ultra-wide and 5x GRBG, front RGGB.
- **Dual-illuminant color calibration** (D65 + Standard A) with ColorMatrix, ForwardMatrix and CalibrationTransform 1 and 2 on every lens.
- **Dynamic black and white level and noise profile** are listed as result keys.

## Paths to 24MP and 48MP

| Path | How | Expected quality | Cost | Risks | Status |
|---|---|---|---|---|---|
| **A. Multi-frame super-resolution** from a binned burst | Align 8–15 handheld 12.5MP RAW frames to sub-pixel accuracy and accumulate them onto a finer grid with anisotropic kernels (Wronski et al. 2019). The natural hand tremor supplies the sub-pixel offsets | Real detail gain at 24MP in good light. 48MP (2× linear) is at the limit of what the method has demonstrated and would show partial gain. Noise also improves through merging | High: GPU required. Estimated 1–3 s for 24MP from 10 frames (**proposed target**, not measured) | Tripod or perfectly still hands give no sub-pixel offsets, so no gain. Moving subjects need robust rejection, otherwise artifacts | **Experimental** |
| **B. Downsample from max-resolution RAW** | Capture 200MP. Demosaic the remosaiced Bayer and resample to 48 or 24MP | Highest detail in bright light | — | The S26 Ultra does not expose max-resolution RAW to apps (verified at HAL level) | **Not supported** on this device |
| **C. Hybrid** | Binned burst plus one max-resolution frame | — | — | Needs path B | **Not supported** on this device |
| D. Single-frame upscaling | Interpolation or ML upscaling | Invented detail | Low | Violates the product rules | **Not supported** |

**Decision:** Path A is the only honest route to 24MP on this device. It is written as a reconstructed linear DNG and as a processed image, and labeled as such. 48MP stays experimental until measurements show a real resolution gain over 24MP (MTF50 on a slanted-edge chart, see [BENCHMARKING.md](BENCHMARKING.md)). Path B is reconsidered only if a future firmware exposes `ULTRA_HIGH_RESOLUTION_SENSOR`. Every firmware update is re-checked with the discovery script.

Note on path A input: the main sensor's binned 12.5MP frames use 2.4 µm effective pixels. The stream config advertises RAW at 30 fps, so a 10-frame burst spans about 0.33 s, which is short enough for hand-tremor super-resolution.

## DNG writing

### Native RAW: platform DngCreator

The AOSP implementation (checked on the main branch) writes DNG 1.4 with:

- CFA pattern, black level (per CFA channel), white level, active area, default crop.
- ColorMatrix1/2, CameraCalibration1/2, ForwardMatrix1/2, CalibrationIlluminant1/2, AsShotNeutral.
- NoiseProfile (from `SENSOR_NOISE_PROFILE`), BaselineExposure.
- OpcodeList2 GainMap from `STATISTICS_LENS_SHADING_MAP`, **only if the shading map is in the capture result.**
- OpcodeList2 FixBadPixelsList from the hot-pixel map, when present.
- OpcodeList3 WarpRectilinear from `LENS_DISTORTION`.
- Max-resolution mode support (uses the max-resolution array sizes).
- EXIF basics and an optional GPS block (only when Luma passes a location).

Requirements for Luma's RAW requests:

- `STATISTICS_LENS_SHADING_MAP_MODE_ON` on every RAW capture (verified available on the S26 Ultra).
- `STATISTICS_HOT_PIXEL_MAP_MODE_ON` where available (not available on the S26 Ultra).
- No location unless the user has enabled location tagging (see [SECURITY_AND_PRIVACY.md](SECURITY_AND_PRIVACY.md)).
- Write through `DngCreator.writeImage` to a MediaStore `OutputStream`, with no intermediate copy.

### Merged and reconstructed RAW: Luma DNG writer (native C++)

DngCreator only accepts a camera `Image` and its result, so it cannot write computed data. Luma needs a small TIFF/DNG writer in the engine. Writing the format is much simpler than reading it, and only a fixed subset is needed:

- Merged RAW: CFA DNG (`PhotometricInterpretation = 32803`), 16-bit, with the color tags copied from the reference frame's metadata, recomputed black and white levels, and a new NoiseProfile reflecting the merge.
- Reconstructed RAW: LinearRaw DNG (`PhotometricInterpretation = 34892`), 3 samples per pixel, 16-bit integer or 16/32-bit float. Shading and black-level correction are already applied, so only color tags and BaselineExposure remain.
- An embedded preview (JPEG in a SubIFD) so the gallery and other apps can show the file quickly.
- Luma provenance in XMP: output class, frame count, reference frame, pixel mode, physical camera ID, engine version.
- Validation: files must open correctly in Adobe Camera Raw / Lightroom, darktable and RawTherapee, and must pass the DNG SDK `dng_validate` tool. The tool is used offline only and never linked into the app.

Adobe DNG SDK is not used inside the app (size, and a C++ codebase that is hard to integrate on Android). LibRaw is not needed (see [DEPENDENCIES_AND_LICENSES.md](DEPENDENCIES_AND_LICENSES.md)).

## Storage cost (estimates)

| Output | Size |
|---|---|
| 12.5MP RAW16 DNG, uncompressed | ~25 MB |
| 200MP RAW16 DNG, uncompressed | ~400 MB |
| 24MP LinearRaw 3×16-bit DNG, uncompressed | ~145 MB |
| 24MP LinearRaw with lossless compression (DNG 1.7 JPEG XL, or tiled deflate) | Expected 40–70 %. **To measure** |

Lossless DNG compression is a later improvement. JPEG XL DNG compression needs DNG 1.7 readers, so compatibility comes first.

## Open questions

1. ~~Does the S26 Ultra expose `ULTRA_HIGH_RESOLUTION_SENSOR`?~~ **Answered (HAL, 2026-11-18): no.** Neither capability is present on any camera.
2. Does a repeating `RAW_SENSOR` stream *sustain* 30 fps alongside preview? The stream configuration allows it. Measure in Phase B.
3. Can RAW be taken from a specific physical stream of camera 0 (for example 3x RAW while the logical camera is at 1×)? Phase A (session query) or Phase B.
4. Do the per-camera ForwardMatrix and ColorMatrix values produce plausible color? Samsung's calibration quality decides whether Luma needs its own sensor characterization (see [COLOR_SCIENCE.md](COLOR_SCIENCE.md)).
5. How large is the residual shading map typically, and does the pre-applied shading differ between RAW10 and RAW16 outputs?
