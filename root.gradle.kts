plugins {
    id("fabric-loom") version "1.7-SNAPSHOT"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.github.breadmoirai.github-release") version "2.5.2"
}

val mcVersion: String by project.extra { property("minecraft_version") as String }

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

repositories {
    mavenCentral()
    maven("https://babbaj.github.io/maven/")
    maven("https://maven.maxhenkel.de/repository/public")
    maven("https://repo.spongepowered.org/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
    maven("https://jitpack.io")
}

// ── Version: from git tag (v1.2.3 → 1.2.3) or gradle.properties fallback ───

val modVersion: String = run {
    try {
        val tag = providers.exec { commandLine("git", "describe", "--tags", "--abbrev=0") }
            .standardOutput.asText.get().trim()
        if (tag.startsWith("v")) tag.substring(1) else tag
    } catch (_: Exception) {
        property("mod_version") as String
    }
}

version = "$modVersion-$mcVersion"
group = property("maven_group") as String
base.archivesName.set(property("archives_base_name") as String)

// ── Dependencies ────────────────────────────────────────────────────────────

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    include(implementation(annotationProcessor("io.github.llamalad7:mixinextras-fabric:0.3.5")!!)!!)
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // Jackson JSON
    implementation("com.fasterxml.jackson.core:jackson-core:2.16.0")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.16.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
    shadow("com.fasterxml.jackson.core:jackson-core:2.16.0")
    shadow("com.fasterxml.jackson.core:jackson-annotations:2.16.0")
    shadow("com.fasterxml.jackson.core:jackson-databind:2.16.0")

    // Nether pathfinder
    implementation("dev.babbaj:nether-pathfinder:1.5")
    include("dev.babbaj:nether-pathfinder:1.5")

    // Voice chat API (compile-time only)
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.0")

    // Py4j (Python bridge)
    implementation("net.sf.py4j:py4j:0.10.9.7")
    shadow("net.sf.py4j:py4j:0.10.9.7")

    // Baritone — source subproject
    compileOnly(project(path = ":baritone", configuration = "namedElements"))
    // Bundle baritone classes into the final JAR
    include(project(":baritone"))

    // Tungsten — source subproject
    compileOnly(project(path = ":tungsten", configuration = "namedElements"))
    include(project(":tungsten"))
}

// ── Build pipeline ──────────────────────────────────────────────────────────

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
    withSourcesJar()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
}

tasks.remapJar {
    dependsOn(tasks.shadowJar)
    inputFile.set(tasks.shadowJar.get().archiveFile)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

// ── GitHub Release ──────────────────────────────────────────────────────────

githubRelease {
    token(providers.gradleProperty("github.token").orElse(""))
    owner("3ndetz")
    repo("unionclef")
    tagName("v$modVersion")
    releaseName("UnionClef v$modVersion (MC $mcVersion)")
    releaseAssets(tasks.remapJar.get().archiveFile)
    targetCommitish("main")
    generateReleaseNotes(true)
    body("")
    draft(false)
    prerelease(false)
    overwrite(false)
    dryRun(false)
}
