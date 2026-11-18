#include "luma/engine/resample.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "internal.h"

namespace luma::engine {
namespace {

constexpr float kPi = 3.14159265358979f;
constexpr int kLobes = 3;

float sinc(float x) {
    if (std::fabs(x) < 1e-6f) return 1.0f;
    const float px = kPi * x;
    return std::sin(px) / px;
}

float lanczos(float x) {
    if (std::fabs(x) >= static_cast<float>(kLobes)) return 0.0f;
    return sinc(x) * sinc(x / static_cast<float>(kLobes));
}

/// Normalised filter taps for one axis: for each destination index, the first source index and weights.
struct Taps {
    int width = 0;  // taps per destination sample
    std::vector<int> first;
    std::vector<float> weights;
};

Taps make_taps(int src, int dst) {
    Taps t;
    const float scale = static_cast<float>(dst) / static_cast<float>(src);
    const float support = scale < 1.0f ? kLobes / scale : static_cast<float>(kLobes);
    const float stretch = scale < 1.0f ? scale : 1.0f;  // widen the kernel when shrinking
    t.width = static_cast<int>(std::ceil(support)) * 2 + 1;
    t.first.resize(static_cast<size_t>(dst));
    t.weights.resize(static_cast<size_t>(dst) * static_cast<size_t>(t.width));
    for (int d = 0; d < dst; ++d) {
        const float center = (static_cast<float>(d) + 0.5f) / scale - 0.5f;
        const int first = static_cast<int>(std::floor(center - support)) + 1;
        t.first[static_cast<size_t>(d)] = first;
        float sum = 0.0f;
        float* w = &t.weights[static_cast<size_t>(d) * static_cast<size_t>(t.width)];
        for (int k = 0; k < t.width; ++k) {
            w[k] = lanczos((static_cast<float>(first + k) - center) * stretch);
            sum += w[k];
        }
        if (sum != 0.0f) for (int k = 0; k < t.width; ++k) w[k] /= sum;
    }
    return t;
}

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

}  // namespace

bool resample_rgba8(const uint8_t* src, int src_w, int src_h, int src_stride,
                    uint8_t* dst, int dst_w, int dst_h, int dst_stride) noexcept {
    if (!src || !dst || src_w <= 0 || src_h <= 0 || dst_w <= 0 || dst_h <= 0) return false;
    if (src_stride < src_w * 4 || dst_stride < dst_w * 4) return false;
    try {
        const Taps hx = make_taps(src_w, dst_w);
        const Taps vy = make_taps(src_h, dst_h);
        // Rows are handled in bands so each worker reuses one vertical-pass buffer.
        constexpr int kBand = 16;
        const int bands = (dst_h + kBand - 1) / kBand;
        detail::parallel_for(0, bands, [&](int band) {
            std::vector<float> row(static_cast<size_t>(src_w) * 4);
            const int y0 = band * kBand;
            const int y1 = std::min(dst_h, y0 + kBand);
            for (int y = y0; y < y1; ++y) {
                // Vertical pass: one source-width row of float RGBA.
                std::fill(row.begin(), row.end(), 0.0f);
                const float* wv = &vy.weights[static_cast<size_t>(y) * static_cast<size_t>(vy.width)];
                const int fy = vy.first[static_cast<size_t>(y)];
                for (int k = 0; k < vy.width; ++k) {
                    const float w = wv[k];
                    if (w == 0.0f) continue;
                    const uint8_t* s = src + static_cast<size_t>(clampi(fy + k, 0, src_h - 1)) * static_cast<size_t>(src_stride);
                    for (int i = 0; i < src_w * 4; ++i) row[static_cast<size_t>(i)] += w * static_cast<float>(s[i]);
                }
                // Horizontal pass into the destination row.
                uint8_t* out = dst + static_cast<size_t>(y) * static_cast<size_t>(dst_stride);
                for (int x = 0; x < dst_w; ++x) {
                    const float* wh = &hx.weights[static_cast<size_t>(x) * static_cast<size_t>(hx.width)];
                    const int fx = hx.first[static_cast<size_t>(x)];
                    float acc[4] = {0.0f, 0.0f, 0.0f, 0.0f};
                    for (int k = 0; k < hx.width; ++k) {
                        const float w = wh[k];
                        const float* p = &row[static_cast<size_t>(clampi(fx + k, 0, src_w - 1)) * 4];
                        acc[0] += w * p[0];
                        acc[1] += w * p[1];
                        acc[2] += w * p[2];
                        acc[3] += w * p[3];
                    }
                    for (int c = 0; c < 4; ++c) out[x * 4 + c] = static_cast<uint8_t>(clampi(static_cast<int>(acc[c] + 0.5f), 0, 255));
                }
            }
        });
        return true;
    } catch (...) {
        return false;
    }
}

}  // namespace luma::engine
