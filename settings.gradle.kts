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
include(":tungsten")   // single build for shredder dependency

// ── MC version subprojects (altoclef + tungsten source, preprocessed) ────────
listOf(
    "1.21.11",
    "1.21.1",
    "1.21",
).forEach { version ->
    include(":$version")
    project(":$version").apply {
        projectDir = file("versions/$version")
        buildFileName = "../../build.gradle"
        name = version
    }
}
