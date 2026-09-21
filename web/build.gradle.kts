import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    js {
        browser()
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.browser)
        }
    }
}

// Node and Yarn come from the repositories settings.gradle.kts declares; the plugin's own are refused there.
rootProject.plugins.withType<NodeJsRootPlugin> {
    rootProject.the<NodeJsEnvSpec>().downloadBaseUrl.apply { convention(null as String?); set(null as String?) }
}
rootProject.plugins.withType<YarnPlugin> {
    rootProject.the<YarnRootEnvSpec>().downloadBaseUrl.apply { convention(null as String?); set(null as String?) }
}
