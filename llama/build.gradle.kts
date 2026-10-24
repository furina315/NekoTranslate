plugins {
    alias(libs.plugins.android.library)
}

val cpuAllVariants = (findProperty("lt.cpuAllVariants") as String?)?.toBoolean() ?: true
val cpuArch = (findProperty("lt.cpuArch") as String?) ?: "armv8.6-a+dotprod+i8mm+fp16"
val kleidiai = (findProperty("lt.kleidiai") as String?)?.toBoolean() ?: true
// Experimental Adreno GPU backend (ggml OpenCL), arm64-v8a only; -Plt.opencl=false leaves it out.
val opencl = (findProperty("lt.opencl") as String?)?.toBoolean() ?: true
// Same ABI list as :app (see app/build.gradle.kts).
val abis = ((findProperty("lt.abis") as String?) ?: "armeabi-v7a,arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
// -Plt.skipNative=true: no CMake/NDK at all (JVM unit tests and compile checks in CI). Any APK
// built this way has no native libraries and cannot load models.
val skipNative = (findProperty("lt.skipNative") as String?)?.toBoolean() ?: false

android {
    namespace = "com.nekohome.translate.llama"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += abis
        }
        externalNativeBuild {
            cmake {
                // Native code is always built optimized, even for the debug APK:
                // a -O0 llama.cpp is 10x+ slower and useless for testing.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=OFF"
                arguments += "-DLLAMA_BUILD_TESTS=OFF"
                arguments += "-DLLAMA_BUILD_TOOLS=OFF"
                arguments += "-DLLAMA_BUILD_EXAMPLES=OFF"
                arguments += "-DLLAMA_BUILD_SERVER=OFF"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                arguments += "-DGGML_VULKAN=OFF"
                arguments += "-DLT_CPU_ALL_VARIANTS=${if (cpuAllVariants) "ON" else "OFF"}"
                arguments += "-DLT_CPU_ARCH=$cpuArch"
                // KleidiAI and the multi-variant CPU backend apply to arm64-v8a only; CMakeLists.txt
                // switches armeabi-v7a to a single NEON (vfpv4) backend by itself.
                arguments += "-DLT_KLEIDIAI=${if (kleidiai) "ON" else "OFF"}"
                // OpenCL: arm64-v8a only (CMakeLists.txt ignores it for armeabi-v7a), built as a
                // separately loaded module so devices without libOpenCL.so are unaffected.
                arguments += "-DLT_OPENCL=${if (opencl) "ON" else "OFF"}"
            }
        }
    }

    if (!skipNative) {
        externalNativeBuild {
            cmake {
                path("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
