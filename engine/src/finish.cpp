// Final stages on gamma-encoded RGB: noise estimate, luminance noise reduction (cross-bilateral)
// and halo-limited, noise-cored unsharp masking. All changes are applied as a luminance delta.

#include <cmath>

#include "internal.h"

namespace luma::engine::detail {
namespace {

float estimate_noise(const Plane& e) {
    // Robust estimate: per 32x32 block median of |Laplacian| on a sparse grid; take a low percentile
    // across blocks so texture does not dominate.
    constexpr int kBlock = 32;
    std::vector<float> block_estimates;
    for (int by = 1; by + kBlock < e.h - 1; by += kBlock)
        for (int bx = 1; bx + kBlock < e.w - 1; bx += kBlock) {
            float vals[64];
            int n = 0;
            for (int y = by; y < by + kBlock && n < 64; y += 4)
                for (int x = bx; x < bx + kBlock && n < 64; x += 4) {
                    const float d = e.at(x, y) - 0.25f * (e.at(x - 1, y) + e.at(x + 1, y) + e.at(x, y - 1) + e.at(x, y + 1));
                    vals[n++] = std::abs(d);
                }
            if (n == 0) continue;
            std::nth_element(vals, vals + n / 2, vals + n);
            block_estimates.push_back(vals[n / 2] * 1.4826f / 1.118f);
        }
    if (block_estimates.empty()) return 0.0f;
    const size_t i = block_estimates.size() / 5;
    std::nth_element(block_estimates.begin(), block_estimates.begin() + static_cast<std::ptrdiff_t>(i), block_estimates.end());
    return block_estimates[i];
}

}  // namespace

void finish(Plane& R, Plane& G, Plane& B, const ProcessOptions& opt, OutputSpace space, float noise_hint) {
    (void)noise_hint;
    const int W = R.w, H = R.h;
    const auto lw = luminance_weights(space);
    Plane Y(W, H);
    parallel_for(0, H, [&](int y) {
        for (int x = 0; x < W; ++x) Y.at(x, y) = lw[0] * R.at(x, y) + lw[1] * G.at(x, y) + lw[2] * B.at(x, y);
    }, 16);
    const float sigma = estimate_noise(Y);

    // Luminance noise reduction: 5x5 cross-bilateral with range sigma from the noise estimate.
    Plane Yd = Y;
    const float nr = std::clamp(opt.luma_denoise, 0.0f, 1.0f);
    if (nr > 0.0f && sigma > 0.002f) {
        const float sr = 2.2f * sigma;
        const float inv_r = -0.5f / (sr * sr);
        parallel_for(0, H, [&](int y) {
            for (int x = 0; x < W; ++x) {
                const float c = Y.at(x, y);
                float acc = 0, ws = 0;
                for (int dy = -2; dy <= 2; ++dy) {
                    const float* row = Y.row(std::clamp(y + dy, 0, H - 1));
                    for (int dx = -2; dx <= 2; ++dx) {
                        const float v = row[std::clamp(x + dx, 0, W - 1)];
                        const float d = v - c;
                        const float w = std::exp(d * d * inv_r - static_cast<float>(dx * dx + dy * dy) * (1.0f / 4.5f));
                        acc += w * v;
                        ws += w;
                    }
                }
                Yd.at(x, y) = lerp(c, acc / ws, nr);
            }
        }, 4);
    }

    // Sharpening with noise coring and overshoot limited to the local 3x3 range.
    const float amount = 1.1f * std::clamp(opt.sharpen, 0.0f, 1.0f) * (sigma > 0.02f ? 0.6f : 1.0f);
    Plane out = Yd;
    if (amount > 0.0f) {
        const Plane blur = gaussian_blur(Yd, 1.0f);
        const float core = 1.5f * sigma + 1e-4f;
        const float core2 = core * core;
        parallel_for(0, H, [&](int y) {
            for (int x = 0; x < W; ++x) {
                const float v = Yd.at(x, y);
                float d = v - blur.at(x, y);
                d *= d * d / (d * d + core2);
                float lo = v, hi = v;
                for (int dy = -1; dy <= 1; ++dy)
                    for (int dx = -1; dx <= 1; ++dx) {
                        const float n = Yd.clamped(x + dx, y + dy);
                        lo = std::min(lo, n);
                        hi = std::max(hi, n);
                    }
                const float margin = 0.015f + 0.1f * (hi - lo);
                out.at(x, y) = std::clamp(v + amount * d, lo - margin, hi + margin);
            }
        }, 4);
    }

    parallel_for(0, H, [&](int y) {
        for (int x = 0; x < W; ++x) {
            const float delta = out.at(x, y) - Y.at(x, y);
            R.at(x, y) = std::clamp(R.at(x, y) + delta, 0.0f, 1.0f);
            G.at(x, y) = std::clamp(G.at(x, y) + delta, 0.0f, 1.0f);
            B.at(x, y) = std::clamp(B.at(x, y) + delta, 0.0f, 1.0f);
        }
    }, 16);
}

}  // namespace luma::engine::detail
