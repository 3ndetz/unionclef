plugins {
    id("fabric-loom") version "1.15.5" apply false
    id("com.replaymod.preprocess") version "1678b67"
}

subprojects {
    repositories {
        mavenCentral()
        maven("https://libraries.minecraft.net/")
        maven("https://repo.spongepowered.org/repository/maven-public/")
        maven("https://maven.fabricmc.net/")
        maven("https://jitpack.io")
        maven("https://babbaj.github.io/maven/")
    }
}

// Default MC version — root tasks (runClient, build, etc.) delegate here
val defaultVersion = "1.21.11"

listOf("compileJava", "runClient", "build", "remapJar", "processResources", "githubRelease", "githubPreRelease").forEach { taskName ->
    tasks.register(taskName) {
        dependsOn(":$defaultVersion:$taskName")
    }
}

preprocess {
    // altoclef + tungsten source preprocessed together
    val mc12111 = createNode("1.21.11", 12111, "yarn")
    val mc12101 = createNode("1.21.1", 12101, "yarn")
    val mc12100 = createNode("1.21", 12100, "yarn")

    mc12111.link(mc12101)
    mc12101.link(mc12100)
}
