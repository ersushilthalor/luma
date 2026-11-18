#pragma once

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <thread>
#include <vector>

#include "luma/engine/raw_pipeline.h"

namespace luma::engine::detail {

// ---------------------------------------------------------------------------------------------
// Threading

inline std::atomic<int>& configured_threads() {
    static std::atomic<int> value{0};
    return value;
}

inline int worker_count() {
    const int configured = configured_threads().load();
    if (configured > 0) return configured;
    const unsigned hw = std::thread::hardware_concurrency();
    return hw == 0 ? 1 : static_cast<int>(hw);
}

/// Runs fn(i) for i in [begin, end) on a short-lived pool. `grain` indices are claimed at a time.
template <class F>
void parallel_for(int begin, int end, F&& fn, int grain = 1) {
    const int total = end - begin;
    if (total <= 0) return;
    grain = std::max(1, grain);
    const int workers = std::min(worker_count(), (total + grain - 1) / grain);
    if (workers <= 1) {
        for (int i = begin; i < end; ++i) fn(i);
        return;
    }
    std::atomic<int> next{begin};
    auto run = [&]() {
        for (;;) {
            const int start = next.fetch_add(grain);
            if (start >= end) break;
            const int stop = std::min(end, start + grain);
            for (int i = start; i < stop; ++i) fn(i);
        }
    };
    std::vector<std::thread> pool;
    pool.reserve(static_cast<size_t>(workers - 1));
    for (int t = 1; t < workers; ++t) pool.emplace_back(run);
    run();
    for (auto& thread : pool) thread.join();
}

// ---------------------------------------------------------------------------------------------
// Images

struct Plane {
    int w = 0;
    int h = 0;
    std::vector<float> v;

    Plane() = default;
    Plane(int width, int height, float fill = 0.0f)
        : w(width), h(height), v(static_cast<size_t>(width) * static_cast<size_t>(height), fill) {}

    float* row(int y) { return v.data() + static_cast<size_t>(y) * static_cast<size_t>(w); }
    const float* row(int y) const { return v.data() + static_cast<size_t>(y) * static_cast<size_t>(w); }
    float& at(int x, int y) { return v[static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)]; }
    float at(int x, int y) const { return v[static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)]; }
    float clamped(int x, int y) const {
        x = std::clamp(x, 0, w - 1);
        y = std::clamp(y, 0, h - 1);
        return at(x, y);
    }
    bool empty() const { return v.empty(); }
    void release() {
        std::vector<float>().swap(v);
        w = h = 0;
    }
};

/// 2x2 box downsample (odd edges replicate).
Plane downsample2(const Plane& src);
/// Separable box blur with radius r (clamped edges).
Plane box_blur(const Plane& src, int r);
/// Separable Gaussian blur (clamped edges).
Plane gaussian_blur(const Plane& src, float sigma);

inline float lerp(float a, float b, float t) { return a + (b - a) * t; }

inline double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

// ---------------------------------------------------------------------------------------------
// CFA helpers. Position index k = (y & 1) * 2 + (x & 1). Colour: 0 = R, 1 = G, 2 = B.

inline std::array<int, 4> cfa_colors(Cfa cfa) {
    switch (cfa) {
        case Cfa::RGGB: return {0, 1, 1, 2};
        case Cfa::GRBG: return {1, 0, 2, 1};
        case Cfa::GBRG: return {1, 2, 0, 1};
        case Cfa::BGGR: return {2, 1, 1, 0};
    }
    return {0, 1, 1, 2};
}

/// Reflects an index into [0, n) while preserving parity (keeps the CFA phase).
inline int reflect(int i, int n) {
    if (i < 0) i = -i;
    if (i >= n) i = 2 * (n - 1) - i;
    return std::clamp(i, 0, n - 1);
}

// ---------------------------------------------------------------------------------------------
// Colour maths

Mat3 mul(const Mat3& a, const Mat3& b);
std::array<float, 3> mul(const Mat3& m, const std::array<float, 3>& v);
Mat3 inverse(const Mat3& m);
Mat3 diag(float a, float b, float c);
Mat3 identity3();
bool is_zero(const Mat3& m);

/// XYZ (D50) to linear RGB of the output space (D65 white, Bradford-adapted).
Mat3 xyz_d50_to_output(OutputSpace space);
std::array<float, 3> luminance_weights(OutputSpace space);

inline float srgb_oetf(float x) {
    if (x <= 0.0031308f) return 12.92f * std::max(x, 0.0f);
    return 1.055f * std::pow(x, 1.0f / 2.4f) - 0.055f;
}
inline float srgb_eotf(float e) {
    if (e <= 0.04045f) return e / 12.92f;
    return std::pow((e + 0.055f) / 1.055f, 2.4f);
}

// ---------------------------------------------------------------------------------------------
// Stages

/// Merged 4-plane, half-resolution Bayer data (normalised, pre-shading, pre white balance).
struct MergeResult {
    std::array<Plane, 4> planes;
    int reference = 0;
    int contributing = 1;
    float mean_weight = 0;
};

struct FrameData {
    std::vector<uint16_t> mosaic;  // crop region, crop.width * crop.height
    float scale = 1.0f;            // brightness multiplier to match the reference exposure
    Plane green;                   // half-res mean green, normalised (alignment)
    std::vector<Plane> pyramid;    // green pyramid, level 0 = green
    Plane green_mean;              // half-res 5x5 box mean of green (robustness)
};

struct SensorNorm {
    std::array<float, 4> black;
    std::array<float, 4> inv_range;
};

void prepare_frame(FrameData& frame, int width, int height, const SensorNorm& norm,
                   const std::array<int, 4>& colors);
MergeResult align_and_merge(std::vector<FrameData>& frames, int width, int height, const SensorNorm& norm,
                            const std::array<int, 4>& colors, const RawMetadata& meta, bool merge,
                            double& ms_align, double& ms_merge);

/// Super-resolved, demosaiced, white-balanced camera RGB on a grid `scale` times denser than the crop.
struct SuperResOutput {
    Plane r, g, b;
    std::vector<std::array<float, 2>> shift;  // per frame: mean estimated offset to the reference (raw pixels)
    int reference = 0;
    int contributing = 1;
    float mean_weight = 0;
    /// RMS fractional raw-pixel shift between frames (~0.29 for random hand shake, ~0 on a tripod).
    float subpixel_spread = 0;
    float coverage_gap = 0;    // mean distance to the nearest green sample over the scene, raw pixels
    bool declined = false;     // coverage_gap above max_gap: nothing merged, frames untouched
};

/// Handheld multi-frame super-resolution. `gains` (per CFA position, half-res) = lens shading x white balance;
/// `clip` = per-site highlight clip (min gain).
SuperResOutput super_resolve(std::vector<FrameData>& frames, int width, int height, float scale, const SensorNorm& norm,
                             const std::array<int, 4>& colors, const RawMetadata& meta,
                             const std::array<Plane, 4>& gains, const Plane& clip, double& ms_align, double& ms_merge,
                             const std::vector<std::array<float, 2>>* known_shifts = nullptr, float max_gap = 1e9f);

/// Full-resolution demosaic of a white-balanced float mosaic into three planes.
void demosaic(const Plane& mosaic, const std::array<int, 4>& colors, Plane& r, Plane& g, Plane& b);

struct ToneResult {
    float applied_ev = 0;
    float scene_key = 0;
    float dynamic_range = 0;
};

/// Tone maps linear output-space RGB in place and leaves gamma-encoded values in [0, 1].
ToneResult tone_map(Plane& r, Plane& g, Plane& b, const ProcessOptions& options, float noise_sigma_linear);

/// Noise reduction and sharpening on gamma-encoded RGB.
void finish(Plane& r, Plane& g, Plane& b, const ProcessOptions& options, OutputSpace space, float noise_hint);

void chroma_denoise_linear(Plane& r, Plane& g, Plane& b, OutputSpace space, float strength, float noise_sigma);

Plane resize_bicubic(const Plane& src, int width, int height);

}  // namespace luma::engine::detail
