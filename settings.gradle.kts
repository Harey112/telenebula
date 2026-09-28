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
        // pinned Node for the Dex web build
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist") {
                    name = "Node Distributions"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
    }
}

rootProject.name = "telenebula"
include(":app", ":core", ":vpn", ":calls", ":dex", ":web")
