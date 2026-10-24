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
option(LT_OPENCL "arm64-v8a: also build ggml's OpenCL backend (Adreno GPU, experimental, loaded on demand)" ON)

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
    # Experimental Adreno GPU acceleration: ggml's OpenCL backend as a separate module
    # (libneko-opencl.so) that llama_jni.cpp dlopen()s only when the user turns it on. It needs
    # GGML_BACKEND_DL: linked into libggml, a missing libOpenCL.so would stop the app from starting.
    set(LT_OPENCL_ON OFF)
    if (LT_OPENCL AND LT_CPU_ALL_VARIANTS)
        set(LT_OPENCL_ON ON)
        include(${CMAKE_CURRENT_LIST_DIR}/opencl-stub.cmake)
        lt_opencl_stub(${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/OpenCL-Headers)
        set(OpenCL_INCLUDE_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/OpenCL-Headers CACHE PATH "" FORCE)
        set(OpenCL_LIBRARY lt_opencl_stub CACHE STRING "" FORCE)
        set(GGML_OPENCL ON)
        set(GGML_OPENCL_EMBED_KERNELS ON)       # kernels compiled by the driver at first use
        set(GGML_OPENCL_USE_ADRENO_KERNELS ON)  # Adreno-tuned matmuls; other GPUs are dropped
    elseif (LT_OPENCL)
        message(WARNING "NekoTranslate: LT_OPENCL needs LT_CPU_ALL_VARIANTS=ON (dynamically loaded backends); building without the OpenCL backend")
    endif()
    message(STATUS "NekoTranslate arm64-v8a: all CPU variants=${LT_CPU_ALL_VARIANTS} arch=${LT_CPU_ARCH} kleidiai=${LT_KLEIDIAI} opencl=${LT_OPENCL_ON}")
elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
    # 32-bit ARM: one statically linked CPU backend. ggml's runtime variants, the arm64 -march
    # flags (dotprod/i8mm/sve/sme) and KleidiAI are AArch64-only. NEON + VFPv4 (FMA, fp16
    # conversion) is what ggml's ARM SIMD path needs and every Android 10+ armv7 core has it.
    set(LT_DL OFF)
    set(LT_OPENCL_ON OFF)   # CPU only on 32-bit
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

if (LT_OPENCL_ON)
    # A name ggml_backend_load_all_from_path() does not match ("libggml-opencl[-*].so"), so the
    # backend is never probed at start-up; llama_jni.cpp loads it by path on demand.
    set_target_properties(ggml-opencl PROPERTIES OUTPUT_NAME "neko-opencl")
endif()

# Vision encoder speed-up: compile a patched copy of tools/mtmd/clip.cpp that puts the mmproj's
# quantized matmul weights in ggml's repacked CPU buffers (see patch-clip-repack.cmake).
option(LT_CLIP_REPACK "Patch clip.cpp to use repacked (i8mm/dotprod) CPU weights" ON)
if (LT_CLIP_REPACK)
    include(${CMAKE_CURRENT_LIST_DIR}/patch-clip-repack.cmake)
    lt_patch_clip(${LLAMA_SRC} LT_CLIP_PATCHED)
    if (LT_CLIP_PATCHED)
        get_target_property(LT_MTMD_SOURCES mtmd SOURCES)
        list(TRANSFORM LT_MTMD_SOURCES REPLACE "^clip\\.cpp$" "${LT_CLIP_PATCHED}")
        set_property(TARGET mtmd PROPERTY SOURCES ${LT_MTMD_SOURCES})
        message(STATUS "NekoTranslate: mtmd uses patched clip.cpp (${LT_CLIP_PATCHED})")
    endif()
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED
        llama_jni.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        LT_BACKEND_DL=$<BOOL:${LT_DL}>
        LT_OPENCL=$<BOOL:${LT_OPENCL_ON}>)

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
