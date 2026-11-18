cmake_minimum_required(VERSION 3.22)

project(luma_imaging LANGUAGES CXX)

if(NOT DEFINED LUMA_ENGINE_DIR)
    message(FATAL_ERROR "LUMA_ENGINE_DIR must point to the engine/ source directory")
endif()

add_subdirectory(${LUMA_ENGINE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/engine)

add_library(luma_imaging SHARED
    jni_bridge.cpp
)

target_compile_features(luma_imaging PRIVATE cxx_std_20)
target_compile_options(luma_imaging PRIVATE -Wall -Wextra -Wpedantic -fvisibility=hidden -O3)
target_link_libraries(luma_imaging PRIVATE luma_engine log jnigraphics)
target_link_options(luma_imaging PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
