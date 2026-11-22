cmake_minimum_required(VERSION 3.18)
project(komikku-upscale)

set(CMAKE_CXX_STANDARD 17)

# ncnn (Vulkan, static) official prebuilt Android package. It is downloaded and checked against a
# fixed hash on first configure, so no binaries have to live in the repository.
set(NCNN_VERSION 20260113)
set(NCNN_ZIP_SHA256 41944630a7b305b190e7caea4b405bc6d103c6383dc4988e9e85616058e3cccd)
set(NCNN_ROOT "${CMAKE_CURRENT_BINARY_DIR}/ncnn-sdk")
set(NCNN_SDK_DIR "${NCNN_ROOT}/ncnn-${NCNN_VERSION}-android-vulkan")

if(NOT EXISTS "${NCNN_SDK_DIR}/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake")
    set(NCNN_ZIP "${NCNN_ROOT}/ncnn-${NCNN_VERSION}-android-vulkan.zip")
    file(MAKE_DIRECTORY "${NCNN_ROOT}")
    message(STATUS "Downloading ncnn ${NCNN_VERSION}")
    file(DOWNLOAD
        "https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/ncnn-${NCNN_VERSION}-android-vulkan.zip"
        "${NCNN_ZIP}"
        EXPECTED_HASH SHA256=${NCNN_ZIP_SHA256}
        SHOW_PROGRESS
    )
    file(ARCHIVE_EXTRACT INPUT "${NCNN_ZIP}" DESTINATION "${NCNN_ROOT}")
    file(REMOVE "${NCNN_ZIP}")
endif()

set(ncnn_DIR ${NCNN_SDK_DIR}/${ANDROID_ABI}/lib/cmake/ncnn)
if(NOT EXISTS "${ncnn_DIR}/ncnnConfig.cmake")
    message(FATAL_ERROR "ncnn package for ABI ${ANDROID_ABI} not found under ${ncnn_DIR}")
endif()

find_package(ncnn REQUIRED)

# The NDK ships glslc, used to compile the two fused pre/post-processing compute shaders.
file(GLOB GLSLC_CANDIDATES
    "${ANDROID_NDK}/shader-tools/*/glslc"
    "${ANDROID_NDK}/shader-tools/*/glslc.exe"
)
if(GLSLC_CANDIDATES)
    list(GET GLSLC_CANDIDATES 0 GLSLC_EXECUTABLE)
else()
    find_program(GLSLC_EXECUTABLE glslc REQUIRED)
endif()

set(GENERATED_SHADER_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated-shaders")
file(MAKE_DIRECTORY "${GENERATED_SHADER_DIR}")

set(FUSED_PREPROC_SHADER "${CMAKE_CURRENT_SOURCE_DIR}/waifu2x_fused_preproc.comp")
set(FUSED_POSTPROC_SHADER "${CMAKE_CURRENT_SOURCE_DIR}/waifu2x_fused_postproc.comp")
set(FUSED_SHADER_OUTPUTS
    "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_fp16.inc"
    "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_fp16.inc"
    "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_fp32.inc"
    "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_fp32.inc"
)

add_custom_command(
    OUTPUT ${FUSED_SHADER_OUTPUTS}
    COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -DFUSED_FP16=1 -mfmt=c "${FUSED_PREPROC_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_fp16.inc"
    COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -DFUSED_FP16=1 -mfmt=c "${FUSED_POSTPROC_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_fp16.inc"
    COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -mfmt=c "${FUSED_PREPROC_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_fp32.inc"
    COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -mfmt=c "${FUSED_POSTPROC_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_fp32.inc"
    DEPENDS "${FUSED_PREPROC_SHADER}" "${FUSED_POSTPROC_SHADER}"
    VERBATIM
)
add_custom_target(upscale-fused-shaders DEPENDS ${FUSED_SHADER_OUTPUTS})

add_library(komikku-upscale SHARED
    waifu2x.cpp
    upscale_jni.cpp
)
add_dependencies(komikku-upscale upscale-fused-shaders)

target_include_directories(komikku-upscale PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${GENERATED_SHADER_DIR}
)

target_link_libraries(komikku-upscale
    ncnn
    android
    log
    jnigraphics
    dl
)

# Android 15+ devices may use 16 KB memory pages; targetSdk 36 requires 16 KB aligned libs.
target_link_options(komikku-upscale PRIVATE "-Wl,-z,max-page-size=16384")
