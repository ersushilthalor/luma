#include <cmath>

#include "internal.h"

namespace luma::engine {
namespace detail {

Mat3 mul(const Mat3& a, const Mat3& b) {
    Mat3 r{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) {
            float s = 0;
            for (int k = 0; k < 3; ++k) s += a[static_cast<size_t>(i * 3 + k)] * b[static_cast<size_t>(k * 3 + j)];
            r[static_cast<size_t>(i * 3 + j)] = s;
        }
    return r;
}

std::array<float, 3> mul(const Mat3& m, const std::array<float, 3>& v) {
    return {m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
}

Mat3 inverse(const Mat3& m) {
    const double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    const double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    const double det = a * A + b * B + c * C;
    if (std::abs(det) < 1e-12) return identity3();
    const double inv = 1.0 / det;
    return {static_cast<float>(A * inv), static_cast<float>(-(b * i - c * h) * inv), static_cast<float>((b * f - c * e) * inv),
            static_cast<float>(B * inv), static_cast<float>((a * i - c * g) * inv), static_cast<float>(-(a * f - c * d) * inv),
            static_cast<float>(C * inv), static_cast<float>(-(a * h - b * g) * inv), static_cast<float>((a * e - b * d) * inv)};
}

Mat3 diag(float a, float b, float c) { return {a, 0, 0, 0, b, 0, 0, 0, c}; }
Mat3 identity3() { return diag(1, 1, 1); }

bool is_zero(const Mat3& m) {
    for (float v : m)
        if (v != 0.0f) return false;
    return true;
}

namespace {

constexpr Mat3 kXyzD50ToSrgb{3.1338561f, -1.6168667f, -0.4906146f,
                             -0.9787684f, 1.9161415f, 0.0334540f,
                             0.0719453f, -0.2289914f, 1.4052427f};
constexpr Mat3 kBradfordD50ToD65{0.9555766f, -0.0230393f, 0.0631636f,
                                 -0.0282895f, 1.0099416f, 0.0210077f,
                                 0.0122982f, -0.0204830f, 1.3299098f};
constexpr Mat3 kXyzD65ToP3{2.4934969f, -0.9313836f, -0.4027108f,
                           -0.8294890f, 1.7626641f, 0.0236247f,
                           0.0358458f, -0.0761724f, 0.9568845f};
constexpr std::array<float, 3> kD50Xyz{0.9642f, 1.0f, 0.8251f};

float illuminant_cct(int code) {
    switch (code) {
        case 17: case 3: return 2856.0f;      // Standard A, tungsten
        case 24: return 3200.0f;              // ISO studio tungsten
        case 15: return 3450.0f;              // white fluorescent
        case 2: case 14: return 4150.0f;      // fluorescent, cool white fluorescent
        case 18: return 4874.0f;              // Standard B
        case 13: case 23: return 5003.0f;     // day white fluorescent, D50
        case 1: case 4: case 9: return 5500.0f;
        case 20: return 5503.0f;              // D55
        case 12: return 6430.0f;              // daylight fluorescent
        case 10: return 6500.0f;              // cloudy
        case 21: return 6504.0f;              // D65
        case 19: return 6774.0f;              // Standard C
        case 11: case 22: return 7504.0f;     // shade, D75
        default: return 5000.0f;
    }
}

float cct_from_xy(float x, float y) {
    // McCamy (1992). Adequate for the 2000..12000 K interpolation weight.
    const float n = (x - 0.3320f) / (0.1858f - y);
    const float cct = 449.0f * n * n * n + 3525.0f * n * n + 6823.3f * n + 5520.33f;
    return std::clamp(cct, 1500.0f, 25000.0f);
}

std::array<float, 2> xy_from_cct(float t) {
    // Kim et al. (2002) cubic spline approximation of the Planckian locus.
    t = std::clamp(t, 1667.0f, 25000.0f);
    const double T = t;
    double x;
    if (T <= 4000.0)
        x = -0.2661239e9 / (T * T * T) - 0.2343589e6 / (T * T) + 0.8776956e3 / T + 0.179910;
    else
        x = -3.0258469e9 / (T * T * T) + 2.1070379e6 / (T * T) + 0.2226347e3 / T + 0.240390;
    double y;
    if (T <= 2222.0)
        y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
    else if (T <= 4000.0)
        y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
    else
        y = 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
    return {static_cast<float>(x), static_cast<float>(y)};
}

std::array<float, 3> xyz_from_xy(float x, float y) {
    y = std::max(y, 1e-6f);
    return {x / y, 1.0f, (1.0f - x - y) / y};
}

Mat3 blend(const Mat3& a, const Mat3& b, float wa) {
    Mat3 r{};
    for (size_t i = 0; i < 9; ++i) r[i] = a[i] * wa + b[i] * (1.0f - wa);
    return r;
}

/// Weight of illuminant 1 for a scene CCT (DNG: linear in inverse temperature).
float weight1(const ColorCalibration& cal, float cct) {
    if (!cal.has_second) return 1.0f;
    const float t1 = illuminant_cct(cal.illuminant1);
    const float t2 = illuminant_cct(cal.illuminant2);
    if (std::abs(t1 - t2) < 1.0f) return 1.0f;
    const float w = (1.0f / cct - 1.0f / t2) / (1.0f / t1 - 1.0f / t2);
    return std::clamp(w, 0.0f, 1.0f);
}

/// Bradford chromatic adaptation between two XYZ whites.
Mat3 bradford(const std::array<float, 3>& from, const std::array<float, 3>& to) {
    constexpr Mat3 M{0.8951f, 0.2664f, -0.1614f, -0.7502f, 1.7135f, 0.0367f, 0.0389f, -0.0685f, 1.0296f};
    const Mat3 Mi = inverse(M);
    const auto a = mul(M, from);
    const auto b = mul(M, to);
    return mul(Mi, mul(diag(b[0] / a[0], b[1] / a[1], b[2] / a[2]), M));
}

}  // namespace

Mat3 xyz_d50_to_output(OutputSpace space) {
    if (space == OutputSpace::SRGB) return kXyzD50ToSrgb;
    return mul(kXyzD65ToP3, kBradfordD50ToD65);
}

std::array<float, 3> luminance_weights(OutputSpace space) {
    if (space == OutputSpace::SRGB) return {0.2126f, 0.7152f, 0.0722f};
    return {0.2289746f, 0.6917385f, 0.0792869f};
}

}  // namespace detail

using namespace detail;

ColorSolution solve_color(const ColorCalibration& cal, const std::array<float, 3>& neutral_in) {
    std::array<float, 3> neutral = neutral_in;
    for (float& n : neutral) n = std::max(n, 1e-4f);
    const float g = neutral[1];
    for (float& n : neutral) n /= g;

    const Mat3 cm1 = mul(cal.calibration1, cal.color_matrix1);
    const Mat3 cm2 = cal.has_second ? mul(cal.calibration2, cal.color_matrix2) : cm1;

    // Iterate the white point: neutral -> XYZ through the interpolated ColorMatrix.
    std::array<float, 2> xy{0.3457f, 0.3585f};
    float w = 0.5f;
    for (int iter = 0; iter < 30; ++iter) {
        const float cct = cct_from_xy(xy[0], xy[1]);
        w = weight1(cal, cct);
        const Mat3 cm = blend(cm1, cm2, w);
        const auto xyz = mul(inverse(cm), neutral);
        const float sum = xyz[0] + xyz[1] + xyz[2];
        if (sum <= 1e-6f) break;
        const std::array<float, 2> next{xyz[0] / sum, xyz[1] / sum};
        const bool done = std::abs(next[0] - xy[0]) < 1e-6f && std::abs(next[1] - xy[1]) < 1e-6f;
        xy = next;
        if (done) break;
    }

    ColorSolution out;
    out.cct = cct_from_xy(xy[0], xy[1]);
    w = weight1(cal, out.cct);
    out.wb_gains = {1.0f / neutral[0], 1.0f, 1.0f / neutral[2]};

    const Mat3 cc = cal.has_second ? blend(cal.calibration1, cal.calibration2, w) : cal.calibration1;
    if (cal.has_forward && !is_zero(cal.forward_matrix1)) {
        const Mat3 fm = cal.has_second && !is_zero(cal.forward_matrix2) ? blend(cal.forward_matrix1, cal.forward_matrix2, w)
                                                                         : cal.forward_matrix1;
        // DNG: CameraToXYZ_D50 = FM * inv(diag(refNeutral)) * inv(CC), acting on raw camera values.
        // Our data is already multiplied by wb_gains = 1 / neutral, so undo that first.
        const auto ref = mul(inverse(cc), neutral);
        const Mat3 raw_to_xyz = mul(fm, mul(diag(1.0f / ref[0], 1.0f / ref[1], 1.0f / ref[2]), inverse(cc)));
        out.camera_to_xyz_d50 = mul(raw_to_xyz, diag(neutral[0], neutral[1], neutral[2]));
    } else {
        const Mat3 cm = blend(cm1, cm2, w);
        const Mat3 cam_to_xyz = inverse(cm);
        const auto white = mul(cam_to_xyz, neutral);
        Mat3 m = mul(bradford(white, kD50Xyz), mul(cam_to_xyz, diag(neutral[0], neutral[1], neutral[2])));
        const auto mapped = mul(m, std::array<float, 3>{1, 1, 1});
        const float s = kD50Xyz[1] / std::max(mapped[1], 1e-6f);
        for (float& v : m) v *= s;
        out.camera_to_xyz_d50 = m;
    }
    return out;
}

std::array<float, 3> neutral_for_kelvin(const ColorCalibration& cal, float kelvin, float tint) {
    const auto xy = xy_from_cct(kelvin);
    const auto xyz = xyz_from_xy(xy[0], xy[1]);
    const float w = weight1(cal, kelvin);
    const Mat3 cm1 = mul(cal.calibration1, cal.color_matrix1);
    const Mat3 cm2 = cal.has_second ? mul(cal.calibration2, cal.color_matrix2) : cm1;
    auto n = mul(blend(cm1, cm2, w), xyz);
    n[1] *= 1.0f + 0.15f * std::clamp(tint, -1.0f, 1.0f);
    const float g = std::max(n[1], 1e-6f);
    return {std::max(n[0] / g, 1e-3f), 1.0f, std::max(n[2] / g, 1e-3f)};
}

}  // namespace luma::engine
