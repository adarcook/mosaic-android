cmake_minimum_required(VERSION 3.22)
project(mosaic_whisper LANGUAGES C CXX)
include(FetchContent)
# Native Debug builds must still optimize the inference hot path.
set(CMAKE_C_FLAGS_DEBUG "-O3 -g" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -g" CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# Pixel voice POC: enable ARM FP16 vector arithmetic and integer dot products.
# This debug-only POC requires ARMv8.2 FP16 + dotprod (Pixel 7 and Pixel 10).
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
# Reviewed v1.8.3. Fetch build source only; model weights are never bundled.
FetchContent_Declare(whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG 2eeeba56e9edd762b4b38467bab96c2517163158)
FetchContent_MakeAvailable(whisper)
add_library(mosaic_whisper SHARED bridge.cpp)
target_compile_features(mosaic_whisper PRIVATE cxx_std_17)
target_link_libraries(mosaic_whisper PRIVATE whisper)
