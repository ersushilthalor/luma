// Burst alignment and robust merge on half-resolution Bayer planes.
//
// Alignment: coarse-to-fine tile search on a mean-green pyramid (4 levels, factor 2), L1 distance.
// Merge: 16x16 half-resolution tiles with 50 % overlap and raised-cosine windows (they sum to one),
// per-pixel robustness from 5x5 box means of half-resolution green at the aligned offset, compared against the
// sensor noise model plus a local-variance allowance. Moving content falls back to the reference.

#include <cmath>

#include "internal.h"

namespace luma::engine::detail {
namespace {

constexpr int kLevels = 4;
constexpr int kTile0 = 16;
constexpr int kStride0 = 8;
constexpr int kTileN = 8;
constexpr int kStrideN = 4;
constexpr int kRadius[kLevels] = {1, 2, 2, 4};
constexpr int kGuideRadius = 2;                       // 5x5 half-res box
constexpr float kGuideSamples = 50.0f;                 // green samples per box mean

struct Offset {
    int dx = 0;
    int dy = 0;
};

struct Grid {
    int nx = 0, ny = 0, tile = 0, stride = 0;
    std::vector<Offset> offsets;
    Offset& at(int tx, int ty) { return offsets[static_cast<size_t>(ty * nx + tx)]; }
    const Offset& at(int tx, int ty) const { return offsets[static_cast<size_t>(ty * nx + tx)]; }
};

Grid make_grid(int w, int h, int tile, int stride) {
    Grid g;
    g.tile = tile;
    g.stride = stride;
    g.nx = (w + stride - 1) / stride + 1;
    g.ny = (h + stride - 1) / stride + 1;
    g.offsets.assign(static_cast<size_t>(g.nx * g.ny), Offset{});
    return g;
}

inline float half_value(const FrameData& f, int width, int x, int y, int k, int w2, int h2, const SensorNorm& norm) {
    x = std::clamp(x, 0, w2 - 1);
    y = std::clamp(y, 0, h2 - 1);
    const int sx = 2 * x + (k & 1);
    const int sy = 2 * y + (k >> 1);
    const float raw = static_cast<float>(f.mosaic[static_cast<size_t>(sy) * static_cast<size_t>(width) + static_cast<size_t>(sx)]);
    return (raw - norm.black[static_cast<size_t>(k)]) * norm.inv_range[static_cast<size_t>(k)] * f.scale;
}

float sharpness(const Plane& p) {
    double s = 0;
    for (int y = 1; y < p.h; y += 2) {
        const float* r = p.row(y);
        const float* u = p.row(y - 1);
        for (int x = 1; x < p.w; x += 2) s += std::abs(r[x] - r[x - 1]) + std::abs(r[x] - u[x]);
    }
    return static_cast<float>(s);
}

float tile_sad(const Plane& ref, const Plane& alt, int ox, int oy, int tile, int dx, int dy, float best) {
    float sad = 0;
    for (int y = oy; y < oy + tile; ++y) {
        if (y < 0 || y >= ref.h) continue;
        const int ay = std::clamp(y + dy, 0, alt.h - 1);
        const float* r = ref.row(y);
        const float* a = alt.row(ay);
        for (int x = std::max(ox, 0); x < std::min(ox + tile, ref.w); ++x) {
            sad += std::abs(r[x] - a[std::clamp(x + dx, 0, alt.w - 1)]);
        }
        if (sad >= best) return sad;
    }
    return sad;
}

/// Aligns `alt` to `ref`; returns level-0 offsets on the merge tile grid (half-res pixels).
Grid align(const FrameData& ref, const FrameData& alt) {
    Grid coarser;
    for (int level = kLevels - 1; level >= 0; --level) {
        const Plane& rp = ref.pyramid[static_cast<size_t>(level)];
        const Plane& ap = alt.pyramid[static_cast<size_t>(level)];
        const int tile = level == 0 ? kTile0 : kTileN;
        const int stride = level == 0 ? kStride0 : kStrideN;
        Grid grid = make_grid(rp.w, rp.h, tile, stride);
        const int radius = kRadius[level];
        parallel_for(0, grid.ny, [&](int ty) {
            for (int tx = 0; tx < grid.nx; ++tx) {
                const int ox = tx * stride - stride;
                const int oy = ty * stride - stride;
                Offset guess;
                if (!coarser.offsets.empty()) {
                    const int cx = std::clamp((tx * stride / 2 + coarser.stride / 2) / coarser.stride, 0, coarser.nx - 1);
                    const int cy = std::clamp((ty * stride / 2 + coarser.stride / 2) / coarser.stride, 0, coarser.ny - 1);
                    guess = coarser.at(cx, cy);
                    guess.dx *= 2;
                    guess.dy *= 2;
                }
                Offset best = guess;
                float best_sad = tile_sad(rp, ap, ox, oy, tile, guess.dx, guess.dy, 3.4e38f);
                for (int dy = -radius; dy <= radius; ++dy)
                    for (int dx = -radius; dx <= radius; ++dx) {
                        if (dx == 0 && dy == 0) continue;
                        const float sad = tile_sad(rp, ap, ox, oy, tile, guess.dx + dx, guess.dy + dy, best_sad);
                        if (sad < best_sad) {
                            best_sad = sad;
                            best = {guess.dx + dx, guess.dy + dy};
                        }
                    }
                grid.at(tx, ty) = best;
            }
        }, 2);
        coarser = std::move(grid);
    }
    return coarser;
}

inline float noise_var(const RawMetadata& meta, float x) {
    x = std::max(x, 0.0f);
    float v = meta.noise_scale * x + meta.noise_offset;
    if (v <= 0.0f) v = 4e-4f * x + 1e-5f;  // fallback when the HAL reports no noise model
    return v + 1e-7f;
}

}  // namespace

void prepare_frame(FrameData& frame, int width, int height, const SensorNorm& norm, const std::array<int, 4>& colors) {
    const int w2 = width / 2, h2 = height / 2;
    frame.green = Plane(w2, h2);
    int g0 = -1, g1 = -1;
    for (int k = 0; k < 4; ++k)
        if (colors[static_cast<size_t>(k)] == 1) (g0 < 0 ? g0 : g1) = k;
    parallel_for(0, h2, [&](int y) {
        float* out = frame.green.row(y);
        for (int x = 0; x < w2; ++x)
            out[x] = 0.5f * (half_value(frame, width, x, y, g0, w2, h2, norm) + half_value(frame, width, x, y, g1, w2, h2, norm));
    }, 16);
    frame.pyramid.clear();
    frame.pyramid.push_back(frame.green);
    for (int level = 1; level < kLevels; ++level) frame.pyramid.push_back(downsample2(frame.pyramid.back()));
    frame.green_mean = box_blur(frame.green, kGuideRadius);
}

MergeResult align_and_merge(std::vector<FrameData>& frames, int width, int height, const SensorNorm& norm,
                            const std::array<int, 4>& /*colors*/, const RawMetadata& meta, bool merge,
                            double& ms_align, double& ms_merge) {
    const int w2 = width / 2, h2 = height / 2;
    MergeResult result;
    const int n = static_cast<int>(frames.size());

    // Reference: sharpest of the first three frames (least shake), which also keeps latency low.
    int ref = 0;
    float best = -1;
    for (int i = 0; i < std::min(n, 3); ++i) {
        const float s = sharpness(frames[static_cast<size_t>(i)].pyramid[1]);
        if (s > best) {
            best = s;
            ref = i;
        }
    }
    result.reference = ref;
    const FrameData& R = frames[static_cast<size_t>(ref)];

    for (auto& p : result.planes) p = Plane(w2, h2);
    if (!merge || n == 1) {
        parallel_for(0, h2, [&](int y) {
            for (int k = 0; k < 4; ++k) {
                float* out = result.planes[static_cast<size_t>(k)].row(y);
                for (int x = 0; x < w2; ++x) out[x] = half_value(R, width, x, y, k, w2, h2, norm);
            }
        }, 16);
        return result;
    }

    double t0 = now_ms();
    std::vector<int> alts;
    std::vector<Grid> grids;
    for (int i = 0; i < n; ++i) {
        if (i == ref) continue;
        alts.push_back(i);
        grids.push_back(align(R, frames[static_cast<size_t>(i)]));
    }
    ms_align = now_ms() - t0;
    t0 = now_ms();

    // Local variance of the reference green (tolerates sub-pixel misalignment on edges).
    Plane ref_var(w2, h2);
    {
        Plane sq(w2, h2);
        for (size_t i = 0; i < sq.v.size(); ++i) sq.v[i] = R.green.v[i] * R.green.v[i];
        const Plane m2 = box_blur(sq, kGuideRadius);
        for (size_t i = 0; i < sq.v.size(); ++i) {
            const float m = R.green_mean.v[i];
            ref_var.v[i] = std::max(0.0f, m2.v[i] - m * m);
        }
    }

    float window[kTile0];
    for (int i = 0; i < kTile0; ++i)
        window[i] = 0.5f - 0.5f * std::cos(2.0f * 3.14159265f * (static_cast<float>(i) + 0.5f) / static_cast<float>(kTile0));

    std::array<Plane, 4> num{Plane(w2, h2), Plane(w2, h2), Plane(w2, h2), Plane(w2, h2)};
    Plane den(w2, h2);
    const Grid& g0 = grids.front();
    const int nalts = static_cast<int>(alts.size());
    std::vector<double> weight_sum(static_cast<size_t>(g0.ny) * static_cast<size_t>(nalts), 0.0);
    std::vector<double> weight_count(static_cast<size_t>(g0.ny), 0.0);
    constexpr float k_align = 0.02f;

    auto merge_row = [&](int ty) {
        std::vector<float> wt(static_cast<size_t>(nalts) * kTile0 * kTile0);
        float tile_acc[4][kTile0 * kTile0];
        float tile_w[kTile0 * kTile0];
        for (int tx = 0; tx < g0.nx; ++tx) {
            const int ox = tx * kStride0 - kStride0;
            const int oy = ty * kStride0 - kStride0;
            // Per-pixel robustness from 5x5 green means at the exact aligned offset.
            for (int a = 0; a < nalts; ++a) {
                const FrameData& A = frames[static_cast<size_t>(alts[static_cast<size_t>(a)])];
                const Offset off = grids[static_cast<size_t>(a)].at(tx, ty);
                double tile_weight = 0;
                int tile_count = 0;
                for (int y = 0; y < kTile0; ++y)
                    for (int x = 0; x < kTile0; ++x) {
                        const int hx = ox + x, hy = oy + y;
                        float w = 0;
                        if (hx >= 0 && hy >= 0 && hx < w2 && hy < h2) {
                            const float mr = R.green_mean.at(hx, hy);
                            const float d = A.green_mean.clamped(hx + off.dx, hy + off.dy) - mr;
                            const float var = 2.0f * noise_var(meta, mr) / kGuideSamples + k_align * ref_var.at(hx, hy) + 1e-9f;
                            const float dn2 = d * d / var;
                            w = std::exp(-std::max(0.0f, dn2 - 4.0f) / 8.0f);
                            if (w < 0.03f) w = 0;
                            tile_weight += w;
                            ++tile_count;
                        }
                        wt[static_cast<size_t>(a * kTile0 * kTile0 + y * kTile0 + x)] = w;
                    }
                if (tile_count > 0) weight_sum[static_cast<size_t>(ty * nalts + a)] += tile_weight / tile_count;
            }
            weight_count[static_cast<size_t>(ty)] += 1.0;

            // Weighted temporal average within the tile.
            for (int y = 0; y < kTile0; ++y)
                for (int x = 0; x < kTile0; ++x) {
                    const int hx = ox + x, hy = oy + y;
                    const int i = y * kTile0 + x;
                    tile_w[i] = 0;
                    if (hx < 0 || hy < 0 || hx >= w2 || hy >= h2) continue;
                    float wsum = 1.0f;
                    float acc[4];
                    for (int k = 0; k < 4; ++k) acc[k] = half_value(R, width, hx, hy, k, w2, h2, norm);
                    for (int a = 0; a < nalts; ++a) {
                        const float w = wt[static_cast<size_t>(a * kTile0 * kTile0 + i)];
                        if (w <= 0.0f) continue;
                        const FrameData& A = frames[static_cast<size_t>(alts[static_cast<size_t>(a)])];
                        const Offset off = grids[static_cast<size_t>(a)].at(tx, ty);
                        for (int k = 0; k < 4; ++k) acc[k] += w * half_value(A, width, hx + off.dx, hy + off.dy, k, w2, h2, norm);
                        wsum += w;
                    }
                    const float inv = 1.0f / wsum;
                    for (int k = 0; k < 4; ++k) tile_acc[k][i] = acc[k] * inv;
                    tile_w[i] = window[x] * window[y];
                }
            for (int y = 0; y < kTile0; ++y) {
                const int hy = oy + y;
                if (hy < 0 || hy >= h2) continue;
                for (int x = 0; x < kTile0; ++x) {
                    const int hx = ox + x;
                    if (hx < 0 || hx >= w2) continue;
                    const int i = y * kTile0 + x;
                    const float win = tile_w[i];
                    for (int k = 0; k < 4; ++k) num[static_cast<size_t>(k)].at(hx, hy) += win * tile_acc[k][i];
                    den.at(hx, hy) += win;
                }
            }
        }
    };
    // Tile rows two apart never overlap, so even and odd rows each run in parallel safely.
    for (int parity = 0; parity < 2; ++parity) {
        const int rows = (g0.ny - parity + 1) / 2;
        parallel_for(0, rows, [&](int r) { merge_row(2 * r + parity); });
    }

    parallel_for(0, h2, [&](int y) {
        for (int x = 0; x < w2; ++x) {
            const float d = den.at(x, y);
            for (int k = 0; k < 4; ++k) {
                result.planes[static_cast<size_t>(k)].at(x, y) =
                    d > 1e-6f ? num[static_cast<size_t>(k)].at(x, y) / d : half_value(R, width, x, y, k, w2, h2, norm);
            }
        }
    }, 16);

    double total_count = 0;
    for (double c : weight_count) total_count += c;
    float mean_all = 0;
    result.contributing = 1;
    for (int a = 0; a < nalts; ++a) {
        double s = 0;
        for (int ty = 0; ty < g0.ny; ++ty) s += weight_sum[static_cast<size_t>(ty * nalts + a)];
        const float mean = total_count > 0 ? static_cast<float>(s / total_count) : 0.0f;
        mean_all += mean;
        if (mean > 0.15f) ++result.contributing;
    }
    result.mean_weight = nalts > 0 ? mean_all / static_cast<float>(nalts) : 0.0f;
    ms_merge = now_ms() - t0;
    return result;
}

// ---------------------------------------------------------------------------------------------
// Handheld multi-frame super-resolution (after Wronski et al. 2019): raw Bayer samples of every frame are
// placed at their sub-pixel aligned position and gathered per output pixel on a grid `scale` times denser
// than the sensor, separately for R, G and B. Demosaicing and super-resolution happen in one step, from
// real samples only; hand tremor between frames supplies the sub-pixel diversity.

namespace {

struct SubOffset {
    float dx = 0;  // half-resolution pixels (alt = ref + offset)
    float dy = 0;
};


inline float bilinear(const Plane& p, float x, float y) {
    x = std::clamp(x, 0.0f, static_cast<float>(p.w - 1));
    y = std::clamp(y, 0.0f, static_cast<float>(p.h - 1));
    const int x0 = std::min(static_cast<int>(x), p.w - 2 < 0 ? 0 : p.w - 2);
    const int y0 = std::min(static_cast<int>(y), p.h - 2 < 0 ? 0 : p.h - 2);
    const int x1 = std::min(x0 + 1, p.w - 1), y1 = std::min(y0 + 1, p.h - 1);
    const float fx = x - static_cast<float>(x0), fy = y - static_cast<float>(y0);
    return lerp(lerp(p.at(x0, y0), p.at(x1, y0), fx), lerp(p.at(x0, y1), p.at(x1, y1), fx), fy);
}

/// Global motion of one frame relative to the reference, half-resolution pixels:
/// dx = p0 + p1 u + p2 v, dy = p3 + p4 u + p5 v with u, v = position relative to the centre / width
/// (translation plus the rotation, scale and rolling-shutter shear of a handheld burst).
struct Motion {
    double p[6] = {0, 0, 0, 0, 0, 0};
    double cx = 0, cy = 0, s = 1;
    SubOffset at(double x, double y) const {
        const double u = (x - cx) / s, v = (y - cy) / s;
        return {static_cast<float>(p[0] + p[1] * u + p[2] * v), static_cast<float>(p[3] + p[4] * u + p[5] * v)};
    }
};

/// Blurred pyramid of half-resolution green: level 0 lightly blurred, each next level 2x2-averaged.
std::vector<Plane> blur_pyramid(const Plane& green, int levels) {
    std::vector<Plane> pyr;
    pyr.push_back(gaussian_blur(green, 1.0f));
    for (int l = 1; l < levels; ++l) {
        const Plane b = gaussian_blur(pyr.back(), 1.0f);
        Plane o(std::max(1, b.w / 2), std::max(1, b.h / 2));
        parallel_for(0, o.h, [&](int y) {
            const int y1 = std::min(2 * y + 1, b.h - 1);
            for (int x = 0; x < o.w; ++x) {
                const int x1 = std::min(2 * x + 1, b.w - 1);
                o.at(x, y) = 0.25f * (b.at(2 * x, 2 * y) + b.at(x1, 2 * y) + b.at(2 * x, y1) + b.at(x1, y1));
            }
        }, 16);
        pyr.push_back(std::move(o));
    }
    return pyr;
}

/// Solves the n x n system a x = b in place (Gaussian elimination, partial pivoting). False if singular.
bool solve(double a[6][6], double b[6], int n) {
    for (int c = 0; c < n; ++c) {
        int piv = c;
        for (int r = c + 1; r < n; ++r)
            if (std::abs(a[r][c]) > std::abs(a[piv][c])) piv = r;
        if (std::abs(a[piv][c]) < 1e-18) return false;
        if (piv != c) {
            for (int k = 0; k < n; ++k) std::swap(a[c][k], a[piv][k]);
            std::swap(b[c], b[piv]);
        }
        for (int r = c + 1; r < n; ++r) {
            const double f = a[r][c] / a[c][c];
            for (int k = c; k < n; ++k) a[r][k] -= f * a[c][k];
            b[r] -= f * b[c];
        }
    }
    for (int c = n - 1; c >= 0; --c) {
        for (int k = c + 1; k < n; ++k) b[c] -= a[c][k] * b[k];
        b[c] /= a[c][c];
    }
    return true;
}

/// Estimates the global motion of `alt` against `ref` (both blur pyramids): exhaustive integer search at the
/// coarsest level (fine periodic texture is blurred away there and cannot produce false matches), then robust
/// (Cauchy-weighted) Gauss-Newton on the six-parameter model, level by level down to half resolution. Local
/// movers are down-weighted here and rejected per pixel by the merge.
Motion estimate_motion(const std::vector<Plane>& ref, const std::vector<Plane>& alt) {
    Motion m;
    const Plane& r0 = ref[0];
    m.cx = 0.5 * (r0.w - 1);
    m.cy = 0.5 * (r0.h - 1);
    m.s = std::max(1, r0.w);
    const int top = static_cast<int>(ref.size()) - 1;
    {
        const Plane& rp = ref[static_cast<size_t>(top)];
        const Plane& ap = alt[static_cast<size_t>(top)];
        constexpr int kR = 4;
        double best = 1e300;
        int bx = 0, by = 0;
        for (int dy = -kR; dy <= kR; ++dy)
            for (int dx = -kR; dx <= kR; ++dx) {
                double ssd = 0;
                long cnt = 0;
                for (int y = kR; y < rp.h - kR; ++y)
                    for (int x = kR; x < rp.w - kR; ++x) {
                        const double e = ap.at(x + dx, y + dy) - rp.at(x, y);
                        ssd += e * e;
                        ++cnt;
                    }
                if (cnt > 0 && ssd / static_cast<double>(cnt) < best) {
                    best = ssd / static_cast<double>(cnt);
                    bx = dx;
                    by = dy;
                }
            }
        m.p[0] = bx * static_cast<double>(1 << top);
        m.p[3] = by * static_cast<double>(1 << top);
    }
    for (int l = top; l >= 0; --l) {
        const Plane& rp = ref[static_cast<size_t>(l)];
        const Plane& ap = alt[static_cast<size_t>(l)];
        const double f = static_cast<double>(1 << l);
        const double off = 0.5 * (f - 1.0);
        const int step = l == 0 ? 3 : 1;
        const int np = (l >= top - 1 && top > 1) ? 2 : 6;  // translation only while the image is tiny
        double scale_e = -1;
        for (int it = 0; it < (l == 0 ? 5 : 8); ++it) {
            double h[6][6] = {}, g[6] = {};
            double abs_sum = 0;
            long cnt = 0;
            for (int y = 2; y < rp.h - 2; y += step)
                for (int x = 2; x < rp.w - 2; x += step) {
                    const double x0 = f * x + off, y0 = f * y + off;
                    const SubOffset d = m.at(x0, y0);
                    const float wx = static_cast<float>(x + d.dx / f), wy = static_cast<float>(y + d.dy / f);
                    if (wx < 1 || wy < 1 || wx > static_cast<float>(ap.w - 2) || wy > static_cast<float>(ap.h - 2)) continue;
                    const double e = bilinear(ap, wx, wy) - rp.at(x, y);
                    const double gx = 0.5 * (rp.at(x + 1, y) - rp.at(x - 1, y)) / f;
                    const double gy = 0.5 * (rp.at(x, y + 1) - rp.at(x, y - 1)) / f;
                    const double u = (x0 - m.cx) / m.s, v = (y0 - m.cy) / m.s;
                    const double w = scale_e > 0 ? 1.0 / (1.0 + (e / scale_e) * (e / scale_e)) : 1.0;
                    abs_sum += std::abs(e);
                    ++cnt;
                    double j[6];
                    if (np == 2) {
                        j[0] = gx;
                        j[1] = gy;
                    } else {
                        j[0] = gx; j[1] = gx * u; j[2] = gx * v; j[3] = gy; j[4] = gy * u; j[5] = gy * v;
                    }
                    for (int a = 0; a < np; ++a) {
                        g[a] += w * j[a] * e;
                        for (int b = a; b < np; ++b) h[a][b] += w * j[a] * j[b];
                    }
                }
            if (cnt < 16) break;
            for (int a = 0; a < np; ++a)
                for (int b = 0; b < a; ++b) h[a][b] = h[b][a];
            double tr = 0;
            for (int a = 0; a < np; ++a) tr += h[a][a];
            for (int a = 0; a < np; ++a) h[a][a] += 1e-6 * tr / np + 1e-12;  // keeps flat scenes well-posed
            double delta[6];
            for (int a = 0; a < np; ++a) delta[a] = -g[a];
            if (!solve(h, delta, np)) break;
            if (np == 2) {
                m.p[0] += delta[0];
                m.p[3] += delta[1];
            } else {
                for (int a = 0; a < 6; ++a) m.p[a] += delta[a];
            }
            scale_e = std::max(1e-5, 1.5 * abs_sum / static_cast<double>(cnt));
            if (std::abs(delta[0]) < 1e-3 && std::abs(np == 2 ? delta[1] : delta[3]) < 1e-3) break;
        }
    }
    return m;
}

/// Offset field at a half-resolution position: bilinear between tile centres (centres sit at multiples of the
/// tile stride).
inline SubOffset field_at(const std::vector<SubOffset>& f, const Grid& g, float hx, float hy) {
    const float gx = std::clamp(hx / static_cast<float>(g.stride), 0.0f, static_cast<float>(g.nx - 1));
    const float gy = std::clamp(hy / static_cast<float>(g.stride), 0.0f, static_cast<float>(g.ny - 1));
    const int x0 = std::min(static_cast<int>(gx), g.nx - 2 < 0 ? 0 : g.nx - 2);
    const int y0 = std::min(static_cast<int>(gy), g.ny - 2 < 0 ? 0 : g.ny - 2);
    const int x1 = std::min(x0 + 1, g.nx - 1);
    const int y1 = std::min(y0 + 1, g.ny - 1);
    const float fx = gx - static_cast<float>(x0);
    const float fy = gy - static_cast<float>(y0);
    const SubOffset a = f[static_cast<size_t>(y0 * g.nx + x0)], b = f[static_cast<size_t>(y0 * g.nx + x1)];
    const SubOffset c = f[static_cast<size_t>(y1 * g.nx + x0)], d = f[static_cast<size_t>(y1 * g.nx + x1)];
    return {lerp(lerp(a.dx, b.dx, fx), lerp(c.dx, d.dx, fx), fy), lerp(lerp(a.dy, b.dy, fx), lerp(c.dy, d.dy, fx), fy)};
}


}  // namespace

SuperResOutput super_resolve(std::vector<FrameData>& frames, int width, int height, float scale, const SensorNorm& norm,
                             const std::array<int, 4>& colors, const RawMetadata& meta,
                             const std::array<Plane, 4>& gains, const Plane& clip, double& ms_align, double& ms_merge,
                             const std::vector<std::array<float, 2>>* known_shifts, float max_gap) {
    SuperResOutput out;
    const int n = static_cast<int>(frames.size());
    const int w2 = width / 2, h2 = height / 2;
    const int ow = std::max(2, static_cast<int>(std::lround(width * scale)) & ~1);
    const int oh = std::max(2, static_cast<int>(std::lround(height * scale)) & ~1);
    const float sx = static_cast<float>(width) / static_cast<float>(ow);
    const float sy = static_cast<float>(height) / static_cast<float>(oh);

    // Reference: the sharpest frame of the whole burst.
    int ref = 0;
    float best = -1;
    for (int i = 0; i < n && !known_shifts; ++i) {
        const float s = sharpness(frames[static_cast<size_t>(i)].pyramid[1]);
        if (s > best) {
            best = s;
            ref = i;
        }
    }
    out.reference = ref;
    const FrameData& R = frames[static_cast<size_t>(ref)];

    double t0 = now_ms();
    const int levels = std::clamp(static_cast<int>(std::floor(std::log2(std::max(16, std::min(w2, h2)) / 16.0))) + 1, 1, 5);
    const std::vector<Plane> ref_pyr = blur_pyramid(R.green, levels);
    std::vector<int> order{ref};
    std::vector<Grid> grids;
    std::vector<std::vector<SubOffset>> fields;
    for (int i = 0; i < n; ++i) {
        if (i == ref) continue;
        order.push_back(i);
        grids.push_back(make_grid(w2, h2, kTile0, kStride0));
        const Grid& g = grids.back();
        std::vector<SubOffset> f(g.offsets.size());
        if (known_shifts) {
            // Test hook: exact alt-minus-ref offsets (raw pixels) instead of the estimate.
            const auto& s = (*known_shifts)[static_cast<size_t>(i)];
            const auto& r0 = (*known_shifts)[static_cast<size_t>(ref)];
            for (auto& o : f) o = {0.5f * (s[0] - r0[0]), 0.5f * (s[1] - r0[1])};
        } else {
            const Motion m = estimate_motion(ref_pyr, blur_pyramid(frames[static_cast<size_t>(i)].green, levels));
            for (int ty = 0; ty < g.ny; ++ty)
                for (int tx = 0; tx < g.nx; ++tx) f[static_cast<size_t>(ty * g.nx + tx)] = m.at(tx * g.stride, ty * g.stride);
        }
        fields.push_back(std::move(f));
    }
    out.shift.assign(static_cast<size_t>(n), {0.0f, 0.0f});
    for (size_t k = 0; k < fields.size(); ++k) {
        double ax = 0, ay = 0;
        for (const auto& o : fields[k]) { ax += o.dx; ay += o.dy; }
        const double c = static_cast<double>(std::max<size_t>(1, fields[k].size()));
        out.shift[static_cast<size_t>(order[k + 1])] = {static_cast<float>(2.0 * ax / c), static_cast<float>(2.0 * ay / c)};
    }
    // Sub-pixel diversity: RMS of the fractional raw-pixel shifts (uniform shake ~0.29; tripod ~0).
    double frac2 = 0;
    size_t nfrac = 0;
    for (const auto& f : fields)
        for (const auto& o : f) {
            const float fx = 2.0f * o.dx - std::round(2.0f * o.dx);
            const float fy = 2.0f * o.dy - std::round(2.0f * o.dy);
            frac2 += 0.5 * (fx * fx + fy * fy);
            ++nfrac;
        }
    out.subpixel_spread = nfrac > 0 ? static_cast<float>(std::sqrt(frac2 / static_cast<double>(nfrac))) : 0.0f;
    // Sample coverage: over a 2x2 CFA cell (it repeats), the mean distance from a point to the nearest green
    // sample of any frame, using each frame's offset at the image centre. Hand-held ~0.2, tripod ~0.5.
    {
        std::vector<std::array<float, 2>> greens;
        for (int k = 0; k < 4; ++k)
            if (colors[static_cast<size_t>(k)] == 1) greens.push_back({static_cast<float>(k & 1), static_cast<float>(k >> 1)});
        std::vector<std::array<float, 2>> offs{{0.0f, 0.0f}};
        for (size_t k = 0; k < fields.size(); ++k) {
            const SubOffset d = field_at(fields[k], grids[k], 0.5f * static_cast<float>(w2), 0.5f * static_cast<float>(h2));
            offs.push_back({2.0f * d.dx, 2.0f * d.dy});
        }
        constexpr int kPts = 10;
        double sum = 0;
        for (int j = 0; j < kPts; ++j)
            for (int i = 0; i < kPts; ++i) {
                const float px = 2.0f * (static_cast<float>(i) + 0.5f) / kPts, py = 2.0f * (static_cast<float>(j) + 0.5f) / kPts;
                float nearest = 9.0f;
                for (const auto& o : offs)
                    for (const auto& g : greens) {
                        // Alt sample at raw g lands at reference position g - offset; the lattice repeats every 2 px.
                        float ex = px - (g[0] - o[0]), ey = py - (g[1] - o[1]);
                        ex -= 2.0f * std::floor((ex + 1.0f) * 0.5f);
                        ey -= 2.0f * std::floor((ey + 1.0f) * 0.5f);
                        nearest = std::min(nearest, std::sqrt(ex * ex + ey * ey));
                    }
                sum += nearest;
            }
        out.coverage_gap = static_cast<float>(sum / (kPts * kPts));
    }
    ms_align = now_ms() - t0;
    if (out.coverage_gap > max_gap) {
        out.declined = true;
        return out;
    }
    t0 = now_ms();

    // Local variance of the reference green (tolerates residual misalignment on edges).
    Plane ref_var(w2, h2);
    {
        Plane sq(w2, h2);
        for (size_t i = 0; i < sq.v.size(); ++i) sq.v[i] = R.green.v[i] * R.green.v[i];
        const Plane m2 = box_blur(sq, kGuideRadius);
        for (size_t i = 0; i < sq.v.size(); ++i) ref_var.v[i] = std::max(0.0f, m2.v[i] - R.green_mean.v[i] * R.green_mean.v[i]);
    }

    // Gaussian kernel LUTs over squared raw-pixel distance: narrow (detail) and wide (fills sparse R/B coverage).
    constexpr int kLut = 256;
    constexpr float kMaxD2 = 9.0f;
    std::array<float, kLut + 1> narrow_g{}, narrow_rb{}, wide{};
    // Narrow kernels shrink with the output spacing, so a denser grid keeps its extra detail.
    const float sig_g = 0.42f * std::max(0.75f, std::min(sx, sy) * 1.5f);
    const float sig_rb = 0.60f * std::max(0.75f, std::min(sx, sy) * 1.5f);
    for (int i = 0; i <= kLut; ++i) {
        const float d2 = kMaxD2 * static_cast<float>(i) / kLut;
        narrow_g[static_cast<size_t>(i)] = std::exp(-d2 / (2.0f * sig_g * sig_g));
        narrow_rb[static_cast<size_t>(i)] = std::exp(-d2 / (2.0f * sig_rb * sig_rb));
        wide[static_cast<size_t>(i)] = std::exp(-d2 / (2.0f * 0.75f * 0.75f));
    }
    auto lut = [&](const std::array<float, kLut + 1>& t, float d2) {
        return d2 >= kMaxD2 ? 0.0f : t[static_cast<size_t>(d2 * (kLut / kMaxD2))];
    };

    out.r = Plane(ow, oh);
    out.g = Plane(ow, oh);
    out.b = Plane(ow, oh);
    std::vector<double> weight_rows(static_cast<size_t>(oh), 0.0);
    constexpr float k_align = 0.02f;

    parallel_for(0, oh, [&](int oy) {
        double wrow = 0;
        for (int ox = 0; ox < ow; ++ox) {
            const float rx = (static_cast<float>(ox) + 0.5f) * sx - 0.5f;  // reference raw coordinates
            const float ry = (static_cast<float>(oy) + 0.5f) * sy - 0.5f;
            const float hx = rx * 0.5f, hy = ry * 0.5f;
            const float mr = bilinear(R.green_mean, hx, hy);
            const float var_base = 2.0f * noise_var(meta, mr) / kGuideSamples + k_align * bilinear(ref_var, hx, hy) + 1e-9f;
            float nn[3] = {0, 0, 0}, nd[3] = {0, 0, 0}, wn[3] = {0, 0, 0}, wd[3] = {0, 0, 0};
            for (size_t fi = 0; fi < order.size(); ++fi) {
                const FrameData& F = frames[static_cast<size_t>(order[fi])];
                float qx = rx, qy = ry, robust = 1.0f;
                if (fi > 0) {
                    const SubOffset d = field_at(fields[fi - 1], grids[fi - 1], hx, hy);
                    qx += 2.0f * d.dx;
                    qy += 2.0f * d.dy;
                    const float diff = bilinear(F.green_mean, hx + d.dx, hy + d.dy) - mr;
                    const float dn2 = diff * diff / var_base;
                    robust = std::exp(-std::max(0.0f, dn2 - 4.0f) / 8.0f);
                    if (robust < 0.03f) continue;
                    wrow += robust;
                }
                // The 3x3 nearest raw samples (beyond 1.5 px the kernels carry no useful weight).
                const int x0 = static_cast<int>(std::lround(qx)) - 1;
                const int y0 = static_cast<int>(std::lround(qy)) - 1;
                for (int yy = y0; yy < y0 + 3; ++yy) {
                    if (yy < 0 || yy >= height) continue;
                    const float dy = static_cast<float>(yy) - qy;
                    for (int xx = x0; xx < x0 + 3; ++xx) {
                        if (xx < 0 || xx >= width) continue;
                        const float dx = static_cast<float>(xx) - qx;
                        const float d2 = dx * dx + dy * dy;
                        const int k = ((yy & 1) << 1) | (xx & 1);
                        const int col = colors[static_cast<size_t>(k)];
                        const float raw = static_cast<float>(F.mosaic[static_cast<size_t>(yy) * static_cast<size_t>(width) + static_cast<size_t>(xx)]);
                        const float g = gains[static_cast<size_t>(k)].at(xx >> 1, yy >> 1);
                        const float v = std::clamp((raw - norm.black[static_cast<size_t>(k)]) * norm.inv_range[static_cast<size_t>(k)] * F.scale * g,
                                                   0.0f, clip.at(xx >> 1, yy >> 1));
                        const float wnar = robust * lut(col == 1 ? narrow_g : narrow_rb, d2);
                        const float wwid = robust * lut(wide, d2);
                        nn[col] += wnar * v;
                        nd[col] += wnar;
                        wn[col] += wwid * v;
                        wd[col] += wwid;
                    }
                }
            }
            float rgb[3];
            for (int c = 0; c < 3; ++c) {
                const float wide_v = wd[c] > 1e-6f ? wn[c] / wd[c] : 0.0f;
                const float narrow_v = nd[c] > 1e-6f ? nn[c] / nd[c] : wide_v;
                // Too few nearby samples of this colour: lean on the wide estimate (no holes, no speckle).
                const float t = std::clamp(nd[c] / 0.35f, 0.0f, 1.0f);
                rgb[c] = lerp(wide_v, narrow_v, t);
            }
            out.r.at(ox, oy) = rgb[0];
            out.g.at(ox, oy) = rgb[1];
            out.b.at(ox, oy) = rgb[2];
        }
        weight_rows[static_cast<size_t>(oy)] = wrow / std::max(1, ow);
    }, 4);

    double total = 0;
    for (double w : weight_rows) total += w;
    const int nalts = n - 1;
    out.mean_weight = nalts > 0 ? static_cast<float>(total / static_cast<double>(oh) / nalts) : 0.0f;
    out.contributing = 1 + static_cast<int>(std::lround(out.mean_weight * nalts));
    ms_merge = now_ms() - t0;
    return out;
}

}  // namespace luma::engine::detail
