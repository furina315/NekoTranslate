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

if (NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "NekoTranslate only supports arm64-v8a (got ${ANDROID_ABI})")
endif()

# ggml options must be set before add_subdirectory (CMP0077: option() honors normal variables).
set(GGML_SYSTEM_ARCH "ARM")
set(GGML_OPENMP ON)
set(GGML_CPU_KLEIDIAI ${LT_KLEIDIAI})

if (LT_CPU_ALL_VARIANTS)
    set(GGML_BACKEND_DL ON)
    set(GGML_CPU_ALL_VARIANTS ON)
else()
    set(GGML_BACKEND_DL OFF)
    set(GGML_CPU_ALL_VARIANTS OFF)
    set(GGML_CPU_ARM_ARCH "${LT_CPU_ARCH}" CACHE STRING "" FORCE)
endif()

message(STATUS "NekoTranslate: all CPU variants=${LT_CPU_ALL_VARIANTS} arch=${LT_CPU_ARCH} kleidiai=${LT_KLEIDIAI}")

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
if (NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp submodule missing at ${LLAMA_SRC}. Run: git submodule update --init --recursive")
endif()
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        llama_jni.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        LT_BACKEND_DL=$<BOOL:${LT_CPU_ALL_VARIANTS}>)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        ggml
        android
        log)
