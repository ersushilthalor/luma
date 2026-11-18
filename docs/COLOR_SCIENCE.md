# Color science

Rendering philosophy: **professional but natural.** Color is accurate where accuracy is defined (neutrals, skin, memory colors within tolerance), and pleasing where it is not (highlight roll-off, saturation compression). The process is never a generic LUT applied to a display-referred image.

## Pipeline

```
camera RGB (linear, WB applied)
  → XYZ (D50)        ForwardMatrix interpolated by CCT
  → XYZ (D65)        Bradford chromatic adaptation
  → linear Rec.2020  working space, scene-referred
  → exposure, local tone mapping (luminance)
  → Luma rendering curve (luminance) + hue-preserving chroma handling
  → look (Oklab): saturation, skin protection, memory-color constraints
  → gamut map to Display P3 (Oklch, constant hue)
  → encode Display P3 (default) or sRGB
```

## 1. Sensor characterization

| Source | What it gives | Status |
|---|---|---|
| Camera2 static metadata | `SENSOR_COLOR_TRANSFORM1/2`, `SENSOR_FORWARD_MATRIX1/2`, `SENSOR_CALIBRATION_TRANSFORM1/2`, `SENSOR_REFERENCE_ILLUMINANT1/2`, per physical camera | **Present on every S26 Ultra lens** (HAL, 2026-11-18). Illuminants D65 and Standard A. Plausibility of the values: to measure |
| Luma calibration | Own ForwardMatrix per module, fitted from ColorChecker (24 and SG) captures under two illuminants (D65-like daylight and a ~2850 K tungsten source). Minimizes mean ΔE2000 with a neutral-axis constraint | **Proposed** (Milestone 8) |

Rules:

- Matrices are **per physical camera.** When a logical camera switches modules, color goes through that module's matrices. The equality of neutral rendering across modules is a benchmark item.
- Interpolation between the two calibration illuminants uses the DNG method: find the CCT that makes the camera neutral consistent, then interpolate the matrices in inverse-CCT (mired) space.
- Samsung's matrices are used until Luma's own calibration measurably improves ΔE2000 on held-out patches. The improvement is recorded in [TECHNICAL_DECISIONS.md](TECHNICAL_DECISIONS.md).

## 2. White balance

| Stage | Approach | Status |
|---|---|---|
| v1 | Use the HAL's AWB result (`COLOR_CORRECTION_GAINS` from the capture result, which the DNG AsShotNeutral also reflects) as the estimate. Luma adds temporal smoothing and per-module consistency when the lens switches | Proposed |
| v2 | Luma's own estimator on RAW statistics: gray-world restricted to plausible illuminant chromaticities (Planckian locus ± tolerance), with bright-neutral weighting and exclusion of saturated pixels. The HAL estimate is a prior | Proposed |
| v3 | Learned illuminant estimation (FFCC-style, as in Liba et al. 2019) | Deferred (optional, never mandatory) |
| Mixed light | No global fix. Luma preserves the warmth of warm ambient light (it does not neutralize sunsets or tungsten interiors) by limiting correction strength toward warm CCTs | Proposed |
| Pro mode | Kelvin + tint via `COLOR_CORRECTION_MODE_CCT` (API 36) if listed. Otherwise manual gains through `COLOR_CORRECTION_GAINS` (needs `MANUAL_POST_PROCESSING`) for preview, and the exact value in Luma's RAW pipeline | To verify |

DXOMARK rates the stock white balance as a strength. Luma's first priority is therefore to *not regress* it: v1 starts from the HAL estimate.

## 3. Working space and scene-referred processing

- Working space: linear Rec.2020 primaries, D65 white, float. It is wide enough that camera colors seldom go out of range before gamut mapping, and it is standard.
- Exposure is a linear multiplier. Local tone mapping operates on log-luminance only and preserves chromaticity (ratios), so tone mapping does not cause hue shifts.

## 4. Rendering curve (tone)

The Luma rendering curve maps scene-linear luminance to display luminance:

- **Toe:** gentle. Shadows are lifted by local tone mapping, not by flattening the toe. Black is not clipped hard.
- **Mid-tones:** contrast slope of about 1.2–1.4 in log space (tunable per look). Mid-gray is anchored at a fixed display value.
- **Shoulder:** long and soft, compressing about 2.5–4 stops above mid-gray into the top of the display range. This is the "controlled highlight roll-off". It directly targets DXOMARK's observation of "flat, unnatural-looking spots" from highlight compression.
- **Highlight desaturation:** as luminance enters the shoulder, chroma is reduced **at constant hue** (Oklch), so bright skies do not turn cyan and bright skin does not turn yellow.

The curve is defined in parametric form (not a LUT), so it is tunable, invertible and documented. Its parameters are versioned, and every output records the version in metadata.

## 5. Look and skin

- Saturation: global chroma gain in Oklab, ≤ 1.10 by default, with a soft limit: already-saturated colors are boosted less (avoids neon foliage and clipped reds).
- **Skin protection:** a hue band around typical skin hue (calibrated on a diverse set of real skin captures, not only ColorChecker patches 1 and 2) gets reduced saturation gain and no hue rotation. Skin lightness is not smoothed (no beautification).
- Memory colors (sky, foliage) are not pushed toward stereotypes. Only gamut-related chroma compression applies.
- No texture enhancement, clarity or "structure" boost by default.

## 6. Gamut mapping and output

- Default output: **Display P3** (the S26 Ultra display and most modern viewers support it), with an embedded ICC profile. sRGB output is a setting for compatibility.
- Mapping: in Oklch, compress chroma toward the destination gamut boundary at constant L and h, with a soft knee starting at about 90 % of the boundary. Never clip channels independently (that would shift hue).
- **Ultra HDR** (gain map JPEG, API 34): the SDR base image is the standard rendering. The gain map stores the highlight headroom the rendering curve compressed. Offered as an option after the SDR rendering is validated (Milestone 9).
- 10-bit HEIF (`HEIC`, `HEIC_ULTRAHDR` on API 36) is evaluated for storage efficiency. JPEG remains the default for compatibility.

## 7. Validation

| Test | Metric | Target (proposed) |
|---|---|---|
| ColorChecker 24, D65-like light | Mean / max ΔE2000 (after exposure normalization) | Mean ≤ 3.0. Stock measured 3.04 by Notebookcheck under their conditions, so Luma must measure stock itself under identical conditions |
| Neutral consistency across modules | Δ chromaticity of gray patch between 0.6×, 1×, 3×, 5× | ≤ 1.5 ΔE2000 |
| Skin | Blind preference on a diverse skin set (≥ 10 subjects, multiple illuminants) | No regression against stock. Improvement target set after the first study |
| Highlight roll-off | Hue drift of a saturated patch across an exposure sweep | Hue change ≤ 3° in Oklch until near white |

## Future refinement path

1. Per-module calibrated matrices (Milestone 8).
2. Own AWB estimator (v2), measured against the HAL.
3. Dual-illuminant and spectral calibration with a monochromator or a multi-LED light source, if available.
4. Optional learned AWB (never mandatory).
