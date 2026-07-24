# AUTONOMOUS WORK CHECKLIST (mandatory process)

> Rule #0: **WORK ONLY BY THIS CHECKLIST.** No phase is skipped.
> The most important phase is **TESTING** (phase 5): a real, battle-grade check of
> YOUR function + adjacent functions — never "green noise". A task with no test is
> NOT done.
>
> Rule #0.1 (language): **ALL instructions / docs / checklists / code comments are
> written in ENGLISH.** (Existing Russian in the repo stays as-is; new instructional
> text is English.) See AGENTS.md.

This file is the single source of truth for the process. `AGENTS.md` links here.
Related docs: `AGENTS.md` (project rules), `TODOS.md` (global goals),
`docs/ai/progress.md` (detailed IPI progress), `docs/RELEASE.md` (how to release),
`docs/DEVELOP.md` (build/run/stand).

---

## Single-task cycle (phases 1–8)

### Phase 1 — FORMULATE GOALS (in TODOS.md)
- `TODOS.md` holds ONLY **the user's GENERAL GOALS** — the high-level task list.
  Where possible with **acceptance criteria** and branches.
- **HARD SEPARATION:** `TODOS.md` is NOT for the working process, steps, or
  test/audit stages. All of that goes in your **own TODO tool** (phase 3). Don't mix.
- References to reference sources (baritone/shredder — sources sit alongside) are fine.

### Phase 2 — PICK A TASK
- The user sets priority. **If not set — you pick yourself, in order** from
  `TODOS.md` (top-down / the nearest open large vector).
- One task at a time. Don't scatter, don't run ahead.

### Phase 3 — PREPARE (before writing any code)
- `git fetch` + `git pull --rebase` on the working branch (sync with origin BEFORE edits).
- **Assess the code and the task**: read the relevant modules, understand the current design.
- **DECOMPOSE** the task into simple subtasks.
- Record the decomposition in your **own TODO tool** (`TaskCreate`/Task*) — this is
  SEPARATE from `TODOS.md`. The decomposition of a SPECIFIC task **MUST** include, as
  explicit items:
  - implementation steps (Investigate → Plan → Implement),
  - a **TESTING stage** (targeted test + adjacent-function regression — phase 5),
  - an **AUDIT stage** (phase 6),
  - a **RELEASE stage** (phase 7, if applicable),
  - a **TRANSITION-to-next-task stage** (checkpoint — phase 8).
  Track progress on it (update statuses). `docs/ai/progress.md` is the detailed IPI log.

### Phase 4 — IMPLEMENT
- Solve it **CORRECTLY IN THE CORE** (pathfinder / physics / heuristics), with **NO**
  patch-scripts / hacks / fallbacks. Missing feature → add it to the core, don't route
  around it reactively.
- **Tungsten-first, no baritone fallbacks** (do not import baritone/shredder).
- Flexible, composable, reusable primitives; single source of truth, no duplication.
  **Primitives, not policy**: the mod executes, the agent decides strategy.
- **NOT "fast".** Slow but sure. "Faster" is not a goal — nobody asked for it.
- **Atomic commits**: one commit = one logical change. **Do NOT dump unrelated
  side-tasks into one commit.** Author = owner (not AI), no `Co-Authored-By`.
- **Push after every atomic commit** (git discipline, below). Periodically `pull`.

### Phase 5 — TESTING (the KEY phase — details at the end)
Nothing is "done" without a battle test on the `deploy/` stand. In short:
1. **Your function** — a targeted test proving the feature ACTUALLY works.
2. **Adjacent functions** — regression: nothing next to it broke.
3. **Overall sanity** — common sense: does the result make sense? (not "passed, but for
   a weird reason").
4. **Real, not noise** — a battle scenario, not a mock / a meaningless green tick.
5. **Separate a real failure from stand flakiness** (warm bot, restart + wait for py4j,
   retry). Flakiness ≠ regression.

### Phase 6 — AUDIT THE RESULT
- Assess the work as a whole for adequacy: does it meet the acceptance criteria? did it
  leave a regression / half-measure? does the code read like its surroundings? no
  garbage/duplication?
- **DEFENSIVE-ERROR REVIEW (mandatory — scan the changed code for the common crash
  classes; one of these takes the WHOLE client tick down):**
  - **NULL** — dereferences without a guard; `Optional`/map/`get()` results assumed
    present; entity/world/target that can be null mid-tick.
  - **INDEX / ARRAY BOUNDS** — `get(size-1)` / `[len-1]` on a possibly-EMPTY list or
    array (→ index -1), off-by-one, negative or out-of-range index, `subList`, iteration
    past the end. (This exact class crashed the client — issue #26.)
  - **THREADING / CONCURRENCY** — shared mutable **static** state touched from several
    threads (render + PathFinder + client tick), data races, `ConcurrentModification`
    / iterator invalidation, non-atomic check-then-act. Marshal to the right thread.
  If a value can be empty / null / out-of-range / concurrently mutated at runtime, guard
  it before shipping. Prefer failing gracefully (log + no-op) over throwing in a tick.
- If the feature was NOT achieved — do not pass it off as done. Either finish it, or
  honestly revert to stable and document the reason and next step.

### Phase 7 — RELEASE (tested, stable work only)
Full guide — **`docs/RELEASE.md`**. In short:
1. Notes `docs/releases/<mod_version>.md` (test results, known bugs, HOW to test the new
   features — which commands to run).
2. Bump `mod_version` in `gradle.properties`.
3. `gradlew githubRelease` (the only way; tags + publishes + attaches the JAR).
- Release accumulated STABLE work regularly. Don't hoard, don't release raw work.

### Phase 8 — CHECKPOINT and NEXT TASK
- Record progress: tick it in `TODOS.md`, detail it in `docs/ai/progress.md`.
- TG notification (`PushNotification`) if this is a milestone the user would want now.
- **MILESTONE ≠ STOP (user 2026-07-23).** Reaching a milestone (a fix released + validated)
  is NOT a stop and NOT "report and wait". At every milestone: (1) run an **AUDIT regression
  test** of it (guard against regressions), then (2) IMMEDIATELY pick the next-priority task
  and start a **fresh, thorough, full focused pass** — seamlessly. NEW tasks that emerged
  mid-run (discovered bugs/flakiness/follow-ups) are reprioritised and taken into work
  IMMEDIATELY too. You MAY use conversation **COMPACTING** to keep momentum across a long
  run — a fresh compacted context is the tool for the next focused pass, not a reason to
  finalise. Pausing "to be safe" at a milestone shames the closed-loop setup — don't.
- **DO NOT STOP.** Take the next task (phase 2) and go through the cycle again.

### Phase 9 — FINAL STOP (only when ALL tasks closed or hardware fails)
- **Closed-loop autonomy (user 2026-07-23):** you run a CLOSED LOOP — the user is not
  needed for decisions or tests. Doubt on a hard task is NOT a reason to defer or stop —
  it is a reason to **test MORE thoroughly** on the stand and finish. "Risky /
  multi-session / regression-prone" are NOT reasons to leave a TODO item — decompose it,
  do a focused pass, test to green, release. Only a truly-all-closed TODO (incl. child /
  emerging tasks) or hardware failure ends the loop.
- ⛔⛔ **VERIFY WITH EYES (SCREENSHOT/FRAMES) + LOGS BEFORE CLAIMING OR SENDING — MANDATORY
  (user 2026-07-24, after I sent broken videos unwatched).** NEVER claim a demo/test works, and
  NEVER send a video, on the strength of an assumption. Before sending a clip: (a) grab a
  screenshot / sample a frame and LOOK at it, AND (b) read the run LOGS (getRecentChat for
  'command not found'/errors, bridgePlaced/damage/kills counters, the actual block/entity state
  via rcon). A passing unit test does NOT prove the DEMO scenario is correct (wrong command name,
  wrong setup). If the footage doesn't visibly show the claimed behaviour, DO NOT SEND IT — fix
  the scenario and re-verify. Sending unverified footage is a позор. This applies to every claim.
- **REPORT SUCCESSFUL TESTS AS VIDEO TO TG — STANDING RULE (user 2026-07-24).** A TG bot token
  exists, so after a notable test passes (esp. PvP / combat / worldedit / bridging), RECORD it and
  send the clip to TG. Send OFTEN + VARIED (different PvP scenarios). Tooling: `deploy/runner/
  capture_demo.py <scenario>` records an mp4/gif on the stand, then `scratchpad/send_video.py
  <remote_mp4> <local> "<caption RU>"` SFTPs it + `sendVideo`s to TG. ⛔ Do NOT keep videos on the
  computer — the sender DELETES the local + stand file after upload (disk hygiene). Captions in Russian.
- **Send the final report to Telegram** if a bot token exists — **IN RUSSIAN** (the operator
  reads Russian; TG reports are always Russian, user 2026-07-24):
  `python C:/repos/pet/mineswarm/game/cristalix/tg_report.py <text_file>` (reads
  `TG_BOT_TOKEN`/`OPERATOR_CHAT_ID` from `mineswarm/.env`; never print the token). The
  report states: what was done + releases, the status of EVERY TODO item, and — if the
  stop is NOT "everything closed" — EXACTLY why and what blocked a focused pass on the
  next task.

---

## ⛔ IRON RULES (never break)

1. **Work ONLY by this checklist.** All phases, in order.
2. **Stop only on hardware failure or when ALL tasks are done.** A user's priority
   choice, the user's harsh tone, a "convenient checkpoint" — are **NOT** reasons to
   stop. User affect = amplify the error's priority, not a reason to halt.
3. **TESTING IS MANDATORY FOR EVERYTHING** — your function + adjacent, battle-grade, not
   noise. Without a test the task is not closed and is not released.
4. **Every task — fully and thoroughly.** Not "fast", not a half-measure, not a hack.
5. **Do not dump unrelated changes into one commit.** Atomicity.
6. **Solve in the core, no scripts/fallbacks.** Tungsten-first, do not import baritone.
7. **git discipline:** before work `fetch` + `pull --rebase`; after every atomic commit
   `push`; at the end a clean `git status -sb`, nothing unpushed.
8. **Author = owner, no `Co-Authored-By`.** No emoji in UIs (only in prose).
9. **All instructions / docs / checklists / code comments in ENGLISH.**
10. If a phase fails (red test) — **fix it or revert to stable**, don't leave it broken
    and don't build on top of a regression.
11. **Discovering work mid-task = EXPAND the plan and do a full focused pass, never a
    "tail".** If the work reveals that a task needs a deeper/focused effort, or uncovers a
    NEW task or a needed experiment: IMMEDIATELY add the goal(s) + an experiment plan to
    `TODOS.md`, assign the priority yourself, decompose it in your own TODO tool, and do a
    proper THOROUGH pass on it — the full checklist (implement → test → audit → release).
    There is no "marathon", no "tail", no "leftover for later", no shallow surface work.
    Every discovered task gets the same complete treatment. "This needs a focused effort"
    is an instruction to START that focused effort now, not to stop.

---

## 🎯 PHASE 5 IN DETAIL — HOW TO TEST (this matters most)

Goal: prove that **your exact function** works in battle, and that **you touched nothing
next to it**. A green tick for its own sake is forbidden.

### 5.1 Targeted test of your function
- The test reproduces a **real battle scenario** of the feature (the `deploy/` stand,
  runners `deploy/runner/*.py`), not a mock and not "it didn't crash".
- A meaningful pass threshold (e.g. bow: standing ≥3/5 — accounting for vanilla arrow
  spread). If the threshold is tuned to "just barely pass" — that's not a test.
- Look at the CAUSE, not just the outcome: if "0/5 — because the target teleports / the
  bot is airborne", that's a SCENARIO defect — fix the scenario, don't accept a false
  result. Instrument it (diagnostics/logs/a py4j primitive) to understand WHY, don't guess.

### 5.2 Regression of adjacent functions
- List what nearby could have suffered and **run their tests**. Example couplings:
  - walker/pathfinder change → `swap_test`, `terrain_test` (staircase A!), `far_test`,
    `goto_test`, `slime_test` (flat).
  - tungsten physics/movement change → clean build + `slime_test` (flat sim must stay
    correct) is mandatory, otherwise working flat breaks.
  - combat change → `pvp_test`, `multitarget_test`, `runaway_test`, `shield_test`.
  - break/place change → `break_test`, `place_test`, `protect_test`, `worldedit_test`.
- **Canary:** keep one known-good case as a regression indicator (for navigation that's
  course A — the continuous staircase; if A breaks, you broke something).

### 5.3 Overall sanity / common sense
- Is the result physically sensible? (e.g. "bestSoFar returned a path" — does the path
  lead TOWARD the goal, not into a wall / backwards?). Verify by looking at logs/positions,
  not on faith.
- Did you break a core invariant (drift, "stands still", walks backwards, freezes)?

### 5.3b STALE-JAR gotcha (verify the deploy actually has your change)
- `gradlew build` can leave the `versions/<ver>/build/libs/*.jar` **CACHED** (up-to-date) so it
  deploys the PREVIOUS jar without your latest source — the test then reflects OLD code and you
  chase a ghost. Seen 2026-07-23: a py4j method (mineBlocks) "does not exist" for 3 build cycles
  because the jar was stale (had the prior commit's methods, not the new one).
- GUARD: after building, VERIFY the jar contains the change before testing, e.g.
  `unzip -p <jar> adris/altoclef/Py4jEntryPoint.class | strings | grep <yourMethod>` (or check a
  new string/renderer). If missing, force it: `gradlew clean :<ver>:build` (clean rebuild of the
  version chain). Bumping mod_version alone does NOT force a source recompile.

### 5.4 Stand flakiness ≠ regression
- The stand bots **flake** (especially cold after a container restart: chunks/pathfinder
  not warmed → path degradation). Flakiness signs: the bot wanders chaotically, ALL
  courses degraded at once, `py4j` didn't come up.
- Runs must be on a **warm** bot: restart → **wait for `py4j`** (poll until `inGame`) →
  pause → test. If flakiness is suspected — rerun clean, compare to a known baseline.
  Don't confuse flakiness with a real code failure.

### 5.5 Record the results
- Test outcomes (what PASS/FAIL, numbers, known gaps, how to reproduce) → release notes
  `docs/releases/<ver>.md` and `docs/ai/progress.md`. So the next Claude doesn't guess.

---

## Quick links
- Project rules and tone: **`AGENTS.md`**
- Global goals: **`TODOS.md`**
- IPI progress / format: **`docs/ai/progress.md`**, `docs/ai/readme.md`
- Release: **`docs/RELEASE.md`**
- Build/run/stand: **`docs/DEVELOP.md`**, `deploy/`

## 🏁 FINAL GOAL OF THE WHOLE PLAN
When ALL `TODOS.md` tasks are done and **thoroughly tested**, and all repo issues/PRs are
triaged (closed where possible, or clarified by requesting details) — **merge the working
branch `1.21.11` INTO `main`** (the full project merge). This is the last `TODOS.md` item.
Do not merge before that.
