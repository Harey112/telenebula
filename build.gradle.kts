// Declaring the Kotlin plugins here puts KGP on the build classpath so AGP 9's built-in Kotlin
// uses this version instead of its bundled one.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
