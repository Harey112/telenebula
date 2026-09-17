pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // gomobile bindings for nebula (built by scripts/build-nebula-aar.sh, committed)
        exclusiveContent {
            forRepository { maven { url = uri("vpn/local-maven") } }
            filter { includeGroup("net.defined") }
        }
    }
}

rootProject.name = "telenebula"
include(":app", ":core", ":vpn", ":calls")
