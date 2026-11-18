#include <cmath>
#include <cstdio>
#include <cstring>

#include "internal.h"

namespace luma::engine {

using namespace detail;

struct Burst::Impl {
    RawMetadata meta;
    Rect crop;
    SensorNorm norm{};
    std::array<int, 4> colors{};
    std::vector<FrameData> frames;
    int frames_added = 0;
    MergeResult merged;
    bool processed = false;
};

namespace {

constexpr int kMaxFrames = 16;

Rect sanitize(const RawMetadata& meta, Rect c) {
    if (c.width <= 0 || c.height <= 0) c = {0, 0, meta.sensor_width, meta.sensor_height};
    c.x = std::clamp(c.x, 0, std::max(0, meta.sensor_width - 16)) & ~1;
    c.y = std::clamp(c.y, 0, std::max(0, meta.sensor_height - 16)) & ~1;
    c.width = std::clamp(c.width, 16, meta.sensor_width - c.x) & ~1;
    c.height = std::clamp(c.height, 16, meta.sensor_height - c.y) & ~1;
    return c;
}

float shading_gain(const ShadingMap& map, int channel, float u, float v) {
    // u, v in [0, 1] across the full sensor.
    const float fx = u * static_cast<float>(map.columns - 1);
    const float fy = v * static_cast<float>(map.rows - 1);
    const int x0 = std::clamp(static_cast<int>(fx), 0, map.columns - 1), x1 = std::min(x0 + 1, map.columns - 1);
    const int y0 = std::clamp(static_cast<int>(fy), 0, map.rows - 1), y1 = std::min(y0 + 1, map.rows - 1);
    const float tx = fx - static_cast<float>(x0), ty = fy - static_cast<float>(y0);
    auto g = [&](int x, int y) {
        return map.gains[static_cast<size_t>((y * map.columns + x) * 4 + channel)];
    };
    return lerp(lerp(g(x0, y0), g(x1, y0), tx), lerp(g(x0, y1), g(x1, y1), tx), ty);
}

/// Dynamic defect correction (no hot-pixel map exists on the target, TD-20): a sample far outside the
/// range of its eight same-colour neighbours is replaced by their mean. Runs on merged planes, so
/// a defect that sits still on the sensor while the scene moves between frames is also caught.
int correct_defects(std::array<Plane, 4>& planes, const RawMetadata& meta) {
    std::atomic<int> fixed{0};
    for (auto& p : planes) {
        Plane src = p;
        parallel_for(1, p.h - 1, [&](int y) {
            for (int x = 1; x < p.w - 1; ++x) {
                float lo = 3.4e38f, hi = -3.4e38f, sum = 0;
                for (int dy = -1; dy <= 1; ++dy)
                    for (int dx = -1; dx <= 1; ++dx) {
                        if (dx == 0 && dy == 0) continue;
                        const float n = src.at(x + dx, y + dy);
                        lo = std::min(lo, n);
                        hi = std::max(hi, n);
                        sum += n;
                    }
                const float v = src.at(x, y);
                const float sigma = std::sqrt(std::max(meta.noise_scale * std::max(hi, 0.0f) + meta.noise_offset, 1e-6f));
                const float threshold = 6.0f * sigma + 0.03f;
                if (v > hi + threshold || v < lo - threshold) {
                    p.at(x, y) = sum / 8.0f;
                    fixed.fetch_add(1, std::memory_order_relaxed);
                }
            }
        }, 16);
    }
    return fixed.load();
}

/// Camera RGB (white balanced) -> output RGB, chroma NR, tone mapping, finishing, resize and 8-bit write.
void render_tail(Plane& R, Plane& G, Plane& B, const ColorSolution& sol, const ProcessOptions& opt, const RawMetadata& meta,
                 float n_eff, uint8_t* rgba, int stride, ProcessStats& stats) {
    const int W = R.w, H = R.h;
    double t = now_ms();
    const Mat3 m = mul(xyz_d50_to_output(opt.space), sol.camera_to_xyz_d50);
    parallel_for(0, H, [&](int y) {
        float* pr = R.row(y);
        float* pg = G.row(y);
        float* pb = B.row(y);
        for (int x = 0; x < W; ++x) {
            const float r = pr[x], g = pg[x], b = pb[x];
            pr[x] = m[0] * r + m[1] * g + m[2] * b;
            pg[x] = m[3] * r + m[4] * g + m[5] * b;
            pb[x] = m[6] * r + m[7] * g + m[8] * b;
        }
    }, 16);
    const float s_eff = std::max(meta.noise_scale, 1e-6f) / n_eff;
    const float noise_sqrt = 0.5f * std::sqrt(s_eff);
    chroma_denoise_linear(R, G, B, opt.space, opt.chroma_denoise, noise_sqrt);
    stats.ms_color = static_cast<float>(now_ms() - t);

    t = now_ms();
    const ToneResult tone = tone_map(R, G, B, opt, noise_sqrt);
    stats.applied_ev = tone.applied_ev;
    stats.scene_key = tone.scene_key;
    stats.dynamic_range_ev = tone.dynamic_range;
    stats.ms_tone = static_cast<float>(now_ms() - t);

    t = now_ms();
    finish(R, G, B, opt, opt.space, noise_sqrt);
    const int ow = opt.output_width > 0 && opt.output_height > 0 ? opt.output_width : W;
    const int oh = opt.output_width > 0 && opt.output_height > 0 ? opt.output_height : H;
    if (ow != W || oh != H) {
        R = resize_bicubic(R, ow, oh);
        G = resize_bicubic(G, ow, oh);
        B = resize_bicubic(B, ow, oh);
    }
    parallel_for(0, oh, [&](int y) {
        uint8_t* out = rgba + static_cast<size_t>(y) * static_cast<size_t>(stride);
        for (int x = 0; x < ow; ++x) {
            // Small ordered dither breaks up banding in smooth gradients.
            const float d = (static_cast<float>(((x * 7 + y * 13) ^ (x * y)) & 15) - 7.5f) / 32.0f;
            out[4 * x + 0] = static_cast<uint8_t>(std::clamp(R.at(x, y) * 255.0f + 0.5f + d, 0.0f, 255.0f));
            out[4 * x + 1] = static_cast<uint8_t>(std::clamp(G.at(x, y) * 255.0f + 0.5f + d, 0.0f, 255.0f));
            out[4 * x + 2] = static_cast<uint8_t>(std::clamp(B.at(x, y) * 255.0f + 0.5f + d, 0.0f, 255.0f));
            out[4 * x + 3] = 255;
        }
    }, 16);
    stats.ms_finish = static_cast<float>(now_ms() - t);
    stats.output_width = ow;
    stats.output_height = oh;
}

}  // namespace

Burst::Burst(const RawMetadata& meta, const Rect& crop) : impl_(std::make_unique<Impl>()) {
    impl_->meta = meta;
    impl_->crop = sanitize(meta, crop);
    impl_->colors = cfa_colors(meta.cfa);
    for (size_t k = 0; k < 4; ++k) {
        impl_->norm.black[k] = meta.black_level[k];
        impl_->norm.inv_range[k] = 1.0f / std::max(1.0f, meta.white_level - meta.black_level[k]);
    }
    if (static_cast<int>(impl_->meta.shading.gains.size()) != impl_->meta.shading.columns * impl_->meta.shading.rows * 4 ||
        impl_->meta.shading.columns < 2 || impl_->meta.shading.rows < 2) {
        impl_->meta.shading = ShadingMap{};
    }
}

Burst::~Burst() = default;

bool Burst::add_frame(const uint16_t* data, int row_stride, float brightness_scale) {
    if (!data || impl_->processed || static_cast<int>(impl_->frames.size()) >= kMaxFrames) return false;
    const Rect& c = impl_->crop;
    if (row_stride < c.x + c.width) return false;
    FrameData frame;
    frame.scale = brightness_scale > 0 ? brightness_scale : 1.0f;
    frame.mosaic.resize(static_cast<size_t>(c.width) * static_cast<size_t>(c.height));
    for (int y = 0; y < c.height; ++y) {
        std::memcpy(frame.mosaic.data() + static_cast<size_t>(y) * static_cast<size_t>(c.width),
                    data + static_cast<size_t>(c.y + y) * static_cast<size_t>(row_stride) + static_cast<size_t>(c.x),
                    static_cast<size_t>(c.width) * sizeof(uint16_t));
    }
    impl_->frames.push_back(std::move(frame));
    ++impl_->frames_added;
    return true;
}

int Burst::frame_count() const { return impl_->frames_added; }
const Rect& Burst::crop() const { return impl_->crop; }

void Burst::output_size(const ProcessOptions& options, int& width, int& height) const {
    if (options.super_resolution > 1.01f && options.merge && impl_->frames_added > 1) {
        // Must match super_resolve's grid.
        width = std::max(2, static_cast<int>(std::lround(impl_->crop.width * options.super_resolution)) & ~1);
        height = std::max(2, static_cast<int>(std::lround(impl_->crop.height * options.super_resolution)) & ~1);
    } else if (options.output_width > 0 && options.output_height > 0) {
        width = options.output_width;
        height = options.output_height;
    } else {
        width = impl_->crop.width;
        height = impl_->crop.height;
    }
}

ProcessStats Burst::process(const ColorCalibration& color, const ProcessOptions& opt, uint8_t* rgba, int stride) {
    ProcessStats stats;
    stats.frames_added = impl_->frames_added;
    if (impl_->frames.empty() || impl_->processed || !rgba) return stats;
    configured_threads().store(opt.threads);
    const double t_start = now_ms();
    const Rect c = impl_->crop;
    const RawMetadata& meta = impl_->meta;
    const int W = c.width, H = c.height, w2 = W / 2, h2 = H / 2;

    double ms_align = 0, ms_merge = 0;
    double ms_prepare = 0;
    {
        const double tp = now_ms();
        for (auto& frame : impl_->frames) prepare_frame(frame, W, H, impl_->norm, impl_->colors);
        ms_prepare = now_ms() - tp;
    }
    const bool super_res = opt.super_resolution > 1.01f && opt.merge && impl_->frames.size() > 1;
    ProcessOptions eff = opt;
    if (super_res) {
        const ProcessStats s = process_super_res(color, opt, rgba, stride, stats, t_start, ms_prepare);
        if (s.super_resolution > 1.01f) return s;
        // Declined (the frames do not cover the denser grid): merge at sensor resolution, enlarge to the same size.
        stats.subpixel_spread = s.subpixel_spread;
        stats.coverage_gap = s.coverage_gap;
        output_size(opt, eff.output_width, eff.output_height);
        eff.super_resolution = 1.0f;
        ms_prepare += s.ms_align;
    }
    impl_->merged = align_and_merge(impl_->frames, W, H, impl_->norm, impl_->colors, meta, opt.merge, ms_align, ms_merge);
    impl_->frames.clear();
    impl_->frames.shrink_to_fit();
    impl_->processed = true;
    stats.defects_corrected = correct_defects(impl_->merged.planes, meta);
    const MergeResult& merged = impl_->merged;
    stats.reference_index = merged.reference;
    stats.frames_merged = merged.contributing;
    stats.mean_merge_weight = merged.mean_weight;
    const float n_eff = 1.0f + merged.mean_weight * static_cast<float>(std::max(0, stats.frames_added - 1)) * (opt.merge ? 1.0f : 0.0f);
    stats.noise_reduction_db = 10.0f * std::log10(n_eff);
    stats.ms_align = static_cast<float>(ms_prepare + ms_align);
    stats.ms_merge = static_cast<float>(ms_merge);

    // White balance and colour solution.
    const std::array<float, 3> neutral = opt.white_balance_kelvin > 0
        ? neutral_for_kelvin(color, opt.white_balance_kelvin, opt.white_balance_tint)
        : meta.as_shot_neutral;
    const ColorSolution sol = solve_color(color, neutral);

    // Lens shading, white balance, highlight clip; rebuild the full-resolution mosaic.
    double t = now_ms();
    Plane mosaic(W, H);
    const bool shade = !meta.shading.gains.empty();
    const float inv_sw = 1.0f / static_cast<float>(std::max(1, meta.sensor_width - 1));
    const float inv_sh = 1.0f / static_cast<float>(std::max(1, meta.sensor_height - 1));
    std::array<int, 4> shading_channel{};
    for (int k = 0; k < 4; ++k) {
        const int col = impl_->colors[static_cast<size_t>(k)];
        shading_channel[static_cast<size_t>(k)] = col == 0 ? 0 : col == 2 ? 3 : ((k >> 1) == 0 ? 1 : 2);
    }
    parallel_for(0, h2, [&](int y) {
        for (int x = 0; x < w2; ++x) {
            float vals[4];
            float clip = 3.4e38f;
            for (int k = 0; k < 4; ++k) {
                const int sx = c.x + 2 * x + (k & 1), sy = c.y + 2 * y + (k >> 1);
                const float sg = shade ? shading_gain(meta.shading, shading_channel[static_cast<size_t>(k)],
                                                      static_cast<float>(sx) * inv_sw, static_cast<float>(sy) * inv_sh)
                                       : 1.0f;
                const float gain = sg * sol.wb_gains[static_cast<size_t>(impl_->colors[static_cast<size_t>(k)])];
                vals[k] = merged.planes[static_cast<size_t>(k)].at(x, y) * gain;
                clip = std::min(clip, gain);
            }
            for (int k = 0; k < 4; ++k) mosaic.at(2 * x + (k & 1), 2 * y + (k >> 1)) = std::clamp(vals[k], 0.0f, clip);
        }
    }, 8);

    Plane R, G, B;
    demosaic(mosaic, impl_->colors, R, G, B);
    mosaic.release();
    stats.ms_demosaic = static_cast<float>(now_ms() - t);

    render_tail(R, G, B, sol, eff, meta, n_eff, rgba, stride, stats);
    stats.ms_total = static_cast<float>(now_ms() - t_start);
    return stats;
}

ProcessStats Burst::process_super_res(const ColorCalibration& color, const ProcessOptions& opt, uint8_t* rgba, int stride,
                                      ProcessStats stats, double t_start, double ms_prepare) {
    const Rect c = impl_->crop;
    const RawMetadata& meta = impl_->meta;
    const int W = c.width, H = c.height, w2 = W / 2, h2 = H / 2;
    const std::array<float, 3> neutral = opt.white_balance_kelvin > 0
        ? neutral_for_kelvin(color, opt.white_balance_kelvin, opt.white_balance_tint)
        : meta.as_shot_neutral;
    const ColorSolution sol = solve_color(color, neutral);

    // Per-site gains (lens shading x white balance) and highlight clip, at half resolution.
    std::array<Plane, 4> gains{Plane(w2, h2), Plane(w2, h2), Plane(w2, h2), Plane(w2, h2)};
    Plane clip(w2, h2);
    const bool shade = !meta.shading.gains.empty();
    const float inv_sw = 1.0f / static_cast<float>(std::max(1, meta.sensor_width - 1));
    const float inv_sh = 1.0f / static_cast<float>(std::max(1, meta.sensor_height - 1));
    std::array<int, 4> shading_channel{};
    for (int k = 0; k < 4; ++k) {
        const int col = impl_->colors[static_cast<size_t>(k)];
        shading_channel[static_cast<size_t>(k)] = col == 0 ? 0 : col == 2 ? 3 : ((k >> 1) == 0 ? 1 : 2);
    }
    parallel_for(0, h2, [&](int y) {
        for (int x = 0; x < w2; ++x) {
            float lo = 3.4e38f;
            for (int k = 0; k < 4; ++k) {
                const int sx = c.x + 2 * x + (k & 1), sy = c.y + 2 * y + (k >> 1);
                const float sg = shade ? shading_gain(meta.shading, shading_channel[static_cast<size_t>(k)],
                                                      static_cast<float>(sx) * inv_sw, static_cast<float>(sy) * inv_sh)
                                       : 1.0f;
                const float g = sg * sol.wb_gains[static_cast<size_t>(impl_->colors[static_cast<size_t>(k)])];
                gains[static_cast<size_t>(k)].at(x, y) = g;
                lo = std::min(lo, g);
            }
            clip.at(x, y) = lo;
        }
    }, 8);

    double ms_align = 0, ms_merge = 0;
    double t = now_ms();
    // Coverage needed grows with the scale: ~0.27 px mean gap at 2x per side, more lenient below.
    const float max_gap = 0.27f * std::sqrt(2.0f / std::max(1.0f, opt.super_resolution));
    SuperResOutput sr = super_resolve(impl_->frames, W, H, opt.super_resolution, impl_->norm, impl_->colors, meta, gains, clip,
                                      ms_align, ms_merge, nullptr, max_gap);
    if (sr.declined) {
        stats.super_resolution = 1.0f;
        stats.subpixel_spread = sr.subpixel_spread;
        stats.coverage_gap = sr.coverage_gap;
        stats.ms_align = static_cast<float>(ms_align);
        return stats;
    }
    stats.coverage_gap = sr.coverage_gap;
    impl_->frames.clear();
    impl_->frames.shrink_to_fit();
    impl_->processed = true;
    stats.reference_index = sr.reference;
    stats.frames_merged = sr.contributing;
    stats.mean_merge_weight = sr.mean_weight;
    stats.super_resolution = opt.super_resolution;
    stats.subpixel_spread = sr.subpixel_spread;
    // Each output pixel averages fewer samples than a same-size merge: noise gain from the frames at sensor scale.
    const float n_eff = std::max(1.0f, (1.0f + sr.mean_weight * static_cast<float>(std::max(0, stats.frames_added - 1))) /
                                           (opt.super_resolution * opt.super_resolution));
    stats.noise_reduction_db = 10.0f * std::log10(n_eff);
    stats.ms_align = static_cast<float>(ms_prepare + ms_align);
    stats.ms_merge = static_cast<float>(ms_merge);
    stats.ms_demosaic = static_cast<float>(now_ms() - t - ms_align - ms_merge);
    ProcessOptions o = opt;
    o.output_width = sr.r.w;
    o.output_height = sr.r.h;
    render_tail(sr.r, sr.g, sr.b, sol, o, meta, n_eff, rgba, stride, stats);
    stats.ms_total = static_cast<float>(now_ms() - t_start);
    return stats;
}

bool Burst::merged_mosaic(uint16_t* out) const {
    if (!out || !impl_->processed) return false;
    const MergeResult& m = impl_->merged;
    // A super-resolved burst has no same-size merged mosaic (its frames were gathered onto a denser grid).
    if (m.planes[0].v.empty()) return false;
    const int W = impl_->crop.width, H = impl_->crop.height;
    const float white = impl_->meta.white_level;
    parallel_for(0, H / 2, [&](int y) {
        for (int x = 0; x < W / 2; ++x)
            for (int k = 0; k < 4; ++k) {
                const float black = impl_->norm.black[static_cast<size_t>(k)];
                const float v = m.planes[static_cast<size_t>(k)].at(x, y) * (white - black) + black;
                out[static_cast<size_t>(2 * y + (k >> 1)) * static_cast<size_t>(W) + static_cast<size_t>(2 * x + (k & 1))] =
                    static_cast<uint16_t>(std::clamp(v + 0.5f, 0.0f, white));
            }
    }, 16);
    return true;
}

std::string describe(const ProcessStats& s) {
    char buf[512];
    std::snprintf(buf, sizeof(buf),
                  "frames %d (merged %d, ref %d, weight %.2f, +%.1f dB) defects %d ev %+.2f key %.2f dr %.1f out %dx%d | "
                  "align %.0f merge %.0f demosaic %.0f color %.0f tone %.0f finish %.0f total %.0f ms",
                  s.frames_added, s.frames_merged, s.reference_index, s.mean_merge_weight, s.noise_reduction_db,
                  s.defects_corrected, s.applied_ev, s.scene_key, s.dynamic_range_ev, s.output_width, s.output_height, s.ms_align, s.ms_merge,
                  s.ms_demosaic, s.ms_color, s.ms_tone, s.ms_finish, s.ms_total);
    return buf;
}

}  // namespace luma::engine
