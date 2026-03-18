# unionclef — Project Context

RE-READ THIS FULLY IF THE CONVERSATION WAS SUMMARIZED! Always read this file at the start of every conversation before doing anything else.

> При первом прочтении или после суммаризации диалога — скажи кратко (5-10 слов) выжимку правил. Не повторяй каждый раз.

## What is this

Unified monorepo: altoclef (bot) + baritone (pathfinding) + tungsten (A* movement) + shredder (pathfinder v2).
Single Gradle project, no submodules, all source compiled together.

## Project structure

- `src/main/java/` — altoclef source (bot logic, tasks, commands)
- `baritone/src/main/java/` — baritone source (pathfinding, remapped to yarn)
- `tungsten/src/main/java/` — tungsten source (A* movement)
- `shredder/src/main/java/` — shredder source (pathfinder v2, fork of baritone)
- `root.gradle.kts` — root build config (MC 1.21, yarn mappings)
- `baritone/build.gradle` — baritone subproject (yarn mappings)
- `tungsten/build.gradle` — tungsten subproject (yarn mappings)
- `shredder/build.gradle` — shredder subproject (yarn mappings)
- `docs/DEVELOP.md` — build & run instructions

## STRICT Rules

- **NEVER run Gradle** (`gradlew build`, `runClient`, `compileJava`, etc.) without the user explicitly asking. Running build recompiles JARs and breaks active hot swap / debug sessions, costing ~10 min to restart.
- After editing code, just describe changes. Do NOT "verify" by building.
- Auto-commit and PUSH!! your changes (if not explained otherwise).
  - **Do NOT add `Co-Authored-By` lines to commit messages.** Ever.
  - Add upperleveled module name to commit message if relevant (e.g. "tungsten: implement ...").
  - Do not forget periodically do pulls to keep up with parallel workers.
- **All three modules use yarn mappings.** Baritone was migrated from mojmap to yarn. Do NOT switch back to mojmap.
- **Автономность:** делай только то, что помечено как TODO в `TODOS.md`. Не забегай вперёд, не делай лишнего.

## Tone & style

No pompous slogans, no self-praise, no "elite" or "advanced" anything. Short, dry, casual — like baritone's "Google Maps for Blockgame" or "plays block game". If it sounds like a marketing pitch, rewrite it. Think understated British humour, not a startup landing page.

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

## Введение в проект (план для AI)

1. Прочитать `CLAUDE.md` (этот файл)
2. Прочитать `docs/ai/progress.md` — **обязательно**
3. Прочитать `TODOS.md` — текущие задачи
4. Если нужен контекст — изучить код по задаче

## Документация сессий

- `TODOS.md` — верхнеуровневые задачи (пишет юзер, AI отмечает выполнение)
- `docs/ai/progress.md` — детальный прогресс по структуре **IPI** (Investigate → Plan → Implement)
- `docs/ai/archive/` — архив прогресса (при >500 строк или завершении крупного блока)
- `docs/ai/readme.md` — формат и правила ведения progress-файлов

Формат архивов: `DD-MM-YYYY-task-name.md`

## Important files

- `README.md` — project overview, fork history, credits
- `docs/DEVELOP.md` — how to build and run from scratch
- `CLAUDE.md` — this file (AI assistant rules)
- `TODOS.md` — текущие задачи
- `docs/ai/progress.md` — прогресс AI по задачам
