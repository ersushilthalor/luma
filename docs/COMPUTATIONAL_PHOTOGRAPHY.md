# Computational photography

Luma relies on techniques with published, reproducible foundations. AI and learned components are optional and never required for a photo to be produced.

## Technique selection

| Technique | Basis | Use in Luma | Status |
|---|---|---|---|
| Burst merge (denoise + dynamic range) | HDR+ (Hasinoff et al. 2016). IPOL reference implementation (Monod et al. 2021) | Core of Photo mode in medium and low light, and in high-contrast scenes | Proposed (Milestone 7) |
| Underexposed burst for highlight protection | HDR+: capture all frames at the same short exposure, merge, then tone map up | High-contrast scenes: protects highlights without bracketing ghosts | Proposed |
| Exposure bracketing (adding long frames) | Google "HDR with bracketing" (2021): a few longer frames added to a short burst for shadows | Extreme dynamic range when shadow SNR from short frames is insufficient | Proposed (after merge is stable) |
| Motion-aware capture scheduling | Night Sight (Liba et al. 2019): motion metering picks per-frame exposure from measured motion | Night mode and low light: longer frames only when hand and scene motion allow | Proposed (Milestone 10) |
| Multi-frame super-resolution | Wronski et al. 2019 | 24MP reconstructed output and telephoto detail | Experimental |
| Local tone mapping | Local Laplacian filters (Paris et al. 2011). HDR+ synthetic exposure fusion as the alternative | Shadow lift and highlight compression without halos | Proposed |
| Display-referred exposure fusion | Mertens et al. 2009 | Not used on final output (operates on rendered images, ghosts with motion). Kept as a quality reference only | Not used |
| Face-aware metering | Platform face detection (`STATISTICS_FACE_DETECT_MODE`) | Exposure target and tone-mapping protection on faces | Proposed |
| Learned denoise / segmentation / portrait depth | Various | Portrait background separation if no Bokeh extension. Never in the default path | Deferred |

## Burst capture plan

The **capture planner** turns scene analysis into a plan:

```
CapturePlan(
  camera:          physical camera + pixel mode
  frames:          [FrameSpec(exposureNs, iso, isReference?)]
  merge:           NONE | BURST_MERGE | BRACKET_MERGE | SUPER_RES
  toneTarget:      exposure system output
  maxLatencyMs:    UI budget for this mode
)
```

| Scene class | Frames | Rationale |
|---|---|---|
| Bright, low dynamic range, static or moving | 1 (or 2–3 short frames merged if a ZSL ring buffer exists) | A good single frame is the default. Merge only when it costs nothing in latency |
| High dynamic range | 4–8 identical short frames (exposure set by highlight protection) | Same exposure means robust alignment and no bracket ghosting |
| High DR with deep shadows | 4–8 short frames + 1–2 long frames | Long frames weighted only where short frames are noise-limited and motion is low |
| Indoor / low light, static | 6–12 frames, per-frame exposure from motion metering | Noise reduction proportional to √N |
| Moving subject detected | Fewer, shorter frames. Merge robustness raised | Motion consistency over noise |
| Night (explicit mode) | Up to ~15 frames, total time up to a few seconds handheld | User-chosen trade-off. Shows progress |

Frame counts and exposure limits are starting points to be tuned on the device. They are not measured values.

## Avoiding artifacts

| Artifact | Cause | Mitigation |
|---|---|---|
| Ghosting | Merging misaligned or moving content | Same-exposure bursts. Per-tile robustness (Wiener shrinkage toward the reference when frames disagree beyond the noise model). Region fallback to the reference frame. Ghost-pixel fraction logged |
| Halos | Local tone mapping with large spatial kernels | Local Laplacian (edge-aware by construction). Strength caps at strong edges. Measured with an edge-overshoot metric |
| Smearing / plastic texture | Over-aggressive spatial denoise | Most noise removal comes from merging, which preserves real detail. Spatial denoise is residual and noise-model driven. Fine-grain luma noise is allowed to remain at low levels |
| False detail | Learned enhancement, or sharpening of noise | No learned enhancement in the default path. Sharpening only on luminance, with overshoot clamp, after denoise |
| Unnatural skin | Denoise and sharpening on faces. Saturation boost | Face regions get reduced sharpening and no saturation boost (see [COLOR_SCIENCE.md](COLOR_SCIENCE.md)) |
| Excessive sharpening | Fixed high-gain sharpening | Output-scale-dependent, low by default, measured by edge overshoot |
| Super-resolution artifacts (zippering, false color) | Insufficient sub-pixel coverage or misregistration | Coverage map. Fall back to native resolution if coverage is below threshold |

## What "better than stock" means here

The stock pipeline is a mature, privileged, multi-frame, AI-assisted system with access to sensor HDR modes Luma cannot use. Luma's realistic advantages are:

1. **Consistency:** deterministic exposure decisions, with every capture's plan and parameters logged. DXOMARK reports shot-to-shot exposure variation on stock.
2. **Highlight rendering:** an explicit, documented soft shoulder and hue-preserving desaturation.
3. **Restraint:** lower sharpening and no texture synthesis. Notebookcheck reports over-sharpening on stock.
4. **Transparency:** honest labels for the source lens, crop and processing class.
5. **RAW-centric output:** the processed image and the DNG come from the same data.

Luma will likely **not** beat stock in fast-moving low-light scenes (stock uses sensor-level HDR readouts and privileged ZSL) or in portrait segmentation, unless measurements say otherwise. These are recorded as expected weaknesses, not hidden.
