# Telephoto investigation

## Reported problems

| Report | Source | Confidence |
|---|---|---|
| 3x photos come out blurry, most noticeably in close-ups, in bright light, and with flash in the dark. A repair center found no hardware fault. Samsung support said it would be "fixed and improved in a future software update" | Android Central, 2026-04-04 | Low–Medium (user account relayed by press) |
| Samsung described the cause as software: a processing error during the multi-lens transition (per one report), or positional parallax and lens movement during AF (per another) | Gadget Hacks, BigGo, Technobezz (secondary) | Low. Explanations conflict, and none is a formal Samsung statement |
| April 2026 security update (reported build S94xBXXS2AZCL) said to contain the fix. Other outlets say the changelog never mentions it and that users still saw the issue | Android Authority (snippet), SammyFans, Android Headlines | Low. Contradictory |
| DXOMARK: tele photo score 141, close to the S25 Ultra. Lower texture measurements at most zoom ratios. Better noise and color than the S25 Ultra | DXOMARK, 2026-03-09 | Medium |
| Notebookcheck: the 3x is "still low resolution" but "visibly improved" over the S25 Ultra | Notebookcheck | Medium |

Whether the issue still reproduces on current firmware (One UI 9) is **unknown**.

## Possible causes

| Cause | Mechanism | Can Luma influence it? |
|---|---|---|
| **Close-focus fallback** | When the subject is closer than the 3x module's minimum focus distance, the logical camera switches to a crop of the main camera. A 2.9× crop of a 12.5MP binned frame contains about 1.5MP of real information, upscaled to the output size | **Yes.** Luma reads `ACTIVE_PHYSICAL_ID` and the active crop. It can show the user the real source and choose a better one (see the strategy below) |
| Transition processing | Blending or switching between the main crop and the 3x module during zoom transitions | Partly. Luma controls when it captures and can wait for `ACTIVE_PHYSICAL_ID` to settle |
| Autofocus | PDAF hunting, or focus not settled at capture. Parallax between modules means focus distance must be re-established after a switch | Partly. Luma can require AF convergence (`CONTROL_AF_STATE`) before capture, and can show focus state |
| Optics and sensor | 1/3.94" sensor, f/2.4 aperture, 1.0 µm pixels. The diffraction spot (≈3.2 µm Airy diameter at 550 nm) spans about 3 pixels | **No.** Physical limit |
| ISP processing | Denoise and sharpening applied by the HAL to processed outputs | **Yes, for RAW-based output.** Luma processes RAW itself |
| Low-light behavior | Small sensor plus a slower aperture means short exposures are noisy. The HAL may fall back to the main crop in low light | Partly. Multi-frame merging on the 3x RAW, and an explicit lens-choice policy |
| Third-party integration | Physical lens availability for apps has changed between firmware versions | Detect and report. Cannot fix |
| Hardware defect (misaligned module) | Single unverified report | **No** |

Luma does not claim to fix a hardware or firmware defect. It can (1) make the lens and crop in use visible, (2) choose capture sources deliberately, and (3) replace the HAL's processing with its own RAW pipeline.

## Verified hardware facts (HAL static metadata, 2026-11-18)

| Fact | Value | Relevance |
|---|---|---|
| 3x module | 7.0 mm, **66.4 mm eq. (2.89×)**, f/2.4, 3648 × 2736, 1.0 µm, OIS | The "3×" preset is really 2.89× |
| 3x minimum focus | 2.5 dpt ≈ **0.40 m** (APPROXIMATE) | Below about 40 cm the 3x module **cannot focus**. A 3× view of a closer subject must come from another lens |
| 5x module | 18.6 mm, **112.7 mm eq. (4.90×)**, f/2.9, 4080 × 3060 binned, OIS | |
| 5x minimum focus | 1.25 dpt ≈ **0.80 m** | Below about 80 cm, 5× must come from the 3x or main camera |
| Max analog ISO | 3x: **371**, 5x: **203** (main: 1200) | Telephoto low light goes digital very early |
| Public max-resolution readout | **None** (no UHR capability, no `pixelMode` request key) | 10× is a digital crop of the 12.5MP 5x frame |
| Logical zoom range | 0.6–10.0 | Luma's longest preset is 10× |
| Lens/crop reporting | `activePhysicalId`, `activePhysicalSensorCropRegion` in results | Luma can always show the real source |
| Physical request keys on camera 0 | None | Physical streams of the 3x/5x inherit the logical request's settings |

This turns the close-focus fallback from a hypothesis into a **hardware-backed mechanism**. A 3× request for a subject closer than about 40 cm cannot be served optically. Whatever the stock pipeline does in that case (main-camera crop, possibly with a transition defect), it starts from far less information. Whether a separate processing defect also existed, and whether it was fixed, still needs a capture test.

## Pixel arithmetic for each zoom level

Information captured, before upscaling, for each candidate source. Main-sensor numbers use the verified 4080 × 3060 public readout. The 16320 × 12240 rows describe the Samsung-private mode, which Luma **cannot** use (verified).

| Requested zoom | Source | Real pixels in frame | Notes |
|---|---|---|---|
| 2× | Main, binned, 2× crop | ~3.1 MP | Poor |
| 2× | Main, max-resolution, 2× crop | ~50 MP sampled (downsample to 12–24 MP output) | Good in daylight. Remosaic and noise limits in low light |
| 3× | 3x module, native | ~10 MP | Best default, if focus is achievable |
| 3× | Main, binned, 2.9× crop | ~1.5 MP | The likely source of "blurry 3x" at close range |
| 3× | Main, max-resolution, 2.9× crop | ~24 MP sampled | Optical resolution of the f/1.4 lens over 0.6 µm pixels is the real limit. Daylight only |
| 5× | 5x module, binned | ~12.5 MP (expected) | Best default |
| 5× | 3x module, 1.66× crop | ~3.6 MP | Fallback |
| 10× | 5x module, binned, 2× crop | ~3.1 MP | Poor |
| 10× | 5x module, max-resolution, 2× crop | ~12.5 MP sampled | The stock app uses this approach. Diffraction at f/2.9 on 0.7 µm pixels (≈3.9 µm Airy diameter) limits real gain. **To measure** |

Conclusions, given the verified absence of public max-resolution readouts:

- **Luma's 2× and 10× are digital crops** (about 3.1MP and 3.0MP captured). They are labeled that way. Multi-frame super-resolution (Experimental) is the only honest way to add detail to them.
- **Below 0.40 m at 3×**, the best public source is a crop of the 12.5MP main frame (about 1.4MP captured at 3.0×). Luma will tell the user that the subject is too close for the 3× lens and offer to step back or switch to 1×/2× framing. It will not silently produce a soft image.
- Rows that rely on max-resolution readout apply only to the stock app (private configuration).

## Luma telephoto strategy (proposed)

1. **Know the source.** Every preview frame and every capture records `ACTIVE_PHYSICAL_ID` and `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION` (API 35). The zoom control shows whether the active source is optical, a sensor crop or digital.
2. **Choose the source by measurement, not by threshold.** The camera selector scores candidates by effective resolution at the requested zoom, focus feasibility (subject distance against minimum focus distance), light level (noise per output pixel), motion and stabilization. Switching uses hysteresis to avoid flapping. Details are in [ARCHITECTURE.md](ARCHITECTURE.md#camera-selection).
3. **Use physical streams when the logical camera chooses badly.** If the device allows direct physical streams for the 3x and 5x modules, Luma can keep the telephoto module at close range and focus it at its minimum focus distance, or deliberately use the main max-resolution crop instead of a binned crop.
4. **Gate capture on focus.** Telephoto captures wait for `AF_STATE` FOCUSED_LOCKED (bounded timeout). Luma reports a focus failure instead of silently saving a soft image.
5. **Multi-frame on telephoto RAW.** In indoor and low light, merge short exposures (stabilized by OIS) instead of one long exposure.
6. **Process RAW without aggressive sharpening.** Output sharpening is subdued and tied to output scale (see [IMAGE_PIPELINE.md](IMAGE_PIPELINE.md)).

## Benchmark plan

The general method and the equal-framing rules are in [BENCHMARKING.md](BENCHMARKING.md). Telephoto-specific matrix:

| Axis | Values |
|---|---|
| Zoom | 3×, 5×, 10× |
| Light | Daylight (>10,000 lux), indoor (200–500 lux), low light (5–20 lux) |
| Subject motion | Static, slow walking person |
| Subject | ISO 12233 / slanted-edge chart, printed text (8–12 pt at defined distances), architecture with fine repeating detail, fabric/foliage texture |
| Distance (3× only) | 0.2, 0.3, 0.5, 0.8, 1.2, 2, 5 m, infinity. This locates the close-focus fallback distance on stock and on Luma |
| Mounting | Tripod (repeatability) and handheld (real use) |

For every capture, record the active physical camera ID (Luma: from metadata; stock: inferred from EXIF focal length, if present). Report MTF50 (slanted edge), text legibility (blind rating), noise (SNR on gray patches) and artifact flags.

## Required validation

- [ ] 3× close-focus distance sweep on stock and Luma, on current firmware (Phase B of [HARDWARE_VALIDATION_PLAN.md](HARDWARE_VALIDATION_PLAN.md)).
- [x] Minimum focus distance of each module from metadata (3x 0.40 m, 5x 0.80 m, APPROXIMATE). Measured confirmation pending (Phase B).
- [ ] Whether physical 3x/5x streams are available to Luma (Phase A query, Phase B).
- [x] Max-resolution availability on the 5x module: **not available publicly** (HAL, 2026-11-18). 10× is digital.
