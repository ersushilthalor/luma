#pragma once

#include <string_view>

namespace luma::engine {

struct BuildInfo {
    std::string_view version;
    std::string_view architecture;
    std::string_view compiler;
    bool simd_neon;
};

BuildInfo build_info() noexcept;

}  // namespace luma::engine
