cmake_minimum_required(VERSION 3.22)

project(luma_engine VERSION 0.2.0 LANGUAGES CXX)

option(LUMA_ENGINE_TESTS "Build the desktop engine tests" OFF)

add_library(luma_engine STATIC
    src/build_info.cpp
    src/color.cpp
    src/filters.cpp
    src/align_merge.cpp
    src/demosaic.cpp
    src/tone.cpp
    src/finish.cpp
    src/pipeline.cpp
    src/resample.cpp
)

target_include_directories(luma_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_features(luma_engine PUBLIC cxx_std_20)
set_target_properties(luma_engine PROPERTIES CXX_EXTENSIONS OFF POSITION_INDEPENDENT_CODE ON)
target_compile_definitions(luma_engine PRIVATE LUMA_ENGINE_VERSION="${PROJECT_VERSION}")

find_package(Threads REQUIRED)
target_link_libraries(luma_engine PUBLIC Threads::Threads)

if(MSVC)
    target_compile_options(luma_engine PRIVATE /W4 /permissive- /O2 /fp:fast)
else()
    target_compile_options(luma_engine PRIVATE -Wall -Wextra -Wpedantic -Wshadow -O3 -ffp-contract=fast)
endif()

if(LUMA_ENGINE_TESTS)
    add_executable(luma_engine_tests tests/engine_tests.cpp tests/superres_tests.cpp)
    target_link_libraries(luma_engine_tests PRIVATE luma_engine)
endif()
