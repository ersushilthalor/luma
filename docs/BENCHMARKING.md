# Benchmarking and validation

Luma is not described as better than Samsung Camera in any scenario until a controlled comparison under this method shows it. Every result records the firmware version of both apps, because stock behavior changes with updates.

## Equipment

| Item | Purpose |
|---|---|
| Sturdy tripod + phone clamp with repeatable positioning | Identical framing between apps |
| X-Rite ColorChecker Classic (24) and, if available, ColorChecker SG | Color accuracy |
| Slanted-edge / ISO 12233-style resolution chart (printed at high quality) | MTF50, sharpening overshoot |
| Dead-leaves (spilled coins) chart | Texture retention vs noise reduction |
| Transmissive step chart, or a reproducible high-contrast scene (window + interior) | Dynamic range |
| Light sources with known CCT and high CRI (daylight LED ~5600 K, tungsten ~2850 K), dimmable | Repeatable illuminants and light levels |
| Lux meter | Recording light level per scene |
| Remote trigger or Bluetooth shutter | Avoids touch shake on tripod captures |
| Gray card | Exposure reference |

## Rules for a fair comparison

1. **Same device, same moment.** Alternate the order (A, B, B, A) so lighting drift affects both apps equally. At least 3 captures per app per scene.
2. **Default and matched settings.** Compare (a) both apps on defaults, and (b) matched modes (resolution class, HDR/processing modes, flash off, same aspect ratio). Record the settings.
3. **Equal framing.** Set the same zoom label in both apps. Verify the actual field of view from the chart. If fields of view differ, crop the wider image to the narrower field of view.
4. **Equivalent output resolution.** Before pixel-level comparison, resample both images to the smaller of the two pixel counts. Use the same filter (Lanczos-3 in linear light). Comparisons at each app's native resolution are reported separately and labeled as such.
5. **Registration.** For difference-based metrics (ghosting, noise maps), register images with a homography from feature matching.
6. **Focus.** On tripod scenes, confirm focus on the target in both apps (tap to focus at the same point).
7. **Blind subjective review.** Pairs shown side by side in random order, app identity hidden, on a calibrated Display P3 monitor and on the phone itself. Several reviewers. Two-alternative forced choice plus "no preference".
8. **Report everything.** Losses are reported as clearly as wins.

## Scene matrix

| Scene | Light | Motion | Primary metrics |
|---|---|---|---|
| Daylight landscape | >10,000 lux | Static | Exposure, DR, detail, color |
| High-contrast daylight (backlit, sky + shade) | >10,000 lux | Static | Highlight clipping, shadow noise, halos |
| Indoor room | 200–500 lux | Static | Noise, detail, white balance |
| Low light | 5–20 lux | Static | Noise, detail, color, exposure |
| Night street | 1–10 lux, mixed sources | Static and slow motion | Noise, flare, ghosting, exposure |
| Portrait (several skin tones) | Daylight, indoor, backlit | Slight motion | Face exposure, skin color, skin texture |
| Skin tone set | D65-like and tungsten | Static | Skin hue and chroma consistency |
| Architecture | Daylight | Static | Detail, distortion, sharpening halos |
| Text (printed, signage) | Daylight and indoor | Static | Legibility, MTF50 |
| Fine detail / texture | Daylight and indoor | Static | Dead-leaves texture acutance |
| Moving subjects | Daylight and indoor | Walking, children/pets if possible | Motion blur, ghosting |
| 3× telephoto | All three light levels | Static and motion | See [TELEPHOTO_INVESTIGATION.md](TELEPHOTO_INVESTIGATION.md) |
| 5× telephoto | All three light levels | Static and motion | As above |
| 10× zoom | Daylight and indoor | Static | As above |
| Macro / close-up | Indoor | Static | Framing accuracy, focus, detail |

## Metrics

| Metric | Method |
|---|---|
| Exposure accuracy | Subject-region mean log-luminance against the target and against the gray card |
| Highlight clipping | Fraction of pixels with any channel at maximum, per region (sky, faces) |
| Shadow detail | SNR in dark step-chart patches. Visual check of the shadow region |
| Dynamic range | Number of step-chart stops above an SNR threshold (fixed threshold, applied to both apps) |
| Color accuracy | ΔE2000 on ColorChecker patches after exposure normalization. Mean and max |
| Skin-tone quality | Hue and chroma statistics in face regions. Blind preference |
| Noise | Standard deviation on uniform patches (luma and chroma), at equal output resolution |
| Detail retention | MTF50 from slanted edges. Dead-leaves texture acutance |
| Sharpening artifacts | Edge overshoot / undershoot percentage on slanted edges |
| Motion artifacts / ghosting | Registered difference maps on motion scenes. Count of ghost regions. Blind review |
| Focus accuracy | MTF50 against the best manual-focus capture of the same target |
| Capture consistency | Spread of exposure and color over 10 repeated captures |
| Processing latency | Shutter to final file, measured from traces (Luma) and from screen recording (stock) |

## Data management

- Raw benchmark captures are stored outside the repository (`benchmarks/captures/` is git-ignored).
- Each benchmark run produces a committed report: date, firmware versions (stock app and system), Luma commit, scene list, metric tables, and links to the off-repository capture archive.
- Metric scripts (Milestone 12) live in `benchmarks/` and run on a desktop (see [DEPENDENCIES_AND_LICENSES.md](DEPENDENCIES_AND_LICENSES.md#desktop-tooling-proposed-not-part-of-the-app)).

## Engine regression benchmarks

Separate from app-versus-stock comparisons, the engine has a fixed corpus of stored RAW bursts (with metadata) from the device. Every engine change is run on it on the desktop. The run reports PSNR/SSIM against the previous output, metric deltas (MTF50, noise, overshoot) and timing, so quality regressions are caught before they reach the phone.
