# Development Guide

## Prerequisites

**JDK 21** is required. Two options:

| JDK | Hot-swap | Download |
|-----|----------|----------|
| **JetBrains Runtime (JBR)** with JCEF | Methods, fields, classes — no restart | [JBR releases](https://github.com/JetBrains/JetBrainsRuntime/releases) — grab `jbr_jcef-21.*` for your OS |
| OpenJDK 21 | Method bodies only | [Adoptium](https://adoptium.net/) |

JBR is strongly recommended — you can add new methods and fields on the fly while debugging, which saves ~10 min per restart.

## Clone & build

```bash
git clone https://github.com/3ndetz/unionclef
cd unionclef
```

Set JAVA_HOME:
```bash
# Windows
set JAVA_HOME=C:\Components\jbr_jcef-21.0.10-windows-x64-b1163.110

# macOS
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jbr_jcef-21.jdk/Contents/Home

# Linux
export JAVA_HOME=/opt/jbr_jcef-21
```

Compile everything:
```bash
gradlew compileJava
```

First run downloads ~1 GB (Minecraft + Fabric + deps). Cached after that.

## Run the client

```bash
gradlew runClient
```

## Debug with hot-swap (VSCode)

### 1. Start Minecraft in debug mode

```bash
gradlew runClient --debug-jvm
```

Minecraft launches and **waits** for a debugger on port **5005**.

### 2. Attach VSCode debugger

Open `unionclef.code-workspace`, then:

- Press **F5** → select **"Attach to Minecraft (debug)"**
- Or use the compound launch: **"unionclef: Debug Client"** (starts gradle + attaches automatically)

### 3. Hot-swap workflow

1. Set breakpoints, step through code
2. Edit a Java file while paused or running
3. Save → VSCode auto-reloads changed classes
4. With JBR: new methods, new fields, new classes — all hot-swapped without restart
5. With regular JDK: only method body changes are hot-swapped

> **Tip:** The `--info` flag (`gradlew runClient --debug-jvm --info`) shows detailed Gradle output, useful for diagnosing startup issues.

## VSCode setup

Open `unionclef.code-workspace` — it includes:
- Gradle task panels for all subprojects
- Build tasks (compile, build, runClient, debug)
- Debug launch config on port 5005
- Java LS settings with 4GB heap

## Project dependencies

Everything builds from source. No pre-built JARs, no `publishToMavenLocal`, no submodule dance.

```
gradlew compileJava     # compiles tungsten + altoclef (baritone/shredder are NOT compiled — see G-0 in TODOS.md)
gradlew build           # full build with JAR output
gradlew runClient       # compile + launch Minecraft
```

## Troubleshooting

### Stale Loom cache

If VSCode shows "references non existing library" errors:

```bash
# 1. Close VSCode
# 2. Kill all Java processes
taskkill /f /im java.exe    # Windows
pkill -f java               # macOS/Linux
# 3. Delete cache
rm -rf .gradle/loom-cache
# 4. Re-open VSCode, rebuild
gradlew compileJava
```

### Syncing upstream baritone

⛔ STALE, FLAGGED 2026-09-01: this recipe predates the "G-0" migration (2026-08-24), which
commented `include(":baritone")` out of `settings.gradle.kts` entirely — there is no `:baritone`
Gradle project any more, so `gradlew :baritone:migrateMappings` below has nothing to run against.
`baritone/` is source-reference-only now (see `TODOS.md`'s G-0 section), so pulling upstream
changes into it is no longer a build-system operation; whoever needs to do this again should
temporarily re-add the `include(":baritone")` line to run the mapping migration, then comment it
back out afterward. The steps themselves (remap, migrate, fix imports) are otherwise still the
right shape and are kept for that reason:

```bash
# 1. Temporarily switch baritone/build.gradle mappings to loom.officialMojangMappings()
# 2. Copy new upstream source into baritone/src/main/java/
# 3. Uncomment include(":baritone") in settings.gradle.kts
# 4. Run: gradlew :baritone:migrateMappings --mappings "net.fabricmc:yarn:1.21+build.9:v2"
# 5. Replace source with remappedSrc output
# 6. Fix ~10-15 stale imports (java.awt.*, mojmap wildcards, mixin annotations)
# 7. Switch mappings back to yarn, then re-comment include(":baritone") in settings.gradle.kts
```

## Releasing

See **[docs/RELEASE.md](RELEASE.md)** for tagging, building release JARs, and publishing to GitHub releases.
