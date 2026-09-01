# Archive: pvp suite fixes -- allround/bow_flee/edge_duel (2026-08-08/09/10)

Archived 2026-09-01 from `docs/ai/progress.md` per the >500-line rule in `docs/ai/readme.md`. Content moved verbatim, not edited.

## 2026-08-08 — ASSESS (checklist section 6, for the interact-movement + scanner pass)

**1. Did the score move?** Yes. Craft suite was **9 PASS / 3 INVALID**, now **10 PASS / 2 INVALID**,
0 gate failures both times. The new green is `craft_at_distant_table` itself (6/6 across standalone
runs and the suite). Nothing was traded for it: the two remaining INVALIDs are the same two courses
(`chop_tree`, `mine_diamond`) at the same ~10 fps as before.

Numbers that moved underneath the score:
- scanner reach `13 -> 157` chunks walked per pass (31 re-scanned)
- station lookup hit rate `510/6018` -> `6059/6059`
- distance to the target table at end of run `28.0 (frozen 5 min)` -> `0.4`

**2. Which end goal did this advance?** Beating the game on tungsten. Every tool rung above wood
needs a 3x3 station, so "walk to a crafting table you can see" is on the critical path, not a nicety.
The scanner fixes are broader still: a 13-chunk world model bounded ore, tree and station finding
alike, and that ceiling is now gone.

**3. Is this the right road?** Yes, and deliberately so. The movement fix restores the drive AT THE
SOURCE — `AltoGoal.near` through the live tungsten path — rather than papering over a frozen bot with
a timeout, a retry or a nudge. The scanner fixes are in the traversal itself, not in a caller working
around it. No band-aid, no hardcode, no server-specific anything. The legacy engine the earlier pass
removed STAYS removed.

**4. Are we treading water?** We were — two iterations moved nothing — and the thing that broke it
was not trying harder at the same approach. Three hypotheses were argued convincingly and all three
were false (scanner blind; table blacklisted; 40-block threshold flipping). What ended it was
instrumenting the DECISION instead of reasoning about it a fourth time: `near=true makeNew=INF` on
every tick with `dist` frozen at 28.0 said the container task was right all along and the body simply
never moved. **When two passes produce no movement, the next move is a counter, not another theory.**

## 2026-08-09 — pvp: allround diagnosed to the harness, bow_flee fixed (deaths 10 -> 4)

**Investigate.** allround's gate (`bot deaths <= 0`) held at 17-19 deaths against 8-11 kills
across ~10 runs at 29 fps. Every subsystem that could carry the deficit was measured and
cleared, each by its own instrument rather than by argument:

| checked | result |
|---|---|
| melee engine | `melee_basic` PASSES 10:10; counters symmetric to the unit (punk ticks 1360 vs 1364, hits taken 38 vs 37, damage 200.0 vs 205.0) |
| shooting | all loss counters zero (wild 0, noSol 0, restart 0, both timeouts 0), aim within 0.04 blocks; re-confirmed under load on bow_flee (12 loosed, aim 0.10 while running) |
| void falls | server log: slain 25, fell 4 |
| reach-control bundle | mirrored by `--pin combatReachControl=false` -> 19 deaths vs a 17-18 baseline |
| swing charge | 1.000, full |
| weapon in hand | 21% of swings held a bow -> 0%, gate unmoved |

**What remains, measured and never refuted:** exposure. The bot's punk task ticks 199 times
against the victim's 300 and is inactive 26 times against zero, because allround's driver calls
punkStop and re-arms the bow on every death (scenarios_pvp:693,701; scenario.py:449 polls once a
second) and never touches the opponent's. That is in the harness. Taking the gate from inside the
mod would mean editing the course to make the test pass, which was declined — the decision is the
course owner's: either the ranged phase is intended, and zero deaths over 120s of continuous
respawning is unreachable for a fighter that draws even in a symmetric duel, or the phase is
restructured so the bot is not a stationary target while the opponent closes 27 blocks.

**Implement — the one fix that moved an outcome.** `RunAwayTask:136` held position whenever
`dist >= keepDistance + 1.5` (9.5), stopping the bot AND cancelling the search. A sprinting player
covers ~5.6 blocks/s, so that safety lasted under two seconds and the bot restarted from a
standstill. Three numbers said so together: the course reported a 9.32 mean separation (parked on
the threshold) and PASSED it, while dw `rangedHits` read 2 of 38 — 36 hits landed from inside 4.5
blocks. Fix: hold only while the threat is NOT gaining ground. Measured 10 -> 4 deaths, fleeHeld
52 -> 26, fleeRan 34 -> 150+, avg separation 9.32 -> 8.22 (gate >= 7, the falsification test that
was on record before the run).

**Instrument repairs, five of them, without which none of the above was readable:**
`tungstenSetting(name, "")` WROTE false when asked to read (cost a contaminated run);
closeStats' counters never reset while `gTotal` beside them did; a deliberate bow release counted
as a wild one; two bow exits (solver refusal, request-discards-draw) counted nothing at all.

**Eight field-meaning errors, all mine, all named in the commits:** ctl counted completions not
entries; dw's rangedHits is field five; `hits=`/`dmgTaken=` are MobDefenseChain, not combat;
closeStats vs gTotal reset points; wildShots on the success path; resetAllState fires on DISCONNECT
not on death; "searching" does not mean standing because driveAwayRaw runs then; and dw's third
field is a DISTANCE in blocks, not ticks between hits. The bench already documents half the rule at
run_suite:214 — *a counter is only a measurement if you know its zero* — and it needs the other
half: know the UNIT.

**Regression sweep (full pvp suite, in flight at time of writing).** Recorded prior failing set in
TODOS.md:3315 was bow_flee, bow_flee_hard, chase_terrain, edge_duel, melee_basic,
narrow_bridge_duel. So far `melee_basic` and `narrow_bridge_duel` PASS, `edge_duel` FAILs twice
(self-falls 2, knockback 0) as it did before, and nothing regressed.

**Owed next:** a rate over 5-6 runs (the flee fix rests on one); the suite's remaining GATE-red
courses, taken by STATUS — bow_flee was picked by adjacency and is marked INFO
(scenarios_pvp:403); and a baseline for edge_duel on the previous jar.

## 2026-08-10 — edge_duel: the self-falls are KNOCKBACK, and the fix is positional

**Investigate.** The full pvp sweep left exactly two gate-red courses: allround (cause established,
harness-side) and edge_duel (cause unknown, and self-falls are the mod's own business). The source
itself had the question open at `scenarios_pvp:103-105`: low fps was "a plausible cause but that is
a correlation, not the measurement — flag it when someone measures the mechanism, not before".

**Measured, and the correlation is dead.** Self-falls reproduce at 29.4 and 29.2 fps, twice the
floor. That also removes the leading suspect from #60's nav_ladder note.

**The three-way split.** vgCalls/vgEdgeSeen said the guard RUNS (528-1232 calls) and DOES see the
rim, so "never fires" and "sees nothing" are both out. What remained needed an instrument that
records the state at the moment a fall BEGINS, because edgeAir accumulates a tick at a time *during*
a fall and therefore measures the consequence — I misread it as a cause and withdrew that.

    vgFall = onset / hurt / sprint / afterEdge = 5/5/0/5, then 9/8/0/7

Every fall starts on a tick with hurtTime > 0. None while sprinting. The bot is HIT off the
platform. Nothing inside VoidGuard can answer that: knockback is a velocity the server applies and
the guard's whole hold is releasing keys and pressing sneak, which is inert mid-air anyway.

**Fix one, reverted.** Refuse to retreat and close instead. Measured WORSE (onsets 5 -> 9), and the
reason was my own gate: the closing half sat behind `!canStrafe`, almost never true on open ground,
so it reduced to "do not press back" and never moved the bot. Suppressing a direction is not
repositioning.

**Fix two, kept.** Choose the ORBIT SIDE by where it leaves you: probe both a stride out, ask
whether the rim would still lie on the knockback line, take the side that clears it.

    rimBack (exposure)  327 -> 99      fall onsets  9 -> 5      under-a-hit  8 -> 4

rimBack was named as the success measure BEFORE the run precisely because self-falls flicker
(2, 0, 1, 1, 2, 2 over six runs). It fell 70%. **The gate did not move** — self-falls stayed 2 and
the course is still red, its criterion being ZERO.

**Fix three, in flight.** The residual 99 ticks are where a 5x5 board leaves both arcs bad at once;
there the only direction with guaranteed floor is the one the opponent stands on, so close. Same
move as fix one, but on the gate the measurement identified rather than one that never fires.

**Instrument errors this pass**, both mine and both caught: reading edgeAir (a during-fall
accumulator) as a cause, and an argument-order slip that printed afterEdge=117 against onset=5 —
impossible, since both increment in the same branch, and only that impossibility caught it.

**Owed:** melee_basic and narrow_bridge_duel as the mirror-duel regression check — the first attempt
at it was consumed by an edge_duel retry and is NOT done.

## G-1.70 edge_duel — the gated fall counter was misreading knockback as "walked off" (CLOSED GREEN)

edge_duel PASSES 4/4. self-falls 0 every run; knockback falls 1 per run, from 3-4.

The defect was in the instrument. The runner samples at 1 Hz and classified a fall by reading
`hurtTime`, a flag that lasts 10 ticks — half a second. A blow whose window fell between two
samples was invisible, and the fall it caused was filed as "SELF (walked off)". `self-falls`
is GATED here; knockback falls are not gated at all. So the gate was red for hits the sampler
could not see.

Fixed by exposing `VoidGuard.kbImpulseN` — blows taken, monotonic. A count cannot be missed by
a slow poll, only read late. First reading with working attribution: self=0 knockback=4, then
self=0 knockback=3.

The mirror defect was caught before the result was believed: a two-sample window makes
"knockback" the default answer in a duel, where blows land most seconds, and the criterion
becomes unfalsifiable — the same disease, inverted. Narrowed to ONE sample; `self=0` held under
the stricter test, which is the only reason it counts.

Engine work that stands: orbit-side choice (exposure 327 → 99), `KNOCKBACK_REACH` 3.0 → 2.0 on
the measured carry (→ 24), and reach scaled to the attacker's sprint. Mean impulse 0.439 carries
~1.1 blocks; the max seen, 0.854, carries ~2.1 — past the platform radius of 2.0. The mean said
everything was survivable, the tail said not from a sprint, and only the two together named the
case worth guarding. `melee_basic` PASS at 29.5 fps — nothing was traded for it.

Two cautions for the next session. "Won the exchange" is noisy: 6/10 and 7/12 on one jar, 9/7
and 12/9 on the same jar minutes later — a single run of it means nothing either way. And both
baselines returned INVALID at ~10 fps after five consecutive runs, with fresh containers
restoring 29.5; that is client degradation, not course weight.

The lesson is the session's, not the course's: three of my own knockback statistics were
rejected before one survived, and then the quantity I had measured so carefully turned out not
to be the one the gate checks. Reading the criterion's source costs less than four measurements
of the wrong thing.

## Session close 2026-08-10 — what survived, what was retracted, and the rule it cost

VALIDATED ON THE BENCH, all of it re-run after the change:
- `edge_duel` GREEN 4/4 (also PASS inside a full sweep). The gated fall counter had been reading
  knockback as "walked off": a 1 Hz sampler chasing a 10-tick `hurtTime` flag. Fixed with a
  monotonic blows-taken counter; `self=0` every run, and it held when the window was NARROWED,
  which is the only reason it counts.
- `narrow_bridge_duel` 2/3 (third INVALID on client wear, not a gate failure).
- G-0 26 -> 21. Three cuts: `canPlaceAgainst` ported to vanilla (`nav_bridge` PASS,
  `bridge_assault` PASS with 15 blocks placed), two dead locals, and two calls moved behind the
  `Nav` seam (`mob_melee` PASS, `escape_lava` PASS).
- Bench guards: a starved client now refreshes and re-measures instead of recording INVALID; a
  run where the fight never happened can no longer score a clean sheet.

RETRACTED, and this is the more useful half:
- "The flee fix replicated, 10 -> 4 -> 5." `bow_flee` deaths are **4, 5, 5, 6, 6, 6, 6, 10** over
  n=8 at healthy fps — median 6, range 4-10. Every delta claimed on that course sits inside the
  spread of unchanged code.
- "Low fps flatters the bot, r=+0.47" — computed on that same quantity, with one starved run
  against four healthy ones.
- "The deaths are not melee catches" — drawn from one run's coarse silence; the 10-death run
  reached 2.44 blocks, inside reach.

EIGHT hypotheses raised and refuted in one session, all mine. The trajectory is the point: the
first five cost bench runs, the sixth and seventh were killed by data already on disk, and the
eighth died before a line of code was written. Cheap evidence for "this is impossible", expensive
evidence for "this is better".

The rule that came out of it is now CHECKLIST section 4b: characterise a metric at n>=8 before
quoting any delta, report median and range, and call anything smaller "not distinguishable from
run-to-run variation". The bench measured honestly all evening. The conclusions were the unsound
part.

## bow_flee, consolidated — what is measured, what is fixed, what is still open (2026-08-10)

MEASURED AND TRUSTWORTHY (each on its own denominator, counters reset per run):
- Sword reach, calibrated by the blows themselves at the rising edge of `hurtTime`:
  **mean 4.25 blocks, max 5.35** over 22 hits. Every band I picked by argument was wrong — 3.0
  first, then a "correction" to 3.6. What the code calls `nearTicks` (3.6–5.0) IS the killing zone.
- The bot takes ~19–22 blows a run while only 3–5 ticks fall inside 3.6. The mislabel, not a new
  mechanism, explains that gap.
- Stalls: the flee cornered itself on the rim of a `flat_field(half=20)` platform — every stall at
  radius 18.57–18.7, movement keys held, no subsystem contending, chaser not in contact (0 of 17).
- `VoidGuard` is INNOCENT. It clears keys on ~40% of stalls because the bot is at a real void edge,
  which is also why self-falls here are zero. I suspected it twice and withdrew twice.

FIXED: the flee objective sampled only ±80° from straight-away, so at a boundary every candidate
pointed outward, all failed `hasRoomBeyond`, and the dead-end fallback took the rim. Added ±115 and
±145. Stalls **54 → 0/8/8/5**; exposure inside 3.6 **55 → 8.5** ticks/run. Baselines after the
change: `edge_duel`, `melee_basic`, `nav_flat` all PASS.

STILL RED, and the exposure win is weaker than it looked: deaths 4–5 against a criterion of zero,
and the exposure figure was counted on the wrong band, so it describes about a quarter of the blows
that land. The bot is hit at 4.25 blocks while under orders to hold **12** — the gap collapses to a
third of what was asked, and the flee neither prevents nor recovers from it. That is the open
question.

METHOD, which cost more than the code did. Thirteen hypotheses refuted, six fixes reverted on
measures named before they ran, seven of my own instruments corrected, and eight assertions made
before reading the line that settled them. Every one of the six fixes edited the DRIVE; the fault
was in the OBJECTIVE, which task G-1.66 had already recorded as "seeks corners — furthest from the
threat has no continuation". Reading that first would have been cheaper than the entire session.

## bow_flee, final state of this session — three fixes, the gate metric moved, the gate not met

DEATHS, the gate metric, n=8 vs n=9 under the same conditions:
  before  4, 5, 5, 6, 6, 6, 6, 10        median 6, range 4–10
  after   4, 4, 4, 4, 4, 4, 5, 5, 6      median 4, range 4–6
Seven of nine below the old median. The gate wants **zero**, so the course is still RED.

THREE FIXES, each judged on a number named before it ran:
1. The flee objective could not express "run along the rim" — it sampled only ±80° from
   straight-away, so at a boundary every candidate pointed outward, all failed `hasRoomBeyond`,
   and the dead-end fallback took the rim itself. Added ±115/±145. **Stalls 54 → 0/8/8/5.**
2. The bow enforced its gap threshold only at a shot's START, so a shot begun safely kept the
   camera while the gap collapsed. Now checked every tick. **Sprint 24% → ~39%.**
3. The flee never TOOK the camera the bow released, so it kept retreating backwards, which vanilla
   will not sprint. Requests the away heading through WindMouse. **Sprint → ~50–60%.**
Baselines after each: `edge_duel`, `melee_basic`, `ranged_moving`, `nav_flat` all PASS.

REACH, calibrated by the blows at the rising edge of `hurtTime`: **mean 4.25, max 5.35** over 22
hits. Every band chosen by argument was wrong (3.0, then 3.6). `REACH_BAND`/`NEAR_BAND` are now
named constants the bench READS rather than restates — the duplicate drifted twice in one evening.

IDLE TIME, fully attributed by counting: shot running 35–40, mid-turn 30–47 (structural — `setYaw`
is banned), collision 20, sneak 5–9, unexplained ~28. **No single cause dominates.** The two I
would have named without counting — the bow, then sneak — are a third and a twentieth.

REFUTED BY EXPERIMENT: shooting less does not buy survival. `SHOOT_ABOVE_FRACTION` 0.5 → 0.75 left
deaths at 5/4/5 and took arrow hits to 0/0/0. There is no tension between the course's two criteria;
cutting shots simply loses the hits.

NEXT: mid-flight collision is the largest actionable bucket. Measure what the bot collides with
during flight before touching it — the rim collision was a different event and assuming otherwise is
how several wrong turns started tonight.

## SESSION 2026-08-10 — one defect under two red courses, and three instruments that were lying

INVESTIGATE: the final pvp sweep closed 9/12 with three gate failures — `melee_basic`,
`narrow_bridge_duel`, `allround`. Reading the criteria rather than the verdicts showed the first two
failing **exactly one** check each, the same one (won the exchange), with swings, crits, damage,
freezes, standstill and fps all green: 5:6, 5:6 and 12:15, 11:17. `edge_duel` carried the same kit
and passed 4/4 on the same jar. That pattern is one cause, not two courses.

FOUND: `CombatController` broke contact whenever the bot fell below half a bar and the opponent was
outside sword reach. Its own comment justifies the retreat from the bow — "out past reach the bow
becomes the weapon" — and the branch never asked whether a bow existed. `KIT_SWORD` is an iron sword
and nothing else. The three courses order by the **cost** of retreating, not the room for it:
`edge_duel` cannot retreat (green throughout), `melee_basic` loses tempo (coin flip), and on
`narrow_bridge`'s one-block bridge retreating **is how you fall off** (worst). I predicted the
opposite for the bridge, in writing, before the data — the model was wrong and the registration is
what made that visible.

IMPLEMENT: `WeaponSelector.hasRangedOption` — launcher in hotbar or offhand, ammunition anywhere in
the inventory (vanilla finds arrows wherever they are; a narrower scan would have made the answer
depend on where a `/give` landed). Built on `Items.*` constants, as that class requires: the item
class hierarchy is version-dependent, the constants are not.

MEASURED: `lowHp` reads 0 on every bowless run — exact, no eight runs needed. `melee_basic` on its
six HEALTHY runs: median −1 → 0, verdicts 2/6 → 4/6. By rule 4b the margin alone proves nothing
(shift 1, range 2); what carries it is three readings agreeing.

THREE INSTRUMENTS WERE WRONG, and each was found by re-checking something already believed:

1. The `narrow_bridge` probe asked whether the fighters came within **3.0** and answered "they never
   met" on a run with twelve kills. The blows say 4.25 mean / 5.35 max. It now READS the band.
2. `hurtBackingOff` counts a POSITION, not a key press. I read it as "the bot retreats under fire"
   and nearly aimed a fix at a mechanism fixed two commits earlier in the same file.
3. ⭐ The fps refresh only fires on INVALID, and INVALID needs a load-sensitive criterion to FAIL — so
   a course that PASSES while starved never refreshes the stand and every later run inherits the
   degradation. A `--repeat 8` series of `narrow_bridge` ran all five completed runs at **10.0 fps**
   against a baseline at 29.3/18.1, printing clean passes. The series was discarded. Now marked
   `[starved — not comparable]` in the SUMMARY and the clients are replaced mid-series.
   → CHECKLIST rule **4d**, and it is retrospective: older comparisons here have the same hole.

ADDED `lowHpDeclined`, because `lowHp=0` says the branch stopped firing and cannot say how much was
removed — and the only "before" figure to hand (47% of close-quarters ticks) came from another
context. Same course, same jar: the clean runs read `0/166` and `0/136`. Note the asymmetry that
matters: the removed ticks are **stable** (~150/run) while the outcome swings by 5. "The fix does
something" and "the fix changes the verdict" are different claims and only the first is cheap.

ALSO: G-0 closed at its floor — 26 → 18 imports, every remaining one traced to live code, and the
`var` trick refused because it moves the counter without touching the coupling. `BuilderProcess` is
not a 1399-line port: altoclef calls exactly two entry points and always with a 1×1×1 schematic.
`allround` re-read from its own numbers: 1:1 and 1:2 in 120s is a QUARTER of `melee_basic`'s death
rate — it is not losing badly, it is under-fighting (5 landed swings in 120s, aim 89.8° off), and
its gate demands zero deaths.

NEXT: `narrow_bridge` n=8 on the healthy stand, full pvp sweep for the score, then the FULL mob
suite — `mob_trio` and `mob_skeleton` inherit `KIT_SWORD` and are in the blast radius, and `mob_trio`
is the one place this fix could plausibly make things worse. If it does, the answer is a crowd
exemption in the predicate, not a revert.

### ASSESS (checklist §6) — G-1.73, written before the sweep finished

1. **Did the score move?** The pre-fix pvp sweep closed 9/12 with three gate failures
   (`melee_basic`, `narrow_bridge_duel`, `allround`). `melee_basic` now passes — 4 PASS / 2 FAIL over
   healthy repeats and PASS in the sweep (5:5 at 26.2 fps) — against a baseline of 2 PASS / 6. What
   moved in the numbers where the verdict did not: `lowHpDeclined`, the ticks of useless retreat the
   predicate takes back, reads **40 a run on `melee_basic` and 136–187 on `narrow_bridge`**. Note that
   `narrow_bridge`'s sweep PASS came off a 9.9 fps retry and does NOT count.

2. **Which end goal?** Combat competence on the way to `@gamer` finishing the game on tungsten alone,
   plus G-0 closed at its floor (26 → 18 imports, every survivor traced to live code).

3. **Is this the right road?** Yes, and by the stated test: the fix is in the core, not beside it.
   The question "can I turn distance into damage" belongs to the class that already knows about
   weapons, and the retreat asks it. No hardcode, no server specifics, no reactive timer. The
   instrument repairs are the same shape — a threshold that is READ rather than restated, a print
   that cannot raise, a guard that inspects the run actually recorded.

4. **Are we treading water?** On `melee_basic`, no — it moved. On `narrow_bridge`, yes, and the rule
   applies: the target is NOT abandoned. What changes is the approach. Three signals now say the
   wounded retreat is not that course's cause — deaths are blows not falls (`self=0`,
   `knockback=1–2` of 16–17), the dose-response is inverted (three times the ticks removed, less
   effect), and the median sits inside the spread. Read from source instead of patching again: on a
   one-block bridge `strafeSideSafe` rejects both sides and the crit hop is gated off by
   `edgeScore`, so every advantage the bot has is suppressed by its own safety machinery and only
   the tempo-costing stand-off survives. That is G-1.74, and it is a MEASUREMENT (`--pin
   combatReachControl=false`), not another patch.

**Cost of the session's own mistakes, recorded because they are the lesson.** Seven claims of mine
were withdrawn on re-checking, and the last two were defects in code I wrote today: a print that
killed the eight-run series it was written to protect, and a starvation check blind to the retry
path — which is exactly where "passed on the second go" verdicts come from. Both were found by
re-reading after a suspicious number, not by a test.

