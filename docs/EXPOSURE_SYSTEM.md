# Exposure system

Adaptive exposure is one of Luma's main differentiators. The goal is a correctly exposed image that preserves highlight and shadow information. That is decided per scene: never a fixed negative compensation.

## Architecture

```
            ┌────────────── preview stream (YUV, low res) ──────────────┐
            │                                                           │
  Camera ───┤  capture results: AE state, exposure, ISO, face rects,    │
            │  focus distance, active physical ID, lux estimate         │
            │                                                           ▼
            │                                          Scene analyzer (≈10–15 Hz)
            │                                          · luminance histogram, percentiles
  Gyro ─────┼─────────────────────────────────────────▶ · highlight clipping fraction (by region)
            │                                          · face / subject luminance
            │                                          · motion (gyro + frame difference)
            │                                          · scene DR estimate
            │                                                           │
            │                                                           ▼
            │                                          Exposure controller
            │                                          · preview AE bias (smoothed)
            │                                          · capture exposure target
            │                                                           │
            │                                                           ▼
            └──────────────────────────────────────── Capture planner (at shutter)
                                                       · single vs burst vs bracket
                                                       · per-frame exposure / ISO
                                                       · tone-mapping targets for the engine
```

The scene analyzer and controller live in `core:camera` (Kotlin, allocation-free hot loop). The tone-mapping targets go to the engine with the frames.

## Inputs

| Input | Source | Notes |
|---|---|---|
| Luminance histogram and percentiles | Preview YUV `ImageReader` (e.g. 640×480 Y plane) | The YUV stream is ISP-tonemapped. Luma makes the mapping known by requesting `TONEMAP_MODE_CONTRAST_CURVE` with its own curve. The S26 Ultra advertises CONTRAST_CURVE, FAST and HIGH_QUALITY, but not GAMMA_VALUE (verified, HAL). **To verify** whether the HAL honors the curve on the preview stream |
| Highlight clipping | Fraction of pixels above a near-white threshold, weighted by region (center, faces) | Clipping in tiny specular areas is tolerated. Clipping in large areas, skies and faces is not |
| Scene brightness | Exposure × ISO from results → scene luminance estimate (EV100). `CONTROL_LOW_LIGHT_BOOST` lux range where available | Absolute brightness picks the scene class |
| Faces | `STATISTICS_FACES` (platform face detection) | Face luminance target, regardless of background |
| Subject position | Tap-to-meter region, or AF region | User intent overrides automatic weighting |
| Motion | Gyroscope (hand motion) + preview frame difference (subject motion) | Caps per-frame exposure time |
| Focal length / stabilization | Active physical camera, OIS state | Handheld exposure limit scales with focal length. OIS extends it |
| Capability limits | Exposure and ISO ranges of the active camera, max frame duration | Hard constraints |

## Decisions

### Preview

The HAL AE stays in control of preview (stable, fast, low power). Luma applies a **bias** through `CONTROL_AE_EXPOSURE_COMPENSATION` (S26 Ultra: ±2 EV in 1/10 steps, verified), derived from highlight protection and face targets, with slow temporal smoothing and hysteresis so the preview does not pump. The S26 Ultra allows **one** AE region and no AWB regions (verified), so the region goes to the face or the user's tap, whichever has priority.

### Capture target

At shutter, the controller computes:

1. **Highlight-protecting exposure** E_h: the exposure at which important highlights (large clipped regions, faces) sit below clipping with margin. Specular highlights are excluded.
2. **Subject exposure** E_s: the exposure that places the subject (face or metered region) at its target value after tone mapping.
3. **Scene dynamic range** = E_s / E_h (in stops).

| Scene DR | Plan |
|---|---|
| ≤ ~1 stop | Single frame at E_s (or a short merged burst if free) |
| 1–3 stops | Burst at E_h. Merge. Local tone mapping lifts the subject to E_s |
| > 3 stops | Burst at E_h + long frames for shadows (bracketing), if motion allows. Otherwise burst only, with more frames |

The amount of "underexposure" therefore comes from the scene, not from a fixed rule. A low-contrast scene is captured normally.

### Motion

- Per-frame exposure ≤ min(handheld limit from focal length and OIS, subject-motion limit from frame difference, the lens's max exposure: 176–274 ms on the S26 Ultra).
- Per-frame ISO ≤ the lens's max analog sensitivity where noise allows (S26 Ultra: main 1200, UW 950, 3x 371, 5x 203). Beyond that, more frames are preferred over digital gain.
- If subject motion is high: shorter frames and higher ISO, fewer frames, higher merge robustness. Motion consistency over noise.
- With a face moving, the face motion limit applies even if the background is static.

### Consistency

- The same scene, captured repeatedly, should give the same exposure. The controller is deterministic for identical inputs and smooths its inputs over time.
- Each capture logs its decision: inputs, scene class, E_h, E_s, the plan. Visible in diagnostics. This also makes benchmarking explainable.

## Pro mode interaction

- Manual ISO and shutter disable the controller's exposure decisions but keep the analyzer for the meter display (zebra and histogram, evaluated after the approximate tone-curve inversion).
- On API 36+, if `CONTROL_AE_AVAILABLE_PRIORITY_MODES` lists them, shutter priority and ISO priority use the HAL's `CONTROL_AE_PRIORITY_MODE`. Otherwise Luma implements priority modes by fixing one parameter and solving for the other from its own meter.

## Validation

| Test | Measure |
|---|---|
| Repeatability | 10 captures of the same tripod scene. Spread of mean log-luminance of the subject region. Target ≤ 0.1 EV (proposed) |
| Highlight retention | Backlit scene with sky. Clipped-area fraction in sky and faces against stock |
| Face exposure | Backlit portraits, several skin tones. Face luminance against target. Blind preference |
| Dynamic range | Transmissive step chart or high-contrast real scenes. Usable stops |
| Motion | Walking subject in indoor light. Blur and ghost rate |

## Open questions

1. Does the HAL honor `TONEMAP_MODE` on a combined preview + analysis configuration? Otherwise, the analysis needs another way to linearize: calibrate the HAL's default tone curve from a RAW + YUV pair.
2. Is `CONTROL_AE_EXPOSURE_COMPENSATION` applied identically on every physical module? Needed for consistency across lens switches.
3. Latency between an AE bias change and its effect in results. Needed for controller tuning.
