plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nekohome.translate"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.nekohome.translate"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Shown in 设置 → 关于; keep in sync with the third_party/llama.cpp submodule tag.
        buildConfigField("String", "LLAMA_CPP_VERSION", "\"b11707\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
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
            // TEMPORARY: signed with the local debug keystore so the CI release APK installs
            // directly (adb install / file manager). Replace with a real release keystore before
            // publishing anywhere; an APK signed this way cannot be updated by a properly signed one.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material.color.utilities)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
