# How to work here

One loop. Repeated forever, autonomously, with no pause between iterations.

```
AUDIT -> PLAN -> FIX -> TEST -> VIDEO -> ASSESS -> REPORT -> next iteration
```

No questions to the user between steps. He sets big goals; he does not adjudicate details.

Language rule: instructional text and code comments are written in ENGLISH (AGENTS.md).

---

## 0. Where we are actually going

**END GOAL: `@gamer` plays the WHOLE game on tungsten, and `baritone`/`shredder` are
deleted from the project.**

Everything else is a step toward that. Work that does not move toward it is not needed.

Progress is measured by the courses — not by feelings, not by reports:

```
python deploy/runner/run_suite.py nav      # navigation, 10 courses
python deploy/runner/run_suite.py pvp      # combat
```

The score IS "how far we got".

---

## 1. AUDIT — every iteration, before a single line of code

⛔ **RULE ZERO: BEFORE BELIEVING A RED, ASK WHETHER THE MACHINE WAS THERE.**

Added 2026-08-06, after most of an evening's readings turned out to be about the host. Another
project's containers took 250-450% of this machine's CPU for hours; the stand ran at 10 fps; four
sweeps in a row said "nothing reached" against 3/3 from the last quiet sample. Nothing in the
output distinguished that from a code regression, and a whole pass nearly went to bisecting my own
commits.

`docker stats --no-stream` answers it in one second. Both benches now answer it themselves —
`run_suite` counts a starved run INVALID (never PASS: it must be re-run, not counted), and
`gamer_smoke` samples the client's fps every poll and stands down below the healthy line. If a run
is INVALID, it measured the MACHINE. Do not read it as a regression, do not revert on it, and do
not ship a behaviour change measured against it.

The mirror rule holds too: a behaviour change that CANNOT be measured today does not get shipped
today. Park it with its patch, its blast radius and its reason written at the site, and bench it
first thing when the machine is quiet. There is one parked right now (TODOS G-0.3).

⛔ **THE RULE: before fixing anything, PROVE BY EXPERIMENT that the code runs.**

Breaking this rule on 2026-07-27 cost a whole session: a search engine was reworked while a
different one drives the bot. It is cheap to check — pin a setting (`;settings <flag> false`)
and look for the engine's fingerprint in the log (fingerprint table: [NAVIGATION.md](NAVIGATION.md)).

Then:
- Read the CODE, not the comments or the docs — both have been wrong repeatedly.
- Every claim carries a `file:line` you actually opened. Cannot cite it? Write "unverified".
- Distinguish: runs by default / gated off / dead / missing. **A dead flag is not a detail,
  it is a missing feature** — that was the root cause four times in a row.

## 1b. READ UPSTREAM FIRST — you are almost certainly re-deriving something (user, 2026-07-30)

⛔ **BEFORE writing a mechanism, OPEN THE ONE THAT ALREADY EXISTS.** `baritone/` is in this
repo as source reference precisely so its logic can be COPIED rather than rediscovered. Also
check `TODOS.md` — the register frequently already names the bug you are about to hunt.

Three questions, answered with `file:line`, before any new mechanism:

1. **Does baritone already do this?** Where, and what exactly does it do differently?
2. **Am I walking its path of mistakes from scratch?** If the answer is "probably", stop and
   port instead. Copying working logic is not cheating — it is the job.
3. **Does `TODOS.md` already name this?** If yes, the entry has the diagnosis; read it first.

Evidence that this rule was earned, all from 2026-07-29:

- Placement was hand-rolled. `MovementTraverse.cost` prefers a SIDE place and only falls back
  to a backplace at `SNEAK_ONE_BLOCK_COST`, and `updateState` holds `Input.SNEAK` and clicks
  only once `isInSneakingPose()`. Tungsten implemented the backplace alone, without sneaking,
  so the bot slid off the lip it was paving and fell into the void — 20.7 blocks short, twice
  in a row, for a reason upstream solved years ago.
- `canPlaceAgainst` accepts normal cubes and glass only. Tungsten accepted "any non-empty
  collision shape", which is not the same question.
- A whole session went into finding that the search burns its budget writing chat from the
  inner loop. `TODOS.md` already carried it: **C4.4 "Search threads write to Minecraft chat
  directly from background threads."** The diagnosis was sitting in the register.

The `ponytail` skill (installed) is the same instinct as a standing rule: stop at the first
rung that holds, reach for what exists, and do not build what you can borrow. `ponytail-audit`
scans the repo for exactly the over-building this rule is meant to prevent.

## 2. PLAN

- One iteration = one root cause. Fixing five things at once tells you nothing about which worked.
- Fix in the CORE. Band-aids, hardcode and reactive timeouts are banned.
- Never keep a duplicate engine "just in case" — that is exactly how four pathfinders appeared.

## 3. FIX

- Build: `.\gradlew.bat :1.21.11:build -x check` (via PowerShell).
- Deploy ONLY with `sh deploy/deploy_jar.sh`.
  Never copy by hand: the previous jar stays next to the new one, Fabric loads the wrong one,
  and a whole before/after comparison runs against old code. This already happened.

## 4. TEST

- Run the target course AND the three green baselines (`nav_flat`, `nav_staircase`,
  `nav_descend`) — otherwise you will not see a regression.
- **One good run is not a result.** Run 5-6 times and report the rate (`4/6`).
  Flaky is RED, not "mostly works".
- Diagnose a failure from that run's log, never by guessing.

## 5. VIDEO

`--record` on the run, then:

```
python deploy/runner/tg_send.py --text-file report.txt clip.mp4
```

Send one per meaningful iteration. The clip is the evidence; a text verdict is not.

## 6. ASSESS — mandatory, never skipped

Right after the test, answer in writing:

1. **Did the score move?** Was X/10, now Y/10. If unchanged — what moved in the numbers
   (distance, time, falls)?
2. **Which end goal did this advance, and by how much?**
3. **Is this the right road?** Does it match the philosophy: core over band-aids, one engine
   instead of three, primitives over policy, no server-specific hardcode?
4. **Are we treading water?** Two iterations with no movement in the numbers means the
   APPROACH is wrong — **not** that the target is wrong.

   ⛔ **CHANGING APPROACH IS NOT CHANGING TARGET.** You finish what you started: the course
   goes green. Abandoning a half-done target and picking an easier one is forbidden — that
   is how a project ends up with ten things at 80%. What changes is HOW you work it:
   stop patching, stop guessing, go back and RE-READ the sources end to end until you can
   explain the mechanism, then make ONE correct fix. Three failed patches mean you do not
   yet understand the code — so go and read it, not write it.

If (3) is "no" — revert (`git revert`, never delete) and rethink.

## 7. REPORT — write it for someone who has never seen this code

Commit and push immediately; never leave unpushed commits.

The report is NOT a changelog. The reader does not know the class names and should not
have to. Explain the SUBSTANCE, in plain words, with a concrete example.

Required structure:

**1. What was broken — in plain language, with an example.**
Not "nextLegNeedsPhysics was never read". Instead:
> The bot walks up to a gap, and at that point the walking engine is supposed to say
> "I can't jump, physics takes it from here". It set a flag meaning exactly that — but
> nobody ever looked at the flag. So the bot arrived at the edge of the gap and just
> stood there, because the part that performs the jump was never told to start.

**2. How I know — the proof.**
Always a log line, a number or a run, never reasoning:
> The log repeated `Walker: BFS 2 wp` fifteen times and then gave up. Two waypoints means
> the plan ended at the lip of the gap every single time.

**3. What I changed — the idea, not the diff.**
> Now the landing cell on the far side is remembered and handed to the physics engine,
> which is the part that can actually jump.

**4. What it gave — numbers before/after.**
> nav_gaps: never passed -> passes in 8.0s, 6 runs out of 6, zero falls.

**5. What is still broken.** Honestly, by name.

**6. ASSESS** (the four questions from phase 6).

**7. The closing check — MANDATORY, copy it verbatim every time:**

```
STOP CONDITION CHECK:
- Is the work actually finished?        -> no / yes
- Is the END GOAL reached?              -> no / yes  (@gamer plays the whole game on
                                           tungsten, baritone deleted)
- Did the customer say to stop?         -> no / yes
VERDICT: if ANY answer is "no", the stop condition has FAILED — work continues
         immediately and the next iteration starts at once.
```

**Call red things red.** Partial success is "the course is still red, this much moved".
No victory is ever declared without runs.

**Never end a turn on the word "starting".** Either the work is in the turn, or
the sentence does not belong in it. A report is a checkpoint, not a stopping point.

## 7b. CONTEXT REFRESH — when it starts feeling hard, RE-READ. Do not wind down.

⛔ **A STALE OR EXHAUSTED CONTEXT IS A RE-ENTRY, NOT AN EXIT (user 2026-07-30).** "My usable
context is exhausted", "I can no longer read the sources in full", "this needs a fresh session",
"I will hand over and stop" — every one of these is the SAME event as a summarisation, and the
answer is the same: re-read AGENTS.md, this checklist, NAVIGATION.md and TODOS.md, re-open the
current task's sources, check `git log --oneline -10` and the last measurements, and take the
next focused pass. **You are the fresh session.** No one else is coming. Writing
`.claude/autonomy_stop.flag` for this reason is a process violation — the flag is for hardware
failure or an entirely closed and tested TODO, nothing else.

⛔⛔ **IT IS STRICTLY FORBIDDEN TO WORK A TASK WHILE TIRED.**
Not "wind down", not "one more small step to finish it off" — both are banned. A tired pass
does not produce a smaller result, it produces a WRONG one: on 2026-07-28 the tired steps
were an inverted coordinate convention (reverted), a floor check one level off, and a
capability that could never fire. Every one of them cost more time than the refresh would have.

When tired you do a **FULL FRESH RE-ENTRY FROM ZERO**, and that re-entry IS the work:
re-read the docs, RE-OPEN the source files (never from memory), re-establish the whole
context out loud, then act. Compaction refreshes the session automatically — you do not
need permission and you do not need a new session; you need to actually re-enter.

Signs you need this: the same course fails three iterations in a row; you are guessing
instead of citing; you catch yourself writing "I am running low on context" or "the next
session should…"; you avoid re-opening a file because you only half-remember it.

**That feeling is NOT a reason to stop. It is the signal to re-read.** Being fuzzy on
something you read hours ago is normal — the fix is thirty seconds of reading, not a
handover. Refusing to re-open a file you half-remember is the actual failure mode: it makes
you guess, and guessing is what has cost this project entire sessions.

Do this, in order, then continue:

1. Re-read [NAVIGATION.md](NAVIGATION.md) — which engine does what, and the log fingerprints.
2. Re-read this checklist, phases 1 and 6.
3. Re-read [../TODOS.md](../TODOS.md) section 0 and the critical register — restate the END
   GOAL out loud: `@gamer` plays the whole game on tungsten, baritone deleted.
4. Re-open the actual files involved in the current problem. Not from memory. Open them.
5. Re-state, in writing: what is PROVEN, what is ASSUMED, what is UNKNOWN. Attack the
   UNKNOWN with one experiment — never with a code change.
6. Write down the vectors again: where we are (score X/10), what is red, what is next.

Then keep going with a clear head. A refresh is part of the loop, not an exit from it.

## 8. Next iteration — immediately

Reaching a milestone is not a reason to stop. Close a root cause, take the next priority,
start again from AUDIT.

Stop only on: hardware failure, or everything in `TODOS.md` closed and tested.

---

## Hard bans

- Never call something done without a stand run.
- Never fix a symptom instead of the cause.
- Never leave dead code, dead flags or duplicate engines.
- Never hardcode a specific server / slot / coordinates.
- Never put emoji in a user interface.
- Never dress up a result — the user checks it on video.

## Where things are

| file | what for |
|---|---|
| [NAVIGATION.md](NAVIGATION.md) | engine map + log fingerprint table. Read FIRST when touching pathfinding |
| [../TODOS.md](../TODOS.md) | user's goals + critical register C0-C8 |
| [ai/nav-baseline-2026-07-27.md](ai/nav-baseline-2026-07-27.md) | course baseline |
| [ai/audit-2026-07-27-tungsten-full.md](ai/audit-2026-07-27-tungsten-full.md) | full tungsten audit |
| `deploy/runner/` | stand, suites, video sending |
| [RELEASE.md](RELEASE.md) | how to release |
