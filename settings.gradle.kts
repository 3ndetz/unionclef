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

// ── Library subprojects (built once, shared across MC versions) ──────────────
include(":baritone")
include(":tungsten")

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
