pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net")
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.replaymod.preprocess" -> {
                    useModule("com.github.replaymod:preprocessor:${requested.version}")
                }
            }
        }
    }
}

rootProject.name = "unionclef"
rootProject.buildFileName = "root.gradle.kts"

// ── Library subprojects ──────────────────────────────────────────────────────
// include(":baritone")  // kept as source reference, not compiled
include(":shredder")

// tungsten — versioned via preprocessor (like altoclef)
// Source lives in tungsten/src/, built per MC version
listOf(
    "1.21.1",
    "1.21.11",
).forEach { version ->
    include(":tungsten-$version")
    project(":tungsten-$version").apply {
        projectDir = file("tungsten/versions/$version")
        buildFileName = "../../build.gradle"
    }
}

// ── MC version subprojects (altoclef core, preprocessed per version) ─────────
listOf(
    "1.21.1",
    "1.21",
    // "1.20.6",
    // "1.20.4",
    // "1.20.1",
    // "1.19.4",
).forEach { version ->
    include(":$version")
    project(":$version").apply {
        projectDir = file("versions/$version")
        buildFileName = "../../build.gradle"
        name = version
    }
}
