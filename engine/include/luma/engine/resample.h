#pragma once

#include <cstdint>

namespace luma::engine {

/// Resamples an 8-bit RGBA image (row strides in bytes) with a separable Lanczos-3 kernel. When
/// shrinking, the kernel is widened by the reduction factor (area-correct, no aliasing). Works one
/// output row at a time, so even a 200 MP destination needs no full-size intermediate buffer.
/// Returns false on invalid arguments.
bool resample_rgba8(const uint8_t* src, int src_w, int src_h, int src_stride,
                    uint8_t* dst, int dst_w, int dst_h, int dst_stride) noexcept;

}  // namespace luma::engine
