pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // chesslib is not on Maven Central; only its group is fetched from JitPack.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.bhlangonijr") }
        }
    }
}

rootProject.name = "postmortem"

include(":core")

// CI has no Android SDK: -Ppostmortem.coreOnly=true builds and tests :core alone.
if (!providers.gradleProperty("postmortem.coreOnly").isPresent) include(":app")
