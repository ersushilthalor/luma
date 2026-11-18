#include "luma/engine/build_info.h"

namespace luma::engine {
namespace {

constexpr std::string_view architecture() noexcept {
#if defined(__aarch64__) || defined(_M_ARM64)
    return "arm64";
#elif defined(__x86_64__) || defined(_M_X64)
    return "x86_64";
#else
    return "unknown";
#endif
}

constexpr std::string_view compiler() noexcept {
#if defined(__clang__)
    return "clang " __clang_version__;
#elif defined(_MSC_VER)
    return "msvc";
#elif defined(__GNUC__)
    return "gcc " __VERSION__;
#else
    return "unknown";
#endif
}

constexpr bool neon() noexcept {
#if defined(__ARM_NEON)
    return true;
#else
    return false;
#endif
}

}  // namespace

BuildInfo build_info() noexcept {
    return BuildInfo{LUMA_ENGINE_VERSION, architecture(), compiler(), neon()};
}

}  // namespace luma::engine
