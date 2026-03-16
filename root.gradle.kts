plugins {
    id("fabric-loom") version "1.7-SNAPSHOT" apply false
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

preprocess {
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
}
