#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace luma::engine {

/// Colour filter layout of the 2x2 Bayer tile, named row-major from the top-left pixel.
enum class Cfa : int { RGGB = 0, GRBG = 1, GBRG = 2, BGGR = 3 };

using Mat3 = std::array<float, 9>;  // row-major 3x3

/// Camera2 lens shading map (STATISTICS_LENS_SHADING_CORRECTION_MAP): per-sample gains in
/// [R, G_even, G_odd, B] order, `columns` x `rows` samples spread over the full sensor.
struct ShadingMap {
    int columns = 0;
    int rows = 0;
    std::vector<float> gains;  // rows * columns * 4
};

/// Per-capture sensor metadata, already normalised from Camera2 into engine conventions.
struct RawMetadata {
    int sensor_width = 0;   // full RAW buffer width in pixels
    int sensor_height = 0;  // full RAW buffer height in pixels
    Cfa cfa = Cfa::RGGB;
    std::array<float, 4> black_level{64, 64, 64, 64};  // per 2x2 position, row-major
    float white_level = 1023;
    std::array<float, 3> as_shot_neutral{1, 1, 1};     // camera RGB of a neutral, G normalised to 1
    float noise_scale = 0;   // variance = noise_scale * x + noise_offset, x in [0,1]
    float noise_offset = 0;
    ShadingMap shading;      // empty = no correction
};

/// Colour calibration of one camera module (Camera2 SENSOR_* characteristics). Matrices follow the
/// DNG convention: ColorMatrix maps XYZ to reference camera space, ForwardMatrix maps white-balanced
/// camera space to XYZ D50, CalibrationTransform maps reference to individual camera space.
struct ColorCalibration {
    Mat3 color_matrix1{}, color_matrix2{};
    Mat3 forward_matrix1{}, forward_matrix2{};
    Mat3 calibration1{1, 0, 0, 0, 1, 0, 0, 0, 1}, calibration2{1, 0, 0, 0, 1, 0, 0, 0, 1};
    int illuminant1 = 21;  // EXIF LightSource codes (21 = D65, 17 = Standard A)
    int illuminant2 = 17;
    bool has_second = true;
    bool has_forward = true;
};

/// Even-aligned rectangle in full RAW pixel coordinates.
struct Rect {
    int x = 0, y = 0, width = 0, height = 0;
};

enum class Look : int { Natural = 0, Vivid = 1, Monochrome = 2, Flat = 3, Food = 4 };
enum class OutputSpace : int { DisplayP3 = 0, SRGB = 1 };

struct ProcessOptions {
    int output_width = 0;   // 0 = native crop size
    int output_height = 0;
    float exposure_ev = 0;          // digital gain before tone mapping, e.g. to undo planned underexposure
    float local_tone = 0.6f;        // 0 = global curve only, 1 = strong local tone mapping
    float shadows = 0;              // -1 .. 1
    float highlights = 0;           // -1 .. 1
    float contrast = 0;             // -1 .. 1
    float saturation = 0;           // -1 .. 1
    float sharpen = 0.5f;           // 0 .. 1
    float luma_denoise = 0.5f;      // 0 .. 1, scaled further by the measured noise level
    float chroma_denoise = 0.7f;    // 0 .. 1
    float white_balance_kelvin = 0; // > 0 overrides as-shot neutral with a Planckian white
    float white_balance_tint = 0;   // -1 .. 1 green/magenta shift applied with the Kelvin override
    Look look = Look::Natural;
    OutputSpace space = OutputSpace::DisplayP3;
    bool merge = true;              // false = reference frame only
    float super_resolution = 1.0f;  // > 1: multi-frame super-resolution onto a grid this many times denser per side
    int threads = 0;                // 0 = hardware concurrency
};

struct ProcessStats {
    int frames_added = 0;
    int frames_merged = 0;      // frames that contributed after robustness weighting (>= 1)
    int reference_index = 0;
    float mean_merge_weight = 0;  // average per-pixel weight of alternate frames (0..1)
    float noise_reduction_db = 0; // expected SNR gain from merging, 10*log10(effective frames)
    int defects_corrected = 0;    // samples replaced by dynamic defect correction
    float applied_ev = 0;         // exposure gain chosen by the auto exposure stage plus requested ev
    float scene_key = 0;          // log2 average luminance before tone mapping
    float dynamic_range_ev = 0;   // p99.5 - p0.5 of log2 luminance
    int output_width = 0;
    int output_height = 0;
    float super_resolution = 1.0f;  // scale actually applied (1 = none)
    float subpixel_spread = 0;      // RMS fractional shift between frames, raw pixels (needs > ~0.15 for real gain)
    // Mean distance from a point of the scene to the nearest green sample of any frame, raw pixels: ~0.2 for a
    // hand-held burst, ~0.5 on a tripod. Above the limit for the scale, super-resolution is declined and the
    // burst is merged at sensor resolution and enlarged instead (super_resolution then stays 1).
    float coverage_gap = 0;
    float ms_align = 0, ms_merge = 0, ms_demosaic = 0, ms_color = 0, ms_tone = 0, ms_finish = 0, ms_total = 0;
};

/// Burst of RAW frames that share one sensor configuration and crop. Frames are copied on add,
/// so the caller can release camera buffers immediately.
class Burst {
public:
    Burst(const RawMetadata& meta, const Rect& crop);
    ~Burst();
    Burst(const Burst&) = delete;
    Burst& operator=(const Burst&) = delete;

    /// Adds a RAW16 frame. `data` points to the top-left of the full sensor buffer; `row_stride`
    /// is in pixels. `brightness_scale` multiplies this frame's signal to match the reference
    /// exposure (1 for equal exposures). Returns false if the frame was rejected.
    bool add_frame(const uint16_t* data, int row_stride, float brightness_scale = 1.0f);

    int frame_count() const;
    const Rect& crop() const;

    /// Output dimensions for the given options (native crop size unless overridden).
    void output_size(const ProcessOptions& options, int& width, int& height) const;

    /// Runs the full pipeline into an RGBA8888 buffer of `output_size` dimensions.
    ProcessStats process(const ColorCalibration& color, const ProcessOptions& options,
                         uint8_t* rgba, int rgba_stride_bytes);

    /// Writes the merged (or reference) mosaic of the crop region back as RAW16 in sensor units,
    /// for a DNG. Valid after `process`. `out` holds crop.width * crop.height values.
    bool merged_mosaic(uint16_t* out) const;

private:
    struct Impl;
    ProcessStats process_super_res(const ColorCalibration& color, const ProcessOptions& options, uint8_t* rgba, int stride,
                                   ProcessStats stats, double t_start, double ms_prepare);
    std::unique_ptr<Impl> impl_;
};

/// Computes white-balance gains (1 / neutral, G = 1) and the white-balanced camera -> XYZ(D50)
/// matrix for a neutral, following the DNG specification's two-illuminant interpolation.
struct ColorSolution {
    std::array<float, 3> wb_gains{1, 1, 1};
    Mat3 camera_to_xyz_d50{};
    float cct = 5000;
};
ColorSolution solve_color(const ColorCalibration& cal, const std::array<float, 3>& neutral);

/// Neutral (camera RGB) that a Planckian illuminant at `kelvin` produces, for manual white balance.
std::array<float, 3> neutral_for_kelvin(const ColorCalibration& cal, float kelvin, float tint);

std::string describe(const ProcessStats& stats);

}  // namespace luma::engine
