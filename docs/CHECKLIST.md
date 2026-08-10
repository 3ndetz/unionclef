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

⛔ **RULE FIVE: A COUNTER FROM ONE RUN IS A SAMPLE, NOT A MECHANISM.**

Added 2026-08-08, and it is the subtle one. RULE FOUR is about claims nobody ever checked. This is
about a claim that WAS checked — once — which feels like evidence and is not.

`mob_skeleton` read `dte=2022/0/0/0/0/0`: of the five guards on the engage gate, only `inRange` was
ever false, on all 2022 evaluations. That was read as a property of the bot — "it never closes on a
skeleton" — and a fix was built on it: hold SPRINT while approaching, since a walking bot cannot
catch a mob that backs away at walking pace.

The fix measured WORSE (`closest_gap` 5.73 → 7.78, `min_hp` 5.0 → 2.0) and was reverted. Then a third
run refuted the mechanism outright:

```
run A   dte=2022/0/0/0/0/0     gap 5.73    in range NEVER
run B   dte=1842/0/0/0/0/0     gap 7.78    in range NEVER   (with the sprint fix)
run C   dte=1903/37/0/0/0/57   gap 4.85    IN RANGE 37 TIMES
```

The counter was accurate every time. The inference from one sample was not, and the spread between
runs was larger than the effect being chased.

**The rule this repo already had, applied to counters as well as verdicts: one good run is not a
result.** A rate is 5–6 runs. That applies to the numbers you diagnose from, not only to the pass or
fail you report — because a diagnosis is a claim about the bot, and a single run cannot support one
on a stand whose frame rate moves between 9 and 15.

*Second example, 2026-08-09, because knowing the rule is not the same as obeying it.* A ballistics
fix was reported as moving `bow_flee` from `hits=0 avg_dist=6.66` to `hits=2 avg_dist=11.35`. One
run, at 5.9 fps. The repeat on a healthy stand: `hits=0 avg_dist=6.07`. The physics was right and
the attribution was invented — and the tell was in the report itself, which said in the same breath
that the change had no mechanism by which it could move a DISTANCE gate at all. **When you cannot
name the mechanism connecting your change to the number that moved, the number is noise until a
repeat says otherwise.** Say so instead of publishing it.

⛔ **RULE SEVEN: A BROKEN STAND OUTLIVES THE RUN THAT BROKE IT, AND LOOKS EXACTLY LIKE A BROKEN BOT.**

`ensure_grounded` lifts a bot out of the void with `gamemode spectator` → `tp` → `gamemode
survival`. A scenario aborted between those lines. `tester2` stayed a SPECTATOR for the rest of the
suite, through a rebuild, through a redeploy, and into two later runs — where every course reported
at once:

```
melee_basic  swings=0 crits=0 damage=0.0      bow_flee  bowShots=0 avg_dist=0.0 hits=0
chase_flat   contact=None freezes=13          ranged_moving hits=0
```

A spectator cannot be hit, takes no damage and has no collision, so it stands inside the bot. That
profile is indistinguishable from a dead mod, and a build plus two runs went into hunting one —
client logs read for a stack trace, the last code change suspected, the format string suspected.

The tell was `dist=0.0` in EVERY sample of a fight whose victim never lost a hit point: not "the bot
cannot reach it" but "the bench thinks they are in the same block". One command settles it —
`data get entity <name> playerGameType` — and it is worth running the moment a whole suite goes to
zero at once.

Two habits follow, both now in the harness:
- **state a run changes, it restores in a `finally`** — teardown cannot run when SETUP is what threw;
- **normalise at the START of each course**, not only at the end of the previous one. That bounds
  any leak of this shape to a single course instead of a night.

And when the whole suite fails at once, **suspect the stand before the bot.** Bots regress on one
course. Stands take out every course simultaneously.

⛔ **RULE SIX: A COURSE THAT HANDS THE BOT THE FINISHED STATE CANNOT TEST HOW THE BOT REACHES IT.**

Added 2026-08-08. The most expensive kind of blind spot, because every gate stays GREEN.

Every pvp and mob course kits the bot with

```
item replace entity {name} weapon.mainhand with iron_sword
```

The weapon starts **already in the hand**. So the entire "choose a weapon and equip it" path was
invisible to the bench — and it was completely dead on 1.21.11 for as long as the port has existed.
`KillAura.equipWeapon`'s whole body sat inside a `//#if MC < 12111` branch, and `bestWeapon` returned
whatever was already held instead of scanning the pack. Twelve pvp courses and three mob courses ran
against that for weeks and none of them could see it.

**The reason nothing rang: the force field wins bare-handed.** Measured both ways on the same arena,
same host, only the Java differing:

```
pre-fix  jar   held_during_run=[]               the zombie still DIED
post-fix jar   held_during_run=['iron_sword']   the zombie still DIED
```

Every outcome gate above the missing capability — "the zombie is dead", "won the exchange" — stayed
green while the bot fought with its fists. **A capability can be entirely absent and every gate above
it still pass**, which is why "the suite is green" is not the same as "the suite covers it".

The test that finds this asks for the STEP, not the outcome: empty hand, sword in `inventory.0` where
it must be found rather than selected, and the gate is "is the sword in the hand". Two further
properties are worth copying:

- **It is an inventory outcome, so it survives a starved host.** Both runs above sat at 5–6 fps
  against a floor of 14 and both gave a clean verdict. Same reason the craft ladder judges the item
  in the pack rather than the seconds it took.
- **It guards against lying to itself.** "The bot started EMPTY-HANDED" is its own gate, so a kit
  that silently failed — or a sword inherited from the previous run's hotbar — cannot make "it armed
  itself" pass without the bot doing anything.

**Before trusting a green suite on a capability, ask what the kit or the arena already GIVES the
bot.** Whatever is handed over is not being tested, however many courses pass.

⛔ **RULE FOUR: A REMOVAL MAY DECLARE A DEBT. IT MAY NOT ASSERT AN UNCHECKED FACT.**

Added 2026-08-08, after it cost six days on one course.

`InteractWithBlockTask`'s out-of-reach branch was reduced to `setDebugState("Getting to our goal")`
and a timer reset. No goal, no movement. The bot stood `dist=28.0` from a crafting table for five
minutes announcing it was on its way. The comment justifying the removal said the legacy engine does
not drive the body — TRUE — and that "something else does the walking" — FALSE. Nothing else did.

The distinction that matters, because two other removals in the same pass were FINE:

- `TimeoutWanderTask` keeps a dead-engine `explore()` call and says why: an A/B odometer showed the
  live-engine replacement covered LESS ground (`wanderMoved 42.6` vs `24.6`). A measurement.
- `AbstractDoToEntityTask` drops the step-away and says "NOT replaced, deliberately", with a reason
  and a passing gate. A declared debt.
- `InteractWithBlockTask` asserted a **checkable claim about the running system** and never checked
  it.

The first two are honest. The third reads like reassurance and rots silently, because nothing fails
loudly — the task still returns, the chain still ticks, the debug string still says something
plausible. All 15 files of that pass were swept afterwards; this was the only one, but one was
enough.

**When a cleanup comment contains a claim of the form "X still happens elsewhere", that claim is a
TEST YOU OWE, not a note.** Either measure it in the same pass, or write "unmeasured" and gate it.

⛔ **RULE THREE: `gradlew build` DOES NOT DEPLOY. AFTER A BUILD, RUN `deploy/deploy_jar.sh`.**

Added 2026-08-08. Building writes a jar into `versions/1.21.11/build/libs`. The containers run
whatever `deploy/deploy_jar.sh` last copied into `deploy/run/mods` — a SEPARATE step. Build without
deploy and the bench measures the PREVIOUS code while you read the numbers as if they were the new
code.

It cost a wrong conclusion in the BlockScanner work. A fix was built, the course was run twice, and
the station lookup dropped from 510 hits to 0 and then 0 again — read as "my change made it strictly
worse", which nearly got it reverted. Both runs were the OLD jar, and the 510-vs-0 spread was this
course's ordinary run-to-run variance. Deployed, the same change took the hit rate to 6034/6034.

Two ways to not get caught: deploy in the same command as the build, and when a result is surprising,
confirm the binary under test contains the change before believing anything it says.

⛔ **RULE TWO: AN IDLE BOT RUNS NOTHING. A COURSE MUST GIVE IT A TASK BEFORE MEASURING ANYTHING.**

Added 2026-08-08, after it produced a fully corroborated finding that was completely wrong.
`TaskRunner.tick()` opens with `if (!active) return;`, and `active` stays false until a USER TASK
starts. So a bot standing idle ticks NO chains at all — not survival, not mob defence, not food.

The escape_lava course issued no command, dropped the bot in lava, and watched it do nothing for
ninety seconds. Three independent witnesses agreed: the block read back as lava through py4j, 24
timeline samples showed it never moving, health and death counters showed it alive and stable. The
conclusion — "lava escape is a dead survival path" — was published, and it was false. A counter on
the FIRST LINE of `WorldSurvivalChain.getPriority()` read zero: the method was never called, because
the runner was switched off.

With a harmless `@get oak_log 1` ordered first, the same course reads `surv=145/145`,
`lavaEsc=101`, and the bot escapes — into WATER six blocks south rather than dry ground one step
east, which is the behaviour the old heuristic's -100 water term was there to produce.

**Agreement between witnesses is not truth when they share a hidden assumption.** All three were
answering "what does an idle bot do", and none of them could see that the question was wrong.

---

⛔ **RULE ONE: WHEN PLAUSIBLE FIXES KEEP SCORING THE SAME, THE INPUT IS LYING — INSTRUMENT IT.**

Added 2026-08-08, after it cost most of an evening. TODOS #37 took FOUR consecutive fixes, each made
by careful reading of a real mechanism, and each measured EXACTLY the same course score (2/4). All
four were reverted. What broke it was giving up on fixes for three runs and putting a counter on the
INPUT instead: the block filter reported `cb=0/18456/0/0` on its first run — every candidate refused
by a fifty-block no-break ban that one unreachable log had triggered. Reading had blamed four
different links and been wrong every time.

The tell is not "the fix failed". It is **identical numbers across different fixes**. Different
causes produce different failures; the same number four times means none of them was the cause, and
the thing feeding the code is what needs measuring.

Two corollaries, both paid for the same day:
- A counter at zero is ambiguous until you know it CAN be non-zero. `recipesKnown=0` meant "nobody
  asked" (trackers rebuild lazily), not "the port failed"; `toolSwap=0` meant "the branch never
  fired", so a green suite proved nothing about it.
- Poll a per-run counter and you will lose the window: `chop_canopy` finishes in fifteen seconds
  when the bot ignores the bait, so a fifteen-second sampler read 0 for runs where the branch DID
  fire. Print it in the verdict instead.

**RULE ONE HAS A MIRROR IMAGE, and it cost a fix that was probably correct.** A disconnected SENSOR
reports nothing happening. A disconnected ACTUATOR *also* reports nothing happening, and looks
exactly like a refuted hypothesis.

Two of these on one evening, 2026-08-09:
- `dmgTaken=0.0 hits=0/0/0/0` on a course where the bot died five times. altoclef's damage tracker
  hangs off `MobDefenseChain.getPriority()`, and on courses the agent drives with tungsten
  primitives that chain never ticks — `mdCalls=0`. The number meant "nothing is measuring", not
  "nothing hit the bot".
- A break-contact fallback that drove the movement keys away during a path search measured 22 hits
  against 23, i.e. nothing — and was reverted as refuted. It had never run. `RunAwayTask` ticks
  BEFORE `MovementQueue`/`BlockPathWalker`, which release every key and press their own. The rule is
  spelled out in that very tick file: *"ONE OWNER OF THE TICK ... a SECOND per-tick writer does not
  merely conflict, it silently wins half the ticks"* — pitfall P1, with its own measured failure
  (`called=11041 inRange=11040 clicked=0`) already recorded next to it.

So before filing a behaviour change as refuted, prove it EXECUTED: a counter on the branch, a
visible effect in the trace, anything that distinguishes "did nothing" from "did not run". A fix
that never reached the game has not been tested, and burying it as a dead end is worse than never
having tried it — the next session inherits the wrong conclusion.

**And the same trap on the other side: REPRODUCE THE FAILURE IN ITS OWN CONTEXT BEFORE COMPARING
VERSIONS.** nav_ladder failed twice in a full nav sweep (self_falls=2), so the night's shared-code
edits went on trial. Pre-tonight sources passed, which looked like confirmation, and a five-step
bisect followed — every commit passing, until only one remained whose diff could not touch a nav
course. The suspect by then was the method, and the control proved it: CURRENT HEAD, run the same
isolated way, PASSES. Every version passes alone; the failure exists only at position nine of a
twelve-course suite on a warmed, loaded stand.

All five bisect steps had been comparing isolated-vs-in-suite, never commit-vs-commit. One control
run — the one skipped at the start — settled what an hour of bisection could not. If a failure
appeared in a suite, the A/B has to run in that suite too; otherwise the thing being varied is the
harness, not the code.

---

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
- `--only` takes a comma-separated LIST, so that is one invocation, not five. The stand is
  rebuilt per invocation, and courses run in the order you ask for — put the fixed one first.
- **One good run is not a result.** Run 5-6 times and report the rate (`4/6`).
  Flaky is RED, not "mostly works".
- Diagnose a failure from that run's log, never by guessing.

**Launch long runs with the tool's own background mode, never `nohup … &` in a subshell.**
A backgrounded shell job is killed when the tool call returns: the suite dies seconds in and
leaves an artifacts directory containing one empty `timeline.jsonl`. That looks exactly like a
course that crashed on entry, and it will be diagnosed as a bot bug. Symptom to recognise: the
log stops right after the `--- <course> ---` banner and the process is already gone.

## 4b. MEASURE THE SPREAD BEFORE YOU COMPARE MEANS (2026-08-10)

A metric you have not characterised cannot support a before/after claim, and most bench metrics
here are noisier than the effects being chased.

Worked example, from the session that produced this rule: `bow_flee` death counts came back
**10, 4, 5, 6 on four runs with the same jar, the same criteria and healthy fps**. On that
course a "10 -> 4" improvement is one draw from each tail of an uncharacterised distribution.
Three separate claims were built on such pairs that night and all three had to be retracted --
including one that had been recorded as "REPLICATED".

The rule:

1. Before quoting any delta, run the metric **n >= 8** under fixed conditions and record its
   **median and range**, not its mean.
2. Treat any difference **smaller than that range** as unproven. Say "not distinguishable from
   run-to-run variation", never "improved".
3. A single run can still **refute** (one counter-example kills a universal), and a counter can
   still be exact (`rimBack=0`, `self=0`, an import count). Asymmetry is fine: cheap evidence
   for "this is impossible", expensive evidence for "this is better".
4. Prefer a metric with no spread when one exists. Mechanisms read from source, and counts that
   are zero or not-zero, do not need eight runs.

The failure this prevents is not carelessness -- it is comparing point estimates while feeling
rigorous, which is exactly how a whole evening of measurements ends up unable to carry its own
conclusions.

## 4c. TWO RULES THAT COST MORE THAN THE VARIANCE ONE (2026-08-10)

**Read the CLOSED tasks before you instrument.** The largest defect of that session --- a flee
objective that ran itself into a corner and stood there --- was already written down, in one
sentence, in a task marked completed: "seeks corners --- furthest from the threat has no
continuation" (G-1.66). Finding it again from the outside cost thirteen refuted hypotheses and six
reverted fixes. Closed does not mean absent; it often means *described and not yet fixed*. Grep the
task list for the subsystem before building a single counter.

**Measure across the population, not inside the condition.** Every wrong number that session ---
nine corrections to figures I had published --- was the same error: a statistic taken over the
ticks that already satisfied the thing being studied. "Sprint 3-5%" was measured inside melee
range, where sprint fails by construction; over the whole flee it was 24%. A subsample selected by
the condition under study is not a measurement of the population, and it will read as evidence.

Two corollaries, both earned the hard way:

- **Count the denominator, never assume it.** "538 of 1200 ticks" was a deficit invented by
  assuming 60 seconds at 20 tps. The player entity does not tick while dead, and that course kills
  the bot five times a run. Count it.
- **A duplicated constant will drift.** The same reach band lived in the mod and the bench and
  diverged twice in one evening, each time because one side was corrected. Care at the moment of
  editing prevented neither. Have one side READ the other.

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
