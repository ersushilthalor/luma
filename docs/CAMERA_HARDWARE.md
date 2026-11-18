# Galaxy S26 Ultra camera hardware

This document separates four things that are often conflated:

1. **Physical hardware**: what is in the phone.
2. **Public Android API**: what Camera2 can express.
3. **Samsung proprietary capability**: what the stock Camera app uses through privileged or private configuration.
4. **Verified on device**: what Luma has observed on the owner's SM-S948B.

Last updated 2026-11-18, after read-only ADB discovery on firmware **S948BXXS6BZK1** (Android 17, One UI 9.0, patch 2026-11-05). Full evidence: [DEVICE_DISCOVERY_S26_ULTRA.md](DEVICE_DISCOVERY_S26_ULTRA.md). "Verified (HAL)" means observed in the camera service's HAL static metadata. Whether a third-party app sees identical values is a separate check (see [HARDWARE_VALIDATION_PLAN.md](HARDWARE_VALIDATION_PLAN.md)).

## Platform

| Item | Value | Evidence |
|---|---|---|
| Model / codename | SM-S948B / m3q | Verified on device |
| SoC | Qualcomm SM8850 (`canoe`). Marketed as Snapdragon 8 Elite Gen 5 for Galaxy | Verified (SM8850); Reported (marketing name) |
| CPU | 6 × 3.63 GHz + 2 × 4.74 GHz | Verified on device |
| GPU | Adreno 840, Vulkan **1.4.295**, OpenGL ES 3.2 | Verified on device |
| RAM | 11.35 GB kernel-visible (12 GB class) | Verified on device |
| ABIs | arm64-v8a only | Verified on device |
| OS | Android 17 (API 37), One UI 9.0. Launched on API 36 | Verified on device |

## Camera modules

| Module | Sensor (reported) | Camera2 IDs | Focal / 35 mm eq. | Aperture | Sensor size | Default pixel array | AF / min focus | OIS | Evidence |
|---|---|---|---|---|---|---|---|---|---|
| Wide (main) | Samsung ISOCELL HP2, 200MP | physical **5** of logical **0** (internal 56) | 6.5 mm / **23.0 mm** | f/1.4 | 9.792 × 7.344 mm | 4080 × 3060 (12.5MP) | Yes / **0.10 m** | Yes | Verified (HAL). Sensor model reported |
| Ultra-wide | 50MP (identity uncertain) | **2** (also physical of 0; internal 58) | 2.2 mm / **13.3 mm** | f/1.9 | 5.712 × 4.284 mm | 4080 × 3060 | Yes / **0.05 m** | No | Verified (HAL) |
| Tele 3x | Samsung S5K3LD, 10MP | physical **6** of 0 (internal 52) | 7.0 mm / **66.4 mm** (2.89×) | f/2.4 | 3.648 × 2.736 mm (1.0 µm) | 3648 × 2736 (10.0MP) | Yes / **0.40 m** | Yes | Verified (HAL). Sensor model reported |
| Tele 5x | Sony IMX854, 50MP | physical **7** of 0 (internal 54) | 18.6 mm / **112.7 mm** (4.90×) | f/2.9 | 5.712 × 4.284 mm | 4080 × 3060 (12.5MP binned) | Yes / **0.80 m** | Yes | Verified (HAL). Sensor model reported |
| Front | 12MP | **1** (full), **3** (cropped ~30 mm, 7MP) | 3.3 mm / 23.8 mm | f/2.2 | 4.80 × 3.60 mm | 4000 × 3000 | Yes / 0.20 m | No | Verified (HAL) |

All minimum focus distances are `APPROXIMATE` calibration (diopters). The camera service reports 4 public/system cameras, and API1 lists IDs 0, 1 and 3. The Camera2 ID list an app receives is expected to be 0, 1, 2 and 3 (**to verify in app**).

## Readout modes vs public access

| Sensor | Hardware readout | Public Camera2 | Samsung-private (vendor metadata) |
|---|---|---|---|
| Main HP2 | 200MP full, 50MP binned, 12.5MP binned | **12.5MP only.** No `ULTRA_HIGH_RESOLUTION_SENSOR`, no `sensor.pixelMode` request key, no max-resolution RAW | 16320 × 12240 and 8160 × 6120 RAW10 (`availableHighresRawStreamConfigurations`, Expert RAW, ProRAW) |
| 5x IMX854 | 50MP, 12.5MP binned | **12.5MP only** | 8160 × 6120 RAW10 / RAW16 (Expert RAW, ProRAW) |
| Ultra-wide | 50MP, 12.5MP binned | **12.5MP only** | 8160 × 6120 (Expert RAW, ProRAW) |
| 3x S5K3LD | 10MP | 10MP | 3648 × 2736 |

The stock app's 50MP and 200MP modes, and Expert RAW's high-resolution RAW, use private configurations that the public stream maps don't list. Luma cannot use them.

## Capability matrix

| Capability | Hardware | Public Camera2 (HAL-advertised) | Samsung stock app | Verified on device |
|---|---|---|---|---|
| 12.5MP main RAW | Yes | RAW16/RAW10 4080 × 3060 @ 30 fps | Expert RAW | **Yes (HAL)** |
| 200MP / 50MP main RAW | Yes | **No** | Yes (private) | **Yes (HAL): unavailable publicly** |
| 24MP output | Computational | Computational only | 24MP via Camera Assistant | n/a |
| Ultra-wide RAW | Yes | 12.5MP @ up to 60 fps | Expert RAW | **Yes (HAL)** |
| 3x / 5x RAW | Yes | 10MP / 12.5MP, as physical streams of camera 0 or after a lens switch | Expert RAW | HAL yes. App-level physical streams **unverified** |
| Physical lens selection | Yes | Logical camera 0 (`zoomRatio` 0.6–10) and physical streams. No physical request keys | Full | HAL yes |
| Manual ISO / shutter | Yes | `MANUAL_SENSOR` on all back lenses and the front camera | Pro mode | **Yes (HAL)** |
| ISO / shutter priority | — | `CONTROL_AE_PRIORITY_MODE` both modes (API 36) | Pro mode | **Yes (HAL)** |
| Kelvin white balance | — | `COLOR_CORRECTION_MODE_CCT`, 2300–10000 K (API 36) | Pro mode | **Yes (HAL)** |
| Manual focus | Yes | `LENS_FOCUS_DISTANCE`, APPROXIMATE diopters | Pro mode | **Yes (HAL)** |
| OIS control | Main, 3x, 5x | Physical cameras list OFF/ON. **Logical camera 0 lists OFF only** | Full | HAL yes. Behavior **unverified** |
| Zero-shutter-lag RAW | — | RAW16 30 fps stream config. One RAW stream per session | Privileged | Config yes. Sustained rate **unverified** |
| Ultra HDR (JPEG_R) output | — | HAL advertises JPEG_R up to 12.5MP. Framework derivation disabled | Yes | App-level **unverified** |
| HEIC output | — | Not advertised | Yes | **Unavailable** |
| Camera extensions | — | Vendor extensions enabled (`ro.camerax.extensions.enabled`) | Nightography etc. | Modes **unverified** |
| Dual Slope Gain / sensor HDR readouts | Yes | No public control | Yes | n/a |
| Hot pixel map | — | Not available | — | **Yes (HAL): unavailable** |
| Lens shading in RAW | — | Partly pre-applied (`lensShadingApplied = TRUE`). Residual map available | — | **Yes (HAL)** |

## Sensitivity limits (verified, HAL)

| Lens | ISO range | Max analog ISO | Exposure range |
|---|---|---|---|
| Main | 9–3200 | 1200 | 43 µs – 255 ms |
| Ultra-wide | 15–3200 | 950 | 84 µs – 176 ms |
| 3x | 23–3200 | **371** | 33 µs – 214 ms |
| 5x | 25–3200 | **203** | 48 µs – 274 ms |
| Front | 43–3200 | 345 | 81 µs – 203 ms |

Above max analog ISO, gain is digital. On the telephotos this starts very early, which is why multi-frame merging is central to telephoto low-light quality.

## What third-party apps cannot do on this device

- Read out the main sensor at 50MP or 200MP, or the 5x and ultra-wide sensors at 50MP (verified at HAL level: absent from public maps and request keys).
- Control sensor-internal HDR readouts.
- Zoom beyond 10× through the logical camera.
- Set per-physical-camera request values on camera 0 (no physical request keys).
- Use Samsung's private processing, except through any extension modes Samsung exposes (to verify).
