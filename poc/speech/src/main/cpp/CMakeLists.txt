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
# Optional GPU experiment. Android uses the NDK loader and shader compiler;
# pin current C++ Vulkan headers separately from the NDK's older headers.
if(GGML_VULKAN)
    FetchContent_Declare(vulkan_headers
        GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
        GIT_TAG 1d9bcc9af77d93ba355b15994b9f82a130e9df3a)
    FetchContent_MakeAvailable(vulkan_headers)
    set(Vulkan_INCLUDE_DIR "${vulkan_headers_SOURCE_DIR}/include" CACHE PATH "" FORCE)
    find_library(ANDROID_VULKAN_LOADER vulkan REQUIRED)
    set(Vulkan_LIBRARY "${ANDROID_VULKAN_LOADER}" CACHE FILEPATH "" FORCE)
    if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(SHADER_HOST "windows-x86_64")
        set(SHADER_SUFFIX ".exe")
    elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        set(SHADER_HOST "darwin-x86_64")
        set(SHADER_SUFFIX "")
    else()
        set(SHADER_HOST "linux-x86_64")
        set(SHADER_SUFFIX "")
    endif()
    set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${SHADER_HOST}/glslc${SHADER_SUFFIX}" CACHE FILEPATH "" FORCE)
endif()
# Reviewed v1.8.3. Fetch build source only; model weights are never bundled.
FetchContent_Declare(whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG 2eeeba56e9edd762b4b38467bab96c2517163158)
FetchContent_MakeAvailable(whisper)
# v1.8.3 drops abort callbacks in scheduled encoder/decoder graphs.
# Apply an idempotent, version-pinned patch so cancellation reaches CPU kernels.
find_package(Git REQUIRED)
set(WHISPER_ABORT_PATCH "${CMAKE_CURRENT_SOURCE_DIR}/patches/whisper-abort.patch")
execute_process(COMMAND "${GIT_EXECUTABLE}" apply --reverse --check "${WHISPER_ABORT_PATCH}"
    WORKING_DIRECTORY "${whisper_SOURCE_DIR}" RESULT_VARIABLE PATCH_ALREADY_APPLIED
    OUTPUT_QUIET ERROR_QUIET)
if(NOT PATCH_ALREADY_APPLIED EQUAL 0)
    execute_process(COMMAND "${GIT_EXECUTABLE}" apply "${WHISPER_ABORT_PATCH}"
        WORKING_DIRECTORY "${whisper_SOURCE_DIR}" RESULT_VARIABLE PATCH_RESULT)
    if(NOT PATCH_RESULT EQUAL 0)
        message(FATAL_ERROR "Cannot apply pinned Whisper cancellation fix")
    endif()
endif()

# Hybrid Tensor G5 gate: allow one context to bypass whisper.cpp's audio encoder
# while retaining its exact mel frontend, cross-attention preparation and decoder.
set(WHISPER_EXTERNAL_ENCODER_PATCH "${CMAKE_CURRENT_SOURCE_DIR}/patches/whisper-external-encoder.patch")
execute_process(COMMAND "${GIT_EXECUTABLE}" apply --reverse --check "${WHISPER_EXTERNAL_ENCODER_PATCH}"
    WORKING_DIRECTORY "${whisper_SOURCE_DIR}" RESULT_VARIABLE EXTERNAL_PATCH_ALREADY_APPLIED
    OUTPUT_QUIET ERROR_QUIET)
if(NOT EXTERNAL_PATCH_ALREADY_APPLIED EQUAL 0)
    execute_process(COMMAND "${GIT_EXECUTABLE}" apply "${WHISPER_EXTERNAL_ENCODER_PATCH}"
        WORKING_DIRECTORY "${whisper_SOURCE_DIR}" RESULT_VARIABLE EXTERNAL_PATCH_RESULT)
    if(NOT EXTERNAL_PATCH_RESULT EQUAL 0)
        message(FATAL_ERROR "Cannot apply pinned Whisper external encoder seam")
    endif()
endif()
add_library(mosaic_whisper SHARED bridge.cpp)
target_compile_features(mosaic_whisper PRIVATE cxx_std_17)
target_link_libraries(mosaic_whisper PRIVATE whisper)
if(GGML_VULKAN)
    target_compile_definitions(mosaic_whisper PRIVATE MOSAIC_VULKAN=1)
endif()
