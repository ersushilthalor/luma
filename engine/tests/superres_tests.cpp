// Ground-truth tests for handheld multi-frame super-resolution (detail::super_resolve).
// A continuous test scene is imaged through a box pixel aperture (like a binned sensor) at random sub-pixel
// shifts, mosaicked RGGB with noise, then super-resolved 2x and compared with the true 2x image, against the
// honest baseline of one demosaiced frame enlarged with bicubic interpolation.

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <random>
#include <string>
#include <vector>

#include "../src/internal.h"

using namespace luma::engine;
using namespace luma::engine::detail;

int superres_failures = 0;

namespace {

void check(bool ok, const std::string& what) {
    std::printf("%s %s\n", ok ? "[ OK ]" : "[FAIL]", what.c_str());
    if (!ok) ++superres_failures;
}

constexpr int W = 256, H = 192;
constexpr float kBlack = 64.0f, kWhite = 1023.0f;

/// Scene radiance in [0.05, 0.95] at continuous sensor-pixel coordinates: natural 1/f texture (random-phase
/// waves from 0.01 to 0.7 cycles per pixel, amplitude falling with frequency), a patch of fine fabric-like
/// texture beyond the sensor's Nyquist limit, a soft disc and an oblique edge.
float scene_eval(float x, float y) {
    struct Wave { float fx, fy, ph, amp; };
    static const std::vector<Wave> waves = [] {
        std::mt19937 g(1234);
        std::uniform_real_distribution<float> u(0.0f, 1.0f);
        std::vector<Wave> v;
        for (int i = 0; i < 24; ++i) {
            const float f = 0.01f * std::pow(70.0f, u(g));  // log-uniform 0.01 .. 0.7
            const float th = 6.2831853f * u(g);
            v.push_back({f * std::cos(th), f * std::sin(th), 6.2831853f * u(g), 0.012f / std::sqrt(f)});
        }
        return v;
    }();
    const float pi = 3.14159265f;
    float v = 0.5f;
    for (const Wave& w : waves) v += w.amp * std::sin(2.0f * pi * (w.fx * x + w.fy * y) + w.ph);
    if (x > 24 && x < 120 && y > 20 && y < 92) {
        v += 0.10f * std::sin(2.0f * pi * (0.31f * x + 0.07f * y));
        v += 0.08f * std::sin(2.0f * pi * (0.05f * x + 0.58f * y));  // above Nyquist: aliased in every frame
        v += 0.07f * std::sin(2.0f * pi * (0.43f * x - 0.36f * y));
    }
    const float dx = x - 0.62f * W, dy = y - 0.41f * H;
    v += (dx * dx + dy * dy < 900.0f) ? 0.08f : 0.0f;
    v += (0.8f * x + 0.6f * y > 170.0f) ? 0.05f : -0.05f;
    return std::clamp(v, 0.05f, 0.95f);
}

/// The scene sampled once on a 1/6-pixel grid with a margin; lookups interpolate it (fast frame synthesis).
constexpr int kOver = 6, kMargin = 6;
constexpr int kGw = (W + 2 * kMargin) * kOver, kGh = (H + 2 * kMargin) * kOver;
float scene(float x, float y) {
    static const std::vector<float> grid = [] {
        std::vector<float> g(static_cast<size_t>(kGw) * kGh);
        for (int j = 0; j < kGh; ++j)
            for (int i = 0; i < kGw; ++i)
                g[static_cast<size_t>(j) * kGw + i] =
                    scene_eval(static_cast<float>(i) / kOver - kMargin, static_cast<float>(j) / kOver - kMargin);
        return g;
    }();
    const float gx = std::clamp((x + kMargin) * kOver, 0.0f, kGw - 1.001f);
    const float gy = std::clamp((y + kMargin) * kOver, 0.0f, kGh - 1.001f);
    const int i = static_cast<int>(gx), j = static_cast<int>(gy);
    const float fx = gx - static_cast<float>(i), fy = gy - static_cast<float>(j);
    const float* r0 = &grid[static_cast<size_t>(j) * kGw + i];
    const float* r1 = r0 + kGw;
    return (r0[0] * (1 - fx) + r0[1] * fx) * (1 - fy) + (r1[0] * (1 - fx) + r1[1] * fx) * fy;
}

/// Pixel value: box average over the pixel's footprint (6x6 sub-samples), at shift (sx, sy).
float pixel(int i, int j, float sx, float sy) {
    float acc = 0;
    for (int b = 0; b < 6; ++b)
        for (int a = 0; a < 6; ++a) acc += scene(i + (a + 0.5f) / 6.0f + sx, j + (b + 0.5f) / 6.0f + sy);
    return acc / 36.0f;
}

FrameData make(float sx, float sy, std::mt19937& rng, float noise, const SensorNorm& norm, const std::array<int, 4>& colors,
               float move = 0.0f) {
    std::normal_distribution<float> n(0.0f, noise);
    FrameData f;
    f.mosaic.resize(static_cast<size_t>(W) * H);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            float v = pixel(x, y, sx, sy);
            // A moving dark square (only in frames with `move` != 0): must not ghost into the result.
            if (move != 0.0f && x > 30 + move && x < 70 + move && y > 120 && y < 160) v = 0.08f;
            v = std::clamp(v + n(rng), 0.0f, 1.0f);
            f.mosaic[static_cast<size_t>(y) * W + x] = static_cast<uint16_t>(std::lround(kBlack + v * (kWhite - kBlack)));
        }
    prepare_frame(f, W, H, norm, colors);
    return f;
}

/// Ground truth on the 2x grid, aligned to the reference frame's shift.
Plane truth2x(float sx, float sy) {
    Plane t(2 * W, 2 * H);
    for (int y = 0; y < 2 * H; ++y)
        for (int x = 0; x < 2 * W; ++x) {
            float acc = 0;
            for (int b = 0; b < 3; ++b)
                for (int a = 0; a < 3; ++a) acc += scene(x * 0.5f + (a + 0.5f) / 6.0f + sx, y * 0.5f + (b + 0.5f) / 6.0f + sy);
            t.at(x, y) = acc / 9.0f;
        }
    return t;
}

double rmse(const Plane& a, const Plane& b, int border) {
    double s = 0;
    int n = 0;
    for (int y = border; y < a.h - border; ++y)
        for (int x = border; x < a.w - border; ++x) {
            const double d = a.at(x, y) - b.at(x, y);
            s += d * d;
            ++n;
        }
    return std::sqrt(s / n);
}

/// Energy of the result's fine detail that agrees with the truth (high-pass correlation).
double detail_match(const Plane& a, const Plane& t, int border) {
    double num = 0, den = 0;
    for (int y = border; y < a.h - border; ++y)
        for (int x = border; x < a.w - border; ++x) {
            const float ha = 4 * a.at(x, y) - a.at(x - 1, y) - a.at(x + 1, y) - a.at(x, y - 1) - a.at(x, y + 1);
            const float ht = 4 * t.at(x, y) - t.at(x - 1, y) - t.at(x + 1, y) - t.at(x, y - 1) - t.at(x, y + 1);
            num += ha * ht;
            den += ht * ht;
        }
    return num / den;
}

}  // namespace

void run_superres_tests() {
    const std::array<int, 4> colors = cfa_colors(Cfa::RGGB);
    SensorNorm norm{};
    for (int k = 0; k < 4; ++k) {
        norm.black[static_cast<size_t>(k)] = kBlack;
        norm.inv_range[static_cast<size_t>(k)] = 1.0f / (kWhite - kBlack);
    }
    RawMetadata meta;
    meta.sensor_width = W;
    meta.sensor_height = H;
    meta.noise_scale = 2e-5f;
    meta.noise_offset = 4e-6f;
    std::array<Plane, 4> gains{Plane(W / 2, H / 2, 1.0f), Plane(W / 2, H / 2, 1.0f), Plane(W / 2, H / 2, 1.0f), Plane(W / 2, H / 2, 1.0f)};
    Plane clip(W / 2, H / 2, 1.0f);
    std::mt19937 rng(7);
    std::uniform_real_distribution<float> shake(-1.5f, 1.5f);

    // Handheld burst: 12 frames at random sub-pixel shifts.
    std::vector<float> sxs, sys;
    std::vector<FrameData> frames;
    for (int i = 0; i < 12; ++i) {
        sxs.push_back(i == 0 ? 0.0f : shake(rng));
        sys.push_back(i == 0 ? 0.0f : shake(rng));
        frames.push_back(make(sxs.back(), sys.back(), rng, 0.004f, norm, colors));
    }
    // Baseline: the first frame demosaiced and enlarged 2x (bicubic).
    Plane mosaic(W, H);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x)
            mosaic.at(x, y) = (frames[0].mosaic[static_cast<size_t>(y) * W + x] - kBlack) / (kWhite - kBlack);
    Plane br, bg, bb;
    demosaic(mosaic, colors, br, bg, bb);
    const Plane base = resize_bicubic(bg, 2 * W, 2 * H);

    double ma = 0, mm = 0;
    SuperResOutput sr = super_resolve(frames, W, H, 2.0f, norm, colors, meta, gains, clip, ma, mm);
    const int ref = sr.reference;
    double align_rms = 0;
    for (size_t i = 0; i < sxs.size(); ++i) {
        if (static_cast<int>(i) == ref) continue;
        const double ex = sr.shift[i][0] - (sxs[static_cast<size_t>(ref)] - sxs[i]);
        const double ey = sr.shift[i][1] - (sys[static_cast<size_t>(ref)] - sys[i]);
        align_rms += ex * ex + ey * ey;
    }
    align_rms = std::sqrt(align_rms / static_cast<double>(sxs.size() - 1));
    const Plane truth = truth2x(sxs[static_cast<size_t>(ref)], sys[static_cast<size_t>(ref)]);
    const Plane truth0 = truth2x(0.0f, 0.0f);
    const double e_sr = rmse(sr.g, truth, 24), e_base = rmse(base, truth0, 24);
    const double d_sr = detail_match(sr.g, truth, 24), d_base = detail_match(base, truth0, 24);
    // Upper bound with exact alignment (same frames re-synthesised): separates merge quality from alignment.
    std::vector<std::array<float, 2>> known;
    for (size_t i = 0; i < sxs.size(); ++i) known.push_back({-sxs[i], -sys[i]});
    std::vector<FrameData> again;
    for (size_t i = 0; i < sxs.size(); ++i) again.push_back(make(sxs[i], sys[i], rng, 0.004f, norm, colors));
    double a2 = 0, m2 = 0;
    const SuperResOutput ex = super_resolve(again, W, H, 2.0f, norm, colors, meta, gains, clip, a2, m2, &known);
    const double e_exact = rmse(ex.g, truth0, 24);
    std::printf("       super-res 2x: RMSE %.4f (exact-alignment bound %.4f) vs single-frame bicubic %.4f | fine-detail "
                "match %.2f vs %.2f | alignment error %.3f px | sub-pixel spread %.2f px | weight %.2f | %.0f + %.0f ms\n",
                e_sr, e_exact, e_base, d_sr, d_base, align_rms, sr.subpixel_spread, sr.mean_weight, ma, mm);
    check(align_rms < 0.05, "handheld burst aligned to a twentieth of a pixel");
    check(e_sr < 1.05 * e_exact, "estimated alignment is as good as exact alignment for the merge");
    check(sr.r.w == 2 * W && sr.r.h == 2 * H, "super-res output is 2x per side");
    check(e_sr < 0.85 * e_base, "super-res error clearly below single-frame upscale");
    check(d_sr > d_base + 0.10, "super-res recovers fine detail the single frame lacks");
    check(sr.subpixel_spread > 0.2f, "handheld burst shows sub-pixel diversity");

    // Tripod: identical positions -> no spatial gain possible, and the spread says so.
    std::vector<FrameData> still;
    for (int i = 0; i < 8; ++i) still.push_back(make(0.0f, 0.0f, rng, 0.004f, norm, colors));
    SuperResOutput tri = super_resolve(still, W, H, 2.0f, norm, colors, meta, gains, clip, ma, mm);
    check(tri.subpixel_spread < 0.1f, "tripod burst is detected (spread " + std::to_string(tri.subpixel_spread) + ")");
    check(sr.coverage_gap < 0.27f && tri.coverage_gap > 0.4f,
          "sample coverage separates hand-held (" + std::to_string(sr.coverage_gap) + ") from tripod (" +
              std::to_string(tri.coverage_gap) + ")");
    SuperResOutput gated = super_resolve(still, W, H, 2.0f, norm, colors, meta, gains, clip, ma, mm, nullptr, 0.27f);
    check(gated.declined && gated.r.w == 0, "super-resolution is declined for a tripod burst");

    // Motion: a dark square moves in half of the frames; the reference area must stay clean (no ghost).
    std::vector<FrameData> moving;
    for (int i = 0; i < 10; ++i) moving.push_back(make(i == 0 ? 0.0f : shake(rng), i == 0 ? 0.0f : shake(rng), rng, 0.004f, norm,
                                                       colors, i % 2 == 1 ? 60.0f : 0.0f));
    SuperResOutput mv = super_resolve(moving, W, H, 2.0f, norm, colors, meta, gains, clip, ma, mm);
    // Check where the square is NOT in the reference frame (the engine picks the sharpest frame as reference):
    // the result there must show the scene, not a ghost of the square from the other frames.
    const bool ref_moved = mv.reference % 2 == 1;
    const int gx0 = ref_moved ? 40 : 100, gx1 = ref_moved ? 60 : 120;
    double ghost = 0;
    int cnt = 0;
    for (int y = 2 * 125; y < 2 * 155; ++y)
        for (int x = 2 * gx0; x < 2 * gx1; ++x) {
            ghost += std::max(0.0f, 0.4f - mv.g.at(x, y));
            ++cnt;
        }
    ghost /= cnt;
    std::printf("       motion: mean darkening where only moved frames had the square %.4f\n", ghost);
    check(ghost < 0.03, "moving object leaves no ghost in the super-resolved image");
}
