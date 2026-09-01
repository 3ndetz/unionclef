# Archive: mob_skeleton combat investigation (2026-08-13)

Archived 2026-09-01 from `docs/ai/progress.md` per the >500-line rule in `docs/ai/readme.md`. Content moved verbatim, not edited.

## 2026-08-13 — mob_skeleton went green, and the measuring rig was the blocker

### Investigate

Thirteen bot hypotheses had "measured nothing" on this course. Re-reading the harness rather than
the bot showed why: the rig was throwing away half of every series and misnaming the reason.

- The INVALID line printed the fps explanation for EVERY invalidation. One run was discarded with
  "client at 28.0 fps, below the 14.0 floor" — a sentence that contradicts itself. The guard that
  actually fired was the stand-sanity check.
- That guard read dw's gapMAX. The gap is the distance to the closest OTHER living entity, so once
  the target dies it measures the second tester client across the map: one post-kill fall recorded
  ~63 blocks and voided an otherwise clean fight.
- Cost: 7 of 14 runs INVALID in one series, 6 of 13 in the next.

The frame rate itself is BIMODAL — ten runs at 9-10 fps, three at 28.5-29, nothing between. A
render ceiling gives one smeared mode; two clean clusters mean a switch. I proposed the second
tester client as the culprit and then measured it: 44% and 32% of a core on a 24-core host, which
cannot take two thirds of the frame rate. Refuted my own hypothesis the same hour. The better fit
is startup state — clients are restarted between runs, and a condition set at startup lasts a run.

### Implement

- run_suite: each guard records why it fired; the summary prints that reason.
- run_suite: stand sanity reads gapMEAN, so one outlier no longer costs a run. Limitation written
  at the site rather than hidden.
- DamageWatch: health drops with no living entity within 32 blocks are not attributed (nothing
  alive hits from there) and are counted as dwNoBlame — carried through py4j AND the course
  printout in one change, because three counters here have already read zero for want of that.
- MobDefenseChain: mdDamageDealtTenths and per-swing attribution (swingHits) with the HP-ceiling
  confound documented — the sum read 18.0/18.0/18.0 against swing counts 4/3/2 and cannot
  discriminate. One clean datum survived: passed=3, crits=0, dealt=18.0 is 3x6 exactly, so swings
  land and are not being absorbed.

### Result

**mob_skeleton PASSED, twice** (runs 5 and 11 of a 16-launch series): min_hp=20.0, no arrow landed,
no criterion failed. First green for this course. It also settles the long argument about the
threshold: min_hp >= 19 is NOT unreachable, so it does not need correcting.

Not a bot improvement — the DamageWatch fix was deliberately undeployed during that series. What
moved was which runs counted: the unpinned arm read 0.60 arrows against 1.67 on the same code a few
hours earlier. Over an arrow of movement with nothing changed but the filter, which is the pooled
sd of 1.20 behaving exactly as the arm-size note predicts. Treat 0.60 as one draw, not a baseline.

combatEngageBand: interleaved, +0.79 arrows at 1.70 sigma — under the pre-registered 2-sigma bar,
so it stays off. Underpowered rather than refuted (needs 12-24 runs an arm; had 3 and 4). NOT
pooled with the earlier blocked pair despite the same direction: blocked arms here already produced
a 3.18 sigma artefact that interleaving cut to 0.46.

### Open

Stability of the green is unknown — a 12-run series on the deployed fix is measuring it now.


## 2026-08-13 — the pursue walk owns the last three blocks, and combat is locked out

### Investigate

The third engage-band series was pre-registered before its data existed (`deploy/runner/ab_arrows.py`)
on a mechanism argument: pooled over 33 runs arrows correlate with total band time (+0.43) and not
with reach refusals (+0.17), and the controller ticked for only 44 of 135 band ticks. At a 12-tick
cooldown those 44 permit about three swings; the missing 91 would permit eleven.

**That premise is now falsified, by the series measuring it.** At n=7 an arm the flag delivered the
missing ticks -- controller ticks 55 -> 175, a 3.2x increase -- and bought 2.7 swings against 2.5.

What the counters said instead, per run, with `(mdTung - cqEntry)` counting ticks where combat did
NOT own the legs:

    flag off   safety won  5-10 of 32-83     9-20%   reachMean 3.36-3.69   arrows 1.12
    flag on    safety won  6-205 of 67-249   9-82%   reachMean 3.74-5.08   arrows 1.58

    corr(controller ticks, reachMean) = +0.91
    corr(reachMean, arrows)           = +0.49
    skeleton shots fired               4.0 vs 2.3

Ticking the combat controller EARLIER makes the bot stand FURTHER OUT, almost deterministically.

### The line

`CombatController.tick` hands movement to the safety stage whenever `eyeToHitbox > REACH + 1.0`
= 4.0, and `closeQuarters()` -- the only code that presses toward strike distance, sprints, and
knows what REACH means -- runs solely in the `else`. Above 4.0 blocks the legs belong to a BFS
path-follower walking at the block the target occupied when the path was computed. It has no notion
of strike distance, and against something that backs away it is chasing a vacated square.

`reposition=0` and `brake=0` in every one of the fourteen runs, so the claim that beats combat to
the legs is not a safety event: it is the plain PURSUE walk holding a claim it does not need. This
is the THIRD instance of one shape in that file -- the sneak claim and the in-reach danger claim
were both converted from vetoes to layers, and both stopped at the 4.0 line.

It also explains combatEngageBand's three sub-threshold results (+0.83, +0.88 at 1.90 sigma, and
this one). That flag widens WHEN the controller ticks; it does not decide WHETHER those ticks may
drive. Alone it was inert by construction -- every tick it added landed on the far side of the 4.0
test.

### Implement

- `combatCloseOwnsBand` (off by default): within the 7.0 killing band, with line of sight, a plain
  PURSUE claim no longer outranks close-quarters. Braking, repositioning, narrow terrain and escape
  are untouched; PURSUE keeps the legs outside the band, where it is doing travel and obstacle
  avoidance and does them better. This is what `combatCloseToReach`'s javadoc asked for in July --
  "make the CONTROLLER close inside 4.5 rather than make the task wait until 3.0".
- `cqTookFromPursue`, reset per run, printed as the third field of `cq=`. Counts only ticks where
  the stage WOULD have won and no longer does -- not every eligible tick, which would report the
  flag working hard while changing nothing.
- `closeStats()` wired into all three mob courses. It had existed in Py4jEntryPoint since the
  closing telemetry went in and was read only by scenarios_pvp -- the FOURTH dead instrument here.
  Three hypotheses were spent on the last 1.5 blocks without once checking whether `forward` reached
  the keys. `dirAsked`/`dirBlockedFwd` were two lines from the per-run reset without being in it.
- `run_suite` stamped `res["arm"]` above both retry paths, each of which replaces `res` wholesale,
  so runs a client refresh had just RESCUED came out unlabelled. Label and applied pins now go on
  the row that is kept. `ab_arrows` splits a summary itself and drops starved / drift / jar-changed
  rows with a printed tally.

### Open

- The 40-launch series is still running and its verdict is judged by the pre-registration verbatim,
  not by this finding. Stopping it early because the answer now looks knowable is the exact failure
  pre-registration exists to prevent.
- Then: build, deploy, and a 40-launch interleaved series pinning `combatEngageBand` AND
  `combatCloseOwnsBand` together against both off. Check `cqTookFromPursue > 0` first -- a zero
  there means the mechanism never fired and the arrows are about something else.
- Residual, separate and unexplained: even in the flag-off arm, where closeQuarters owns 80-90% of
  ticks, reachMean sits at 3.4-3.7 against a 3.0 reach and the swing gate refuses on reach ~41 times
  a run. The arbitration hole does not obviously account for that. closeStats is now wired to split
  it: wanted > asked is the edge guard refusing, asked > pressed is arbitration, and wanted ==
  pressed at 4.5 blocks means the approach is losing a footrace to a retreating skeleton.


## 2026-08-13 (later) — the approach was never the pathfinder's failure

### Result

Both combat flags are refuted and the "the controller should own the approach" line is CLOSED, as
pre-registered before either series ran.

**combatEngageBand**, 40 interleaved launches, 0 invalid, scored twice (summary + console log split
on per-run PIN lines, agreeing exactly):

    arm A (off)  n=20  mean 1.32 arrows  sd 0.79
    arm B (on)   n=20  mean 1.62 arrows  sd 0.93
    difference  -0.30   SE 0.27   1.10 sigma

Under the 2-sigma bar and in the WORSE direction. The two earlier series read +0.83 and +0.88 at
3-6 runs an arm, both favouring the flag; at 20 an arm the sign REVERSED. Three careful series and
the direction was not stable until the n was.

**combatCloseOwnsBand + combatEngageBand as a pair**, 40 interleaved launches, 0 invalid, with a
mechanism gate declared in advance -- cqTookFromPursue must be 0 in arm A and >0 in arm B, so a
series where nothing fired would come out VOID rather than negative. The gate passed: 0 in all 20
arm-A runs, 8-213 in all 20 arm-B runs.

    arrows       A 0.88   B 1.23    -0.35, SE 0.32, 1.11 sigma  (under the bar, WORSE)
    passes       A 6/20   B 6/20
    ctl          A 52     B 166     combat drove 3x more
    reachMean    A 3.53   B 4.71    ...and stood a FULL BLOCK further out
    inReachRate  A 0.375  B 0.143   share of control ticks inside 3.0, more than halved
    bandToSwing  A 53     B 84      longer before the first swing landed

### The finding, which is larger than the verdict

`closeQuarters()` is a WORSE closer than the BFS pursue walk it was written to displace. The
hypothesis was that a path-follower cannot close because it chases the square the target has
already left; in fact it closes better than the range-band controller on every closing metric, by
a wide margin. The approach was never the pathfinder's failure, and three flags aimed at handing
combat more of it (combatCloseToReach, combatEngageBand, combatCloseOwnsBand) have now each made
things worse in their own instrument.

What replaces the target: corr(inReachRate, arrows) = -0.40 over 40 runs, -0.52 within arm B. The
SHARE of control ticks spent inside reach predicts the result, and the pathfinder is what maximises
it.

### One number recorded as NOT evidence

corr(strafeFar, reachMean) = +0.93 looked like the circle-strafe diluting the approach -- a clean
mechanism, and very nearly a fourth hypothesis. It is an identity: `strafeFarTicks` counts strafe
ticks taken BEYOND reach, so per control tick it is one minus the in-reach rate, and
corr(strafeRate, inReachRate) came out exactly -1.00. A correlation of exactly +/-1.00 between two
derived quantities is the signature of an identity, not a discovery. That counter measures
distance, not strafing, and cannot test the orbit at all.

Same lesson one level up: the FIRST pre-registration rested on corr(band ticks, arrows) = +0.43,
read causally. A longer fight has both more band ticks and more arrows. Two confounded correlations
in one day, one caught before it cost a series and one after.

### Calibration worth keeping

Arm A of the second series is the shipped behaviour and read 0.88 arrows; arm A of the first read
1.32, on behaviour that is identical (both flags default false, and with them off the arbitration
condition is the old one exactly). 0.44 arrows apart, about 1.8 SE. Interleaving makes the
within-series comparison immune to this, which is why it is the rule -- but it says plainly what a
single 20-run arm quoted across sessions is worth.

### Open

- The residual: even in arm A the bot is inside reach on 37.5% of control ticks and takes 53 band
  ticks to land its first swing -- at a ~40-tick shot cycle, more than one free shot spent closing
  the last four blocks.
- The next candidate, and the first one in a while that is not defined in terms of distance:
  `dodgeDrive` reads ~43 ticks a run against arm A's 52 control ticks, and corr(dodgeDrive,
  reachMean) = +0.57. The arrow dodge may be costing more arrows in delay than it avoids in
  flight. It needs its own pre-registration and a counter that is not an identity.


## 2026-08-13 (late) — the approach is fast; it is the HOLDING that fails

### Why a trace

Five pre-registered hypotheses about this approach returned null (engage band, the pair, close-to-
reach, no-orbit, dodge hold). Each was plausible, each had a mechanism, each was judged on an
AGGREGATE -- and aggregates produced four confounded totals in one day (checklist rule 4t). No
aggregate can say WHERE a fight's time goes, only how much of it there was. So: CombatTrace, one
line per tick, sampled at the final-word position after every writer, recording the keys the game is
about to read rather than the ones some layer asked for.

Saved: `docs/traces/mob_skeleton-2026-08-13.txt`.

### The first read was wrong, and checking it took two minutes

The raw trace says the bot presses NO KEYS on 48% of pre-swing ticks and stands still for 54 of
them at 11.7 blocks. That is the harness: the skeleton is summoned `{NoAI:1b}` and stays a statue
until `@test killhostile` and the `NoAI:0b` merge. Those 54 ticks cost nothing, because nothing is
shooting. Had it gone unchecked it would have been the sixth confounded number of the day.

### The engagement, 97 ticks from first movement to first swing

    +0  -> +24   11.57 -> 3.60 blocks, sprinting, 0.33 blocks/tick   THE APPROACH IS FAST
    +24          hurt=10 -- takes a hit, knocked back
    +28 -> +36   4.57 -> 5.77, PRESSING NOTHING                      knockback, no re-approach
    +40 -> +68   5.80 -> 2.58, re-closes                             inside reach at last
    +68          keys = .BL...  -- BACK and left
    +72 -> +84   2.84 -> 4.47, backing out of its own reach
    +88 -> +97   4.18 -> 3.04, closes a third time, first swing

    28% of engagement ticks LOSE ground; 41% of those press nothing at all.

The bot reaches strike distance at tick 24 of 97 and spends the other 73 being pushed out and
re-closing, twice. Every flag tried so far aimed at the approach. The approach was never the
problem.

### Two mechanisms, both visible in one run

1. **Knockback recovery presses nothing.** A hit throws the bot from 3.60 to 5.77 and for the next
   several ticks no key reaches the game at all. The pathfinder considers itself arrived (inRange is
   4.5) and the combat controller does not engage out there, so during the recovery nobody owns the
   legs. This is the 4.5-block line again -- met from the knockback side rather than the approach
   side, and the two flags that attacked it from the approach side were both refuted.

2. **The back-off overshoots its own band by seven times.** MOB_BACK_OFF_DISTANCE is REACH - 0.35 =
   2.65 and MOB_STRIKE_DISTANCE is REACH - 0.1 = 2.9, a band 0.25 blocks wide. At +68 the bot is at
   2.58 -- 0.07 inside the floor -- presses back, and travels to 4.47 before closing again. About 30
   ticks, most of a skeleton's shot cycle, spent leaving a range it had just paid 44 ticks to reach.

### Next

Confirm both across several traces before building on one run, then the obvious core question: a
skeleton has no melee reach to be "too close" to. The file already makes exactly this argument for
the recharge stand-off, which is skipped for RangedAttackMob -- the base band is not, and that is
what pressed `back` here. n=1 so far; it is a hypothesis with a trace behind it, not a result.


## 2026-08-13 (end) — eight series, one model, and the noise floor that explains them

### The model that closed the question

Every pass on mob_skeleton is an arrow MISSING; none is an arrow outrun. All 33 passing runs of the
first 167 were shot at (mean 3.97 arrows fired) and took nothing. The arithmetic forces it: the
skeleton spawns twelve blocks out, draws for 20 ticks, and its arrow crosses that in ~4.5, so the
first shot lands at ~24-25 ticks -- while the fastest traced approach covered 11.57 -> 3.60 blocks
in 24 ticks, about what sprint allows. One 4-damage arrow puts min_hp at 16 against a gate of 19.

    fight ~150 ticks / shot cycle ~40  =>  3-4 arrows fired
    hit rate 27-38%                    =>  about one lands
    the gate allows NONE
    P(pass) = P(all miss) ~ 20%        <- and the measured rate is 20% over 287 runs

The course behaves exactly as its own design predicts. Filed as TODOS G-1.82, with the note that
relaxing a gate in the same pass that is trying to move it is indistinguishable from tuning to
pass, so that decision is the user's.

### The null-control, obtained by accident and worth more than any flag

The last series' flag never fired -- its guard required the target inside REACH while a skeleton
was DRAWING, and skeletons draw at range while backing away. The mechanism gate caught it and the
series is VOID. Which means both arms were the SAME BOT, interleaved, 20 runs each:

    arrows landed  A 1.38  B 1.36   +0.01, 0.05 sigma   <- the pre-registered primary is exact
    passes         A 5/20  B 2/20
    arrows fired   A 5.10  B 3.45
    hit rate       A 27%   B 39%
    toSwing        A 85.3  B 67.2

At n=20 an arm, with nothing changed, pass counts swing by 3, hit rate by 12 points and toSwing by
18 ticks. That is the noise floor, measured at last, and it re-reads the whole day: the draw-dodge's
57% -> 18% (39 points) survives as real; the 38% -> 24% after the heading fix (14 points) is
suggestive only; toSwing differences under ~20 ticks mean nothing.

**The consequence is the honest answer to why eight careful series produced eight nulls: the
effects available on this course are the same size as the noise at affordable n.** Separating them
needs 50-100 runs an arm, four to eight hours a series. That is a reason to stop running
underpowered series, not to run a ninth.

### What was actually fixed (bot behaviour unchanged — every flag defaults off)

- The draw-dodge passed a pure perpendicular while the in-flight dodge blends DODGE_PRESS_BIAS=0.6.
  The constant's own javadoc predicts the cost of 0 -- "holds the range open for ever... a draw the
  bot always loses on damage" -- and that is what the counters showed. Corrected.
- run_suite stamped the A/B arm label above BOTH retry paths, each of which replaces the result
  object, so runs a client refresh had just rescued came out unlabelled.
- ab_arrows counted runs the harness itself had marked "not comparable", and now splits a summary
  by the pins actually applied.
- closeStats (fourth dead instrument), dirAsked/dirBlockedFwd reset, mdBandToFirstSwing,
  cqTookFromPursue, mdDodgeYielded (fifth dead instrument, caught by looking), and CombatTrace.
- A duplicate checklist rule whose first copy recommended the very thing that had failed.

### Process

Rule 4t: a gate metric must be a RATE or a MEDIAN, never a total. Four confounded totals in one
day -- band ticks, strafeFar (an identity: corr came out exactly -1.00), and dodgeDrive twice, one
of those inside a pre-registration written to prevent exactly this.
