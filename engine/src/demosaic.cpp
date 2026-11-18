// Edge-directed demosaic: Hamilton-Adams style green interpolation with gradient-weighted blending,
// then colour-difference interpolation for red and blue. Works on a white-balanced float mosaic.

#include "internal.h"

namespace luma::engine::detail {

void demosaic(const Plane& m, const std::array<int, 4>& colors, Plane& r, Plane& g, Plane& b) {
    const int W = m.w, H = m.h;
    auto color = [&](int x, int y) { return colors[static_cast<size_t>(((y & 1) << 1) | (x & 1))]; };
    auto M = [&](int x, int y) { return m.at(reflect(x, W), reflect(y, H)); };

    g = Plane(W, H);
    // 1. Green everywhere.
    parallel_for(0, H, [&](int y) {
        float* out = g.row(y);
        for (int x = 0; x < W; ++x) {
            if (color(x, y) == 1) {
                out[x] = m.at(x, y);
                continue;
            }
            const float c = m.at(x, y);
            const float gl = M(x - 1, y), gr = M(x + 1, y), gu = M(x, y - 1), gd = M(x, y + 1);
            const float cl = M(x - 2, y), cr = M(x + 2, y), cu = M(x, y - 2), cd = M(x, y + 2);
            const float grad_h = std::abs(gl - gr) + std::abs(2.0f * c - cl - cr);
            const float grad_v = std::abs(gu - gd) + std::abs(2.0f * c - cu - cd);
            const float est_h = 0.5f * (gl + gr) + 0.25f * (2.0f * c - cl - cr);
            const float est_v = 0.5f * (gu + gd) + 0.25f * (2.0f * c - cu - cd);
            float est;
            if (grad_h < 0.5f * grad_v) {
                est = est_h;
            } else if (grad_v < 0.5f * grad_h) {
                est = est_v;
            } else {
                const float wh = 1.0f / (grad_h + 1e-5f), wv = 1.0f / (grad_v + 1e-5f);
                est = (wh * est_h + wv * est_v) / (wh + wv);
            }
            // Limit overshoot to the neighbourhood range (suppresses zipper artefacts).
            const float lo = std::min(std::min(gl, gr), std::min(gu, gd));
            const float hi = std::max(std::max(gl, gr), std::max(gu, gd));
            const float margin = 0.35f * (hi - lo);
            out[x] = std::clamp(est, lo - margin, hi + margin);
        }
    }, 8);

    // 2. Colour differences at native red / blue sites.
    Plane dr(W, H), db(W, H);
    parallel_for(0, H, [&](int y) {
        for (int x = 0; x < W; ++x) {
            const int c = color(x, y);
            if (c == 0) dr.at(x, y) = m.at(x, y) - g.at(x, y);
            else if (c == 2) db.at(x, y) = m.at(x, y) - g.at(x, y);
        }
    }, 16);
    auto D = [&](const Plane& d, int x, int y) { return d.at(reflect(x, W), reflect(y, H)); };

    r = Plane(W, H);
    b = Plane(W, H);
    parallel_for(0, H, [&](int y) {
        for (int x = 0; x < W; ++x) {
            const int c = color(x, y);
            const float gv = g.at(x, y);
            float rv, bv;
            if (c == 0) {
                rv = m.at(x, y);
                bv = gv + 0.25f * (D(db, x - 1, y - 1) + D(db, x + 1, y - 1) + D(db, x - 1, y + 1) + D(db, x + 1, y + 1));
            } else if (c == 2) {
                bv = m.at(x, y);
                rv = gv + 0.25f * (D(dr, x - 1, y - 1) + D(dr, x + 1, y - 1) + D(dr, x - 1, y + 1) + D(dr, x + 1, y + 1));
            } else {
                // Green site: one of R/B lies on this row, the other on this column.
                const bool red_on_row = color(x + 1 < W ? x + 1 : x - 1, y) == 0;
                const float h_diff_r = 0.5f * (D(dr, x - 1, y) + D(dr, x + 1, y));
                const float v_diff_r = 0.5f * (D(dr, x, y - 1) + D(dr, x, y + 1));
                const float h_diff_b = 0.5f * (D(db, x - 1, y) + D(db, x + 1, y));
                const float v_diff_b = 0.5f * (D(db, x, y - 1) + D(db, x, y + 1));
                rv = gv + (red_on_row ? h_diff_r : v_diff_r);
                bv = gv + (red_on_row ? v_diff_b : h_diff_b);
            }
            r.at(x, y) = rv;
            b.at(x, y) = bv;
        }
    }, 8);
}

}  // namespace luma::engine::detail
