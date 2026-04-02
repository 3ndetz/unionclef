plugins {
    id("fabric-loom") version "1.7.4" apply false
    id("com.replaymod.preprocess") version "c2041a3"
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
    // altoclef chain
    val mc12101 = createNode("1.21.1", 12101, "yarn")
    val mc12100 = createNode("1.21", 12100, "yarn")
    // val mc12006 = createNode("1.20.6", 12006, "yarn")
    // val mc12004 = createNode("1.20.4", 12004, "yarn")
    // val mc12001 = createNode("1.20.1", 12001, "yarn")
    // val mc11904 = createNode("1.19.4", 11904, "yarn")

    mc12101.link(mc12100)
    // mc12100.link(mc12006)
    // mc12006.link(mc12004)
    // mc12004.link(mc12001)
    // mc12001.link(mc11904)

    // tungsten chain (1.21.1 is mainProject — code on disk targets 1.21.1)
    val t12111 = createNode("tungsten-1.21.11", 12111, "yarn")
    val t12101 = createNode("tungsten-1.21.1", 12101, "yarn")
    t12111.link(t12101)
}
