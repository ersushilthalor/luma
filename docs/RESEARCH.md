# Research record

Research date: 2026-11-18. All findings below were gathered on that date unless noted.

**Update 2026-11-18 (later the same day):** read-only ADB discovery was performed on the owner's Galaxy S26 Ultra (SM-S948B, firmware S948BXXS6BZK1, Android 17). Findings F19–F30 below come from that discovery. They supersede or confirm F1–F8 and F11–F13. Full record: [DEVICE_DISCOVERY_S26_ULTRA.md](DEVICE_DISCOVERY_S26_ULTRA.md). Device data is HAL-level (camera service static metadata). The app-level view is still pending ([HARDWARE_VALIDATION_PLAN.md](HARDWARE_VALIDATION_PLAN.md)).

## Confidence scale

| Level | Meaning |
|---|---|
| **High** | Primary source (AOSP source, Android SDK data, official documentation, peer-reviewed paper), or several independent credible sources that agree. |
| **Medium** | One credible secondary source (lab test, in-depth review), or primary sources that disagree on details. |
| **Low** | Press reports, forum posts, leaks, or sources that contradict each other. |

## Source access notes

- GSMArena spec and review pages returned HTTP 402/302 (paywall redirect) and could not be read. Hardware figures were taken from DXOMARK and Notebookcheck instead.
- Samsung's US specification page returned 404. Samsung's global newsroom launch article timed out. Samsung's UK newsroom snippet confirms "200MP wide" and "50MP telephoto with 5x optical zoom" only.
- Android Authority's article on the blur fix returned 403. Its claims are known only through search snippets and are treated as Low confidence.
- The Samsung Semiconductor ISOCELL HP2 page is archived ("no longer available as of July 31, 2025") but still readable.
- The official Android "camera extensions supported devices" page could not be located (404 at the expected path).
- No public source confirms whether Samsung allows third-party physical-stream access on the S24/S25/S26 Ultra (searched 2026-11-18). Only owner reports of lens restrictions on mid-range Galaxy models were found.
- SamMobile (2024-05-09, https://sammobile.com/news/android-15-better-third-party-camera-apps) reported that Samsung implemented all five Camera2 extensions on its Android 14 flagships. This is a secondary source and does not cover the S26 Ultra.

## Primary sources used

| Source | Used for |
|---|---|
| AOSP `system/media/camera/docs/metadata_definitions.xml` (main branch), https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/metadata_definitions.xml | Definitions of capabilities, pixel modes, binning, logical multi-camera, zoom, focus calibration and shading-map behavior |
| AOSP `frameworks/base/core/jni/android_hardware_camera2_DngCreator.cpp` (main branch) | Which DNG tags and opcodes the platform DngCreator writes |
| AOSP `MultiResolutionImageReader.java` (main branch) | Variable-size output from logical and ultra-high-resolution cameras |
| Android SDK Platform 37.0 `data/api-versions.xml` (installed locally) | Exact API level of every Camera2 key that Luma relies on |
| https://developer.android.com/media/camera/camera2/multi-camera | Logical/physical camera usage and stream guarantees |
| https://developer.android.com/build/releases/gradle-plugin | AGP 9.5 compatibility (Gradle ≥ 9.6.0, JDK 17, default NDK 28.2.13676358, max API 37) |
| https://developer.android.com/build/migrate-to-built-in-kotlin | AGP 9 built-in Kotlin |
| https://source.android.com/docs/core/graphics/implement-vulkan | Vulkan 1.4 requirement for devices launching with Android 16 |
| https://developer.android.com/media/platform/hdr-image-format | Ultra HDR (gain map JPEG) format |
| https://developer.android.com/training/data-storage/shared/media | MediaStore and scoped storage |
| https://helpx.adobe.com/camera-raw/digital-negative.html | DNG specification |
| Hasinoff et al., "Burst photography for high dynamic range and low-light imaging on mobile cameras", SIGGRAPH Asia 2016, https://www.hdrplusdata.org/hdrplus.pdf | Burst alignment and merge, underexposure strategy |
| Monod, Delon, Veit, "An Analysis and Implementation of the HDR+ Burst Denoising Method", IPOL 2021, https://www.ipol.im/pub/art/2021/336/ | Reproducible reference implementation of the HDR+ merge |
| Liba et al., "Handheld Mobile Photography in Very Low Light", SIGGRAPH Asia 2019, https://arxiv.org/abs/1910.11336 | Motion metering, low-light capture scheduling, learned AWB |
| Wronski et al., "Handheld Multi-Frame Super-Resolution", SIGGRAPH 2019, https://arxiv.org/abs/1905.03277 | Multi-frame demosaic and super-resolution from hand tremor |
| Delbracio et al., "Mobile Computational Photography: A Tour", 2021, https://arxiv.org/abs/2102.09000 | Survey of the mobile pipeline |
| Google Research, "HDR with Bracketing on Pixel Phones", 2021, https://research.google/blog/hdr-with-bracketing-on-pixel-phones/ | Adding long exposures to a short-exposure burst |
| Paris, Hasinoff, Kautz, "Local Laplacian Filters", SIGGRAPH 2011, doi:10.1145/1964921.1964963 | Halo-free local tone mapping |
| Mertens, Kautz, Van Reeth, "Exposure Fusion", Computer Graphics Forum 2009, doi:10.1111/j.1467-8659.2008.01171.x | Display-referred exposure fusion (reference, not the chosen path) |
| Ottosson, "A perceptual color space for image processing" (Oklab), https://bottosson.github.io/posts/oklab/ | Perceptual space for hue-preserving operations |

## Secondary sources used

| Source | Used for | Confidence |
|---|---|---|
| DXOMARK S26 Ultra camera test (2026-03-09), https://www.dxomark.com/samsung-galaxy-s26-ultra-camera-test/ | Module specs, measured weaknesses (exposure inconsistency, flat highlights, lower tele texture) | Medium |
| Notebookcheck S26 Ultra review, https://www.notebookcheck.net/Blocks-prying-eyes-Samsung-Galaxy-S26-Ultra-review.1278349.0.html | SoC, GPU, RAM, sensor model names, over-sharpening, cool low-light WB | Medium |
| Samsung Semiconductor ISOCELL HP2 page (archived), https://semiconductor.samsung.com/image-sensor/mobile-image-sensor/isocell-hp2/ | Tetra²pixel binning modes (50MP 1.2µm, 12.5MP 2.4µm), DSG, Super QPD | High (for the sensor, not for how the S26 Ultra HAL exposes it) |
| Android Central (2026-04-04), https://www.androidcentral.com/phones/samsung-galaxy/fear-not-samsung-is-investigating-the-galaxy-s26-ultras-blurry-camera | 3x blur reports, Samsung support response | Low–Medium |
| SamMobile, 24MP mode articles (2026-02-26, 2026-06-10) and cropping-fix article (2026-04-30) | 24MP via Camera Assistant, 200MP locks the wide camera, Focus Enhancer crop bug and fix | Low–Medium |
| SammyFans, Android Headlines, Gadget Hacks, Technobezz (April 2026) | Conflicting claims about whether the April 2026 update fixed the 3x blur | Low |
| 9to5Google (2026-02-25) | "50MP 5x and 10MP 3x telephoto", wider apertures, no larger sensors | Medium |
| Android Central, SamMobile, 9to5Google (September 2026) | One UI 9 (Android 17) stable rollout to the S26 series from 2026-09-16 | Medium |
| Samsung Developer Forum and Samsung Community threads | No public Samsung camera SDK; third-party apps receive reduced lens access on some models | Low |
| 9to5Google (2024-12-17) | Galaxy S24 Ultra exposed the Night camera extension (used by Instagram) | Medium |
| Khronos Vulkanised 2026, "Vulkan on Android" | VP_ANDROID_16 profile requirements | Medium |

## Key findings

Each finding states its source, its confidence, what it means for Luma, and what still has to be checked.

### F1. Default-mode RAW is limited to the binned sensor resolution
- **Finding:** With the RAW capability, the largest `RAW_SENSOR` stream equals `SENSOR_INFO_PIXEL_ARRAY_SIZE` or `PRE_CORRECTION_ACTIVE_ARRAY_SIZE` in the *default* pixel mode. On binning sensors the default mode is binned.
- **Source:** AOSP metadata definitions (RAW capability, `sensor.pixelMode`).
- **Confidence:** High.
- **Implication:** The 200MP main sensor will most likely give a binned RAW (expected about 12.5MP for Tetra²pixel 4×4 binning) unless the HAL also exposes `ULTRA_HIGH_RESOLUTION_SENSOR`.
- **Validation:** Read `SENSOR_INFO_PIXEL_ARRAY_SIZE` and the `RAW_SENSOR` sizes of every camera on the device.

### F2. Full-resolution RAW needs the ultra-high-resolution capability
- **Finding:** Full-resolution RAW requires `REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR` (API 31), `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION`, and a `RAW_SENSOR` entry in `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION`. Outputs from the two maps cannot be mixed in one request unless both maps list them. If `REMOSAIC_REPROCESSING` is absent, max-resolution RAW already has a regular Bayer pattern. If it is present, RAW can arrive in the binning-factor pattern (`SENSOR_RAW_BINNING_FACTOR_USED` says which).
- **Source:** AOSP metadata definitions.
- **Confidence:** High.
- **Implication:** 200MP native RAW is possible only if Samsung exposes this capability to third-party apps. Nothing found confirms that it does.
- **Validation:** Check capabilities and the max-resolution stream map on the device.

### F3. Physical cameras can be hidden but still reachable
- **Finding:** Since API 29, physical cameras can be hidden from `getCameraIdList()` yet still be queried and streamed through their logical camera. RAW from a logical camera comes from the active physical camera, reported in `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`.
- **Source:** AOSP metadata definitions; developer.android.com multi-camera guide.
- **Confidence:** High.
- **Implication:** The camera inventory must enumerate `physicalCameraIds`, not only the ID list. Lens selection must read the active physical ID from capture results.
- **Validation:** Run the inventory on the device.

### F4. Zoom and lens-switching APIs by level
- **Finding:** `CONTROL_ZOOM_RATIO` (API 30), `CONTROL_SETTINGS_OVERRIDE_ZOOM` (API 34), `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION` (API 35), `CONTROL_ZOOM_METHOD` (API 36) and `LOGICAL_MULTI_CAMERA_ADDITIONAL_RESULTS` (API 37) are the public tools for zoom and lens-switch control. `MultiResolutionImageReader` (API 31; listener added in API 37) gives variable-size stills from logical cameras.
- **Source:** SDK `api-versions.xml`; AOSP.
- **Confidence:** High.
- **Implication:** Luma can watch which lens and which crop the HAL picks. It cannot force a specific physical lens through the logical camera except by opening physical streams directly.
- **Validation:** Check which of these keys the device lists in its available request and result keys.

### F5. DngCreator covers native RAW but not computed RAW
- **Finding:** DngCreator writes DNG 1.4 with ColorMatrix, CameraCalibration and ForwardMatrix for both illuminants, NoiseProfile, BaselineExposure, a bad-pixel list, a WarpRectilinear distortion opcode and a GainMap shading opcode. The GainMap is written only when the capture result contains a lens shading map, which requires `STATISTICS_LENS_SHADING_MAP_MODE_ON`. It supports max-resolution mode.
- **Source:** AOSP DngCreator.cpp.
- **Confidence:** High.
- **Implication:** Native RAW can be saved with DngCreator. Luma must turn on the shading map for RAW captures. Computed outputs (merged or super-resolved) need Luma's own DNG writer, because DngCreator only accepts a camera `Image`.
- **Validation:** Open DNGs from the device in two independent RAW converters.

### F6. Main sensor
- **Finding:** The main camera is a 200MP ISOCELL HP2, 1/1.3", 0.6µm, 23mm equivalent, f/1.4 (S25 Ultra: f/1.7), OIS, Super QPD. Tetra²pixel binning gives 50MP at 1.2µm and 12.5MP at 2.4µm.
- **Source:** DXOMARK; Notebookcheck; Samsung Semiconductor.
- **Confidence:** Medium–High.
- **Implication:** Expected default-mode RAW is about 12.5MP. No 24MP or 48MP sensor readout mode exists, so Samsung's 24MP mode is computational.
- **Validation:** Pixel array sizes and stream maps on the device.

### F7. Telephoto modules
- **Finding:** 3x: 10MP Samsung S5K3LD, 1/3.94", 1.0µm, 67–69mm, f/2.4, OIS. 5x: 50MP Sony IMX854, 1/2.52", 0.7µm, 111–115mm, f/2.9, OIS. The sources disagree on focal length by 2–4mm.
- **Source:** DXOMARK; Notebookcheck.
- **Confidence:** Medium.
- **Implication:** The 3x module is the weakest hardware in the system, and software cannot add resolution it does not capture.
- **Validation:** `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`, `SENSOR_INFO_PHYSICAL_SIZE` and the computed 35mm equivalent.

### F8. Ultra-wide module
- **Finding:** 50MP, f/1.9, about 13mm (120°), dual-pixel PDAF, so it has autofocus and can focus close. DXOMARK lists it as 1/2.5" with 0.7µm pixels. Samsung's published JN3 specification is a different format, so the sensor identity is uncertain.
- **Source:** DXOMARK; Notebookcheck.
- **Confidence:** Medium (module), Low (sensor identity).
- **Implication:** The ultra-wide is the close-focus (macro) camera.
- **Validation:** `LENS_INFO_MINIMUM_FOCUS_DISTANCE` and the AF modes on the device.

### F9. Reported 3x blur
- **Finding:** Owners reported blurry 3x shots, particularly close-ups, in bright light and with flash. Samsung support said the issue would be fixed in a future software update. Reports conflict on whether the April 2026 update fixed it. The cause was variously described as a lens-transition processing error or as parallax and AF movement.
- **Source:** Android Central (2026-04-04); aggregators.
- **Confidence:** Low–Medium.
- **Implication:** See [TELEPHOTO_INVESTIGATION.md](TELEPHOTO_INVESTIGATION.md). Luma must make the active lens and crop visible and must not assume the 3x module is always used at "3x".
- **Validation:** Controlled test on current firmware.

### F10. Focus Enhancer crop bug
- **Finding:** In the stock app, close-up shots at 24MP with Focus Enhancer (automatic switch to the ultra-wide for close focus) were cropped at the right and bottom edges compared with the viewfinder. Samsung acknowledged this, and the second April 2026 update reportedly fixed it.
- **Source:** SamMobile (2026-04-29, 2026-04-30); SammyFans.
- **Confidence:** Medium.
- **Implication:** This is a stock-app bug, not a hardware property. Luma's own close-focus switching must guarantee that preview framing matches capture framing.
- **Validation:** Framing test (see [MACRO_INVESTIGATION.md](MACRO_INVESTIGATION.md)).

### F11. SoC and memory
- **Finding:** Snapdragon 8 Elite Gen 5 for Galaxy (2× Oryon Gen 3 Prime at 4.7 GHz, 6× at 3.6 GHz), Adreno 840, 12 GB LPDDR5X (16 GB on the 1 TB model). Notebookcheck reports that sustained performance is limited by a cooling policy focused on surface temperature.
- **Source:** Notebookcheck.
- **Confidence:** Medium.
- **Implication:** GPU compute is available, but thermal headroom must be managed.
- **Validation:** `vkjson` dump and thermal profiling.

### F12. Vulkan 1.4 baseline
- **Finding:** Devices launching with Android 16 must support Vulkan 1.4. The S26 Ultra launched with Android 16 (One UI 8.5).
- **Source:** source.android.com Vulkan implementation page; Notebookcheck (launch OS).
- **Confidence:** Medium–High.
- **Implication:** Luma can target Vulkan 1.3/1.4 compute features without fallbacks on the target device.
- **Validation:** `adb shell cmd gpu vkjson`.

### F13. Current OS
- **Finding:** One UI 9 (Android 17, API 37) stable reached the S26 series from 2026-09-16.
- **Source:** Android Central, SamMobile, 9to5Google.
- **Confidence:** Medium.
- **Implication:** compileSdk and targetSdk 37. API 37 Camera2 additions are usable.
- **Validation:** `ro.build.version.sdk` on the device.

### F14. No camera SDK for third parties
- **Finding:** Samsung offers no public camera SDK for third-party apps. Third-party access goes through Camera2 and the vendor's camera extensions. Samsung has exposed the Night extension on S24-generation devices. Lens access for third-party apps has varied between firmware versions (S24 Ultra 3x access reportedly broke and was restored).
- **Source:** Samsung Developer Forum; Samsung Community; 9to5Google.
- **Confidence:** Low–Medium.
- **Implication:** Luma must treat every lens and capability as unknown until the device has been probed, and must handle capabilities changing after firmware updates.
- **Validation:** Re-run the inventory after every firmware update.

### F15. Stock 24MP mode is computational
- **Finding:** The stock 24MP mode is enabled through the Camera Assistant app and "uses more of the sensor than 12MP while still applying some pixel binning". The 200MP mode locks the camera to the wide lens.
- **Source:** SamMobile.
- **Confidence:** Medium.
- **Implication:** There is no evidence of a native 24MP sensor mode. Luma's 24MP output must be labeled as computational.
- **Validation:** Pixel array sizes on the device.

### F16. DXOMARK weaknesses
- **Finding:** DXOMARK scored photo Exposure 125, Color 130 and Artifacts 75. It noted exposure varying between consecutive shots (low light, backlit), highlight tone compression creating "flat, unnatural-looking spots", lower tele texture than the S25 Ultra, and strong white balance. Notebookcheck noted over-sharpened contours.
- **Source:** DXOMARK; Notebookcheck.
- **Confidence:** Medium.
- **Implication:** These are the most promising areas for Luma to differentiate on: exposure consistency, highlight roll-off and restrained sharpening. Luma should not try to compete on white balance first.
- **Validation:** Luma's own controlled comparisons ([BENCHMARKING.md](BENCHMARKING.md)).

### F17. LibRaw is not needed for capture
- **Finding:** LibRaw (dual LGPL-2.1 / CDDL-1.0, version 0.22.3, released 2026-10-26) decodes RAW *files*. Camera2 delivers `RAW_SENSOR` buffers and metadata directly.
- **Source:** github.com/LibRaw/LibRaw (COPYRIGHT file, releases).
- **Confidence:** High.
- **Implication:** Luma does not need LibRaw in the app. See [DEPENDENCIES_AND_LICENSES.md](DEPENDENCIES_AND_LICENSES.md).
- **Validation:** None needed.

### F18. API 36 adds native shutter/ISO priority and Kelvin white balance
- **Finding:** `CONTROL_AE_PRIORITY_MODE` (sensitivity or exposure-time priority, API 36) and `COLOR_CORRECTION_MODE_CCT` with Kelvin and tint (API 36) are platform features.
- **Source:** SDK `api-versions.xml`; AOSP.
- **Confidence:** High (API), To verify (device support).
- **Implication:** Pro mode can offer shutter priority and ISO priority without Luma running its own AE loop, if the device lists the modes.
- **Validation:** `CONTROL_AE_AVAILABLE_PRIORITY_MODES` and `COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE` on the device.

## Device findings (read-only ADB discovery, 2026-11-18)

Source for F19–F30: `adb shell dumpsys media.camera` (camera service copy of HAL static metadata), `getprop`, `pm list features`, `cmd gpu vkjson` on SM-S948B / S948BXXS6BZK1. Confidence is **High for the HAL-advertised value**. Whether an ordinary app sees the same value is **Unverified** unless stated.

### F19. Camera topology
- **Finding:** Logical back camera **0** (LEVEL_3) groups physical cameras **2** (ultra-wide), **5** (main), **6** (3x), **7** (5x). Front cameras are **1** (12MP) and **3** (7MP crop). IDs 4 and 90 are secure-only front cameras. IDs 20/21/23/24 (logical) and 52/54/56/58 (single) are vendor duplicates that the camera service does not count as public. The service counts 4 public cameras, and API1 lists 0, 1 and 3.
- **Implication:** Luma's back camera is logical camera 0. Lens identity comes from `activePhysicalId`.
- **Validation:** The app-level `getCameraIdList()` (expected: 0, 1, 2, 3).

### F20. No high-resolution readout for apps
- **Finding:** No camera has `ULTRA_HIGH_RESOLUTION_SENSOR` or `REMOSAIC_REPROCESSING`. `sensor.pixelMode` is not a request key. Max-resolution pixel arrays equal the default arrays (4080 × 3060). Max-resolution stream maps list only 1920 × 1080 processed outputs.
- **Implication:** Native RAW is 12.5MP (10MP on 3x). 200MP/50MP are not available to Luma. 2× and 10× are digital crops. 24MP is reconstruction only.
- **Validation:** Re-check after firmware updates.

### F21. Samsung-private high-resolution configurations
- **Finding:** Vendor tags (`samsung.android.scaler.availableHighresRawStreamConfigurations`, `…ExpertRaw…`, `…ProRaw…`) list 16320 × 12240 and 8160 × 6120 RAW10 on the main camera, and 8160 × 6120 on the 5x and ultra-wide, on internal IDs 52–58.
- **Implication:** Confirms the hardware readouts exist, and that the stock app reaches them privately. Not usable by Luma: the sizes are outside the public stream maps, and depending on undocumented vendor tags is rejected (TD-19).
- **Validation:** None (informational).

### F21a. 35 mm equivalents and native zoom points
- **Finding:** Computed from metadata: main 23.0 mm, ultra-wide 13.3 mm (0.58×), 3x 66.4 mm (2.89×), 5x 112.7 mm (4.90×). This resolves the earlier 67–69 mm and 111–115 mm discrepancy in F7.

### F22. Minimum focus distances (APPROXIMATE)
- **Finding:** Main 0.10 m, ultra-wide 0.05 m, 3x **0.40 m**, 5x **0.80 m**, front 0.20 m.
- **Implication:** Any 3× subject closer than about 0.4 m cannot be served by the 3x module. This is the mechanical basis of the close-up 3× quality drop (F9).
- **Validation:** Measure focus reach in Phase B.

### F23. Telephoto analog gain is low
- **Finding:** Max analog ISO is 371 on the 3x and 203 on the 5x, against 1200 on the main camera. The ISO range on every lens is nominally up to 3200.
- **Implication:** Telephoto low light should stay at or below the analog limit and merge more frames.

### F24. RAW properties
- **Finding:** 10-bit (white level 1023). `lensShadingApplied = TRUE` (the shading map in each result is the residual). No hot-pixel map. Dual-illuminant matrices (D65, Standard A). CFA GBRG (main, 3x), GRBG (ultra-wide, 5x), RGGB (front).
- **Implication:** These change details of the engine's normalization, defect and shading stages.

### F25. Camera 0 request and session limits
- **Finding:** No physical-camera request keys. One RAW output stream. `multiResolutionStreamSupported = FALSE`. Settings override OFF only. `PER_FRAME_CONTROL`. Pipeline depth 8. AE regions 1, AWB 0, AF 1. Logical OIS modes OFF only (physical modules list ON).

### F26. Pro-mode support
- **Finding:** `MANUAL_SENSOR` and `MANUAL_POST_PROCESSING` on all user-facing cameras. AE priority modes (ISO and shutter priority). `COLOR_CORRECTION_MODE_CCT` with a 2300–10000 K range. Tonemap CONTRAST_CURVE. Max exposure 176–274 ms depending on the lens.
- **Implication:** Pro mode is feasible on every lens. Exposure longer than about 0.25 s is not available, so night photography must use multi-frame.

### F27. Output formats
- **Finding:** RAW16/RAW10 (RAW12 on 3x), JPEG, YUV and P010 up to the native size. The HAL advertises JPEG_R on camera 0. No HEIC configurations. `ro.camera.disableJpegR=true` disables only framework derivation (AOSP `CameraProviderManager::deriveJpegRTags`).

### F28. Extensions present but unenumerated
- **Finding:** `ro.camerax.extensions.enabled=true` and the `com.samsung.android.cameraxservice` feature are present. Modes cannot be enumerated over ADB.

### F29. Compute platform
- **Finding:** SM8850, CPU 6 × 3.63 GHz + 2 × 4.74 GHz, Adreno 840 with Vulkan 1.4.295. `VK_ANDROID_external_memory_android_hardware_buffer`, float16/16-bit storage, timeline semaphores, host image copy. 32 KiB shared memory. maxImageDimension2D 16384. 11.35 GB RAM. arm64-only.
- **Implication:** The Vulkan baseline can be 1.3+ with these extensions. Single-image dimensions above 16384 must be tiled (not an issue at 12.5MP).

### F30. OS
- **Finding:** Android 17 (API 37), One UI 9.0, security patch 2026-11-05. Vendor image based on Android 16. First API level 36. Confirms F13.

## Open research items

1. ~~What the S26 Ultra reports for every camera~~: answered at HAL level (F19–F27). App-level confirmation is pending (validation plan, Phase A).
2. Whether repeating `RAW_SENSOR` at 30 fps alongside preview is sustainable (the configuration allows it). Phase B.
3. Which camera extensions (Night, Bokeh, HDR, Auto, Face Retouch, Android 17 vendor-defined) are exposed to third-party apps. Phase A.
4. Whether physical RAW streams of camera 0 (for example the 3x at 1× logical zoom) can be configured. Phase A (session query) / Phase B.
5. Whether the 3× close-up blur still reproduces on S948BXXS6BZK1, and which physical camera is active when it does. Phase B.
6. Actual stabilization behavior of logical-camera frames, given that only OIS OFF is advertised on camera 0. Phase B.
