# NekoTranslate JNI bridge on top of llama.cpp (git submodule at third_party/llama.cpp).
# Pattern follows llama.cpp's examples/llama.android/lib: llama.cpp is add_subdirectory'd,
# ggml CPU backends are built as dynamically loadable variants and picked at runtime.
cmake_minimum_required(VERSION 3.22.1)

project("localtranslate_jni" VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(LT_CPU_ALL_VARIANTS "Build all Android arm64 ggml CPU variants and pick at runtime" ON)
set(LT_CPU_ARCH "armv8.6-a+dotprod+i8mm+fp16" CACHE STRING "-march used when LT_CPU_ALL_VARIANTS=OFF")
option(LT_KLEIDIAI "Enable Arm KleidiAI kernels in the ggml CPU backend" ON)

# ggml options must be set before add_subdirectory (CMP0077: option() honors normal variables).
set(GGML_SYSTEM_ARCH "ARM")
set(GGML_OPENMP ON)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(LT_DL ${LT_CPU_ALL_VARIANTS})
    set(GGML_CPU_KLEIDIAI ${LT_KLEIDIAI})
    if (LT_CPU_ALL_VARIANTS)
        set(GGML_BACKEND_DL ON)
        set(GGML_CPU_ALL_VARIANTS ON)
    else()
        set(GGML_BACKEND_DL OFF)
        set(GGML_CPU_ALL_VARIANTS OFF)
        set(GGML_CPU_ARM_ARCH "${LT_CPU_ARCH}" CACHE STRING "" FORCE)
    endif()
    message(STATUS "NekoTranslate arm64-v8a: all CPU variants=${LT_CPU_ALL_VARIANTS} arch=${LT_CPU_ARCH} kleidiai=${LT_KLEIDIAI}")
elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
    # 32-bit ARM: one statically linked CPU backend. ggml's runtime variants, the arm64 -march
    # flags (dotprod/i8mm/sve/sme) and KleidiAI are AArch64-only. NEON + VFPv4 (FMA, fp16
    # conversion) is what ggml's ARM SIMD path needs and every Android 10+ armv7 core has it.
    set(LT_DL OFF)
    set(GGML_BACKEND_DL OFF)
    set(GGML_CPU_ALL_VARIANTS OFF)
    set(GGML_CPU_KLEIDIAI OFF)
    set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)
    # 64-bit off_t so llama.cpp's fseeko/ftello/lseek handle model files over 2 GB (bionic API 24+).
    # The real limit on 32-bit is the ~3 GB process address space, see README.
    string(APPEND CMAKE_C_FLAGS   " -mfpu=neon-vfpv4 -D_FILE_OFFSET_BITS=64")
    string(APPEND CMAKE_CXX_FLAGS " -mfpu=neon-vfpv4 -D_FILE_OFFSET_BITS=64")
    message(STATUS "NekoTranslate armeabi-v7a: single CPU backend, -mfpu=neon-vfpv4, no KleidiAI")
else()
    message(FATAL_ERROR "NekoTranslate supports arm64-v8a and armeabi-v7a only (got ${ANDROID_ABI})")
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
if (NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp submodule missing at ${LLAMA_SRC}. Run: git submodule update --init --recursive")
endif()
# libmtmd (tools/mtmd): multimodal (image) input for vision-capable models such as Index-Translate
# with its mmproj. LLAMA_BUILD_MTMD builds just that library while LLAMA_BUILD_TOOLS stays OFF.
# Video input would need ffmpeg in a subprocess; never on Android.
set(LLAMA_BUILD_MTMD ON)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        llama_jni.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        LT_BACKEND_DL=$<BOOL:${LT_DL}>)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/tools/mtmd)

target_link_libraries(${CMAKE_PROJECT_NAME}
        mtmd
        llama
        ggml
        android
        log)
