# unionclef — Project Context

RE-READ THIS FULLY IF THE CONVERSATION WAS SUMMARIZED! Always read this file at the start of every conversation before doing anything else.

> При первом прочтении или после суммаризации диалога — скажи кратко (5-10 слов) выжимку правил. Не повторяй каждый раз.

## ⛔⛔ "MY CONTEXT IS EXHAUSTED" IS NOT A STOP. YOU **ARE** THE FRESH SESSION. (user 2026-07-30)

**Re-read this file the same way after a summarisation AND whenever the context goes stale.**
Both are the same event and both have the same answer: **re-enter the repository from zero**.

If you catch yourself reaching for any of these — **stop reaching, and re-enter instead**:

- "my usable context is exhausted / I am at the end of my budget"
- "I cannot read the sources in full any more, so I cannot validate a change"
- "a fresh context is needed for this" / "the next pass needs a fresh session"
- "I am handing this over" / "I will delegate the next pass and stop"
- "tired", "resource", "safer to stop here", or any other dressed-up version of the above

**None of these is a valid stop, and treating one as a stop is a grave process violation.**
There is no other session coming to do the work. When the context is stale you do exactly what
you do after a summarisation:

1. Re-read `AGENTS.md` (this file), `docs/CHECKLIST.md`, `docs/NAVIGATION.md`, `TODOS.md`.
2. RE-OPEN the source files for the current task — read them, do not recall them.
3. `git log --oneline -10` and the last measurements, to see where the work actually stands.
4. Pick the next focused pass and carry on.

That IS the fresh session. Nothing is lost by it: everything that matters is written down —
in the docs, the commit messages and the comments at the site — precisely so that a re-entry
costs a few reads rather than a session.

Delegating a pass to a sub-agent is allowed as **extra hands**, never as an exit: you keep
working, and you check the result when it lands.

The ONLY two valid final stops remain the ones in rule 8: hardware failure, or every TODO item
(including child and emergent ones) closed AND tested. `.claude/autonomy_stop.flag` may be
written for those two reasons only. It was written once for "context exhausted", which was
wrong, and this section exists so it never is again.

> ⛔ **WORK ONLY BY THE CHECKLIST → [docs/CHECKLIST.md](docs/CHECKLIST.md).** It is the
> mandatory autonomous-work process (phases: formulate → pick → decompose in your OWN
> TODO tool → implement → **thorough battle TEST of your + adjacent functions** → audit
> → release → checkpoint-without-stopping). Read it before any work. The rules below are
> part of it.
>
> ⛔ **LANGUAGE RULE: ALL instructions / docs / checklists / code comments MUST be in
> ENGLISH.** (Existing Russian in the repo stays as-is — don't mass-rewrite it — but any
> NEW instructional text is English.)

## What is this

Unified monorepo: altoclef (bot) + tungsten (A* movement) — **tungsten is now the only compiled
pathfinder**. `baritone/` and `shredder/` both stay in the repo as **source reference only**.

⛔⛔ **NEITHER `baritone/` NOR `shredder/` IS COMPILED. THIS IS A CHANGE FROM WHAT THIS FILE SAID
BEFORE.** `settings.gradle.kts` has both `include(":baritone")` and `include(":shredder")`
commented out:
```
// include(":baritone")  // kept as source reference, not compiled
// include(":shredder")  // G-0 (2026-08-24): kept as SOURCE REFERENCE ONLY, not compiled.
include(":tungsten")   // the only pathfinder now
```
Found 2026-09-01, reconciling this file against the actual build for the first time since the
G-0 migration (2026-08-24, see `TODOS.md`, "G-0 COMPLETE: baritone and shredder are out of the
code and out of the build"). This section previously said "the live pathfinder is `shredder/`,
which occupies the `baritone.*` package namespace" (recorded 2026-07-27) — that was true THEN,
but shredder itself was retired three and a half weeks later and this file was never updated to
say so. Whoever reads this note next: check `settings.gradle.kts` yourself before trusting even
this correction, the way this correction had to check it instead of trusting the last one.
"Replace baritone" no longer means anything — there is nothing left of the old engine in the
build to replace. ⛔ CORRECTED 2026-09-02: this used to say `import baritone.…` lines "remain" in
`src/main` as historical debt — checked with a fresh `grep -rl "^import baritone\." src/main/java/
--include=*.java`, **zero files**, not some leftover count. Commit `05d74f3d` (2026-08-24, "G-0
COMPLETE") already recorded this ("imports of baritone in altoclef: 0"); `TODOS.md`'s "G-0
СВЯЗНОСТЬ" item was found still marked open nine days after that commit and closed the same pass.
There is no baritone import debt left in `src/main` at all.

## РАБОЧАЯ ВЕТКА — `main` (юзер 2026-07-23)

После мерджа `1.21.11 → main` рабочая ветка — **`main`** (канон). Коммить и пуш в `main`;
стенд `deploy/` тоже пуллит `main`. Держи `main` и `1.21.11` синхронными (не давай снова
разойтись — если работал в другой ветке, сразу мерджи/ff в main). Релиз по-прежнему
`gradlew :1.21.11:githubRelease` — `:1.21.11:` это gradle-САБПРОЕКТ `versions/1.21.11`
(не ветка), так что от смены ветки не зависит.

## ⭐ ГЛАВНЫЙ ПРИНЦИП ДИЗАЙНА — ИНСТРУМЕНТАРИЙ ДЛЯ АГЕНТА, НЕ СКРИПТЫ (юзер 2026-07-21)

Мы строим **удобную ЭКОСИСТЕМУ / РАБОЧЕЕ МЕСТО** для когнитивного агента (Клод по
py4j/MCP), а НЕ готовые скрипты, которые всё делают за него. Агент сам решает ГДЕ,
КОГДА и ЧТО — ему нужны удобные **рычажки** и понятные объяснения.

- **Минимум хардкода под конкретный сервер, максимум гибкости.** Никаких
  зашитых слотов/координат/названий одного сервера в логике. Параметризуй,
  читай по имени/смыслу, выноси в конфиг/аргументы.
- **Максимум объяснений для агента.** Каждый py4j/MCP-метод — с внятным
  описанием (javadoc → описание MCP-инструмента): что делает, что возвращает,
  когда звать. Агент должен ПОНИМАТЬ рычажок, а не угадывать.
- **Примитивы, а не политика.** Мод исполняет (удар/прицел/бридж/установка/
  чтение меню), агент решает стратегию. Не встраивай «умные» решения за агента —
  дай ему данные (getGameState) и рычаги (bridgeTo/attack/buyByName/clickMenuByName).
- **Гибкие, композируемые, переиспользуемые** методы; без дублей. Один источник
  правды, тонкие обёртки.
- Скриптовые таски (BedWarsTask и пр.) — legacy/удобный дефолт, но цель —
  когнитивный режим, где агент рулит через инструментарий.

## ⚙️ РАБОЧИЙ ПРОЦЕСС АГЕНТА (правила, юзер 2026-07-22)

> FULL process with phases and detailed testing instructions —
> **[docs/CHECKLIST.md](docs/CHECKLIST.md)**. The points below are the short iron rules.
> `TODOS.md` = only the user's GENERAL GOALS; the DECOMPOSITION of a specific task (with
> test→audit→transition stages) goes in your OWN TODO tool, NOT in TODOS.md.

1. **Автономно, без остановок.** Не переспрашивать по очевидному и не гейтить работу
   ничем (в т.ч. тоном). Берёшь самый ценный вектор — делаешь. Аффект/резкость юзера
   = усиление величины ошибки/приоритета, а не повод останавливаться.
2. **Тщательно, НЕ наспех.** Каждый пункт из `TODO.md` — это БОЛЬШАЯ аккуратная
   задача. Медленно, но верно. «Быстрее» — не цель, никто этого не просил.
3. **Декомпозиция через TODO-инструмент.** `TODO.md` — верхнеуровневый, для юзера-
   разработчика (туда НЕ сваливать рабочий процесс/мелкие шаги). Берёшь пункт из
   `TODO.md` → раскладываешь на простые подзадачи → заводишь в свой TODO-инструмент
   (Task*) → ведёшь работу по нему, отмечаешь прогресс.
4. **Тестирование обязательно НА ВСЁ.** Ни одно изменение не считается сделанным без
   фазы теста (стенд `deploy/`, перетест регрессий; для tungsten — clean build).
5. **Tungsten-first, БЕЗ фоллбеков на baritone.** baritone/shredder криво портированы
   до этой версии — на них не опираться и не откатываться. Цель — рабочий tungsten;
   портировать в него всё (эвристики block-space можно КОПИРОВАТЬ из baritone внутрь
   BFS tungsten, но исполнение — физ-движок tungsten).
6. **ПОЛНОЦЕННО, БЕЗ скриптов/костылей/фоллбеков.** Каждую задачу решать ПРАВИЛЬНО в
   ядре (пасфайндер/физика/эвристики), а не реактивным скриптом-заплаткой. Пример:
   вода = умный расчёт ходов в водном pathfinder (нырять/всплывать как часть пути),
   а НЕ «всегда всплывать». Если фичи нет — добавить в TODO.md, декомпозировать в
   свой TODO-инструмент, реализовать в ядре, подробно протестировать.

   ⭐ **БОЛЬШАЯ ПРАВКА (юзер 2026-07-23) — НЕ БОЯТЬСЯ CORE-ПЕРЕДЕЛОК.** Мы строим НА
   БУДУЩЕЕ, надёжно, НАВСЕГДА. Временные фиксы / хардкоды / заплатки / реактивные
   таймаут-триггеры — это ПЛОХО, даже если «работает сейчас». Если правильное решение
   требует переделки ЯДРА (пасфайндер, move-generation, физ-движок, executor) — делаем
   CORE FIX ПОЛНОЦЕННО, а НЕ обходим его заплаткой сбоку. Большая/рискованная/
   регрессионно-опасная задача — НЕ повод выбрать заплатку и НЕ повод отложить: учитываем
   ВСЕ возможные проблемы, декомпозируем, и **ТЕСТИРУЕМ ТЩАТЕЛЬНЕЕ** (все регрессии,
   все курсы, все режимы) до полной уверенности. Пример этого правила: place-as-a-move
   должен быть ПЕРВОКЛАССНЫМ ХОДОМ в block-space поиске (как tryPlanBreakThrough уже
   встроен), а не реактивным «стой 14с → потом мости». Заплатка допустима ТОЛЬКО как
   явно-помеченный временный фоллбек, пока строится core-версия — и должна быть заменена.
7. **РЕЛИЗ-ДИСЦИПЛИНА.** Регулярно релизить накопленную СТАБИЛЬНУЮ (протестированную)
   работу — не копить. После значимой фичи/фикса (напр. поставка блоков, swap,
   input-фикс) поднять `mod_version` (gradle.properties), написать ноты
   `docs/releases/<ver>.md`, `gradlew githubRelease` (тегает + публикует). Каждый
   релиз = тег стабильной версии, чтобы не потерять. Не релизить недотестированное/
   в процессе. См. docs/RELEASE.md.
8. **CLOSED-LOOP АВТОНОМИЯ (юзер 2026-07-23).** Ты работаешь в ЗАМКНУТОМ цикле — юзер НЕ
   нужен для решений/тестов. Юзер ставит БОЛЬШИЕ задачи и ждёт идеальный оттестированный
   продукт. Правила: (а) НЕ ждать решений юзера, НЕ гейтить работу вопросами; (б) сомнение
   в сложной задаче = НЕ повод отложить/остановиться, а повод ТЩАТЕЛЬНЕЕ ПРОТЕСТИРОВАТЬ на
   стенде (`deploy/`, Mac) и довести; (в) «рискованно / многосессионно / регрессионно-
   опасно» — НЕ причины откладывать: разбей на подзадачи, сделай фокус-заход, протестируй
   до зелёного, отрелизь. ЕДИНСТВЕННАЯ валидная причина финально остановиться — отказ
   hardware или ВСЕ задачи TODO (включая дочерние/появляющиеся) закрыты и оттестированы.
   Пока есть незакрытый пункт — берёшь следующий сфокусированный заход, не «финалишь».
9. **ФИНАЛЬНЫЙ ОТЧЁТ В TG — ВСЕГДА НА РУССКОМ** (оператор читает по-русски, юзер 2026-07-24).
   Когда реально заканчиваешь и финально останавливаешься —
   отправь ИТОГОВЫЙ отчёт оператору в Telegram, если есть токен бота. Готовый лончер:
   `python C:/repos/pet/mineswarm/game/cristalix/tg_report.py <файл_с_текстом>` (читает
   `TG_BOT_TOKEN`/`OPERATOR_CHAT_ID` из `mineswarm/.env`, токен НЕ печатать). В отчёте:
   что сделано+релизы, статус ВСЕХ задач TODO, и — если остановка не по «всё закрыто» —
   ЧЁТКО по какой причине и что помешало сделать фокус-заход на следующую задачу.
10. **PR/ISSUES — ЗАКРЫВАЙ САМ, НЕ ОСТАВЛЯЙ ЮЗЕРУ (юзер 2026-07-23).** Все PR ты обрабатываешь
    АВТОНОМНО: посмотри дифф адекватно (нужен ли, не ломает ли логику, не супер-старая ли база),
    проверь против ТЕКУЩЕГО кода (не сделано ли уже), ПРОТЕСТИРУЙ если мержишь — и если всё ок,
    МЕРЖ; если фиксы уже в main / база протухла / диф ревертит текущую работу — ЗАКРОЙ с внятным
    уважительным комментарием. НЕ «оставляй на ревью юзеру». Итог фиксируй в TODO. То же для issues
    (закрыл фикс → коммент+close; не воспроизводится → коммент-вопрос).
    **STOP-HOOK ENFORCEMENT (autonomous mode).** A `Stop` hook (`.claude/hooks/autonomy_stop.py`,
    wired in `.claude/settings.json`) enforces rule #11 mechanically: while `.claude/autonomy_active.flag`
    exists it refuses to let a turn finalise and re-injects the checklist directive (audit -> next
    focused pass). It is a no-op when the flag is absent (normal conversational turns end normally).
    START a relentless run: create `.claude/autonomy_active.flag`. END it (ONLY when ALL TODO incl.
    child/emergent is closed+tested, or hardware failure): create `.claude/autonomy_stop.flag` (or
    remove the active flag) and send the final TG report. The flags are git-ignored (session state).
    Hooks execute code, so wiring them into settings.json / creating the flag needs the user's consent.
11. **MILESTONE IS NOT A STOP — AUDIT, THEN IMMEDIATELY START THE NEXT FOCUSED PASS (юзер 2026-07-23).**
    Reaching a milestone in a BIG run (a fix released + validated) is NOT a reason to stop or wait
    for the user. At every milestone: (a) run an AUDIT regression test of that milestone (guard against
    regressions — the checklist audit phase), (b) pick the NEXT-PRIORITY task and IMMEDIATELY start a
    fresh, thorough, full focused pass on it — record it and continue, do not pause to "report and wait".
    NEW tasks that emerged mid-run (discovered bugs, flakiness, follow-ups) are taken into work
    IMMEDIATELY too — reprioritise and keep going. **There is NO stop here** (only the two valid final
    stops from rule 8: hardware failure, or ALL TODO incl. child/emergent closed + tested). You MAY use
    conversation COMPACTING to keep going across a long run — a fresh compacted context is the tool for
    the next focused pass, not an excuse to finalise. Pausing at a milestone "to be safe" shames the
    user's closed-loop setup — don't. Keep the momentum: milestone → audit → next pass, seamlessly.

## Project structure

- `src/main/java/` — altoclef source (bot logic, tasks, commands)
- `tungsten/src/main/java/` — tungsten source (A* movement) — the only compiled pathfinder
- `baritone/src/main/java/` — baritone source, **NOT compiled**, source reference only (see G-0)
- `shredder/src/main/java/` — shredder source, **NOT compiled**, source reference only (see G-0)
- `root.gradle.kts` — root build config (MC 1.21, yarn mappings)
- `tungsten/build.gradle` — tungsten subproject (yarn mappings)
- `docs/DEVELOP.md` — build & run instructions

## STRICT Rules

- **NEVER run Gradle** (`gradlew build`, `runClient`, `compileJava`, etc.) without the user explicitly asking. Running build recompiles JARs and breaks active hot swap / debug sessions, costing ~10 min to restart.
- After editing code, just describe changes. Do NOT "verify" by building.
- Auto-commit and PUSH!! your changes (if not explained otherwise).
  - **Do NOT add `Co-Authored-By` lines to commit messages.** Ever.
  - **All commits MUST use author name and email, not ai's.** Never use Anthropic/Claude credentials. Use owner's creds if git config differs.
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

## Releasing

Full guide: **[docs/RELEASE.md](docs/RELEASE.md)**. Summary below:

### 1. Write release notes

Update `docs/releases/<mod_version>.md` (version from `gradle.properties`).
Keep it short: test results if available, known bugs, and most importantly —
how to test new features (which commands to run).

### 2. Bump version

Set `mod_version` in `gradle.properties` to the new version.

### 3. Publish to GitHub

```bash
gradlew :1.21.11:githubRelease   # builds the 1.21.11 JAR, creates the GitHub release
```

⛔ **ALWAYS scope the task to `:1.21.11:` — this branch's MC-version subproject.** This
is a MULTI-VERSION mod (`versions/1.16.5` … `versions/1.21.11`) and all versions share
ONE `vX.Y.Z` git-tag namespace. Running the un-scoped `gradlew githubRelease` runs OTHER
versions' release tasks (e.g. it once attached the `1.21.1` jar to the tag and the
`1.21.11` jar never got published). If a version tag is already taken (by another
version line), BUMP to a free `mod_version` — the plugin will NOT overwrite an existing
release. After releasing, VERIFY: `gh release view v<ver> --json assets` must list
`unionclef-1.21.11-<ver>.jar`.

This is the **only** way to release. Do NOT use `gh release create` manually.
The gradle task automatically:
- attaches the remapped JAR
- prepends `docs/releases/base.md` (install/commands info) to the version notes
- tags, names, and publishes the release

## Important files

- `README.md` — project overview, fork history, credits
- `docs/DEVELOP.md` — how to build and run from scratch
- `CLAUDE.md` — this file (AI assistant rules)
- `TODOS.md` — текущие задачи
- `docs/ai/progress.md` — прогресс AI по задачам
