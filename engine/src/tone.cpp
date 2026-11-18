// Tone mapping: mild auto exposure, local tone mapping (bilateral base/detail split of log luminance,
// computed at low resolution and joint-bilaterally upsampled), a filmic global curve, a contrast
// S-curve on encoded luminance, hue-preserving application, then look, highlight desaturation and
// gamut mapping in Oklch at constant hue (docs/COLOR_SCIENCE.md sections 4-6).

#include <cmath>

#include "internal.h"

namespace luma::engine::detail {
namespace {

constexpr float kEps = 1e-6f;
constexpr float kMidTarget = 0.18f;  // linear mid-grey target for the scene key

struct LookParams {
    float contrast;
    float saturation;
    bool mono;
    float warmth = 0.0f;  // small red/blue balance shift applied before tone mapping
};

LookParams look_params(Look look) {
    switch (look) {
        case Look::Vivid: return {0.42f, 0.25f, false};
        case Look::Monochrome: return {0.38f, 0.0f, true};
        case Look::Flat: return {0.0f, -0.10f, false};
        case Look::Food: return {0.32f, 0.30f, false, 0.035f};
        case Look::Natural: default: return {0.24f, 0.10f, false};
    }
}

float percentile(std::vector<float> values, float p) {
    if (values.empty()) return 0;
    const size_t i = static_cast<size_t>(std::clamp(p, 0.0f, 1.0f) * static_cast<float>(values.size() - 1));
    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(i), values.end());
    return values[i];
}

Plane bilateral(const Plane& src, float sigma_s, float sigma_r) {
    const int r = std::max(1, static_cast<int>(std::ceil(2.0f * sigma_s)));
    const float inv_s = -0.5f / (sigma_s * sigma_s);
    const float inv_r = -0.5f / (sigma_r * sigma_r);
    std::vector<float> spatial(static_cast<size_t>((2 * r + 1) * (2 * r + 1)));
    for (int dy = -r; dy <= r; ++dy)
        for (int dx = -r; dx <= r; ++dx)
            spatial[static_cast<size_t>((dy + r) * (2 * r + 1) + dx + r)] = std::exp(static_cast<float>(dx * dx + dy * dy) * inv_s);
    Plane out(src.w, src.h);
    parallel_for(0, src.h, [&](int y) {
        for (int x = 0; x < src.w; ++x) {
            const float c = src.at(x, y);
            float acc = 0, wsum = 0;
            for (int dy = -r; dy <= r; ++dy) {
                const int yy = std::clamp(y + dy, 0, src.h - 1);
                const float* row = src.row(yy);
                for (int dx = -r; dx <= r; ++dx) {
                    const float v = row[std::clamp(x + dx, 0, src.w - 1)];
                    const float d = v - c;
                    const float w = spatial[static_cast<size_t>((dy + r) * (2 * r + 1) + dx + r)] * std::exp(d * d * inv_r);
                    acc += w * v;
                    wsum += w;
                }
            }
            out.at(x, y) = acc / wsum;
        }
    }, 2);
    return out;
}

inline float smoothstep01(float e) {
    e = std::clamp(e, 0.0f, 1.0f);
    return e * e * (3.0f - 2.0f * e);
}

inline float smoothstep(float a, float b, float x) { return smoothstep01((x - a) / (b - a)); }

// Oklab (Ottosson 2020) from linear sRGB primaries. Output-space RGB is converted to linear sRGB
// primaries first (values outside [0, 1] are fine), so the same maths serves Display P3 output.
struct Oklab {
    float L, a, b;
};

constexpr float kP3ToSrgb[9] = {1.2249401f, -0.2249404f, 0.0f, -0.0420569f, 1.0420571f, 0.0f, -0.0196376f, -0.0786361f, 1.0982735f};
constexpr float kSrgbToP3[9] = {0.8224621f, 0.1775380f, 0.0f, 0.0331941f, 0.9668058f, 0.0f, 0.0170827f, 0.0723974f, 0.9105199f};

inline Oklab to_oklab(float r, float g, float b) {
    const float l = std::cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b);
    const float m = std::cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b);
    const float s = std::cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b);
    return {0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s};
}

inline void from_oklab(const Oklab& c, float& r, float& g, float& b) {
    const float l_ = c.L + 0.3963377774f * c.a + 0.2158037573f * c.b;
    const float m_ = c.L - 0.1055613458f * c.a - 0.0638541728f * c.b;
    const float s_ = c.L - 0.0894841775f * c.a - 1.2914855480f * c.b;
    const float l = l_ * l_ * l_, m = m_ * m_ * m_, s = s_ * s_ * s_;
    r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s;
    g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s;
    b = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s;
}

inline void apply3(const float* m, float& r, float& g, float& b) {
    const float x = m[0] * r + m[1] * g + m[2] * b;
    const float y = m[3] * r + m[4] * g + m[5] * b;
    const float z = m[6] * r + m[7] * g + m[8] * b;
    r = x;
    g = y;
    b = z;
}

/// Look, highlight desaturation and gamut mapping in Oklch at constant hue. Input/output: linear RGB
/// of the output space. `yo` is the target display luminance (0..1).
inline void oklch_finish(float& r, float& g, float& b, float yo, float saturation, bool p3) {
    if (p3) apply3(kP3ToSrgb, r, g, b);
    Oklab c = to_oklab(r, g, b);
    const float chroma = std::sqrt(c.a * c.a + c.b * c.b);
    if (chroma > 1e-6f) {
        // Chroma gain with a soft limit (saturated colours are boosted less) and skin protection.
        float gain = 1.0f + saturation / (1.0f + (chroma / 0.12f) * (chroma / 0.12f));
        float hue = std::atan2(c.b, c.a) * 57.29578f;
        if (hue < 0.0f) hue += 360.0f;
        const float skin = smoothstep(25.0f, 45.0f, hue) * (1.0f - smoothstep(70.0f, 90.0f, hue));
        if (saturation > 0.0f) gain = lerp(gain, 1.0f + 0.4f * (gain - 1.0f), skin);
        // Highlight shoulder: reduce chroma at constant hue as luminance approaches white.
        gain *= 1.0f - 0.65f * smoothstep(0.80f, 1.0f, yo);
        c.a *= gain;
        c.b *= gain;
    }
    from_oklab(c, r, g, b);
    if (p3) apply3(kSrgbToP3, r, g, b);
    const float mn = std::min(r, std::min(g, b)), mx = std::max(r, std::max(g, b));
    if (mn >= -1e-4f && mx <= 1.0f + 1e-4f) return;
    // Out of gamut: binary search the largest chroma scale that fits, keeping L and hue.
    float lo = 0.0f, hi = 1.0f;
    float rr = r, gg = g, bb = b;
    for (int i = 0; i < 10; ++i) {
        const float mid = 0.5f * (lo + hi);
        Oklab t{c.L, c.a * mid, c.b * mid};
        from_oklab(t, rr, gg, bb);
        if (p3) apply3(kSrgbToP3, rr, gg, bb);
        const float tmn = std::min(rr, std::min(gg, bb)), tmx = std::max(rr, std::max(gg, bb));
        if (tmn >= 0.0f && tmx <= 1.0f) lo = mid;
        else hi = mid;
    }
    Oklab t{c.L, c.a * lo, c.b * lo};
    from_oklab(t, r, g, b);
    if (p3) apply3(kSrgbToP3, r, g, b);
}

}  // namespace

ToneResult tone_map(Plane& R, Plane& G, Plane& B, const ProcessOptions& opt, float noise_sigma_linear) {
    (void)noise_sigma_linear;
    const int W = R.w, H = R.h;
    const auto lw = luminance_weights(opt.space);
    const LookParams look = look_params(opt.look);

    // Low-resolution log luminance (block means) as guide and base-layer input.
    const int factor = std::max(1, (std::max(W, H) + 319) / 320);
    const int lwid = (W + factor - 1) / factor, lhei = (H + factor - 1) / factor;
    Plane guide(lwid, lhei);
    const float gain0 = std::exp2(opt.exposure_ev);
    parallel_for(0, lhei, [&](int by) {
        for (int bx = 0; bx < lwid; ++bx) {
            double acc = 0;
            int count = 0;
            for (int y = by * factor; y < std::min(H, (by + 1) * factor); y += 2)
                for (int x = bx * factor; x < std::min(W, (bx + 1) * factor); x += 2) {
                    const float Y = (lw[0] * R.at(x, y) + lw[1] * G.at(x, y) + lw[2] * B.at(x, y)) * gain0;
                    acc += std::log2(std::max(Y, 0.0f) + kEps);
                    ++count;
                }
            guide.at(bx, by) = count ? static_cast<float>(acc / count) : std::log2(kEps);
        }
    });

    ToneResult result;
    {
        const float p_lo = percentile(guide.v, 0.005f);
        const float p_hi = percentile(guide.v, 0.995f);
        double sum = 0;
        for (float v : guide.v) sum += v;
        result.scene_key = static_cast<float>(sum / static_cast<double>(guide.v.size()));
        result.dynamic_range = p_hi - p_lo;
    }

    // Mild automatic exposure: move the scene key part of the way to mid-grey, but never push the
    // brightest base regions far past white.
    float auto_ev = 0.65f * (std::log2(kMidTarget) - result.scene_key);
    auto_ev = std::clamp(auto_ev, -1.5f, 2.2f);
    result.applied_ev = opt.exposure_ev + auto_ev;
    for (float& v : guide.v) v += auto_ev;

    const float sigma_s = 0.03f * static_cast<float>(std::max(lwid, lhei));
    Plane base_low = bilateral(guide, std::max(1.5f, sigma_s), 1.2f);

    const float anchor = percentile(base_low.v, 0.5f);
    const float base_hi = percentile(base_low.v, 0.995f);
    const float base_lo = percentile(base_low.v, 0.005f);
    const float range = std::max(base_hi - base_lo, 0.1f);
    constexpr float kTargetRange = 6.5f;
    float compression = 1.0f;
    if (range > kTargetRange) compression = lerp(1.0f, kTargetRange / range, std::clamp(opt.local_tone, 0.0f, 1.0f));
    compression = std::max(compression, 0.4f);
    const float c_low = std::clamp(compression * (1.0f - 0.35f * opt.shadows), 0.3f, 1.3f);
    const float c_high = std::clamp(compression * (1.0f - 0.35f * opt.highlights), 0.3f, 1.3f);
    const float detail_gain = 1.0f + 0.2f * std::clamp(opt.local_tone, 0.0f, 1.0f);
    const float white_log = anchor + (base_hi - anchor) * c_high + 0.3f;
    const float white = std::max(1.0f, std::exp2(white_log));
    const float white2 = white * white;
    const float contrast = std::clamp(look.contrast + 0.3f * opt.contrast, -0.3f, 0.8f);
    const float saturation = look.saturation + 0.5f * opt.saturation;
    const float total_gain = gain0 * std::exp2(auto_ev);
    const bool p3 = opt.space == OutputSpace::DisplayP3;
    const float inv_factor = 1.0f / static_cast<float>(factor);

    parallel_for(0, H, [&](int y) {
        float* pr = R.row(y);
        float* pg = G.row(y);
        float* pb = B.row(y);
        const float fy = (static_cast<float>(y) + 0.5f) * inv_factor - 0.5f;
        const int y0 = std::clamp(static_cast<int>(std::floor(fy)), 0, lhei - 1);
        const int y1 = std::min(y0 + 1, lhei - 1);
        const float ty = std::clamp(fy - static_cast<float>(y0), 0.0f, 1.0f);
        for (int x = 0; x < W; ++x) {
            float r = pr[x] * total_gain * (1.0f + look.warmth), g = pg[x] * total_gain, b = pb[x] * total_gain * (1.0f - look.warmth);
            const float Y = std::max(lw[0] * r + lw[1] * g + lw[2] * b, 0.0f);
            const float L = std::log2(Y + kEps);

            // Joint bilateral upsampling of the base layer.
            const float fx = (static_cast<float>(x) + 0.5f) * inv_factor - 0.5f;
            const int x0 = std::clamp(static_cast<int>(std::floor(fx)), 0, lwid - 1);
            const int x1 = std::min(x0 + 1, lwid - 1);
            const float tx = std::clamp(fx - static_cast<float>(x0), 0.0f, 1.0f);
            const int xs[4] = {x0, x1, x0, x1};
            const int ys[4] = {y0, y0, y1, y1};
            const float bw[4] = {(1 - tx) * (1 - ty), tx * (1 - ty), (1 - tx) * ty, tx * ty};
            float acc = 0, wsum = 0, plain = 0;
            for (int i = 0; i < 4; ++i) {
                const float gd = guide.at(xs[i], ys[i]) - L;
                const float w = bw[i] * std::exp(-gd * gd * 0.5f);
                const float bv = base_low.at(xs[i], ys[i]);
                acc += w * bv;
                wsum += w;
                plain += bw[i] * bv;
            }
            const float base = wsum > 1e-4f ? acc / wsum : plain;
            const float detail = L - base;
            const float tb = anchor + (base - anchor) * (base < anchor ? c_low : c_high);
            const float Lo = tb + detail * detail_gain;
            const float Yt = std::exp2(Lo);

            // Filmic global curve (extended Reinhard to the scene white) and contrast S-curve.
            float yo = Yt * (1.0f + Yt / white2) / (1.0f + Yt);
            float e = srgb_oetf(std::clamp(yo, 0.0f, 1.0f));
            e = e + contrast * (smoothstep01(e) - e);
            yo = srgb_eotf(std::clamp(e, 0.0f, 1.0f));

            const float ratio = Y > 1e-7f ? yo / Y : 0.0f;
            r *= ratio;
            g *= ratio;
            b *= ratio;

            if (look.mono || Y <= 1e-7f) {
                r = g = b = yo;
            } else {
                oklch_finish(r, g, b, yo, saturation, p3);
            }
            pr[x] = srgb_oetf(std::clamp(r, 0.0f, 1.0f));
            pg[x] = srgb_oetf(std::clamp(g, 0.0f, 1.0f));
            pb[x] = srgb_oetf(std::clamp(b, 0.0f, 1.0f));
        }
    }, 4);
    return result;
}

void chroma_denoise_linear(Plane& R, Plane& G, Plane& B, OutputSpace space, float strength, float noise_sigma) {
    if (strength <= 0.0f) return;
    const auto lw = luminance_weights(space);
    const int W = R.w, H = R.h;
    const int w2 = (W + 1) / 2, h2 = (H + 1) / 2;
    Plane ys(w2, h2), cr(w2, h2), cb(w2, h2);
    parallel_for(0, h2, [&](int y) {
        for (int x = 0; x < w2; ++x) {
            float sy = 0, sr = 0, sb = 0;
            for (int dy = 0; dy < 2; ++dy)
                for (int dx = 0; dx < 2; ++dx) {
                    const int xx = std::min(2 * x + dx, W - 1), yy = std::min(2 * y + dy, H - 1);
                    const float r = R.at(xx, yy), g = G.at(xx, yy), b = B.at(xx, yy);
                    const float Y = lw[0] * r + lw[1] * g + lw[2] * b;
                    sy += Y;
                    sr += r - Y;
                    sb += b - Y;
                }
            ys.at(x, y) = std::sqrt(std::max(sy * 0.25f, 0.0f));
            cr.at(x, y) = sr * 0.25f;
            cb.at(x, y) = sb * 0.25f;
        }
    }, 8);

    // Cross-bilateral filter of chroma guided by sqrt luminance (variance-stabilised), two passes
    // with growing footprint.
    const float sigma_r = 0.02f + 3.0f * noise_sigma;
    const float inv_r = -0.5f / (sigma_r * sigma_r);
    auto pass = [&](int step) {
        Plane ocr(w2, h2), ocb(w2, h2);
        parallel_for(0, h2, [&](int y) {
            for (int x = 0; x < w2; ++x) {
                const float c = ys.at(x, y);
                float ar = 0, ab = 0, ws = 0;
                for (int dy = -2; dy <= 2; ++dy)
                    for (int dx = -2; dx <= 2; ++dx) {
                        const int xx = std::clamp(x + dx * step, 0, w2 - 1), yy = std::clamp(y + dy * step, 0, h2 - 1);
                        const float d = ys.at(xx, yy) - c;
                        const float w = std::exp(d * d * inv_r - static_cast<float>(dx * dx + dy * dy) * 0.125f);
                        ar += w * cr.at(xx, yy);
                        ab += w * cb.at(xx, yy);
                        ws += w;
                    }
                ocr.at(x, y) = ar / ws;
                ocb.at(x, y) = ab / ws;
            }
        }, 4);
        cr = std::move(ocr);
        cb = std::move(ocb);
    };
    pass(1);
    if (strength > 0.4f) pass(2);

    const float s = std::clamp(strength, 0.0f, 1.0f);
    parallel_for(0, H, [&](int y) {
        const float fy = (static_cast<float>(y) + 0.5f) * 0.5f - 0.5f;
        const int y0 = std::clamp(static_cast<int>(std::floor(fy)), 0, h2 - 1), y1 = std::min(y0 + 1, h2 - 1);
        const float ty = std::clamp(fy - static_cast<float>(y0), 0.0f, 1.0f);
        for (int x = 0; x < W; ++x) {
            const float fx = (static_cast<float>(x) + 0.5f) * 0.5f - 0.5f;
            const int x0 = std::clamp(static_cast<int>(std::floor(fx)), 0, w2 - 1), x1 = std::min(x0 + 1, w2 - 1);
            const float tx = std::clamp(fx - static_cast<float>(x0), 0.0f, 1.0f);
            auto bil = [&](const Plane& p) {
                return lerp(lerp(p.at(x0, y0), p.at(x1, y0), tx), lerp(p.at(x0, y1), p.at(x1, y1), tx), ty);
            };
            const float r = R.at(x, y), g = G.at(x, y), b = B.at(x, y);
            const float Y = lw[0] * r + lw[1] * g + lw[2] * b;
            const float ncr = lerp(r - Y, bil(cr), s);
            const float ncb = lerp(b - Y, bil(cb), s);
            const float nr = Y + ncr, nb = Y + ncb;
            R.at(x, y) = nr;
            B.at(x, y) = nb;
            G.at(x, y) = (Y - lw[0] * nr - lw[2] * nb) / lw[1];
        }
    }, 8);
}

}  // namespace luma::engine::detail
