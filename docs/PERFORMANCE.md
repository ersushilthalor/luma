# Performance

All targets below are **proposed**. None has been measured. They are replaced by measured baselines (Luma and stock) in Milestone 2 and revised with evidence.

## Targets (proposed)

| Metric | Target | How measured |
|---|---|---|
| Cold start → first preview frame | p50 ≤ 700 ms, p90 ≤ 1000 ms | Macrobenchmark (StartupTimingMetric + custom trace section) |
| Preview frame rate | Steady 30 fps. Dropped frames < 1 % during processing jobs | Perfetto (camera and SurfaceFlinger tracks) |
| Shutter lag (press → reference frame start-of-exposure) | ≤ 50 ms with RAW ring buffer, ≤ 150 ms without | Trace: input event timestamp vs `SENSOR_TIMESTAMP` of the reference frame |
| Shot-to-shot, single-frame mode | ≤ 300 ms | Trace |
| 12.5MP, 8-frame merge → JPEG | p50 ≤ 1.5 s (GPU path) | Engine timing + trace |
| 24MP super-resolution (10 frames) | p50 ≤ 4 s | Engine timing |
| Peak native memory during processing | ≤ 1.2 GB | `dumpsys meminfo`, Perfetto heap profile |
| Sustained shooting | 10 min at one capture per 5 s, 25 °C ambient: no failed captures, processing time growth ≤ 50 % | Thermal status log + timings |
| Battery | Baseline first (Luma vs stock, 30 min preview with one capture per minute). Target set after baseline | ODPM power rails via Perfetto |

## Memory

| Item | Size |
|---|---|
| 12.5MP RAW16 frame | ~25 MB |
| 8-frame RAW ring buffer (gralloc, outside the Java heap) | ~200 MB |
| 10-frame burst for merge | ~250 MB |
| 12.5MP float32 RGB intermediate | ~150 MB (float16 on GPU: ~75 MB) |
| 200MP RAW16 frame | ~400 MB (not reachable through the public API on the S26 Ultra) |

Device budget (verified): 11.35 GB kernel-visible RAM plus 16 GB swap. Swap must never be relied on for image buffers.

Rules:

- Camera frames stay in `ImageReader` buffers until the engine has consumed them. No Java-heap copies of image data. Frames reach native code through direct `ByteBuffer`s.
- The engine allocates from a pool sized per session (burst length × frame size + working set). It refuses a plan that does not fit, instead of degrading at random.
- Large stages work in tiles. Full-frame float32 intermediates are allowed only in the CPU reference path.
- `onTrimMemory` and `ActivityManager.MemoryInfo` reduce ring buffer depth and burst length under memory pressure.

## Thermal and power

- Read `PowerManager.getThermalHeadroom()` (API 30) and listen with `addThermalStatusListener`. The capture planner scales frame counts and disables optional stages (super-resolution, max-resolution capture) as headroom shrinks. The camera UI shows when quality is reduced for thermal reasons.
- Use ADPF performance hint sessions (`APerformanceHintManager`, NDK) for engine worker threads, so the scheduler knows the work duration and target.
- Preview runs at the lowest resolution that looks sharp on the display. The analysis stream runs at reduced rate (10–15 Hz).
- Camera sessions are closed promptly on `onStop`. No background camera use.

## CPU and GPU

- **CPU first.** Every stage starts as a multithreaded C++ implementation (NEON via auto-vectorization or intrinsics where profiling justifies) and serves as the correctness reference.
- **Vulkan when measured.** A stage moves to Vulkan compute when all of these hold:
  1. Its CPU implementation exceeds its latency budget on the target device.
  2. A GPU prototype is at least 3× faster **including** upload and download.
  3. Output matches the CPU reference within a defined tolerance (max abs error and PSNR on test bursts).
- **Expected first GPU stages:** alignment and merge, then local tone mapping and demosaic (see [IMAGE_PIPELINE.md](IMAGE_PIPELINE.md#cpu-vs-gpu-plan)).
- **Vulkan baseline:** 1.3 core with compute, timeline semaphores and 16-bit storage. Verified on the S26 Ultra (2026-11-18): Adreno 840, Vulkan 1.4.295, with float16, 16-bit storage, timeline semaphores, host image copy, AHardwareBuffer import, subgroup size control and cooperative matrix. 32 KiB shared memory per workgroup. Shaders are GLSL compiled to SPIR-V at build time with the NDK's `glslc`. The `libvulkan.so` system loader is used, with no third-party wrapper.
- **No marketing-driven GPU usage.** If the CPU path meets the budget, it stays on the CPU.

## Background processing

1. On shutter, RAW frames needed for processing are written to app-internal storage (`noBackupFilesDir/jobs/<id>/`) before heavy processing starts, unless processing finishes within a short grace period.
2. The processing job runs in the app process on the engine's thread pool. When a job starts while the app is in the foreground, a `shortService` foreground service keeps the process alive if the user leaves (Android limits this type to a few minutes, which fits per-photo processing).
3. On the next app start, unfinished jobs are resumed. Jobs older than a retention limit (default 7 days) are discarded, and the user is told.
4. Processing is never started from the background on its own.

## Profiling toolkit

| Tool | Use |
|---|---|
| Perfetto | System traces: camera HAL, GPU, CPU frequency, thermal, power rails |
| androidx.tracing | App trace sections (Kotlin) |
| ATrace via NDK (`ATrace_beginSection`) | Engine trace sections |
| simpleperf | Native CPU profiles |
| Android GPU Inspector / Snapdragon Profiler | Vulkan compute timing |
| Macrobenchmark | Startup and capture-flow benchmarks (separate module, added in Milestone 12) |
