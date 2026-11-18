// Desktop tests for the Luma engine on synthetic RAW data with known ground truth.
// Build: cmake -S engine -B build/engine -DLUMA_ENGINE_TESTS=ON && cmake --build build/engine --config Release
// Run:   build/engine/Release/luma_engine_tests  (non-zero exit on failure)

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <string>
#include <vector>

#include "luma/engine/raw_pipeline.h"
#include "luma/engine/resample.h"

using namespace luma::engine;

void run_superres_tests();
extern int superres_failures;

namespace {

int failures = 0;

void check(bool ok, const std::string& what) {
    std::printf("%s %s\n", ok ? "[ OK ]" : "[FAIL]", what.c_str());
    if (!ok) ++failures;
}

/// Smooth, textured linear RGB scene in [0, 1] (scene coordinates, continuous).
void scene(float x, float y, float& r, float& g, float& b) {
    const float u = x / 1000.0f, v = y / 800.0f;
    r = 0.15f + 0.35f * u + 0.05f * std::sin(x * 0.05f) * std::cos(y * 0.043f);
    g = 0.20f + 0.25f * v + 0.05f * std::sin(x * 0.031f + y * 0.027f);
    b = 0.10f + 0.20f * (1.0f - u) * v + 0.04f * std::cos(x * 0.06f);
    if (std::fmod(std::floor(x / 64.0f) + std::floor(y / 64.0f), 2.0f) == 0.0f) {
        r *= 0.7f;
        g *= 0.7f;
        b *= 0.7f;
    }
}

struct Synth {
    int w, h;
    RawMetadata meta;
    std::vector<float> truth;  // normalised mosaic truth (before noise), RGGB
};

Synth make_meta(int w, int h) {
    Synth s{w, h, {}, {}};
    s.meta.sensor_width = w;
    s.meta.sensor_height = h;
    s.meta.cfa = Cfa::RGGB;
    s.meta.black_level = {64, 64, 64, 64};
    s.meta.white_level = 1023;
    s.meta.as_shot_neutral = {1, 1, 1};
    s.meta.noise_scale = 4e-4f;
    s.meta.noise_offset = 2e-6f;
    return s;
}

std::vector<uint16_t> make_frame(const Synth& s, int shift_x, int shift_y, std::mt19937& rng, bool noise,
                                 int moving_square = -1) {
    std::vector<uint16_t> raw(static_cast<size_t>(s.w * s.h));
    std::normal_distribution<float> nd(0.0f, 1.0f);
    for (int y = 0; y < s.h; ++y)
        for (int x = 0; x < s.w; ++x) {
            float r, g, b;
            scene(static_cast<float>(x + shift_x), static_cast<float>(y + shift_y), r, g, b);
            if (moving_square >= 0 && x >= moving_square && x < moving_square + 80 && y >= 100 && y < 180) r = g = b = 0.9f;
            const int k = ((y & 1) << 1) | (x & 1);
            float v = k == 0 ? r : k == 3 ? b : g;
            if (noise) v += nd(rng) * std::sqrt(s.meta.noise_scale * v + s.meta.noise_offset);
            raw[static_cast<size_t>(y * s.w + x)] = static_cast<uint16_t>(std::clamp(v * 959.0f + 64.0f + 0.5f, 0.0f, 1023.0f));
        }
    return raw;
}

ColorCalibration identity_calibration() {
    ColorCalibration c;
    // Camera space == XYZ-ish: ColorMatrix = inverse(sRGB->XYZ D50 primaries) gives a plausible sensor.
    const Mat3 xyz_to_cam{3.1338561f, -1.6168667f, -0.4906146f, -0.9787684f, 1.9161415f, 0.0334540f,
                          0.0719453f, -0.2289914f, 1.4052427f};
    c.color_matrix1 = xyz_to_cam;
    c.color_matrix2 = xyz_to_cam;
    c.has_forward = false;
    c.illuminant1 = 21;
    c.illuminant2 = 17;
    return c;
}

double rms_vs_truth(const std::vector<uint16_t>& mosaic, const Synth& s, int border, int sx = 0, int sy = 0) {
    double acc = 0;
    long n = 0;
    for (int y = border; y < s.h - border; ++y)
        for (int x = border; x < s.w - border; ++x) {
            float r, g, b;
            scene(static_cast<float>(x + sx), static_cast<float>(y + sy), r, g, b);
            const int k = ((y & 1) << 1) | (x & 1);
            const float t = k == 0 ? r : k == 3 ? b : g;
            const float v = (static_cast<float>(mosaic[static_cast<size_t>(y * s.w + x)]) - 64.0f) / 959.0f;
            acc += static_cast<double>((v - t) * (v - t));
            ++n;
        }
    return std::sqrt(acc / static_cast<double>(n));
}

void test_color() {
    ColorCalibration c = identity_calibration();
    auto sol = solve_color(c, {1, 1, 1});
    float sum[3] = {0, 0, 0};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) sum[i] += sol.camera_to_xyz_d50[static_cast<size_t>(i * 3 + j)];
    check(std::abs(sum[1] - 1.0f) < 0.01f, "camera white maps to Y = 1");
    check(std::abs(sum[0] / sum[1] - 0.9642f) < 0.02f && std::abs(sum[2] / sum[1] - 0.8251f) < 0.02f,
          "camera white maps to D50 chromaticity");

    // A realistic two-illuminant sensor: tungsten neutral should solve to a low CCT, daylight high.
    ColorCalibration s;
    s.color_matrix1 = {0.7f, -0.15f, -0.05f, -0.45f, 1.3f, 0.15f, -0.1f, 0.25f, 0.6f};    // D65
    s.color_matrix2 = {0.9f, -0.35f, 0.0f, -0.4f, 1.25f, 0.1f, -0.05f, 0.15f, 0.55f};     // A
    s.forward_matrix1 = {0.6f, 0.25f, 0.11f, 0.25f, 0.85f, -0.1f, 0.02f, -0.2f, 1.0f};
    s.forward_matrix2 = s.forward_matrix1;
    const auto daylight = neutral_for_kelvin(s, 6500, 0);
    const auto tungsten = neutral_for_kelvin(s, 2900, 0);
    const auto sd = solve_color(s, daylight);
    const auto st = solve_color(s, tungsten);
    check(std::abs(sd.cct - 6500) < 400, "daylight neutral solves near 6500 K (got " + std::to_string(static_cast<int>(sd.cct)) + ")");
    check(std::abs(st.cct - 2900) < 300, "tungsten neutral solves near 2900 K (got " + std::to_string(static_cast<int>(st.cct)) + ")");
}

void test_merge() {
    Synth s = make_meta(800, 640);
    std::mt19937 rng(42);
    const int shifts[8][2] = {{0, 0}, {2, 0}, {-2, 2}, {4, -2}, {0, 4}, {-4, -4}, {6, 2}, {2, -6}};

    // Reference only.
    {
        Burst one(s.meta, {});
        auto f = make_frame(s, 0, 0, rng, true);
        one.add_frame(f.data(), s.w);
        std::vector<uint8_t> rgba(static_cast<size_t>(s.w * s.h * 4));
        ProcessOptions o;
        o.merge = false;
        one.process(identity_calibration(), o, rgba.data(), s.w * 4);
        std::vector<uint16_t> m(static_cast<size_t>(s.w * s.h));
        one.merged_mosaic(m.data());
        const double e1 = rms_vs_truth(m, s, 32);

        Burst burst(s.meta, {});
        std::mt19937 rng2(42);
        for (auto& sh : shifts) {
            auto fr = make_frame(s, sh[0], sh[1], rng2, true);
            burst.add_frame(fr.data(), s.w);
        }
        auto stats = burst.process(identity_calibration(), ProcessOptions{}, rgba.data(), s.w * 4);
        burst.merged_mosaic(m.data());
        const int ri = stats.reference_index;
        const double e8 = rms_vs_truth(m, s, 32, shifts[ri][0], shifts[ri][1]);
        std::printf("       single rms %.5f, merged(8) rms %.5f, ratio %.2f | %s\n", e1, e8, e1 / e8, describe(stats).c_str());
        check(e1 / e8 > 2.0, "8-frame merge reduces noise by > 2x (ideal 2.83x)");
        check(stats.mean_merge_weight > 0.7f, "static scene merges with high weight");
    }
}

void test_motion() {
    Synth s = make_meta(800, 640);
    std::mt19937 rng(7);
    Burst burst(s.meta, {});
    auto ref = make_frame(s, 0, 0, rng, true, 200);
    burst.add_frame(ref.data(), s.w);
    for (int i = 1; i < 6; ++i) {
        auto f = make_frame(s, 0, 0, rng, true, 200 + 60 * i);  // square moves 60 px per frame
        burst.add_frame(f.data(), s.w);
    }
    std::vector<uint8_t> rgba(static_cast<size_t>(s.w * s.h * 4));
    ProcessOptions o;
    const auto st = burst.process(identity_calibration(), o, rgba.data(), s.w * 4);
    const int sq_x = 200 + 60 * st.reference_index;
    std::vector<uint16_t> m(static_cast<size_t>(s.w * s.h));
    burst.merged_mosaic(m.data());
    std::printf("       motion: %s\n", describe(st).c_str());
    // Ghost check: at x 400..460 (where later frames had the square, but not the reference, which is
    // one of frames 0..2) the merged result must still show the background, not a bright ghost.
    double ghost = 0;
    int n = 0;
    for (int y = 110; y < 170; ++y)
        for (int x = 400; x < 460; ++x) {
            float r, g, b;
            scene(static_cast<float>(x), static_cast<float>(y), r, g, b);
            const int k = ((y & 1) << 1) | (x & 1);
            const float t = k == 0 ? r : k == 3 ? b : g;
            ghost += (static_cast<float>(m[static_cast<size_t>(y * s.w + x)]) - 64.0f) / 959.0f - t;
            ++n;
        }
    ghost /= n;
    std::printf("       mean ghost bias %.4f\n", ghost);
    check(std::abs(ghost) < 0.03, "moving object leaves no ghost");
    // The square itself (present in the reference) stays bright.
    double sq = 0;
    n = 0;
    for (int y = 110; y < 170; ++y)
        for (int x = sq_x + 10; x < sq_x + 70; ++x) {
            sq += (static_cast<float>(m[static_cast<size_t>(y * s.w + x)]) - 64.0f) / 959.0f;
            ++n;
        }
    sq /= n;
    check(sq > 0.8, "reference content preserved under motion");
}

void test_pipeline_output() {
    Synth s = make_meta(4080, 3060);
    std::mt19937 rng(3);
    // Daylight-ish neutral with a realistic sensor and shading map.
    s.meta.as_shot_neutral = {0.49f, 1.0f, 0.40f};
    s.meta.shading.columns = 17;
    s.meta.shading.rows = 13;
    s.meta.shading.gains.assign(17 * 13 * 4, 1.2f);
    ColorCalibration cal;
    cal.color_matrix1 = {0.7f, -0.15f, -0.05f, -0.45f, 1.3f, 0.15f, -0.1f, 0.25f, 0.6f};
    cal.color_matrix2 = {0.9f, -0.35f, 0.0f, -0.4f, 1.25f, 0.1f, -0.05f, 0.15f, 0.55f};
    cal.forward_matrix1 = {0.6f, 0.25f, 0.11f, 0.25f, 0.85f, -0.1f, 0.02f, -0.2f, 1.0f};
    cal.forward_matrix2 = cal.forward_matrix1;
    Burst burst(s.meta, {});
    for (int i = 0; i < 6; ++i) {
        auto f = make_frame(s, 2 * (i % 3), 2 * (i / 3), rng, true);
        burst.add_frame(f.data(), s.w);
    }
    std::vector<uint8_t> rgba(static_cast<size_t>(s.w * s.h * 4));
    ProcessOptions o;
    const auto stats = burst.process(cal, o, rgba.data(), s.w * 4);
    std::printf("       12.5MP x6: %s\n", describe(stats).c_str());
    double mean = 0;
    for (size_t i = 0; i < rgba.size(); i += 4) mean += rgba[i + 1];
    mean /= static_cast<double>(rgba.size() / 4);
    check(mean > 40 && mean < 210, "full pipeline output has plausible brightness (mean G " + std::to_string(static_cast<int>(mean)) + ")");
    check(stats.output_width == 4080 && stats.output_height == 3060, "native output size");

    // Write a small PPM preview for manual inspection.
    if (const char* path = std::getenv("LUMA_TEST_PPM")) {
        if (FILE* f = std::fopen(path, "wb")) {
            const int step = 4;
            std::fprintf(f, "P6\n%d %d\n255\n", s.w / step, s.h / step);
            for (int y = 0; y < s.h / step * step; y += step)
                for (int x = 0; x < s.w / step * step; x += step) std::fwrite(&rgba[static_cast<size_t>((y * s.w + x) * 4)], 1, 3, f);
            std::fclose(f);
        }
    }
}

void test_crop_resize() {
    Synth s = make_meta(1600, 1200);
    std::mt19937 rng(9);
    Burst burst(s.meta, {401, 301, 800, 600});
    check(burst.crop().x % 2 == 0 && burst.crop().y % 2 == 0, "crop origin is CFA aligned");
    auto f = make_frame(s, 0, 0, rng, true);
    burst.add_frame(f.data(), s.w);
    ProcessOptions o;
    o.output_width = 1600;
    o.output_height = 1200;
    std::vector<uint8_t> rgba(static_cast<size_t>(1600 * 1200 * 4));
    const auto stats = burst.process(identity_calibration(), o, rgba.data(), 1600 * 4);
    check(stats.output_width == 1600 && stats.output_height == 1200, "crop upscales to requested output size");
}

void test_defects() {
    Synth s = make_meta(400, 320);
    std::mt19937 rng(11);
    Burst burst(s.meta, {});
    for (int i = 0; i < 4; ++i) {
        auto f = make_frame(s, 0, 0, rng, true);
        f[static_cast<size_t>(100 * s.w + 101)] = 1023;  // stuck-high sample (G site)
        f[static_cast<size_t>(200 * s.w + 200)] = 1023;  // stuck-high sample (R site)
        burst.add_frame(f.data(), s.w);
    }
    std::vector<uint8_t> rgba(static_cast<size_t>(s.w * s.h * 4));
    const auto st = burst.process(identity_calibration(), ProcessOptions{}, rgba.data(), s.w * 4);
    std::vector<uint16_t> m(static_cast<size_t>(s.w * s.h));
    burst.merged_mosaic(m.data());
    check(st.defects_corrected >= 2, "hot pixels detected (" + std::to_string(st.defects_corrected) + ")");
    check(m[static_cast<size_t>(100 * s.w + 101)] < 700 && m[static_cast<size_t>(200 * s.w + 200)] < 700, "hot pixels replaced");
    check(st.defects_corrected < 50, "defect correction leaves normal texture alone");
}

void test_gamut() {
    // A strongly saturated scene must still produce in-range, finite output in both spaces.
    Synth s = make_meta(256, 192);
    std::mt19937 rng(5);
    for (OutputSpace space : {OutputSpace::DisplayP3, OutputSpace::SRGB}) {
        Burst burst(s.meta, {});
        auto f = make_frame(s, 0, 0, rng, false);
        for (size_t i = 0; i < f.size(); ++i) {
            const int x = static_cast<int>(i % static_cast<size_t>(s.w)), y = static_cast<int>(i / static_cast<size_t>(s.w));
            const int k = ((y & 1) << 1) | (x & 1);
            f[i] = static_cast<uint16_t>(k == 0 ? 1000 : 70);  // pure red, near clipping
        }
        burst.add_frame(f.data(), s.w);
        ProcessOptions o;
        o.space = space;
        o.look = Look::Vivid;
        std::vector<uint8_t> rgba(static_cast<size_t>(s.w * s.h * 4));
        burst.process(identity_calibration(), o, rgba.data(), s.w * 4);
        const size_t c = static_cast<size_t>((s.h / 2 * s.w + s.w / 2) * 4);
        check(rgba[c] > 150 && rgba[c + 1] < 120 && rgba[c + 2] < 120, std::string("saturated red stays red in ") +
              (space == OutputSpace::SRGB ? "sRGB" : "P3") + " (" + std::to_string(rgba[c]) + "," + std::to_string(rgba[c + 1]) + "," +
              std::to_string(rgba[c + 2]) + ")");
    }
}

void test_resample() {
    // Flat colour survives any scale; a horizontal ramp stays monotonic; shrinking averages.
    const int sw = 64, sh = 48;
    std::vector<uint8_t> src(static_cast<size_t>(sw * sh * 4));
    for (int y = 0; y < sh; ++y)
        for (int x = 0; x < sw; ++x) {
            uint8_t* p = &src[static_cast<size_t>((y * sw + x) * 4)];
            p[0] = 120; p[1] = static_cast<uint8_t>(x * 4); p[2] = 30; p[3] = 255;
        }
    for (int f : {2, 4}) {
        const int dw = sw * f, dh = sh * f;
        std::vector<uint8_t> dst(static_cast<size_t>(dw * dh * 4));
        check(resample_rgba8(src.data(), sw, sh, sw * 4, dst.data(), dw, dh, dw * 4), "resample x" + std::to_string(f) + " runs");
        bool flat = true, mono = true;
        for (int y = 0; y < dh; ++y)
            for (int x = 0; x < dw; ++x) {
                const uint8_t* p = &dst[static_cast<size_t>((y * dw + x) * 4)];
                flat = flat && p[0] == 120 && p[2] == 30 && p[3] == 255;
                if (x > 8 && x < dw - 8) mono = mono && p[1] + 1 >= dst[static_cast<size_t>((y * dw + x - 1) * 4 + 1)];
            }
        check(flat, "resample x" + std::to_string(f) + " keeps flat channels");
        check(mono, "resample x" + std::to_string(f) + " keeps a ramp monotonic");
    }
    std::vector<uint8_t> small(static_cast<size_t>(16 * 12 * 4));
    check(resample_rgba8(src.data(), sw, sh, sw * 4, small.data(), 16, 12, 16 * 4), "resample /4 runs");
    const int mid = small[static_cast<size_t>((6 * 16 + 8) * 4 + 1)];
    check(std::abs(mid - 130) <= 4, "resample /4 averages the ramp (" + std::to_string(mid) + ")");
    check(!resample_rgba8(src.data(), 0, sh, 0, small.data(), 16, 12, 64), "resample rejects empty input");
}

}  // namespace

int main() {
    test_defects();
    test_gamut();
    test_color();
    test_merge();
    test_motion();
    test_crop_resize();
    test_pipeline_output();
    test_resample();
    run_superres_tests();
    failures += superres_failures;
    std::printf("%s (%d failure%s)\n", failures ? "FAILED" : "PASSED", failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
