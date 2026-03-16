# unionclef — Project Context

RE-READ THIS FULLY IF THE CONVERSATION WAS SUMMARIZED! Always read this file at the start of every conversation before doing anything else.

> VARIOUS!! Every time when you read it, especially after the chat summarization, say "I READ THE RULES" to confirm you understand the project context and rules.

## What is this

Unified monorepo: altoclef (bot) + baritone (pathfinding) + tungsten (A* movement).
Single Gradle project, no submodules, all source compiled together.

## Project structure

- `src/main/java/` — altoclef source (bot logic, tasks, commands)
- `baritone/src/main/java/` — baritone source (pathfinding, remapped to yarn)
- `tungsten/src/main/java/` — tungsten source (A* movement)
- `root.gradle.kts` — root build config (MC 1.21, yarn mappings)
- `baritone/build.gradle` — baritone subproject (yarn mappings)
- `tungsten/build.gradle` — tungsten subproject (yarn mappings)
- `docs/DEVELOP.md` — build & run instructions

## STRICT Rules

- **NEVER run Gradle** (`gradlew build`, `runClient`, `compileJava`, etc.) without the user explicitly asking. Running build recompiles JARs and breaks active hot swap / debug sessions, costing ~10 min to restart.
- After editing code, just describe changes. Do NOT "verify" by building.
- Auto-commit and push your changes (if not explained otherwise).
- **Do NOT add `Co-Authored-By` lines to commit messages.** Ever.
- **All three modules use yarn mappings.** Baritone was migrated from mojmap to yarn. Do NOT switch back to mojmap.

## Build commands (only when user asks)

```bash
gradlew compileJava     # compile all three modules
gradlew build           # full build with JAR
gradlew runClient       # launch Minecraft
```

## Mappings

All modules use **yarn** mappings (`net.fabricmc:yarn:1.21+build.9:v2`).
- altoclef: yarn (original)
- tungsten: yarn (original)
- baritone: yarn (migrated from mojmap via `migrateMappings`)

When referencing Minecraft classes, always use yarn names:
- `MinecraftClient` not `Minecraft`
- `ClientPlayerEntity` not `LocalPlayer`
- `net.minecraft.util.math.BlockPos` not `net.minecraft.core.BlockPos`
- `net.minecraft.block.*` not `net.minecraft.world.level.block.*`

## Important files

- `README.md` — project overview, fork history, credits
- `docs/DEVELOP.md` — how to build and run from scratch
- `CLAUDE.md` — this file (AI assistant rules)
