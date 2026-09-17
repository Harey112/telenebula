plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.telenebula.core"
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        // the framework database is only real on a device; AndroidSqlDb is covered by
        // src/androidTest (./gradlew :core:connectedDebugAndroidTest)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}

kotlin {
    compilerOptions {
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        optIn.add("kotlinx.coroutines.FlowPreview")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    // the store's SQL runs against a real SQLite in the unit tests; the framework database is
    // only available on a device
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
