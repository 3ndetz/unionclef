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
val defaultVersion = "1.21.1"

listOf("compileJava", "runClient", "build", "remapJar", "processResources", "githubRelease").forEach { taskName ->
    tasks.register(taskName) {
        dependsOn(":$defaultVersion:$taskName")
    }
}

preprocess {
    // Single connected graph — preprocessor requires all nodes reachable.
    // Each module has its own versions/mainProject to identify on-disk source.
    val mc12101 = createNode("1.21.1", 12101, "yarn")
    val mc12100 = createNode("1.21", 12100, "yarn")
    val t12101 = createNode("tungsten-1.21.1", 12101, "yarn")
    val t12111 = createNode("tungsten-1.21.11", 12111, "yarn")

    // altoclef: 1.21.1 → 1.21
    mc12101.link(mc12100)
    // tungsten: bridge from altoclef 1.21.1 → tungsten-1.21.1 → tungsten-1.21.11
    mc12101.link(t12101)
    t12101.link(t12111)
}
