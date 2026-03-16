plugins {
    id("fabric-loom") version "1.7-SNAPSHOT"
}

subprojects {
    repositories {
        mavenCentral()
        maven("https://libraries.minecraft.net/")
        maven("https://repo.spongepowered.org/repository/maven-public/")
        maven("https://github.com/jitsi/jitsi-maven-repository/raw/master/releases/")
        maven("https://maven.fabricmc.net/")
        maven("https://jitpack.io")
        maven("https://babbaj.github.io/maven/")
    }
}

// Single-version build: MC 1.21
// Preprocessor and multi-version support can be re-added later

val mcVersion = "1.21"

repositories {
    mavenCentral()
    maven("https://babbaj.github.io/maven/")
    maven("https://maven.maxhenkel.de/repository/public")
    maven("https://repo.spongepowered.org/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
    maven("https://jitpack.io")
}

dependencies {
    "minecraft"("com.mojang:minecraft:$mcVersion")
    "mappings"("net.fabricmc:yarn:1.21+build.9:v2")
    "modImplementation"("net.fabricmc:fabric-loader:${property("loader_version")}")
    "include"(implementation(annotationProcessor("io.github.llamalad7:mixinextras-fabric:0.3.5")!!)!!)
    "modImplementation"("net.fabricmc.fabric-api:fabric-api:0.100.7+1.21")

    // Jackson JSON
    "implementation"("com.fasterxml.jackson.core:jackson-core:2.16.0")
    "implementation"("com.fasterxml.jackson.core:jackson-annotations:2.16.0")
    "implementation"("com.fasterxml.jackson.core:jackson-databind:2.16.0")

    // Nether pathfinder
    "implementation"("dev.babbaj:nether-pathfinder:1.5")

    // Voice chat API (compile-time only)
    "compileOnly"("de.maxhenkel.voicechat:voicechat-api:2.6.0")

    // Py4j (Python bridge)
    "implementation"("net.sf.py4j:py4j:0.10.9.7")

    // Baritone — source subproject (mojmap, loom remaps via namedElements)
    "compileOnly"(project(path = ":baritone", configuration = "namedElements"))

    // Tungsten pathfinder — source subproject
    "compileOnly"(project(path = ":tungsten", configuration = "namedElements"))
}

loom {
    runs {
        named("client") { client() }
        named("server") { server() }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

version = "0.19-${mcVersion}"
group = "gaucho-matrero.altoclef"

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
