# Development roadmap

Milestones are ordered by technical dependency and sized for one developer. Sizes are relative (S ≈ 1–2 weeks, M ≈ 2–4 weeks, L ≈ 4–8 weeks of focused work). They are estimates, not commitments. No feature is promised before its hardware verification step has passed.

```
M0 Foundation ─▶ M1 Hardware verification ─▶ M2 Capture lab + baselines
                                             │
               ┌─────────────────────────────┴───────────────┐
               ▼                                             ▼
        M3 Camera core + preview                   M7 Engine v1 (desktop, CPU)
               ▼                                             │
        M4 Lens selection + zoom                             │
               ▼                                             │
        M5 Still capture + storage                           │
               ▼                                             │
        M6 RAW / DNG + first usable build ◀──────────────────┘
               ▼
        M8 Color & rendering ─▶ M9 Exposure intelligence ─▶ M10 Computational photography + GPU
               ▼
        M11 Pro mode ─▶ M12 Gallery, settings, diagnostics complete ─▶ M13 Night
               ▼
        M14 Portrait / Panorama research ─▶ M15 Performance ─▶ M16 Benchmark & refine (continuous)
```

---

### M0: Foundation ✅ (this task)
- **Objective:** Research, specification, decisions, buildable project.
- **Deliverables:** `spec.md`, `docs/*`, Gradle/AGP/Kotlin/Compose/NDK/CMake project, JNI bridge, camera inventory tool, adb discovery script, CI workflow.
- **Acceptance:** `assembleDebug`, `assembleRelease` and `lint` pass locally (verified 2026-11-18). CI workflow defined (it has not run, because nothing has been pushed).

### M1: Hardware capability verification (S): in progress
- **Status (2026-11-18):** Read-only HAL discovery done ([DEVICE_DISCOVERY_S26_ULTRA.md](DEVICE_DISCOVERY_S26_ULTRA.md)). The app-level view and capture tests follow [HARDWARE_VALIDATION_PLAN.md](HARDWARE_VALIDATION_PLAN.md) Phases A and B, each after the owner's authorization. The phone stays read-only until then.
- **Objective:** Replace "to verify" with facts.
- **Deliverables:** `hardware-reports/` from `collect.sh` and the in-app inventory, for current firmware. [CAMERA_HARDWARE.md](CAMERA_HARDWARE.md) "Verified on device" column filled. Statuses updated in `spec.md`.
- **Dependencies:** M0. The S26 Ultra.
- **Acceptance:** Every camera (public and hidden physical) documented, with RAW sizes per pixel mode, manual-control support, extensions and minimum focus distances.
- **Risks:** Samsung hides lenses or capabilities from third-party apps. If so, scope is reduced in the spec, not worked around with fakes.
- **Hardware verification:** This *is* the hardware verification.

### M2: Capture lab and baselines (M)
- **Objective:** Measure what Camera2 can actually do on the device, and build a RAW corpus for the engine.
- **Deliverables:** A debug-only "Lab" screen: open any camera or physical stream, test stream combinations, repeating RAW frame rate, max-resolution capture latency, active physical ID versus zoom and distance (the telephoto distance sweep). A RAW burst corpus (with metadata JSON) for engine development. A first stock-app baseline for the benchmark scenes.
- **Dependencies:** M1.
- **Acceptance:** Answers recorded for the open research items in [RESEARCH.md](RESEARCH.md#open-research-items).
- **Risks:** Repeating RAW not sustainable at 30 fps. Then ZSL falls back to capture-after-shutter and the latency targets are revised.

### M3: Camera core and live preview (M)
- **Objective:** Production camera session with a correct, smooth preview.
- **Deliverables:** `CameraSession` state machine (open, configure, repeat, reconfigure, close, error recovery), `LensCatalog`, permission flow, preview surface with correct transform in all orientations, lifecycle handling. The camera screen layout (all controls placed; unimplemented ones visibly disabled with a reason).
- **Dependencies:** M1, M2.
- **Acceptance:** Cold start to preview within the proposed target, or the measured value recorded. No leaks across 100 open/close cycles. Correct aspect in all orientations.
- **Risks:** Session reconfiguration latency on lens or pixel-mode switches.

### M4: Lens selection and zoom (M)
- **Objective:** All physical cameras reachable through presets and pinch, with honest source labels.
- **Deliverables:** `CameraSelector` with scoring and hysteresis. Zoom presets 0.6/1/2/3/5/10. Pinch-to-zoom. Source badge (optical / sensor crop / digital). Close-focus suggestion.
- **Dependencies:** M3.
- **Acceptance:** No flapping in a slow zoom sweep. The source label matches `ACTIVE_PHYSICAL_ID` and the crop in 100 % of logged frames. Framing jump at preset changes documented.
- **Risks:** Physical streams unavailable for some lenses: those lenses are then reachable only through the logical camera's own choice.
- **Hardware verification:** Telephoto distance sweep, macro framing test.

### M5: Still capture and storage (M)
- **Objective:** Reliable single-frame capture, saved correctly.
- **Deliverables:** `core:storage` (MediaStore with `IS_PENDING`, EXIF, provenance XMP). HAL-processed JPEG/HEIF capture (labeled "processed by camera HAL" until the Luma engine path exists). Shutter, timer, flash, aspect ratio, resolution choices that the active lens supports. Gallery thumbnail.
- **Dependencies:** M4.
- **Acceptance:** 1000 consecutive captures without failure or corrupt files. EXIF validated. No location without opt-in.

### M6: RAW / DNG and first usable build (M)
- **Objective:** Native RAW and the first usable release (0.2).
- **Deliverables:** RAW toggle. DngCreator output with shading map. Output-class labels. Basic gallery viewer with metadata. Settings skeleton. Diagnostics screen (inventory + active configuration).
- **Dependencies:** M5.
- **Acceptance:** DNGs open correctly in Lightroom/ACR, darktable and RawTherapee. `dng_validate` passes. The UI is complete and coherent: every visible control works or states why it is unavailable.
- **Hardware verification:** RAW from each lens. Max-resolution RAW if exposed.

### M7: Image engine v1, CPU reference (L), in parallel with M3–M6
- **Objective:** A correct RAW-to-JPEG pipeline on the desktop and the device.
- **Deliverables:** Engine stages 1–15 (CPU) from [IMAGE_PIPELINE.md](IMAGE_PIPELINE.md). HDR+-style align and merge. Desktop CLI and tests (ASan/UBSan). Regression corpus runner. JNI submission API. Luma-processed output from single frames and bursts.
- **Dependencies:** M2 corpus.
- **Acceptance:** Merge reduces measured noise by roughly √N on static scenes with no ghosting on the motion test set. Output passes the halo, saturation and sharpening guards. Processing time measured and recorded.
- **Risks:** CPU latency far above budget. GPU work (M10) moves earlier.

### M8: Color science and rendering v1 (M)
- **Objective:** "Professional but natural" rendering.
- **Deliverables:** Per-module calibration (ColorChecker, two illuminants). Rendering curve v1. Hue-preserving highlight desaturation. Oklab look. Gamut mapping. Display P3 JPEG with ICC.
- **Dependencies:** M7.
- **Acceptance:** Color targets in [COLOR_SCIENCE.md](COLOR_SCIENCE.md#7-validation) measured. Cross-module neutral consistency within target.

### M9: Exposure intelligence (M)
- **Objective:** The adaptive exposure system.
- **Deliverables:** Scene analyzer, exposure controller (preview bias), capture planner (single / burst / bracket), face-aware metering, motion limits, decision logging.
- **Dependencies:** M5, M7.
- **Acceptance:** Repeatability ≤ 0.1 EV (proposed). Highlight-retention and face-exposure benchmarks run against stock and reported.

### M10: Computational photography and GPU (L)
- **Objective:** Multi-frame Photo mode at interactive latency.
- **Deliverables:** RAW ring buffer (if M2 allows). Vulkan align and merge (TD-07/TD-08 criteria). Bracketing. Merged-RAW DNG output. Experimental 24MP super-resolution.
- **Dependencies:** M7, M9.
- **Acceptance:** The proposed latency targets met, or measured shortfalls documented. GPU output matches the CPU reference within tolerance.

### M11: Pro mode (M)
- **Deliverables:** ISO, shutter, Kelvin WB, manual focus with peaking, EV, lens choice, RAW, resolution. Priority modes (API 36 or Luma's own). Histogram and zebras.
- **Acceptance:** Every control appears only when the active lens supports it. Values requested equal values reported in capture results.

### M12: Gallery, settings and diagnostics complete (M)
- **Deliverables:** Full gallery and viewer (navigation, metadata, output-class badges, share with location removal, delete). All settings. Diagnostics (capture plans, timings, thermal, crash records, export). Benchmark scripts in `benchmarks/`.

### M13: Night mode (M)
- **Deliverables:** Motion-metered long bursts, progress UI, an optional vendor Night extension shown as a separate labeled choice if the device exposes it.
- **Dependencies:** M10.
- **Acceptance:** Benchmarked against stock night mode at 1–10 lux. Results published either way.

### M14: Portrait and Panorama research (S, then decide)
- **Portrait:** Evaluate the Bokeh extension (if exposed), physical-stream stereo depth (unlikely), and on-device segmentation (an optional ML component). Ship only if edge quality passes a blind test.
- **Panorama:** Feasible in principle (gyro-guided capture + registration + seam blending + cylindrical projection). Scheduled only after Photo quality targets are met.

### M15: Performance and thermal (M)
- **Deliverables:** Macrobenchmark module. Startup and capture-flow traces. Thermal adaptation. Memory caps enforced.

### M16: Benchmarking and quality refinement (continuous)
- Formal comparison per [BENCHMARKING.md](BENCHMARKING.md) at each release. Findings feed back into the color, exposure and engine milestones.
