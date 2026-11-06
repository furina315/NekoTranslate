import com.android.build.api.variant.FilterConfiguration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ABIs to package (comma separated). Default builds all supported ones; e.g.
// `./gradlew :app:assembleRelease -Plt.abis=arm64-v8a` for a faster local build.
val supportedAbis = listOf("armeabi-v7a", "arm64-v8a")
val abis = ((findProperty("lt.abis") as String?) ?: supportedAbis.joinToString(","))
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
require(abis.isNotEmpty() && supportedAbis.containsAll(abis)) { "lt.abis must be a subset of $supportedAbis, got $abis" }

// Per-ABI versionCode = defaultConfig.versionCode * 10 + this. Universal keeps +0 (lowest), so an
// ABI-specific APK always wins over the universal one for the same release.
val abiVersionCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2)

android {
    namespace = "com.nekohome.translate"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.nekohome.translate"
        minSdk = 29
        targetSdk = 37
        // CI release builds pass -Plt.versionCode computed from the tag (vX.Y.Z -> X*10000 + Y*100 + Z),
        // so there is nothing to bump by hand. It is then multiplied per ABI, see abiVersionCodes.
        versionCode = (findProperty("lt.versionCode") as String?)?.toInt() ?: 1
        // CI release builds pass -Plt.versionName=<tag without leading v>.
        versionName = (findProperty("lt.versionName") as String?) ?: "0.1.0"

        // Shown in 设置 → 关于; keep in sync with the third_party/llama.cpp submodule tag.
        buildConfigField("String", "LLAMA_CPP_VERSION", "\"b11827\"")
    }

    // One APK per ABI plus a universal one (all ABIs). Native libs come from :llama.
    splits {
        abi {
            isEnable = true
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = true
        }
    }

    // Release keystore comes from the environment (CI: GitHub Secrets, decoded in release.yml;
    // locally: export the same four variables). Without it, release falls back to the debug key.
    val releaseKeystore = System.getenv("LT_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("LT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LT_KEY_ALIAS")
                keyPassword = System.getenv("LT_KEY_PASSWORD") ?: System.getenv("LT_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 + resource shrinking: Compose runs much faster once minified/optimized, and the
            // profileinstaller below gets Compose's baseline profiles AOT-compiled at install.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Real release key when LT_KEYSTORE_FILE is set; otherwise the debug key (local builds only).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // ggml CPU backend variants are dlopen'ed by path from nativeLibraryDir,
            // so native libs must be extracted at install time.
            useLegacyPackaging = true
            // Link-time OpenCL stub from :llama (opencl-stub.cmake). The real libOpenCL.so is the
            // device vendor's; shipping the stub would shadow it.
            excludes += "**/libOpenCL.so"
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            val base = output.versionCode.orNull ?: 1
            output.versionCode.set(base * 10 + (abi?.let { abiVersionCodes.getValue(it) } ?: 0))
        }
    }
}

composeCompiler {
    // Treat immutable data/collection types from other modules (llama, java.io.File, kotlin.collections)
    // as stable so composables taking them can skip when nothing changed.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    implementation(project(":llama"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material.color.utilities)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
