import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val envStoreFile = System.getenv("TN_KEYSTORE_FILE")
val releaseStorePath = keystoreProps.getProperty("storeFile") ?: envStoreFile
val debugStorePath = keystoreProps.getProperty("debugStoreFile") ?: System.getenv("TN_DEBUG_KEYSTORE_FILE")

/**
 * Local builds climb, so one is never a downgrade against an earlier install. The release workflow
 * stamps the same minutes-since-2024 code at tag time, so a release is newer than any test build.
 */
/** Escape hatch for the release workflow, which publishes unsigned-key builds knowingly. */
val allowDebugSignedRelease = System.getenv("TN_ALLOW_DEBUG_SIGNED_RELEASE") != null

val localVersionCode = ((System.currentTimeMillis() - 1_704_067_200_000L) / 60_000L).toInt()

android {
    namespace = "com.telenebula.app"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.telenebula.app"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("TN_VERSION_CODE")?.toInt() ?: localVersionCode
        // release builds are tagged: the workflow passes the tag without its leading "v"
        versionName = System.getenv("TN_VERSION_NAME") ?: "1.0.0"
        // The nebula AAR and libwebrtc also ship x86; nothing the app targets runs it, and the
        // universal APK would carry those libraries for nobody.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // One APK per ABI (a third of the size) plus a universal one for anyone unsure which to take.
    // Every variant keeps the same versionCode, so swapping between them is a plain reinstall.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        // the shared debug key lives outside the repo (keystore.properties or the TN_DEBUG_* variables); without it AGP's per-machine key applies
        getByName("debug") {
            val storePath = debugStorePath
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = keystoreProps.getProperty("debugStorePassword") ?: System.getenv("TN_DEBUG_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = keystoreProps.getProperty("debugKeyAlias") ?: System.getenv("TN_DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = keystoreProps.getProperty("debugKeyPassword") ?: System.getenv("TN_DEBUG_KEY_PASSWORD") ?: "android"
            }
        }
        create("release") {
            val storePath = releaseStorePath
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("TN_KEYSTORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias") ?: System.getenv("TN_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("TN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // its own package, so a build under test sits beside a real install instead of
            // colliding with it. Nothing hardcodes the id: the provider authority is
            // "${'$'}{applicationId}.files" in the manifest and is read back from packageName.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // the flag wins even when a release key exists: test devices carry debug-signed installs
            signingConfig = if (allowDebugSignedRelease) signingConfigs.getByName("debug")
            else signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = false
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":vpn"))
    implementation(project(":calls"))
    implementation(project(":dex"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.camerax.mlkit.vision)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.konsist)
}

/**
 * A debug-signed release APK is indistinguishable from a real one by name, yet can never be
 * installed over a real one, so handing one out strands whoever installs it. Refuse to build one
 * unless the caller says outright that is what they want.
 *
 * The values are copied into this scope first: a task action that reads a script property captures
 * the script itself, which the configuration cache cannot serialize.
 */
run {
    val isUnsigned = releaseStorePath == null && !allowDebugSignedRelease
    val complaint =
        "No release keystore. Set storeFile, storePassword, keyAlias and keyPassword in " +
            "keystore.properties, or the TN_KEYSTORE_FILE, TN_KEYSTORE_PASSWORD, TN_KEY_ALIAS " +
            "and TN_KEY_PASSWORD environment variables. Use assembleDebug to test locally, or " +
            "set TN_ALLOW_DEBUG_SIGNED_RELEASE=1 to build a debug-signed release on purpose."
    tasks.matching { it.name == "preReleaseBuild" }.configureEach {
        doFirst {
            if (isUnsigned) throw GradleException(complaint)
        }
    }
}

/** The Dex web frontend, built by :web, rides along as assets under `dex/`. */
abstract class BundleDexWeb : DefaultTask() {
    @get:InputFiles
    abstract val bundle: ConfigurableFileCollection

    @get:InputFiles
    abstract val resources: ConfigurableFileCollection

    /** the phone's own emoji catalogue, so the browser offers exactly what the phone does */
    @get:InputFiles
    abstract val shared: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val target = outputDir.get().asFile.resolve("dex")
        target.deleteRecursively()
        target.mkdirs()
        val wanted = setOf("index.html", "app.css", "favicon.svg")
        for (file in resources.asFileTree.files) if (file.name in wanted) file.copyTo(target.resolve(file.name), overwrite = true)
        for (file in shared.asFileTree.files) file.copyTo(target.resolve(file.name), overwrite = true)
        // the webpack task reports its output directory; the bundle is one file inside it
        val js = bundle.asFileTree.files.firstOrNull { it.name == "web.js" } ?: throw GradleException("The :web bundle (web.js) was not produced")
        js.copyTo(target.resolve("web.js"), overwrite = true)
        for (name in wanted + "web.js" + "emoji_catalog.json") if (!target.resolve(name).isFile) throw GradleException("Dex web asset missing: $name")
    }
}

val bundleDexWeb = tasks.register<BundleDexWeb>("bundleDexWeb") {
    val webpack = project(":web").tasks.named("jsBrowserProductionWebpack")
    dependsOn(webpack)
    bundle.from(webpack.map { it.outputs.files })
    resources.from(project(":web").layout.projectDirectory.dir("src/jsMain/resources"))
    shared.from(layout.projectDirectory.file("src/main/assets/emoji_catalog.json"))
    outputDir.set(layout.buildDirectory.dir("generated/dexWeb"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleDexWeb, BundleDexWeb::outputDir)
    }
}
