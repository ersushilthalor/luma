# Macro and close-focus investigation

## Reported problem

| Report | Source | Confidence |
|---|---|---|
| In the stock app, close-up or macro shots taken at **24MP** with **Focus Enhancer** enabled were cropped at the right and bottom edges compared with the viewfinder. Some reports also mention 2× zoom | SamMobile (2026-04-29), SammyFans (2026-04-23), Tuttoandroid | Medium |
| Focus Enhancer (also called Close Focus Enhancement) switches to the ultra-wide module when the subject is too close for the current module, and crops it to keep framing | SammyFans; general descriptions of the feature | Medium |
| Samsung moderators acknowledged the bug. The second April 2026 update reportedly fixed it. A related issue (24MP reset to 12MP after leaving Pro mode) was reported afterwards | SamMobile (2026-04-30); Samflux | Medium (fix), Low (follow-up) |

## Verified facts (HAL static metadata, 2026-11-18)

| Lens | Minimum focus | OIS | Public RAW | Notes |
|---|---|---|---|---|
| Ultra-wide (camera 2) | 20 dpt ≈ **0.05 m** | No | 4080 × 3060 (up to 60 fps) | Shortest focus in the system. Public camera ID and physical member of camera 0 |
| Main (physical 5) | 10 dpt ≈ **0.10 m** | Yes | 4080 × 3060 | Close-ups down to about 10 cm without switching |
| 3x / 5x | 0.40 m / 0.80 m | Yes | — | Never used for close-ups |

All are `APPROXIMATE` calibration: the diopter values are physically meaningful but not exact.

## Analysis

- **Which sensor handles close focus.** The ultra-wide (minimum focus about 5 cm, verified) is the macro camera. Between about 10 cm and the telephoto limits, the main camera can focus by itself, so a macro switch is only needed below about 10 cm.
- **How focus distance affects camera selection.** Each module has its own minimum focus distance. When the subject comes closer than the active module's limit, a camera app has two choices. It can stay on that module and accept defocus, or switch to a module that can focus and crop it to the requested field of view. The second choice produces a framing change unless the crop is matched exactly. That is a software responsibility.
- **Is the reported crop mismatch hardware, firmware or software?** The evidence points to software in the stock app: it appeared only with a specific combination (24MP + Focus Enhancer), Samsung acknowledged it, and a software update addressed it. It is not a hardware property, and Luma's own switching logic will not inherit it. Luma can still create the **same class** of bug if its preview crop and capture crop are computed differently.
- **Camera2 controls that influence the behavior:**
  - Through the logical camera, `CONTROL_ZOOM_RATIO` lets the HAL choose the module. Apps cannot request "switch to ultra-wide when close". The HAL may or may not do that on its own. **To verify.**
  - Opening the ultra-wide (physical or public ID) and applying `SCALER_CROP_REGION` or `CONTROL_ZOOM_RATIO` gives Luma full control over the crop.
  - `LENS_FOCUS_DISTANCE` (manual focus) and `CONTROL_AF_MODE_MACRO`, if listed.
  - `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION` (API 35) to confirm what was actually read out.

## Luma close-focus design (proposed)

1. **Detection.** Subject distance is estimated from the active module's `LENS_FOCUS_DISTANCE` (diopters) when calibration is `APPROXIMATE` or `CALIBRATED` (all S26 Ultra lenses are `APPROXIMATE`), together with AF state. When the AF reports that focus cannot be reached (focus distance pinned at the module minimum and the AF state not focused), Luma suggests close-focus mode.
2. **Explicit, visible switch.** Luma shows a "Close-up" indicator when it moves to the ultra-wide. It never switches silently for the main zoom presets. Automatic switching is a setting (default: suggest, not switch) until framing accuracy is proven.
3. **One crop definition.** Preview and still use a single crop rectangle, computed once in sensor coordinates, from the requested field of view, the lens calibration (intrinsics and pose of both modules) and the output aspect ratio. The same rectangle is applied to the preview request and the capture request. After capture, Luma checks the result's crop region against the requested one and logs any mismatch in diagnostics.
4. **Parallax.** At macro distances the two modules see different perspectives. A crop can match field of view and center, but not perspective. Luma does not hide this: the preview always shows the module that will capture.
5. **Resolution honesty.** Matching the main camera's 1× field of view on the ultra-wide is a 1.73× crop (13.3 → 23.0 mm eq.) of its 12.5MP frame: about 4.2MP captured (verified sizes). The capture info shows the real source and the crop.
6. **No OIS on the ultra-wide (verified).** Close-up captures on it use shorter exposures and multi-frame merging rather than long single exposures.

## Validation tests

| Test | Method | Pass criterion |
|---|---|---|
| Framing accuracy | Photograph a printed grid target at 5, 8, 10, 15 and 20 cm. Compare the final viewfinder frame (screen recording) with the saved image | Edges match within 1 % of frame width on all sides |
| Focus reach per module | Manual-focus sweep at the minimum distance against a resolution target | Measured minimum focus within 20 % of metadata (APPROXIMATE) |
| Switch distance | Slowly approach the target in the stock app and in Luma. Record the distance at which the source changes | Documented. Luma switches within ±1 cm of its policy distance with no oscillation |
| Stock reproduction | Stock app, 24MP + Focus Enhancer, current firmware | Recorded as reproducible or not reproducible. Not assumed |
