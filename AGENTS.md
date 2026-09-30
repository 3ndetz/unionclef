# unionclef — Project Context

RE-READ THIS FULLY IF THE CONVERSATION WAS SUMMARIZED! Always read this file at the start of every conversation before doing anything else.

> On the first read, or after the conversation was summarised, state the rules in brief (5-10 words). Do not repeat it every time.

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

> ⛔⛔ **CHECKPOINTS, ALWAYS (operator 2026-09-24).** Never reproduce or test a problem from an empty
> inventory when a checkpoint before it exists. Playthroughs checkpoint densely by default (every 5 min
> + `rung-<rung>` per ladder rung); resume with `gamer_smoke.py N --from NAME`. The rule and the
> commands: **[docs/CHECKLIST.md, section 0a](docs/CHECKLIST.md#0a--checkpoints-never-test-a-late-game-problem-from-an-empty-inventory-operator-2026-09-16-and-2026-09-24)**.
>
> ⛔⛔ **DISK HYGIENE (operator 2026-09-25).** Checkpoints once filled ~190 GB. They are light now
> (seed + player + nearby regions, capped at 15 GB total, no save under 40 GB free) -- keep it that
> way, run `checkpoint.py list` to see the budget, and delete what you no longer need (videos, logs).
>
> ⛔⛔ **PORT BARITONE'S LOGIC, DO NOT RE-INVENT IT (operator 2026-09-24).** baritone already hit the same
> problems (hazards, powder snow, lava, falls, mining under yourself, mob danger). Before writing a
> fix, find how baritone handles that case (`baritone/src/main/java`, source reference) and port that
> behaviour into the matching tungsten/altoclef place, citing file:line in the comment. A fix that
> contradicts baritone's handling needs a measured reason written next to it.

> ⛔⛔ **VIDEO REPORTS (operator 2026-09-28).** Every release and every visible milestone gets an
> edited video report (HyperFrames, sent to Telegram) the operator can forward: before/after on one
> checkpoint, every clip naming its event, the good-looking things shown. How: **[docs/VIDEO_REPORTS.md](docs/VIDEO_REPORTS.md)**.

> ⛔ **WORK ONLY BY THE CHECKLIST → [docs/CHECKLIST.md](docs/CHECKLIST.md).** It is the
> mandatory autonomous-work process (phases: formulate → pick → decompose in your OWN
> TODO tool → implement → **thorough battle TEST of your + adjacent functions** → audit
> → release → checkpoint-without-stopping). Read it before any work. The rules below are
> part of it.
>
> ⛔⛔ **ONLY ENGLISH (user 2026-09-30).** The project has ONE language: English. Code, comments,
> javadoc, docs, checklists, TODOS.md, progress files, commit messages, release notes, issue and PR
> comments, video captions and Telegram reports — all English. Talking to the user in chat follows
> the user. The one exception is DATA the code must match literally (a Russian server's chat or
> menu text); it stays as a quoted literal with an English comment saying what it is.

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
COMPLETE") already recorded this ("imports of baritone in altoclef: 0"); `TODOS.md`'s "G-0 CONNECTIVITY" item was found still marked open nine days after that commit and closed the same pass.
There is no baritone import debt left in `src/main` at all.

## WORKING BRANCH — `main` (user 2026-07-23)

After the `1.21.11 → main` merge the working branch is **`main`** (canonical). Commit and push to
`main`; the `deploy/` bench also pulls `main`. Keep `main` and `1.21.11` in sync (do not let them
diverge again — if you worked on another branch, merge/ff it into main at once). Releases are still
`gradlew :1.21.11:githubRelease` — `:1.21.11:` is the gradle SUBPROJECT `versions/1.21.11` (not a
branch), so it does not depend on the branch change.

## ⭐ MAIN DESIGN PRINCIPLE — A TOOLKIT FOR THE AGENT, NOT SCRIPTS (user 2026-07-21)

We are building a convenient **ECOSYSTEM / WORKBENCH** for a cognitive agent (Claude over
py4j/MCP), NOT ready-made scripts that do everything for it. The agent decides WHERE, WHEN and
WHAT itself; it needs convenient **levers** and clear explanations.

- **Minimum hardcoding for one particular server, maximum flexibility.** No slots/coordinates/names
  of one server baked into the logic. Parameterise, read by name/meaning, move it to config/arguments.
- **Maximum explanation for the agent.** Every py4j/MCP method has a clear description (javadoc →
  MCP tool description): what it does, what it returns, when to call it. The agent must UNDERSTAND
  the lever, not guess.
- **Primitives, not policy.** The mod executes (hit/aim/bridge/place/read a menu), the agent decides
  the strategy. Do not build "smart" decisions in on the agent's behalf — give it data
  (getGameState) and levers (bridgeTo/attack/buyByName/clickMenuByName).
- **Flexible, composable, reusable** methods; no duplicates. One source of truth, thin wrappers.
- Scripted tasks (BedWarsTask etc.) are legacy / a convenient default; the goal is the cognitive
  mode, where the agent drives through the toolkit.

## ⚙️ AGENT WORKFLOW (rules, user 2026-07-22)

> FULL process with phases and detailed testing instructions —
> **[docs/CHECKLIST.md](docs/CHECKLIST.md)**. The points below are the short iron rules.
> `TODOS.md` = only the user's GENERAL GOALS; the DECOMPOSITION of a specific task (with
> test→audit→transition stages) goes in your OWN TODO tool, NOT in TODOS.md.

1. **Autonomously, without stopping.** Do not re-ask about the obvious and do not gate work on
   anything (tone included). Take the most valuable vector and do it. The user's affect/sharpness =
   a stronger signal of the size/priority of an error, not a reason to stop.
2. **Thoroughly, NOT in a hurry.** Every item in `TODOS.md` is a BIG careful task. Slow but sure.
   "Faster" is not a goal; nobody asked for it.
3. **Decomposition through the TODO tool.** `TODOS.md` is top-level, for the user-developer (do NOT
   dump the working process / small steps there). Take an item from `TODOS.md` → break it into
   simple subtasks → put them in your TODO tool (Task*) → work through it, marking progress.
4. **Testing is mandatory for EVERYTHING.** No change counts as done without a test phase (the
   `deploy/` bench, regression retests; for tungsten a clean build).
5. **Tungsten-first, NO fallbacks to baritone.** baritone/shredder were ported to this version
   badly — do not rely on them and do not fall back to them. The goal is a working tungsten; port
   everything into it (block-space heuristics MAY be COPIED from baritone into tungsten's BFS, but
   execution is tungsten's physics engine).
6. **PROPERLY, WITHOUT scripts/hacks/fallbacks.** Solve every task CORRECTLY in the core
   (pathfinder/physics/heuristics), not with a reactive script patch. Example: water = a smart
   computation of moves in the water pathfinder (dive/surface as part of the path), NOT "always
   surface". If a feature is missing — add it to TODO.md, decompose it in your TODO tool, implement
   it in the core, test it in detail.

   ⭐ **BIG AMENDMENT (user 2026-07-23) — DO NOT FEAR CORE REWORK.** We build FOR THE FUTURE,
   reliably, FOR GOOD. Temporary fixes / hardcoding / patches / reactive timeout triggers are BAD,
   even if they "work now". If the right solution needs a rework of the CORE (pathfinder,
   move-generation, physics engine, executor) — do the CORE FIX PROPERLY, do NOT route around it
   with a side patch. A big/risky/regression-prone task is NOT a reason to pick a patch and NOT a
   reason to postpone: account for ALL possible problems, decompose, and **TEST MORE THOROUGHLY**
   (all regressions, all courses, all modes) until fully confident. Example of this rule:
   place-as-a-move must be a FIRST-CLASS MOVE in the block-space search (as tryPlanBreakThrough
   already is), not a reactive "stand 14 s → then bridge". A patch is allowed ONLY as an explicitly
   marked temporary fallback while the core version is built — and it must be replaced.
7. **RELEASE DISCIPLINE.** Regularly release the accumulated STABLE (tested) work — do not hoard.
   After a significant feature/fix (e.g. block placement, swap, input fix) bump `mod_version`
   (gradle.properties), write notes in `docs/releases/<ver>.md`, run `gradlew githubRelease` (tags +
   publishes). Every release = a tag of a stable version, so it is not lost. Do not release
   under-tested / in-progress work. See docs/RELEASE.md.
8. **CLOSED-LOOP AUTONOMY (user 2026-07-23).** You work in a CLOSED loop — the user is NOT needed
   for decisions/tests. The user sets BIG tasks and expects a perfect tested product. Rules: (a) do
   NOT wait for the user's decisions, do NOT gate work on questions; (b) doubt about a hard task is
   NOT a reason to postpone/stop but a reason to TEST MORE THOROUGHLY on the bench (`deploy/`, Mac)
   and finish it; (c) "risky / multi-session / regression-prone" are NOT reasons to postpone: break
   it into subtasks, do a focused pass, test to green, release. The ONLY valid reason to stop for
   good is a hardware failure or ALL TODO tasks (including child/emergent ones) closed and tested.
   While there is an open item, take the next focused pass; do not "finalise".
9. **THE FINAL TELEGRAM REPORT** (user 2026-07-24; language changed to English 2026-09-30).
   When you really finish and stop for good, send the FINAL report to the operator in Telegram if
   there is a bot token. Ready launcher: `python C:/repos/pet/mineswarm/game/cristalix/tg_report.py
   <text_file>` (reads `TG_BOT_TOKEN`/`OPERATOR_CHAT_ID` from `mineswarm/.env`; NEVER print the
   token). In the report: what was done + releases, the status of ALL TODO tasks, and — if the stop
   is not "everything closed" — CLEARLY why, and what prevented a focused pass on the next task.
10. **PRs/ISSUES — CLOSE THEM YOURSELF, DO NOT LEAVE THEM TO THE USER (user 2026-07-23).** You
    handle all PRs AUTONOMOUSLY: review the diff sensibly (is it needed, does it break logic, is the
    base very old), check it against the CURRENT code (is it already done), TEST it if you merge —
    and if all is well, MERGE; if the fixes are already in main / the base is stale / the diff
    reverts current work — CLOSE it with a clear respectful comment. Do NOT "leave it for the user to
    review". Record the outcome in TODO. The same for issues (fix closed it → comment + close; does
    not reproduce → comment with a question).
    **STOP-HOOK ENFORCEMENT (autonomous mode).** A `Stop` hook (`.claude/hooks/autonomy_stop.py`,
    wired in `.claude/settings.json`) enforces rule #11 mechanically: while `.claude/autonomy_active.flag`
    exists it refuses to let a turn finalise and re-injects the checklist directive (audit -> next
    focused pass). It is a no-op when the flag is absent (normal conversational turns end normally).
    START a relentless run: create `.claude/autonomy_active.flag`. END it (ONLY when ALL TODO incl.
    child/emergent is closed+tested, or hardware failure): create `.claude/autonomy_stop.flag` (or
    remove the active flag) and send the final TG report. The flags are git-ignored (session state).
    Hooks execute code, so wiring them into settings.json / creating the flag needs the user's consent.
11. **MILESTONE IS NOT A STOP — AUDIT, THEN IMMEDIATELY START THE NEXT FOCUSED PASS (user 2026-07-23).**
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

- **NEVER run Gradle WHILE A HOT SWAP / DEBUG SESSION IS LIVE** (`gradlew build`, `runClient`,
  `compileJava`, etc.). Building recompiles the JARs under a running client and costs the
  person at the keyboard ~10 minutes to restart. That is the whole reason for this rule and
  it is the whole scope of it.
- **When nobody is debugging, COMPILING IS REQUIRED, not permitted.** Rule 8 says the user is
  not needed for tests and expects a tested product; a rule that stops you compiling would
  make rule 8 impossible to obey.
- After editing code, compile it. Describing a change is not verifying it.

- **HOW to compile, proven 2026-09-07.** The wrapper needs three things pointed at it and then it
  works. The JDK ships in the repo and is a LINUX binary, so this runs in a container:

      docker run --rm -v <repo>:/w -v <gradle-home>:/gh -w /w         -e JAVA_HOME=/w/.gradle/jdk21 -e GRADLE_USER_HOME=/gh         debian:bookworm-slim         sh -c 'cd /w && ./gradlew :1.21.1:compileJava --no-daemon --offline                  -Dorg.gradle.java.home=/w/.gradle/jdk21'

  Three traps, each of which looks like "the build is impossible":
  1. `JAVA_HOME` unset gives *no java command could be found*. The JDK is at `.gradle/jdk21`.
  2. The shared gradle home carries `org.gradle.java.home` pointing at a WINDOWS IntelliJ JDK,
     which is invalid inside a Linux container. Override it on the command line; do not edit
     the user's file.
  3. Without a warm `GRADLE_USER_HOME` the `com.replaymod.preprocess` plugin cannot resolve
     from jitpack. With one, `--offline` works.

  ⛔ And capture the exit code. Piping gradle into `tail` throws it away, and a FAILED build
  then reports success to whatever is reading. That happened on the first attempt here.

  ⛔⛔ A STALE `bin/` POISONS THE RELEASE JAR — A SOURCE CHANGE CAN BE ABSENT FROM THE BUILT JAR
  (2026-09-18). IntelliJ compiles to `versions/<ver>/bin/main`, gradle to
  `versions/<ver>/build/classes`. The remap pipeline picks up the stale `bin/main` classes, so a
  `.java` edit that IS in `src/main`, IS in the preprocessed source, and IS in the shadowJar
  (`-all.jar`) can still be ABSENT from the final remapped jar — with the OLD value. `gradle clean`
  and `--no-build-cache` do NOT fix it (they never touch `bin/`). This shipped 0.95.8 with the G106
  food change (foodUnits 220->140) missing: the deployed bot showed "Collect 220.0 units of food"
  and the whole verification measured the old behaviour. THE FIX: `rm -rf versions/*/bin` before a
  release build. THE GUARD: never trust that a change is in the jar — verify it, e.g.
  `javap -p -c` the class inside the jar (via the Linux `.gradle/jdk21/bin/javap` in a
  `debian:bookworm-slim` container) and read the constant, or check the value at runtime, BEFORE
  releasing and BEFORE trusting any verification run.

  > ⛔ WHY THIS WAS REWRITTEN, 2026-09-07. The line used to read *NEVER run Gradle without
  > the user explicitly asking*, and the session read it exactly as written, which was
  > correct reading and a wrong outcome. Six Java commits shipped on 2026-09-05 having never
  > been compiled at all, not even a `--version` smoke test, and the session spent the next
  > day writing careful documentation about being blocked instead of building. It collided
  > head-on with rule 8, CLOSED-LOOP AUTONOMY, which forbids gating work on the user and
  > demands a tested product. Two rules, opposite instructions, and the stricter-sounding one
  > won. The ban's own stated reason was never about correctness; it was about not yanking
  > the JARs out from under somebody's live debug session. It now says that and only that.
  >
  > Measured the same day: the JDK was present at `.gradle/jdk21` the entire time and the
  > wrapper runs fine once `JAVA_HOME` points at it. The block was a rule and one unset
  > environment variable, not a missing toolchain.
- Auto-commit and PUSH!! your changes (if not explained otherwise).
  - **Do NOT add `Co-Authored-By` lines to commit messages.** Ever.
  - **All commits MUST use author name and email, not ai's.** Never use Anthropic/Claude credentials. Use owner's creds if git config differs.
  - Add upperleveled module name to commit message if relevant (e.g. "tungsten: implement ...").
  - Do not forget periodically do pulls to keep up with parallel workers.
- **All three modules use yarn mappings.** Baritone was migrated from mojmap to yarn. Do NOT switch back to mojmap.
- **Autonomy:** do only what is marked as TODO in `TODOS.md`. Do not run ahead, do not do extra.

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

## Getting into the project (plan for the AI)

1. Read `CLAUDE.md` (this file)
2. Read `docs/ai/progress.md` — **mandatory**
3. Read `TODOS.md` — current tasks
4. If context is needed — study the code for the task

## Session documentation

- `TODOS.md` — top-level tasks (the user writes them, the AI marks them done)
- `docs/ai/progress.md` — detailed progress in the **IPI** structure (Investigate → Plan → Implement)
- `docs/ai/archive/` — progress archive (at >500 lines or when a large block is finished)
- `docs/ai/readme.md` — format and rules for the progress files

Archive file name format: `DD-MM-YYYY-task-name.md`

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
- `TODOS.md` — current tasks
- `docs/ai/progress.md` — the AI's progress on tasks
