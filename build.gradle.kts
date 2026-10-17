// Top-level build file. Module configuration lives in app/ and llama/.
// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the kotlin-android plugin is not applied.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
