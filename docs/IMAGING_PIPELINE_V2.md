# Imaging pipeline v2: beyond the stock S26 Ultra look

Status: Step 1 (audit), Step 2 (ISP neutralisation) and Step 4 (GPU colour engine) implemented and measured on
SM-S948B (S948BXXS6BZK1). Steps 3 and 5 are planned below. All numbers come from Luma's debug probes on the
device (`StreamProbe`), not from assumptions.

## 1. Audit (before this work)

| Question | Finding |
|---|---|
| Camera API | Camera2 only (no CameraX). Back photos: logical camera 0 (physical 2/5/6/7), ultra-wide RAW from public camera 2. Video: logical 0 with HAL lens switching. |
| Stills | `RAW_SENSOR` 4080x3060 bursts into Luma's C++ engine (align/merge, demosaic, colour, tone, NR, sharpen). HAL `JPEG` only where no autofocused RAW exists (3x/5x). |
| Video | Camera → `SurfaceTexture` → OpenGL ES 3 (RGBA8) → `MediaCodec` surface (HEVC/H.264/APV); 10-bit HLG10 directly camera → encoder (no GPU). Preview: same GPU renderer. |
| ISP controls | Video/preview left at `TEMPLATE_RECORD`/`TEMPLATE_PREVIEW` defaults (EDGE FAST, NR FAST, HAL tone map); HAL stills forced EDGE/NR `HIGH_QUALITY`; `TONEMAP_MODE_CONTRAST_CURVE` used only for Luma Log. |

So Luma photos (main, ultra-wide, front) never had Samsung's tone mapping; the viewfinder, every video and the
telephoto stills did.

## 2. What the HAL lets an app neutralise (measured)

1080p YUV, static indoor scene, ISO ~600 (`ISP` probe):

| Request | Y p1/p50/p99 | Edge energy (mean abs Laplacian) | Frame-to-frame noise |
|---|---|---|---|
| Default (EDGE FAST, NR FAST, HAL curve) | 12/131/166 | 8.73 | 1.16 |
| EDGE OFF + NR MINIMAL | 13/131/165 | 4.43 | 0.66 |
| EDGE OFF + NR OFF | 14/132/165 | 4.44 | 0.65 |
| + linear contrast curve | 2/58/89 | 2.63 | 0.62 |
| + Luma scene-log curve | 23/162/188 | 3.48 | 0.77 |

* `EDGE_MODE_OFF` is honoured and halves fine-detail energy: the default stream is heavily sharpened, which also
  doubles visible noise. Ultra-wide (camera 2) behaves the same (10.6 → 5.4).
* `NOISE_REDUCTION_MODE` MINIMAL vs OFF makes no measurable difference at this ISO.
* `TONEMAP_MODE_CONTRAST_CURVE` is honoured with up to 512 points; AE runs before the curve (exposure identical).
* Scene modes: `FACE_PRIORITY`, `HDR`; no extended scene modes.

## 3. Architecture

| Module (spec name) | Luma implementation |
|---|---|
| Camera2Controller | `core:camera` `CameraController` (photo), `app` `VideoCamera` (video), shared `CameraDevicePool`; **new `IspTuning`** + `SceneLog` |
| RawBurstPipeline | `core:imaging` `RawBurst` → `engine/` (align/merge, demosaic, Oklch tone, NR, sharpen, resample) |
| GpuColorEngine | `gl/GlRenderer` + `gl/FrameShader` colour stage (**new**), `EngineConfig`, `EngineStage` |
| LutManager | `gl/ColorProfiles` (**new**, 33³ RGB16F looks) + `gl/LutLibrary` (user .cube, monitoring LUTs) |
| TemporalDenoiser | planned (Step 5) |
| VideoEncoder10Bit | `video/Recorder` (HEVC Main10 exists for HLG10 direct); GPU 10-bit output planned (Step 5) |

### Step 2 – ISP neutralisation (`IspTuning`)

* Streams (`IspMode.NEUTRAL_LOG`): `EDGE_MODE_OFF`, `NOISE_REDUCTION_MODE_MINIMAL` (OFF if MINIMAL is missing),
  `HOT_PIXEL_MODE_FAST`, scene mode off, `TONEMAP_MODE_CONTRAST_CURVE` with Luma's scene-log curve
  (code = ln(1+48x)/ln 49, ~11.6 stops in 8 bits, 128 points sampled uniformly in code). Every request that feeds the
  viewfinder carries it (preview, AF triggers, RAW bursts), so no frame changes look.
* HAL stills (telephoto): `EDGE_MODE_OFF`, NR high quality (no second denoiser on this path), HAL display tone
  curve; the preview is not a target of the still request (it would get a differently toned frame).
* `IspMode.CAMERA` keeps the stock behaviour, selectable in Pro Tools for A/B.

### Step 4 – GPU colour engine (per pixel, single pass)

1. Decode scene-log → linear; lens-continuity gain in linear light.
2. Highlight headroom: AE compensation lowered by whole steps (0 / 0.7 / 1.3 EV), the same gain restored on the GPU.
3. Filmic curve on luminance only (hue preserved): toe slope 1.2 in log (true black floor, no lifted haze), mid grey
   0.18 → 0.24 display, exponential shoulder reaching 1.0 exactly at scene white, slope-continuous at mid grey.
4. Highlight desaturation in OKLab above 70 % display luminance; gamut mapping by chroma bisection at constant
   OKLab lightness and hue (no cyan skies, no orange skin).
5. Look LUT (33³ RGB16F, OKLCh shaping): **Natural** (skin pulled to a clean 52°, foliage and bright skies calmed),
   **Cinema** (lightness S-curve, -10 % chroma, warm highlights / cool shadows, finer grain), **Vivid** (+18 % chroma
   with soft saturation limit, skin and sky protected). Neutrals stay neutral in Natural and Vivid (unit-tested).
6. Optional shadow-weighted fine grain (breaks 8-bit steps and compression blocks).
7. Luma Log output = the scene-log signal itself; the viewfinder shows the Rec.709 rendering unless a user display
   LUT is on.

Measured against the stock rendering of the same scene: median code 137 vs 149, darkest 1 % kept deep, no edge
halos; 4K60 recording with the engine on: 0 frames lost, GL time 4.1 ms/frame average.

## 4. S26 Ultra limits that shape the design

* RAW: one RAW stream per session; logical-camera RAW pins the main lens; telephoto RAW has no AF/AWB; 12.5 MP
  RAW only (50/200 MP readouts are Samsung-private); RAW stream max 30 fps (main) / 60 fps (ultra-wide) → no RAW video.
* 10-bit: camera dynamic-range profiles are STANDARD and HLG10 only (no HDR10/HDR10+/Dolby Vision for apps). No
  Dolby Vision encoder is exposed (HEVC Main10 incl. HDR10/HDR10+ profiles and APV are). Apple-Log-style 10-bit
  log therefore needs either HLG10 camera buffers imported into GL, or the 8-bit scene-log stream re-encoded to
  10-bit on the GPU.
* 4K60: sustained at 60.0 fps on cameras 0 and 2 although the stream map says 30; an extra 640x360 YUV stream at
  4K60 costs frames on camera 0.
* Display: any app with an active camera is capped to 60 Hz (AOSP `CameraServiceProxy`).
* Thermal behaviour of long 4K60 + GPU sessions: not measured yet.

## 5. 24 / 50 / 200MP: multi-frame super-resolution

**Step 1, max-resolution sanity check (measured 2026-11-19, debug `UhrProbe`, `--ez uhr true`).** Every rear
logical and physical camera (0, 0/2, 0/5, 0/6, 0/7, 2): `REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR`
absent, `SENSOR_PIXEL_MODE` not an available request key, `SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION` =
4080x3060 (= the binned array), `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION` empty or 1920x1080 JPEG only.
Largest RAW_SENSOR / JPEG / YUV is 4080x3060 (telephoto 3x: 3648x2736). With no larger size there is no surface
for a dedicated `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` still request, so the firmware gives apps no 50/200MP readout.

**Step 2, Bayer-domain hand-held super-resolution (`detail::super_resolve`, `engine/src/align_merge.cpp`).**
* Reference: the sharpest frame of the burst.
* Alignment: global six-parameter motion (translation + rotation/scale/rolling-shutter shear) per frame, on
  blurred half-resolution green pyramids: exhaustive integer search at the coarsest level (fine periodic texture
  is blurred away there, so stripes cannot cause false matches), then Cauchy-weighted Gauss-Newton level by level.
  Synthetic burst: 0.018 raw px RMS error; local movers are rejected per pixel by the merge's robustness weights.
* Merge: every frame's raw R/G/B samples are placed at their aligned position and gathered per output pixel on a
  grid up to 2x denser per side (Gaussian kernels over the 3x3 nearest samples, a narrow detail kernel blended
  with a moderate fill kernel where samples are sparse); robustness from green box means against the noise model.
* Coverage gate: mean distance from a scene point to the nearest green sample of any frame (hand-held ~0.2 px,
  tripod ~0.5 px). Above 0.27 px x sqrt(2 / scale) the engine declines: the burst is merged at sensor resolution
  and enlarged, and the photo says so ("camera too still for multi-frame super-resolution"). Without this gate a
  still phone produced a screen-door pattern at 2x (measured on device).
* Bursts: 10 frames for 24MP (1.39x), 14 for 50MP (2x); engine limit 16.
* Engine tests (synthetic 1/f scene through a box pixel aperture, RGGB, noise, +-1.5 px shake): 2x RMSE 0.0283
  vs 0.0352 for one demosaiced frame enlarged (exact-alignment bound 0.0281), fine-detail match 0.40 vs 0.25,
  tripod declined, moving object leaves no ghost.
* Device (SM-S948B, phone resting on a desk, so declined): 24MP 3.1 s, 50MP 4.1 s, 200MP 5.6 s. Accepted
  (super-resolved) bursts measured before the speed pass: 24MP 8.9 s, 50MP 28 s (gather ~70 %).

**Step 3, 200MP, honest.** 200MP = the 50MP super-resolved image enlarged 2x per side with Lanczos-3, labelled
"Enlarged from 49.9MP super-res" in the viewfinder (amber) and "(enlarged from 49.9 MP)" in the photo description.

Not done: ZSL ring buffer (the session has a single RAW stream; bursts start at the shutter press).

## 6. Next increments

* **Step 3 (photo):** the engine already merges a RAW burst in linear Bayer space with tile alignment, robust
  weights, edge-directed demosaic, bilateral local tone mapping and Oklch highlight handling. Missing: ZSL ring
  buffer (only one RAW stream, so the ring must hold the RAW stream itself at 30 fps), asymmetric bracketing
  (-2/-3 EV frames replacing clipped 0 EV pixels), sub-pixel alignment, and the same filmic/look stage as video so
  stills and video match.
* **Step 5 (video):** motion-adaptive temporal denoiser on R11G11B10F/RGBA16F ping-pong history; 10-bit encoder
  surfaces (EGL RGBA1010102 → HEVC Main10, SDR and GPU-rendered HLG); highlight-weighted metering with AE
  hysteresis; shadow dithering is in place (grain).
