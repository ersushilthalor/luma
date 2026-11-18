#include "internal.h"

namespace luma::engine::detail {

Plane downsample2(const Plane& src) {
    const int w = std::max(1, (src.w + 1) / 2);
    const int h = std::max(1, (src.h + 1) / 2);
    Plane dst(w, h);
    parallel_for(0, h, [&](int y) {
        const int y0 = std::min(2 * y, src.h - 1), y1 = std::min(2 * y + 1, src.h - 1);
        const float* a = src.row(y0);
        const float* b = src.row(y1);
        float* out = dst.row(y);
        for (int x = 0; x < w; ++x) {
            const int x0 = std::min(2 * x, src.w - 1), x1 = std::min(2 * x + 1, src.w - 1);
            out[x] = 0.25f * (a[x0] + a[x1] + b[x0] + b[x1]);
        }
    }, 16);
    return dst;
}

Plane box_blur(const Plane& src, int r) {
    if (r <= 0) return src;
    Plane tmp(src.w, src.h), dst(src.w, src.h);
    const float norm = 1.0f / static_cast<float>(2 * r + 1);
    parallel_for(0, src.h, [&](int y) {
        const float* in = src.row(y);
        float* out = tmp.row(y);
        float sum = 0;
        for (int k = -r; k <= r; ++k) sum += in[std::clamp(k, 0, src.w - 1)];
        for (int x = 0; x < src.w; ++x) {
            out[x] = sum * norm;
            sum += in[std::min(x + r + 1, src.w - 1)] - in[std::max(x - r, 0)];
        }
    }, 16);
    parallel_for(0, src.w / 64 + 1, [&](int block) {
        const int x0 = block * 64, x1 = std::min(src.w, x0 + 64);
        std::vector<float> sum(static_cast<size_t>(std::max(0, x1 - x0)), 0.0f);
        for (int k = -r; k <= r; ++k) {
            const float* in = tmp.row(std::clamp(k, 0, src.h - 1));
            for (int x = x0; x < x1; ++x) sum[static_cast<size_t>(x - x0)] += in[x];
        }
        for (int y = 0; y < src.h; ++y) {
            float* out = dst.row(y);
            const float* add = tmp.row(std::min(y + r + 1, src.h - 1));
            const float* sub = tmp.row(std::max(y - r, 0));
            for (int x = x0; x < x1; ++x) {
                const size_t i = static_cast<size_t>(x - x0);
                out[x] = sum[i] * norm;
                sum[i] += add[x] - sub[x];
            }
        }
    });
    return dst;
}

Plane gaussian_blur(const Plane& src, float sigma) {
    if (sigma <= 0.0f) return src;
    const int r = std::max(1, static_cast<int>(std::ceil(sigma * 3.0f)));
    std::vector<float> k(static_cast<size_t>(2 * r + 1));
    float sum = 0;
    for (int i = -r; i <= r; ++i) {
        const float v = std::exp(-0.5f * static_cast<float>(i * i) / (sigma * sigma));
        k[static_cast<size_t>(i + r)] = v;
        sum += v;
    }
    for (float& v : k) v /= sum;
    Plane tmp(src.w, src.h), dst(src.w, src.h);
    parallel_for(0, src.h, [&](int y) {
        const float* in = src.row(y);
        float* out = tmp.row(y);
        for (int x = 0; x < src.w; ++x) {
            float acc = 0;
            for (int i = -r; i <= r; ++i) acc += k[static_cast<size_t>(i + r)] * in[std::clamp(x + i, 0, src.w - 1)];
            out[x] = acc;
        }
    }, 8);
    parallel_for(0, src.h, [&](int y) {
        float* out = dst.row(y);
        for (int x = 0; x < src.w; ++x) out[x] = 0;
        for (int i = -r; i <= r; ++i) {
            const float* in = tmp.row(std::clamp(y + i, 0, src.h - 1));
            const float kv = k[static_cast<size_t>(i + r)];
            for (int x = 0; x < src.w; ++x) out[x] += kv * in[x];
        }
    }, 8);
    return dst;
}

namespace {
inline float cubic(float t) {
    // Catmull-Rom (a = -0.5)
    t = std::abs(t);
    if (t < 1.0f) return 1.5f * t * t * t - 2.5f * t * t + 1.0f;
    if (t < 2.0f) return -0.5f * t * t * t + 2.5f * t * t - 4.0f * t + 2.0f;
    return 0.0f;
}
}  // namespace

Plane resize_bicubic(const Plane& src, int width, int height) {
    if (width == src.w && height == src.h) return src;
    // Horizontal pass then vertical pass. Downscaling widens the kernel to avoid aliasing.
    auto pass = [](const Plane& in, int out_w, int out_h, bool horizontal) {
        Plane out(out_w, out_h);
        const int in_len = horizontal ? in.w : in.h;
        const int out_len = horizontal ? out_w : out_h;
        const float scale = static_cast<float>(in_len) / static_cast<float>(out_len);
        const float support = std::max(1.0f, scale);
        const int taps = static_cast<int>(std::ceil(2.0f * support));
        std::vector<int> first(static_cast<size_t>(out_len));
        std::vector<float> weights(static_cast<size_t>(out_len) * static_cast<size_t>(2 * taps + 1));
        const int stride = 2 * taps + 1;
        for (int o = 0; o < out_len; ++o) {
            const float center = (static_cast<float>(o) + 0.5f) * scale - 0.5f;
            const int start = static_cast<int>(std::floor(center)) - taps;
            first[static_cast<size_t>(o)] = start;
            float sum = 0;
            for (int t = 0; t < stride; ++t) {
                const float w = cubic((static_cast<float>(start + t) - center) / support);
                weights[static_cast<size_t>(o * stride + t)] = w;
                sum += w;
            }
            if (sum != 0.0f)
                for (int t = 0; t < stride; ++t) weights[static_cast<size_t>(o * stride + t)] /= sum;
        }
        parallel_for(0, out_h, [&](int y) {
            float* dst = out.row(y);
            for (int x = 0; x < out_w; ++x) {
                const int o = horizontal ? x : y;
                const int start = first[static_cast<size_t>(o)];
                float acc = 0;
                for (int t = 0; t < stride; ++t) {
                    const float w = weights[static_cast<size_t>(o * stride + t)];
                    if (w == 0.0f) continue;
                    const int i = std::clamp(start + t, 0, in_len - 1);
                    acc += w * (horizontal ? in.at(i, y) : in.at(x, i));
                }
                dst[x] = acc;
            }
        }, 8);
        return out;
    };
    Plane h = pass(src, width, src.h, true);
    return pass(h, width, height, false);
}

}  // namespace luma::engine::detail
