"""OUTCOME OF #7 (2026-08-13). NO EFFECT -- AND THE BASELINE ARM IS THE REAL RESULT.

40 launches, interleaved, 20 an arm, 0 rcon stalls (the server-restart recovery held).

    arm A (off)  mean 5.05 cobblestone   passes 8/20
    arm B (on)   mean 4.60 cobblestone   passes 7/20
    difference  -0.45   SE 1.11   0.40 sigma      -> NO EFFECT. THE FLAG STAYS OFF.

The n=4 preview read 7, 0, 8, 6 and looked promising; the pre-registration said in advance that
four runs cannot separate a spread this wide, and it was right. mineAvoidUnderfoot buys nothing.

*** WHAT THE BASELINE ARM SAYS IS WORTH MORE THAN THE VERDICT. Arm A is the SHIPPED bot, and it
scores 5.05 with 8 passes in 20. Before today's buried-block fix the same rung read 0, 0, 0, 0, 5,
0 -- roughly one pass in six at best, usually a total shutdown. The rung has gone from dead to a
coin-flip, and that is the buried-block fix alone, measured here at n=20 without meaning to.

So the pit-digging story remains a true DESCRIPTION (the trace shows the bot burying itself and
stranding on the wall) that is not the binding CAUSE of the remaining failures -- refusing the
underfoot block changes nothing measurable. What is left is throughput: the gate wants 8 in 120 s
and the bot averages 5.

OUTCOME OF #9 (2026-08-14). NO EFFECT. THE PIT IS A TRUE DESCRIPTION AND NOT A LEVER.

40 launches, interleaved, 20/19 an arm.

    arm A (off)  mean 3.50  zeros 8/20  passes 3
    arm B (on)   mean 4.42  zeros 7/19  passes 7
    PRIMARY  +0.92  SE 1.08  0.85 sigma   -> no effect
    SECOND   zero rate -0.03, 0.20 sigma  -> no effect

THE FLAG STAYS OFF, and with it the pit line closes as a LEVER. It remains true as a description --
the position trace really does show 75 of 120 seconds spent inside the excavation, and the wall
strandings really do start there -- but keeping targets on the surface does not convert that into
cobblestone, and neither did forbidding the block underfoot (#7, 0.40 sigma).

⛔ ONE HONEST WRINKLE ABOUT THE GATE. Scored from the run log, scanBelowFeet reads 0 in BOTH arms,
which looks like a void series. It is a parser artefact of mine: `scan=` is never printed to the
log -- the craft courses print only breakFail= and stranded= -- so the regex found nothing. The
mechanism WAS verified out of band before the series, by reading placeStats over py4j directly:
944 rejections in a single run. So the series is readable, but the gate did not do its job here,
and the fix for next time is to print the token the gate depends on, not merely to expose it.

WHAT IS LEFT ON THIS RUNG, stated plainly after nine pre-registrations: the gate wants 8
cobblestone in 120 s, the shipped bot averages 4.32 (n=60, sd 3.61), and five separate
interventions -- ban radius, re-arm budget, underfoot, stranded rescue, surface-only -- have each
measured nothing. The remaining gap is raw mining throughput and nobody has yet measured WHERE the
seconds go in a healthy, non-stranded run. That measurement, not another flag, is the next step.

PRE-REGISTRATION #9, WRITTEN BEFORE ITS DATA EXISTS (2026-08-14, mineStayOnSurface).

Aimed at the PIT, which the day's measurements make the single cause of both of mine_stone's
failure modes: 75 of 120 seconds spent oscillating inside its own excavation (slow runs), and
climbing out of it onto the arena wall (the 35-45% of runs scoring zero).

  * 40 launches, interleaved, --pin-alt mineStayOnSurface=true, ~20 an arm;
  * STATISTIC: MEAN COBBLESTONE, 2 sigma, against the pooled baseline 4.32 (n=60, sd 3.61);
  * MECHANISM GATE: scanBelowFeet > 0 in the pinned arm, 0 in the baseline. Already verified in a
    smoke test -- 944 rejections in one run -- because a gate declared and not exposed is what made
    series #8 void, and this one was wired WITH the flag rather than after it;
  * SECOND STATISTIC, declared now: the zero rate. The flag targets the pit, and the zeros are the
    pit's other consequence, so a drop there with the mean unmoved is a real outcome;
  * REGRESSION GATE: mine_diamond must still PASS. It is green precisely because the bot can
    descend to ore, and this restricts descending -- the fallback (retry unrestricted when the
    surface is empty) exists for exactly that, and this is where it gets checked rather than
    assumed;
  * if it fails, the pit line is closed as a LEVER while remaining true as a description, and the
    rung's gap goes back to raw throughput.

HONEST PRIOR: six smoke runs read 0, 0, 0, 7, 8, 8 -- mean 3.83, two passes, which is
indistinguishable from the 4.32 baseline at that n. The opening two zeros looked alarming and were
a bad draw. Recorded so neither half of that can be quoted later.

THERE IS NO BASELINE DRIFT ON mine_stone, AND THE POOLED FLOOR IS 4.32 (2026-08-13).

I wrote in a commit that the shipped arm was drifting down -- 5.05, then 4.25, then 3.65 across
three series on unchanged code -- and called it "unexplained and the thing to watch". Checked
rather than left standing:

    frame rate    29.6, 29.4, 29.4 (range 28-30)   flat, so not starvation
    ab16 arm A    n=20  mean 5.05  sd 3.47  SE 0.78
    ab17 arm A    n=20  mean 4.25  sd 3.56  SE 0.80
    ab18 arm A    n=20  mean 3.65  sd 3.66  SE 0.82
    first vs last +1.40, SE 1.13, 1.24 sigma
    all pooled    n=60  mean 4.32  sd 3.61

Three means within about one SE of the pooled value. That is ONE population, not a decline -- I
was pattern-matching on three numbers that agree. Recorded because the previous wording would have
sent the next pass hunting a phantom, which is the specific waste this file exists to prevent.

*** USE 4.32 +/- 0.47 (n=60) AS THE SHIPPED BASELINE, not any single series. The course's own
spread is sd 3.6 -- it produces 0 and 9 from identical builds -- so a 20-run arm carries SE 0.8 and
two arms differ by 1.1 before anything real has happened. That is why every flag tried on this rung
needed 2 sigma on 20 an arm, and why the n=4 and n=5 previews during the day were worthless.

OUTCOME OF #8, RE-RUN AFTER THE FLAG WAS MADE TO ACTUALLY FIRE (2026-08-13). REFUTED.

The first attempt at #8 was VOID: strandedRescues was declared as its mechanism gate and never
exposed, and once exposed it read 0 -- the flag could not fire at all. Three placement fixes later
(the discriminator had to sit above BOTH the tungsten-isActive guard and the isPrimary exemption,
and the no-keys guard had to stop wiping the very history the check needs) it fires. Then:

    MECHANISM GATE   arm A fired 0/20 runs, arm B 12/19        -> PASSES, this is a real negative
    arrows/cobble    A mean 3.65   B mean 2.21   -1.44, 1.27 sigma  -> no effect, WORSE direction
    zero rate        A 0.45        B 0.68        +0.23, 1.47 sigma  -> MORE zeros with it on
    passes           A 7/20        B 3/19

Both declared statistics point the wrong way. THE FLAG STAYS OFF, and the stranding line is closed
exactly as the pre-registration said it would be: "if both fail, the rung's remaining 60% is a
throughput question, not a recovery one."

WHAT IT COST AND WHAT IT BOUGHT. Four instruments (strandedRescues, lastSkip, lastRealSkip, the
getPriority early-return counters), three blind placement patches, and a void 40-launch series.
What it bought is worth keeping: UnstuckChain is deliberately inert under tungsten by an exemption
whose comment assumes "tungsten handles its own stuck recovery" -- and now we know that shimmying
those ticks does not help either, so the assumption's REPLACEMENT is not a shimmy. Whatever fixes
stranding has to be something else.

PRE-REGISTRATION #8, WRITTEN BEFORE ITS DATA EXISTS (2026-08-13, unstuckWhenGoalButNoPath).

Target: the strandings that are ALL of mine_stone's remaining failures. The n=20 baseline is
bimodal -- eight runs at 8-9 cobblestone and six ZEROS -- and the zeros are the bot on the arena
wall at y=-57 with a goal, no path and no keys pressed, which UnstuckChain skips by construction.

  * 40 launches, interleaved, --pin-alt unstuckWhenGoalButNoPath=true, ~20 an arm;
  * STATISTIC: MEAN COBBLESTONE, 2 sigma, against the measured baseline of 5.05 / 8 passes;
  * SECOND STATISTIC, declared now rather than chosen later: the ZERO RATE. This flag targets the
    zeros specifically, so "fewer zeros with the mean unmoved" is a real outcome and not a
    consolation -- but it only counts because it is written here BEFORE the data;
  * MECHANISM GATE: strandedRescues > 0 in the pinned arm and 0 in the baseline, else VOID;
  * REGRESSION GATE: the craft ladder must still read 11/12 afterwards. This touches a chain that
    runs on every course, and shimmying a bot that was waiting on purpose is the harm the guard
    exists to prevent;
  * if both fail, the stranding line is closed and the rung's remaining 60% is a throughput
    question, not a recovery one.

HONEST PRIOR: five runs with the flag on read 5, 6, 0, 8, 9 before a server stall cut the series.
Two passes in five is indistinguishable from 8 in 20, and a zero still appeared WITH the flag on --
which is evidence against, and is recorded here so it cannot be forgotten if the full series looks
better.

PRE-REGISTRATION #7, WRITTEN BEFORE ITS DATA EXISTS (2026-08-13, mineAvoidUnderfoot).

First pre-registration on the PLAYTHROUGH ladder rather than the mob course. Statistic differs
because the ruler differs: mine_stone counts cobblestone gathered in 120 s, gate at 8.

  * 40 launches, interleaved, --pin-alt mineAvoidUnderfoot=true, ~20 an arm;
  * STATISTIC: MEAN COBBLESTONE GATHERED, 2 sigma. The PASS RATE is recorded but not promoted --
    #4 on the mob course is what promoting a secondary after the fact costs;
  * MECHANISM GATE: scanUnderfoot must be > 0 in the pinned arm and 0 in the baseline, or the
    series is VOID rather than negative;
  * REGRESSION GATE, and it is the reason this is a flag at all: mine_diamond must still PASS.
    The bot descends by digging down, so a preference against the underfoot block could break
    exactly the rung that is green today;
  * if it clears: ship the flag on, re-run the full craft ladder, and only then call the rung fixed;
  * if it does not: the flag stays off and the pit-digging explanation stays a description rather
    than a cause -- the trace shows the bot DOES bury itself, but showing that it costs the gate
    is a different claim and would remain unproven.

HONEST PRIOR: a first look at n=4 read 7, 0, 8, 6 (mean 5.25, one pass) against 5, 3, 6, 7, 7, 0
(mean 4.67, no passes) without it. That is suggestive and nothing more -- this course has produced
0 and 8 from identical builds, and four runs cannot separate a spread that wide. Recorded now so
the n=4 cannot later be quoted as the result.

PRE-REGISTRATION #6, WRITTEN BEFORE ITS DATA EXISTS (2026-08-13, combatApproachLatch).

The first fix on this course aimed at the closing BUDGET rather than at who owns the legs.
ApproachLatch presses forward+sprint toward a committed target ONLY on ticks where no other writer
pressed a direction -- no band, no strafe, no back-off, so it cannot fight the controller because
it never runs on a tick the controller drove.

  * 40 launches, interleaved, --pin-alt combatApproachLatch=true, ~20 an arm;
  * STATISTIC: MEAN ARROWS LANDED, 2 sigma. Zero-rate recorded, NOT promoted (#4 is what promoting
    it after the fact costs);
  * MECHANISM GATE: latched must be 0 in arm A and > 0 in arm B, or the series is VOID rather than
    negative. Smoke test already read latch=28/54, so it fires;
  * secondary, and this is the one that says whether the MODEL is right: arrows FIRED. The whole
    claim is that a faster close means fewer shot cycles. If arrows landed improves while arrows
    fired does not, the explanation is wrong even if the number is good;
  * if it clears: mob_melee and mob_trio before shipping. The latch is not skeleton-specific -- it
    fires on any committed kill -- so a zombie course is where it could regress, and "it only fires
    on empty ticks" is a PREDICTION to check, not an assumption;
  * if it fails: the closing-budget line is closed too, and what remains is G-1.82's course-level
    statement.

WHY THIS IS NOT A SIXTH GUESS. The five refuted flags all moved WHO drives. This one was derived
from a measured budget in blocks: an idle tick covers 0.067 against a sprint tick's 0.244 while the
skeleton retreats at 0.215, so 47% idle turns a +0.029 close into a -0.055 loss. The smoke test
then confirmed the mechanism directly in a trace -- re-approach idle 24% -> 0%, meanStep 0.167 ->
0.218, net -0.048 -> +0.003 -- BEFORE any arrows were counted.

HONEST PRIOR: the noise floor here is 3 passes and 12 hit-rate points at n=20 an arm, and the model
says a faster close buys at most one fewer arrow fired. That is about 0.3 arrows landed -- close to
the resolution limit. A null would NOT refute the budget arithmetic, only its size, and that is
recorded now so a null cannot later be written up as one.

*** THE CLOSING BUDGET, IN BLOCKS RATHER THAN TICKS (2026-08-13). AND TWO OF MY OWN CORRECTIONS.

Measured with REAL per-tick displacement (position delta), pooled over four traced fights.

!! CORRECTION 1: "the bot makes 54% of sprint speed" was an artefact of reading
ClientPlayerEntity.getVelocity(), which does not track travel for the LOCAL player -- the client
applies movement to position directly. Real displacement while genuinely sprinting is 0.228-0.244
b/t, i.e. 81-87% of sprint, not 54%. Checked before building on it, which is the only reason it did
not become a fix.

!! CORRECTION 2: "the circle-strafe is the difference between closing and losing ground" held in
ONE fight (0.237 vs 0.212) and DIED on pooling: 0.244 vs 0.241, -0.003 at 0.30 sigma over four
fights, n=123/95. The orbit costs nothing measurable. Flagged as needing pooling when it was found,
so nothing was built on it either.

THE BUDGET THAT SURVIVES, and it is the whole course in four numbers:

    clean sprint tick   step 0.244 b/t     net vs retreat  +0.029
    idle tick (no keys) step 0.067 b/t     net             -0.148
    dodge-driven tick   step 0.179 b/t     net             -0.036
    skeleton retreat         0.215 b/t

AN IDLE TICK LOSES FIVE TIMES WHAT A SPRINT TICK GAINS. That is why the tick COUNT was the wrong
ruler: at 47% idle during re-approach the average is 0.53*0.244 + 0.47*0.067 = 0.160 b/t, BELOW the
skeleton's 0.215 retreat, so the bot loses ground on average and settles at the ~5-block equilibrium
the histogram shows. At 0% idle the average is 0.244 and it gains.

!! WHICH MAKES MY EARLIER DISMISSAL WRONG. "The re-plan window is 15% of engagement, median 1 tick,
~0.56 extra shots a fight -- not the dominant term" counted TICKS. In BLOCKS those ticks dominate
the closing budget, because the margin they are spent against is only +0.029. A one-tick key
release is not one tick of lost progress; it is about five.

SO THE FIX IS NAMED AND SIZED: keep the legs driven during the re-plan gaps. Removing the idle
ticks moves re-approach from -0.055 b/t (losing) to +0.029 (gaining), which is the difference
between a fight that ends and one that runs until the skeleton has fired four arrows.

⛔ NOT ATTEMPTED IN THIS SESSION, DELIBERATELY. It is a change to executor/walker key handling on
the hot path of every course, and the repo's own note on the planner task says the regression-
dangerous work must not be started at the end of a long session. The measurement is the deliverable;
the fix wants a fresh pass with nav + mob baselines around it.

THE 5-BLOCK EQUILIBRIUM IS THE SKELETON'S, NOT THE BOT'S (2026-08-13). TWO HYPOTHESES, BOTH FREE.

Hypothesis: the 9%-of-runs stall is CHATTERING at the 4.5 inRange switch -- two controllers sharing
a threshold with no hysteresis, which would park the bot on the boundary. The stalled runs' lastGap
values did look like it: 4.51, 4.53, 4.51, 4.55, 4.53, 4.68, 4.64, 4.70.

REFUTED from traces already on disk, at no cost. Pooled distance histogram, 1857 fight ticks:

     2.5   90 ###########
     3.0  145 ##################   <- REACH
     3.5  188 ########################
     4.0  157 ####################
     4.5  124 ################     <- the inRange switch: a DIP, not a spike
     5.0  213 ###########################
     5.5  185 ########################
     6.0   75 #########

Only 5.5% of ticks fall in the 4.3-4.7 band. There is no pile-up at the switch, so no chattering,
and the hysteresis fix that would have followed is unnecessary. Two minutes of arithmetic against a
build-deploy-series cycle.

*** WHAT THE HISTOGRAM DOES SAY, and it is the physical reason this course is hard: the mode is
5.0-5.5 blocks, and only 9.8% of fight ticks are inside REACH at all. A vanilla skeleton BACKS AWAY
when the player closes inside about five blocks, so that equilibrium is maintained by the TARGET,
not chosen by the bot. The bot can only beat a retreating skeleton by sprinting continuously, and
the trace says it holds sprint on 51% of re-approach ticks.

That reframes every "closing" hypothesis in this file: the bot is not choosing to stand at 5
blocks, it is being HELD there, and the only counter is uninterrupted sprint. Which is also why
anything that interrupts the legs -- dodge, strafe, knockback, a re-plan -- costs contact
disproportionately to its tick count.

And it is why "hold sprint while approaching" was measured WORSE once already (closest_gap
5.73 -> 7.78): sprint-hits add knockback, so sprinting THROUGH the strike re-opens the gap the
sprint just closed. The lever is sprinting up to reach and arriving unsprinted, which is what the
shipped code already does -- so this is an explanation, not an outstanding fix.

THE YIELD PREMISE IS DEAD, AND A FIVE-MINUTE SMOKE TEST KILLED IT INSTEAD OF A HUNDRED-MINUTE SERIES.

The guard was corrected after the void series -- from "target inside REACH (3.0) during a draw",
which is unreachable, to "inside REACH + 1.5 = 4.5", the same threshold the rest of the file uses
for inRange, with the charged-cooldown condition kept. Smoke-tested BEFORE committing a series:

    dodgeYield = 1 in one fight, 0 in the next

Still essentially never. Skeletons draw at a mean gap of 5.6 blocks and back away as the bot
closes, so an ACTIVE DRAW and a CHARGED SWING almost never coincide -- at any threshold that still
means "close enough to strike".

SO THE PREMISE ITSELF IS WRONG, not just the constant. The draw-dodge cannot be raising exposure by
stealing swings, because it is almost never armed at a moment when a swing was available. Whatever
makes arrows FIRED go 3.15 -> 5.55 is something else -- and the null-control says even that figure
is only 2.4 against a 1.65 swing between IDENTICAL arms, so it may not need explaining at all.

No series was run. The five minutes this cost were the whole point of having a mechanism gate:
three times today it separated "did not work" from "never ran", and this time it did it before the
hundred minutes rather than after.

VOID BY ITS OWN GATE -- AND AN ACCIDENTAL NULL-CONTROL WORTH MORE THAN THE FLAG (2026-08-13).

combatDodgeYieldsToSwing, 40 launches, combatDodgeOnDraw pinned in BOTH arms so the yield was the
only difference. Gate: mdDodgeYielded must be 0 in arm A and >0 in arm B.

    arm A max 0        arm B: ZERO in 20 of 20 runs      GATE FAILS -> SERIES VOID

The guard required the target inside REACH (3.0) while a skeleton was DRAWING. Skeletons draw at
range and back away as you close, so that condition is close to unreachable and the yield never
fired once. A design error in the guard, caught by the gate rather than by a null.

*** SO THE TWO ARMS WERE BEHAVIOURALLY IDENTICAL, WHICH MAKES THIS THE NULL-CONTROL THIS COURSE
HAS NEVER HAD. Twenty runs an arm of exactly the same bot, interleaved, same session:

    arrows landed   A 1.38   B 1.36    +0.01, 0.05 sigma      <- the primary behaves perfectly
    passes          A 5/20   B 2/20    <- a 3-pass gap from nothing at all
    arrows fired    A 5.10   B 3.45    <- 1.65 apart from nothing at all
    hit rate        A 27%    B 39%     <- 12 points apart from nothing at all
    toSwing         A 85.3   B 67.2    <- 18 ticks apart from nothing at all

The pre-registered statistic came back at 0.05 sigma, exactly as it should against no difference.
Every SECONDARY moved by about as much as the real effects claimed for them earlier today.

THAT IS THE CALIBRATION EVERY SECONDARY IN THIS FILE NEEDED. At n=20 an arm, pass counts swing by
3, hit rate by 12 points and toSwing by 18 ticks with NOTHING changed. So:
  * "passes A 1/20 B 8/20" from series #3 is 5 passes -- larger than this, and it still failed to
    replicate, which is now doubly explained;
  * the 57% -> 18% hit-rate shift from the draw-dodge is 39 points, well outside this noise, and
    survives as a real effect;
  * the 38% -> 24% after the heading fix is 14 points -- barely outside it, and should be treated
    as suggestive rather than established;
  * toSwing differences under ~20 ticks mean nothing at this n.

Nothing here changes a verdict, because every verdict was taken on the pre-registered primary and
the primary is the one thing this control shows to be well behaved. It changes how much weight the
supporting numbers can carry, and that is worth a void series.

...AND THE RE-PLAN WINDOW IS TOO SMALL TO MATTER. SIZED BEFORE BUILDING, WHICH IS THE POINT.

The commit above promised the window would be sized from traces on disk before anything was
implemented. Sized, over three traced fights, EXCLUDING the harness's NoAI setup window (the bot
stands still for 52-55 ticks before the fight is ordered, and nothing is shooting):

    engagement ticks           437 across 3 fights
    idle ticks                  67 = 15% of engagement
    windows                     27, MEDIAN 1 tick, max 18
    windows >= 5 ticks           2 in three fights, 29 ticks total (7%)
    => cost                    ~0.56 extra shots a fight

Not the dominant term, and not worth a series. Most "idle windows" are a single tick of key
release between path segments, which is normal.

!! AND THE 47% FIGURE FROM THE PREVIOUS ENTRY NEEDS ITS DENOMINATOR SAID OUT LOUD. It was computed
over RE-APPROACH ticks -- a subset chosen precisely because idling concentrates there. Over the
whole engagement the same data reads 15%. Both numbers are correct and only one of them is a
measure of how much the defect COSTS. That is the fourth denominator mistake of the day and the
first one I caught before spending anything on it, which is the only difference that matters.

WHERE THAT LEAVES THE COURSE, arithmetically rather than hopefully. A fight runs ~150 ticks, a
skeleton shot cycle is ~40, so it fires 3-4 arrows; at the ~30-38% hit rate the baseline shows,
about one lands. The gate allows NONE. To pass, every arrow in the fight has to miss.

    P(pass) = P(all 3-4 arrows miss) ~ 20%   <- and the measured pass rate is 20%, over 287 runs

So the course is behaving exactly as its own arithmetic predicts, and the bot is not obviously
failing at anything. Closing it needs either a much shorter fight (the floor is ~24 ticks to close
plus three swings at a 12-tick cooldown, about 60 ticks, i.e. 2 arrows) or a hit rate near zero.
The draw-dodge reached 24% and that is not enough.

THIS IS NOW A STATEMENT ABOUT THE COURSE, and pre-registration #5 said in advance that if the
avoidance lever failed, such a statement belongs in TODOS as a course-level item rather than as
another bot hypothesis. It is going there.

THE IDLE TICKS ARE A RE-PLAN WINDOW (2026-08-13). NAMED, WITH THE LAYER THAT OWNS IT.

CombatTrace now also carries what tungsten cannot see -- MobDefenseChain publishes Nav.isPathing(),
the chain priority and the holding task every tick. Pooled over three traced fights, 24 idle
re-approach ticks (beyond 4.5 blocks, after having been inside reach, no key reaching the game):

    altoclef Nav.isPathing()  TRUE   24/24  (100%)      and 100% on moving ticks too
    chain claimed the bot            24/24  (prio 65)
    task holding                     KillEntitiesTask, all 24
    tungsten walker / queue           0/24 / 0/24
    tungsten physics executor         8/24  (33%)       against 72% on moving ticks
    dodge driving                     0/24

The executor is the only signal that discriminates. Nav.isPathing() says "yes" on every tick of
both kinds, so it cannot be used to tell a moving bot from a standing one -- worth knowing on its
own, given how many gates hang off it.

FOLLOWING IT ONE LAYER DOWN NAMES THE WINDOW. Nav.isPathing() is true here via
TungstenHelper.isActive(), which returns busy when {@code PATHFINDER.active || isExecutorRunning()}.
The executor is off on these ticks, so what is true is PATHFINDER.active: the pathfinder is
SEARCHING. The bot is standing still inside a re-plan, in the open, being shot at, in a fight it
had already reached.

That was candidate #1 of the three written down before looking ("a re-plan gap, an arrived test
satisfied outside reach, or the chain never ticking the kill task"). The other two are excluded by
the same table: the chain claims the bot on all 24 ticks and KillEntitiesTask holds it on all 24.

WHY THIS IS THE FIRST TARGET HERE THAT IS NEITHER PHYSICS NOR ARBITRATION. Knockback is physics and
the bot recovers from it in one tick. Arbitration was five flags and all five are refuted -- and
could not have helped, because on these ticks there is no competing claim to arbitrate, only an
absence. A re-plan that stops the legs is a plain defect with an obvious shape to its fix: keep
moving toward the target while the search runs, at least where line of sight is clear and a
straight step is safe.

NOT IMPLEMENTED YET, deliberately. This course has spent six series on changes made before their
mechanism was pinned down, and the next one gets pinned down first: how LONG is a re-plan window,
how often does one open per fight, and what fraction of total exposure do they add up to? The trace
can answer all three from runs already on disk.

THE IDLE TICKS ARE A STOPPED PATHFINDER, NOT A FAILING ONE (2026-08-13).

CombatTrace extended with the three signals that say whether anything is trying to move the bot:
BlockPathWalker.isRunning, MovementQueue.isRunning, TungstenModDataContainer.isExecutorRunning.
One traced fight, 222 ticks; 66 of them re-approach (beyond 4.5 blocks after having been in reach),
22 of those idle:

    ON THE IDLE TICKS      walker 0/22    queue 0/22    executor 4/22
                           ALL THREE FALSE on 18 of 22 (82%)
    on the moving ticks    walker 0/44    queue 0/44    executor 25/44

So the bot is not a pathfinder that is running and failing to press keys. It is a pathfinder that
has STOPPED, while the bot stands at 5.1-5.8 blocks from a live skeleton in the middle of a fight
it has already joined. The executor is the only one of the three that ever runs here, and it is
off for most of the gap.

That is a different defect from everything tried on this course. Five flags argued about WHO should
own the legs; the answer on these ticks is nobody, and no arbitration change can help because there
is no competing claim to arbitrate.

Saved: docs/traces/mob_skeleton-pathstate-2026-08-13.txt

NEXT, precisely: find why the executor stops mid-approach. The candidates are a re-plan gap (the
task asks for a new path and nothing drives while it is computed), an "arrived" test satisfied at a
distance that is NOT inside reach, or the chain not giving the kill task a tick at all. Those are
distinguishable by instrumenting the altoclef side -- Nav.isPathing() and the kill task's own state
-- which CombatTrace cannot see from tungsten. Instrument first; this course has now paid six times
for going the other way round.

POST-CONTACT EXPOSURE, MEASURED (2026-08-13). AND A CORRECTION TO MY OWN TARGET.

!! FIRST, THE CORRECTION. Two commits earlier I wrote that "every hit knocks the skeleton 2.78
blocks away, so the bot re-closes 3-4 times per kill" and named that the next target. THE 2.78 WAS
THE BOT BEING KNOCKED BACK BY ARROWS -- it came from player.hurtTime events -- not the skeleton
being knocked back by our sword. Measured properly, over 8 landed swings in 4 traced fights:

    skeleton pushed by our sword:  mean 1.37 blocks, and the bot is back inside reach in 1 TICK

So sword knockback costs nothing and the target I named was wrong. It is corrected here rather
than quietly dropped, because it was committed.

WHAT POST-CONTACT EXPOSURE ACTUALLY IS. Intervals between our own successive swings, 4 traced
fights, against a ~12-tick attack cooldown:

    19 and 22 ticks   -- in reach 32-36% of the interval   (near-optimal)
    90 and 123 ticks  -- in reach 4-6% of the interval     (contact lost entirely)

The long ones are the whole cost. Walking through the 123-tick case: the bot swings at 2.99, the
dodge fires and puts it at 4.25, an arrow lands and it drifts to 7.98 PRESSING NOTHING, re-closes
intermittently, gets to 3.87, dodge and arrow again, out to 5.77, and finally swings at +123.
Through all of it ctl advanced 17 of 123 ticks: the combat controller is beyond its 4.5-block gate
for 86% of the interval.

THE NUMBER THAT NAMES THE DEFECT. Over every RE-APPROACH segment in the four traces -- beyond 4.5
blocks, after the bot had already been inside reach once -- 365 ticks in 8 segments:

    forward pressed     38%
    sprint pressed      51%
    NO KEYS AT ALL      47%
    dodge driving       12%
    being shot           6%

The legs are idle on 47% of the ticks the bot spends getting back into a fight it had already
reached. Dodge and knockback together account for 18% of it, so they are not the explanation.

This is the 4.5-block line for the fourth time, and for the first time it is characterised rather
than guessed at: beyond it the combat controller does not run BY DESIGN, and the pathfinder that
owns the legs there presses nothing half the time.

!! AND NOTE WHAT THIS DOES NOT LICENCE. "Give combat the legs out there" was combatCloseOwnsBand,
and it was measured WORSE (reachMean 3.53 -> 4.71, inReachRate 0.375 -> 0.143) because
closeQuarters orbits and holds a band rather than closing. The fix is not to hand these ticks to
the controller; it is to find out why the PATHFINDER idles on them. That wants the same treatment
the approach got -- instrument it and read it -- not another flag.

OUTCOME OF THE DRAW-DODGE RE-TEST (2026-08-13), AFTER GIVING THAT HEADING ITS CLOSING BIAS.

40 launches, 0 invalid, 20 an arm. Gate passes on the median: dodgeDrive 21 -> 39.

    arrows landed   A 1.20 (sd 0.87)   B 1.31 (sd 1.31)   -0.11, SE 0.35, 0.32 sigma
    passes          A 4/20             B 6/20

Null. THE FLAG STAYS OFF. Six pre-registered series on this course, six nulls.

THE PREDICTION WRITTEN BEFORE THE RUN, SCORED HONESTLY. It said: if the fix preserves the
approach and the hit rate stays near 18%, landed drops to ~0.5 and the gate becomes reachable; if
the hit rate was low only BECAUSE the bot was far and slow, it climbs back toward 57%.

    the fix worked mechanically   toSwing 50.7 -> 106.7 became 61.9 -> 58.8, approach RESTORED
    the hit rate did NOT revert   24% against this series' 38% baseline, not back to 57%
    but exposure stayed up        arrows fired 3.15 -> 5.55, and that is what cancels it

So half right, and the wrong half is the informative one. Avoidance is NOT merely an artefact of
standing far away -- at a restored approach speed the sidestep still cuts the hit rate by about a
third. What did not follow is exposure: the bot still eats 76% more shots, which exactly undoes a
37% better miss rate. Reaching first contact on time is no longer the problem; the fight AFTER
contact is still long enough to be shot through.

NOTE THE BASELINE MOVED AGAIN: arm A's hit rate reads 38% here against 57% in the previous series
on identical behaviour. Between-series drift of that size is why rule 4r interleaves arms, and why
none of the cross-series comparisons in this file are quoted as effects.

WHAT THIS LEAVES, stated as a target rather than a hypothesis: exposure after first contact. The
approach is solved; the kill is not. A skeleton at 20 HP takes 3-4 sword hits at a 12-tick
cooldown, and every hit knocks it 2.78 blocks away (measured), so the bot re-closes 3-4 times per
kill. THAT is where the extra shots are bought, and it is the one segment of the fight nothing has
measured yet.

OUTCOME OF #5 (2026-08-13). NULL ON ARROWS -- AND THE FIRST INTERVENTION THAT MOVED THE VARIABLE.

40 launches, 0 invalid, 20 an arm. Mechanism gate PASSES on the median, as required by rule 4t:
median dodgeDrive 18 -> 64.

    arrows landed   A 1.51 (sd 0.76)   B 1.44 (sd 1.09)   +0.07, SE 0.30, 0.25 sigma

Nothing on the pre-registered statistic. THE FLAG STAYS OFF. But the secondaries are the most
informative numbers of the day:

    arrows FIRED at the bot   A 2.65    B 7.80     <- three times the exposure
    arrows LANDED             A 1.51    B 1.44
    => SKELETON HIT RATE      A 57%     B 18%      <- the dodge WORKS
    ticks to first swing      A 50.7    B 106.7    <- and it wrecks the approach to do it

The model said the pass rate is the rate at which the skeleton misses, and that lateral velocity
at release is the only lever on it. This is the first intervention that moved that lever, and it
moved it a long way: 57% to 18%. The reason the arrows did not follow is equally plain -- the dodge
buys avoidance by overriding the legs, so the approach more than doubles, and three times as many
shots at a third of the hit rate is the same number of hits.

THAT IS A COUPLING, NOT A CEILING, and it is the first target on this course that is not a guess.
ProjectileDodge presses a heading that REPLACES forward motion. It already has the seam for the
fix: DODGE_PRESS_BIAS blends a closing component into the dodge heading, and the point-blank
special case that removed that bias was measured and reverted. So the question is whether the
dodge can keep its lateral velocity while still advancing -- sidestep across the shot without
stopping the approach -- rather than trading one for the other.

Predicted, before that is built or run, so it can be wrong on the record: if a dodge that preserves
the approach holds the hit rate near 18% while keeping arrows fired near 3, the landed count goes
to roughly 0.5 and the gate becomes reachable for the first time. If the hit rate is only low
BECAUSE the bot is far away and slow, it will climb back toward 57% as the approach is restored,
and the lever is an illusion produced by distance.

PRE-REGISTRATION #5, WRITTEN BEFORE ITS DATA EXISTS (2026-08-13, combatDodgeOnDraw).

The model above says the pass rate IS the rate at which the skeleton misses, and that lateral
velocity at release is the only thing that changes it. Exactly one flag in the tree targets that,
and it is off on evidence that no longer counts: n=12, 1.42 vs 1.94 arrows at 1.02 sigma, taken in
the era when ~50% of every series was being discarded and the invalid guard was misfiring.

  * 40 launches, interleaved, --pin-alt combatDodgeOnDraw=true, ~20 an arm;
  * STATISTIC: MEAN ARROWS LANDED, 2 sigma. The zero-rate is recorded but NOT promoted -- #4 is
    what promoting it after the fact costs;
  * MECHANISM GATE, and note it points the OTHER WAY this time: dodgeDrive must RISE in the pinned
    arm, because this flag ADDS dodge episodes (one per draw) rather than shortening them. Judged
    on the MEDIAN, per rule 4t, since the mean of that counter is what broke #4;
  * secondary, recorded not gated: arrows FIRED (the flag must not simply make fights longer) and
    bandToSwing (it must not wreck the approach to buy the dodge);
  * if it clears: mob_melee and mob_trio before shipping -- the draw test is on RangedAttackMob, so
    zombie courses should be inert by construction, and that is a PREDICTION worth checking rather
    than an assumption;
  * if it fails: the arrow-avoidance lever is closed too, and what remains is that this course's
    gate asks for a skeleton to miss every shot in a 12-block open field. That would be a statement
    about the COURSE, and it belongs in TODOS as one -- not as another bot hypothesis.

HONEST PRIOR, recorded before the run as in #3: the earlier measurement was negative, and the
mechanism argument for re-testing is that the stand was broken then, not that the idea has improved.
A null here is a real null and closes the line.

*** WHAT THIS COURSE ACTUALLY MEASURES (2026-08-13, derived then confirmed on 167 runs).

Every pass is an arrow MISSING. Not one is an arrow being outrun.

    167 runs, 33 passes (20%)
    arrows FIRED at the bot in PASSING runs:  mean 3.97, min 1, and ZERO of the 33 had none
    arrows FIRED at the bot in FAILING runs:  mean 4.86, min 1

All 33 passing runs were shot at and took nothing. The arithmetic says why the approach cannot
help: the skeleton spawns at x=12.5 against a bot at ~0.5, its draw is 20 ticks, and an arrow
covers twelve blocks in ~4.5 -- so the first shot lands about 24-25 ticks after the AI wakes. The
best traced approach crossed 11.57 -> 3.60 blocks in 24 ticks at 0.33 blocks/tick, which is about
what sprint allows. It arrived exactly as the arrow did. One 4-damage arrow puts min_hp at 16 and
the gate wants 19, so PASSING REQUIRES THE FIRST ARROW TO MISS -- and the pass rate is simply the
rate at which the skeleton misses.

THIS RETIRES THE ENTIRE APPROACH LINE, and explains all five nulls in one sentence: they were
tuning a variable that does not determine the outcome. combatCloseToReach, combatApproachNoOrbit,
combatEngageBand, combatCloseOwnsBand and combatDodgeHoldByRange each shaved ticks off a 32-tick
approach that would have to beat 24, and none of them could have closed that gap even if they had
worked perfectly. The one that DID work perfectly (combatCloseOwnsBand, mechanism gate passed)
still measured nothing, which is exactly what this model predicts.

It also explains the bimodality that produced the false 2.65-sigma finding: outcomes are "the
first arrow missed" or "it did not", and a hit costs 2.78 blocks of knockback (measured over 7
hurt events) which buys the skeleton another shot -- so a miss tends to stay a miss and a hit
tends to compound.

WHAT IS LEFT, and it is the only thing: make the arrows miss. A mob aims at RELEASE, so lateral
velocity AFTER release is what defeats it. At twelve blocks the flight is ~4.5 ticks, in which a
sprinting bot covers ~1.2 blocks against a 0.6-block hitbox -- comfortably enough. At five blocks
the flight is under two ticks and nothing can be done, which is why the in-flight dodge measured
nothing and why shortening its hold measured nothing either.



OUTCOME OF THE PRE-REGISTERED SERIES BELOW (2026-08-13). THE FLAG IS FINISHED.

40 launches, interleaved, 0 invalid. Scored twice -- by this tool on the summary, and by splitting
the console log on its per-run `PIN combatEngageBand=` lines -- and the two agree exactly:

    arm A (off)  n=20  mean 1.32  sd 0.79
    arm B (on)   n=20  mean 1.62  sd 0.93
    difference  -0.30 arrows   SE 0.27   1.10 sigma

Under the 2-sigma bar, and in the WORSE direction. The rule below said a third sub-threshold
result finishes the idea, so it is finished and the flag stays off.

* THE PART WORTH KEEPING. The two earlier series read +0.83 and +0.88, both favouring the flag.
This one -- the largest, at n=20 an arm against their 3-6 -- reversed the sign. Three careful
series, and the direction was not stable until the n was. That is the fourth time on this course
that a promising sub-threshold reading turned out to be noise, and the first time the reversal was
large enough to be unmissable.

WHAT THE COUNTERS SAID, which the arrows could not: the flag's mechanism fired exactly as designed
(controller ticks 55 -> 175) and made the bot stand FURTHER OUT, reachMean 3.55 -> 4.56, with
corr(controller ticks, reachMean) = +0.91. The cause is an arbitration line, not this flag --
see TungstenConfig#combatCloseOwnsBand.

OUTCOME OF #4 (2026-08-13). THE POST-HOC FINDING DID NOT REPLICATE, AND MY OWN GATE WAS CONFOUNDED.

48 launches, 0 invalid, 23/24 an arm.

PRE-REGISTERED STATISTIC (zero-arrow rate):  A 5/23 = 0.22   B 3/24 = 0.12
                                             -0.09, SE 0.11, 0.84 sigma -- DOES NOT REPLICATE.
Pooled with #3 it is 6/43 vs 11/44, +0.11 at 1.30 sigma. Series #3's 2.65 sigma was noise, which
is what the caution written beside it said it probably was. The flag stays off.

!! AND THE MECHANISM GATE I WROTE WAS ITSELF A CONFOUNDED TOTAL -- the fourth of these in one day.
The gate said "mean dodgeDrive must FALL in the pinned arm". It ROSE: 35.3 -> 108.2, which by the
letter voids the series. But dodgeDrive is a per-run TOTAL, and a run where the bot never engages
accumulates it for two thousand ticks. De-confounded:

    median dodgeDrive        A 21     B 15
    dodgeDrive per arrow     A 8.79   B 6.65

Both lower in the pinned arm. The flag fired exactly as designed; five catastrophic runs in arm B
(dodgeDrive 334-503) dragged the mean over the gate. Band ticks, strafeFar, and now this: three
totals read as rates, and this one was in a gate I had pre-registered specifically to keep myself
honest. THE RULE THAT FOLLOWS: a gate metric must be a RATE or a MEDIAN unless the denominator is
fixed by construction.

The verdict does not depend on resolving the void. The secondary mean reads A 1.14 vs B 2.07 --
2.78 sigma WORSE -- and the arm B tail is 4.25, 4.5, 4.75, 4.75. Every reading of this series
points the same way, so the flag stays off under all of them; there is no rescue here that would
turn it on, which is the only kind of re-reading worth distrusting.

** WHAT THE DIAGNOSIS FOUND, WHICH IS WORTH MORE THAN THE FLAG: A CATASTROPHIC STALL, 9% OF RUNS.
Across all 167 runs of the four series, 15 have the skeleton firing 8-42 arrows while the bot sits
beyond 4.5 blocks for 333-2094 ticks -- up to 104 seconds of a 120-second course. In most of them
mdTung is 13-69, so the combat controller is barely ticking: the bot is parked just OUTSIDE the
inRange test that would let it fight, and it stays there. lastGap clusters at 4.5-5.0, with
outliers at 7.9 and 20.6.

That is a stall state, not a tuning question, and it is the same 4.5-block line three refuted flags
were built around -- approached from the failure side this time. Sizing it honestly before anyone
spends a series on it: at 9% of runs it caps the pass rate at 91%, while the current rate is ~20%,
so it is NOT what keeps the course red. The binding constraint is still that an ordinary run takes
about one arrow and the gate allows none. Fix it as a defect, not as a lever on this gate.

OUTCOME OF #3, AND PRE-REGISTRATION #4 (2026-08-13). THE MEAN SAID NOTHING; THE SHAPE DID NOT.

40 launches, 0 invalid. The mechanism gate passed -- dodgeDrive 31.1 -> 18.9 -- so the flag fired.

    arrows   A 1.19 (sd 0.53)   B 1.12 (sd 1.23)   +0.06, SE 0.30, 0.21 sigma

Nothing, on the pre-registered statistic. THE FLAG STAYS OFF, as declared. The secondary checks
say why the arithmetic did not pay: the returned ticks did not go into closing. toSwing 38.1 ->
40.1, inReachRate 0.36 -> 0.35, reachMean 3.53 -> 3.58 -- all flat. Whatever the dodge was
costing, the approach did not pick it up.

* BUT THE DISTRIBUTIONS ARE NOT THE SAME DISTRIBUTION, and the course is gated on their left tail:

    arm A (off)  0.0 x1,  0.75 x3,  1.0 x10, 1.75 x2, 2.0 x4
    arm B (on)   0.0 x8,  0.75 x1,  1.0 x4,  1.5 x1, 1.75 x1, 2.0 x2, 3.0 x1, 3.25 x1, 4.25 x1
    zero-arrow rate  A 0.05   B 0.40    +0.35, SE 0.13, 2.65 sigma
    passes           A 1/20   B 8/20

Same mean, opposite shape: half of arm A lands on exactly one arrow, while arm B either takes
nothing or takes a beating. 8/20 would be the best pass rate this course has produced.

!! THIS IS POST-HOC AND IS NOT A RESULT. The statistic was declared as the MEAN before the run,
the mean read 0.21 sigma, and finding a better statistic afterwards is the move that produced
"+0.83 and +0.88" and then reversed at n=20. Two further reasons for caution, both against the
finding: arm A's sd of 0.53 is the tightest of any arm measured today (0.79, 0.76, 0.53), so the
ANOMALOUS arm may be the baseline rather than the treatment; and a variance difference is exactly
what small samples manufacture.

So it is tested prospectively instead. PRE-REGISTRATION #4, written before its data exists:

  * 48 launches, interleaved, --pin-alt combatDodgeHoldByRange=true, ~24 an arm;
  * STATISTIC: THE ZERO-ARROW RATE (equivalently the pass rate), declared in advance this time.
    It is the quantity the gate is made of, and checklist rule 4i says the gate's statistic is
    rarely the measurement's -- here they came apart by 2.4 sigma in one series;
  * bar: 2 sigma on the pooled SE of the difference in proportions;
  * mechanism gate, unchanged: dodgeDrive must be lower in the pinned arm or the series is VOID;
  * the MEAN is recorded too. If the zero-rate clears and the mean does not, that is what shipping
    would buy: more zeros AND more disasters, better against a gate that counts only zeros, worse
    for a bot that has to survive. That trade gets stated in the release notes rather than hidden;
  * if it fails to replicate, the flag stays off and the dodge overhang is closed for good.

PRE-REGISTRATION #3, WRITTEN BEFORE ITS DATA EXISTS (2026-08-13, combatDodgeHoldByRange).

40 launches, interleaved, --pin-alt combatDodgeHoldByRange=true. Declared now:

  * statistic: MEAN ARROWS LANDED. Bar: 2 sigma. Not pooled with anything else here;
  * MECHANISM GATE, checked before the arrows are read: dodgeDrive must be LOWER in the pinned arm.
    The flag shortens a hold, so if dodgeDrive does not fall it did not fire and the series is VOID
    rather than negative. Same gate that made #2 readable;
  * secondary, recorded not gated: bandToSwing and inReachRate. The whole claim is that returned
    ticks go into closing, so if arrows move while those two do not, the explanation is wrong even
    if the number is good;
  * if it clears the bar: mob_melee and mob_trio re-run before it ships. The dodge is not
    skeleton-specific and a shorter hold changes every course where something shoots;
  * if it does not: the flag stays off and the dodge overhang is closed as a lever. What would
    remain is the ~2 ticks that ARE flight time, and those are not recoverable by tuning.

WHY THIS IS A DEFECT AND NOT ANOTHER POLICY GUESS. The three flags closed above were all
hypotheses about who SHOULD own the approach. This is a unit mismatch with the arithmetic on
record: DODGE_HOLD_TICKS = 6 carries the comment "an arrow crosses twelve blocks in about eight
ticks", and this course's shots are released at a mean of 5.4-5.7 blocks -- about two ticks of
flight at 2.65 blocks a tick. Four of the six ticks sidestep an arrow that has already arrived,
while the primitive overrides the approach at the final-word position for every one of them. The
change is clamped to [2, 6], so it can only return ticks and never extend a dodge.

!! AND THE HONEST PRIOR: the effect is small. About 4 wasted ticks per arrow at ~4.5 arrows a run
is ~18 ticks against a 109-tick band -- perhaps 0.3 arrows, right at the edge of what n=20 an arm
resolves. A null result here is genuinely uninformative about the mechanism, and that is recorded
NOW so a null is not later written up as a refutation of the arithmetic.

OUTCOME OF PRE-REGISTRATION #2 (2026-08-13). REFUTED, AND THE LINE IS CLOSED AS PROMISED.

40 interleaved launches, 0 invalid. The mechanism gate passed cleanly -- cqTookFromPursue 0 in all
20 arm-A runs and 8-213 in all 20 arm-B runs -- so this is a real negative and not a void series.

    arrows       A 0.88   B 1.23    -0.35, SE 0.32, 1.11 sigma  (under the bar, WORSE)
    passes       A 6/20   B 6/20
    ctl          A 52     B 166     combat drove 3x more
    reachMean    A 3.53   B 4.71    ...and stood a FULL BLOCK further out
    inReachRate  A 0.375  B 0.143   share of control ticks inside 3.0, more than halved
    bandToSwing  A 53     B 84      longer before the first swing landed

The pre-registration said that if this failed, the whole "the controller should own the approach"
line closes and both flags stay off. It failed. They stay off.

* THE RESULT IS BIGGER THAN THE VERDICT, and it is the opposite of the hypothesis: closeQuarters()
is a WORSE closer than the BFS pursue walk it was written to displace. Every closing metric moved
the wrong way when it took the legs. The premise -- that a path-follower cannot close because it
chases a vacated square -- was simply wrong on this course.

What survives as a target: corr(inReachRate, arrows) = -0.40 over the 40 runs and -0.52 within arm
B. The share of control ticks spent inside reach predicts the result, and the pathfinder is what
maximises it.

!! ONE NUMBER FROM THIS SERIES IS NOT EVIDENCE AND MUST NOT BE QUOTED. corr(strafeFar, reachMean)
= +0.93 looked like the circle-strafe diluting the approach -- a clean mechanism, nearly a fourth
hypothesis. It is an IDENTITY: strafeFarTicks counts strafe ticks taken beyond reach, so per
control tick it is one minus the in-reach rate, and corr(strafeRate, inReachRate) came out exactly
-1.00. A correlation of exactly +/-1.00 between two derived quantities is the signature of an
identity, not of a discovery. That counter measures distance, not strafing, and cannot test the
orbit at all.

PRE-REGISTRATION #2, WRITTEN BEFORE ITS DATA EXISTS (2026-08-13, the PAIR).

Next series: 40 launches, interleaved, both combatEngageBand and combatCloseOwnsBand pinned on in
arm B, both off in arm A. Declared now:

  * statistic: MEAN ARROWS LANDED. Bar: 2 sigma. Not pooled with anything above;
  * A GATE BEFORE THE ARROWS ARE READ AT ALL: cqTookFromPursue must be > 0 in arm B and 0 in arm A.
    That counter is the mechanism. If it reads 0 in arm B the flag did nothing, the arrows are
    about something else, and the series is void rather than negative -- exactly the error that
    let combatCloseToReach be argued about for three passes;
  * secondary, recorded but not gated: reachMean, and band ticks to the first swing. If arrows move
    and reachMean does not, the explanation is wrong even if the number is good;
  * if it clears 2 sigma: mob_melee and mob_trio are re-run BEFORE it ships, because this changes
    who drives the legs on every mob course;
  * if it does not: the whole "the controller should own the approach" line is closed, both flags
    stay off, and the next pass goes at the residual instead -- reachMean sits at 3.4-3.7 against a
    3.0 reach even when closeQuarters already owns 80-90% of ticks, and that is a different defect.

THE OBJECTION, STATED RATHER THAN AVOIDED. combatEngageBand was just refuted, and here it is
switched on again inside the very next experiment. That looks like measuring until it passes, so
the distinction has to be load-bearing rather than rhetorical: the refuted claim was "ticking the
controller earlier is worth arrows", and it is dead -- the sign reversed at n=20 an arm. The new
claim is about a DIFFERENT line of code, found afterwards and from counters rather than from a
hunch: past REACH+1.0 the legs go to the pursue walk, so the earlier ticks were never able to
drive. combatEngageBand appears in arm B as a PRECONDITION of the thing under test, not as the
thing under test. If the pair fails, both are finished together and the line is closed for good --
that is the commitment that makes this a test and not a retry.

PRE-REGISTRATION, WRITTEN BEFORE THE DATA EXISTS (2026-08-13, third engage-band series).

Running 40 launches, interleaved, --pin-alt combatEngageBand=true, expecting ~20 valid an arm.
Declared NOW so the result cannot be reinterpreted afterwards:

  * statistic: MEAN ARROWS LANDED, as always;
  * the bar is 2 sigma, unchanged. Two earlier series read +0.83 and +0.88 (1.90 sigma) and the
    flag stayed OFF both times. If this one lands under 2 sigma again, it stays off and the
    engage-band idea is finished -- three sub-threshold results are not evidence, they are a
    quantity too small to matter;
  * arms are NOT pooled with the earlier series. This stands or falls alone;
  * if it clears the bar, the flag goes ON and mob_melee and mob_trio are re-run before anything
    else, because widening the engage test changes who drives the legs on every mob course.

WHY THIS SERIES IS JUSTIFIED AND NOT MEASURING-UNTIL-IT-PASSES. The mechanism was confirmed
independently of the flag: pooled over 33 runs, arrows correlate with TOTAL band time (+0.43) and
not with reach refusals (+0.17), and the counters show the combat controller ticking for only 44 of
135 band ticks. At a 12-tick cooldown those 44 ticks permit about three swings; the missing 91 would
permit eleven. The engage test is what gates them. That is a mechanism argument that did not exist
when the first two series were run, and it is what changed -- not the appetite for a positive.
"""

"""Score a two-arm mob_skeleton A/B on ARROWS LANDED, with the rule fixed in advance.

Usage:  python ab_arrows.py <summary.json> <pinName>              interleaved, one file
        python ab_arrows.py <arm-A.json> <arm-B.json>             two separate series

WHY THIS EXISTS. This course's pass count cannot separate arms at any affordable n — the ruler is
min_hp, and arrows = (20 - min_hp)/4, a small integer per run. Pooled n=14 on the repaired course
gives mean 1.46, sd 1.20, so at 2 sigma the arm size is 8*sd^2/delta^2:

    delta 0.3 -> 128 per arm      delta 0.7 -> 24
    delta 0.5 ->  46 per arm      delta 1.0 -> 12

At the six-run arms used before, nothing below ~1.4 arrows was ever visible, which is why a string
of careful hypotheses each "measured nothing". The gate wants ZERO arrows against a mean of 1.46,
so the effect that matters is ~1.5 — visible at n=12.

THE DECISION RULE, written before the numbers exist so it cannot be fitted to them:
  * the statistic is MEAN ARROWS LANDED, not passes;
  * INVALID runs are dropped (they measured the machine, not the bot);
  * a difference counts only at >= 2 sigma on the pooled SE of the difference;
  * anything else is reported as "no effect at this resolution" and the flag stays off.
"""
import io
import json
import os
import sys

# Every reason a completed run is not evidence. `invalid` is the harness's own verdict; the other
# three are runs it let through as verdicts while printing "NOT comparable against a healthy
# baseline" next to them. Counting those was a hole in this scorer: a starved run PASSES the
# validity check, enters an arm, and drags a mean built to resolve 1.5 arrows. They are dropped
# here and the count is PRINTED -- a scorer that quietly discards half an arm reads exactly like
# one that had a small effect to report.
DROP_REASONS = ("invalid", "starved", "drift_from", "jar_changed")


def _truthy(v):
    return str(v).strip().lower() in ("true", "1", "yes", "on")


def arrows(rows):
    """Arrows landed per run, plus a tally of what was thrown away and why."""
    out, dropped = [], {}
    for r in rows:
        why = next((k for k in DROP_REASONS if r.get(k)), None)
        if why:
            dropped[why] = dropped.get(why, 0) + 1
            continue
        for c in r.get("criteria", []):
            if c["name"] == "at most one arrow landed":
                hp = float(c["detail"].split("min_hp=")[1].split()[0])
                out.append((20.0 - hp) / 4.0)
    return out, dropped


def load(path):
    rows = json.load(io.open(path, encoding="utf-8"))
    return rows if isinstance(rows, list) else [rows]


def split_on_pin(rows, pin):
    """Separate an INTERLEAVED series into arms, preferring what was APPLIED over what was meant.

    `pins` records the flags the run actually carried; `arm` records the letter the loop intended.
    They agree except where a run was retried on fresh clients -- and before the pins were recorded
    the label was stamped on the attempt that got thrown away, so on exactly those runs the letter
    is the less trustworthy of the two. Prefer `pins`, fall back to `arm` for summaries written
    before the recording existed, and say out loud which one was used: the fallback is scoring a
    series by the loop's intention.
    """
    if all(pin in r.get("pins", {}) for r in rows):
        key, basis = (lambda r: _truthy(r["pins"][pin])), "pins"
    elif all(r.get("arm") in ("A", "B") for r in rows):
        # !! AND THE NAME IS NOT CONFIRMED ON THIS PATH, so the report must not print it as though
        # it were. A summary from before pin recording says which arm a run was in and NOTHING
        # about which flag the series varied -- feed it the wrong name and the arms come out
        # confidently labelled with a flag that series never touched. Caught by doing exactly that
        # to a real 26-run file while testing this. The letters are all this data supports.
        print("[!] no `pins` recorded -- splitting on the arm LETTER. That is what the loop "
              "intended rather than what each run carried (retried runs may be mislabelled), and "
              f"it does NOT confirm the series varied {pin!r}. Check the console log.")
        key, basis = (lambda r: r["arm"] == "B"), "arm"
    else:
        raise SystemExit(
            f"cannot split this summary: rows carry neither a `pins` entry for {pin!r} nor an "
            f"arm letter. Was it run with --pin-alt?")
    return [r for r in rows if not key(r)], [r for r in rows if key(r)], basis


def stats(xs):
    n = len(xs)
    if n == 0:
        return 0, 0.0, 0.0
    m = sum(xs) / n
    var = sum((x - m) ** 2 for x in xs) / n if n > 1 else 0.0
    return n, m, var ** 0.5


def _report(label, xs, dropped):
    n, m, s = stats(xs)
    lost = "  ".join(f"-{v} {k}" for k, v in sorted(dropped.items())) or "none dropped"
    print(f"{label}: n={n}  mean arrows={m:.2f}  sd={s:.2f}  ({lost})  {xs}")
    return n, m, s


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    first, second = sys.argv[1], sys.argv[2]
    if os.path.exists(second):
        rows_a, rows_b = load(first), load(second)
        label_a, label_b = "arm A (flag off)", "arm B (pinned)  "
    else:
        rows_a, rows_b, basis = split_on_pin(load(first), second)
        if basis == "pins":
            label_a, label_b = f"arm A ({second}=false)", f"arm B ({second}=true) "
        else:
            label_a, label_b = "arm A (baseline, unverified)", "arm B (alternate, unverified)"
    a, drop_a = arrows(rows_a)
    b, drop_b = arrows(rows_b)
    na, ma, sa = _report(label_a, a, drop_a)
    nb, mb, sb = _report(label_b, b, drop_b)
    if na < 2 or nb < 2:
        print("REFUSING TO JUDGE: an arm has fewer than two valid runs.")
        return 1
    se = (sa ** 2 / na + sb ** 2 / nb) ** 0.5
    if se == 0:
        print("REFUSING TO JUDGE: zero spread in both arms — check the instrument, not the bot.")
        return 1
    d = ma - mb
    print(f"difference = {d:+.2f} arrows   SE = {se:.2f}   {abs(d) / se:.2f} sigma")
    if abs(d) / se >= 2.0:
        print("VERDICT: real at 2 sigma." if d > 0 else "VERDICT: real at 2 sigma — and WORSE.")
    else:
        print("VERDICT: no effect at this resolution. The flag stays off.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

PRE-REGISTRATION #10 -- navStopOnTaskEnd (2026-08-14). Written BEFORE the run.

THE FINDING THIS COMES FROM, because it inverts everything filed about mine_stone above.
The bot does not fail to mine. Polled once a second, with the client log beside it:

    29.0s  cobble=8  "No tasks. Time to add new!"        the eight are IN THE PACK
    05:15:04  [Alto Clef] task FINISHED in 29.5 s
    05:15:06  [Tungsten] MovementQueue: 8 movement(s) 0,-63,0 -> 0,-55,0
    33.7s  y=-57.25 cobble=2
    36.0s  y=-55.00 cobble=0     and it stands there for the remaining 84 seconds

A search still in flight when the task ended landed two seconds later, and its route was eight
MovementPillar steps. The bot spent the whole haul building a tower out of its own pit. The world
read back afterwards shows cobblestone at every y from -63 to -56 in the bot's column and nothing
placed anywhere else. So every earlier entry here that treats mine_stone as a MINING problem was
measuring the wrong half of the run.

THE CHANGE. Nav.cancelAll() -- stop every navigation engine, called where the goal ceases to
exist: UserTaskChain.onTaskFinish next to the runner disable, and AltoClef.stopTasks(). Gated by
TungstenConfig.navStopOnTaskEnd, default TRUE, so the control arm is the one that pins it false.

PREDICTION, and it is a strong one for once, because the mechanism is not statistical: with the
flag on the bot keeps what it gathered. The gate is 8 and it reaches 8 by ~30 s, so the arm should
PASS rather than merely score higher. If it scores 4-5 and fails, the pillar was not the only
thing spending the haul and this entry is wrong.

MECHANISM GATE: navStop=ran/live. `live` counts teardowns that found a route still driving --
the defect itself. Flag off must read 0/0. If the on-arm shows live=0 the flag did not reach the
behaviour and the series is VOID, the same rule that voided the stranded series.

REGRESSION WATCH, because this fires at the END of every task on every course: nav_flat,
nav_bridge, nav_wall2. Those END on arrival, so if stopping navigation at task end ever cuts a
live leg short they are where it shows.

ARMS: interleaved (rule 4r), --repeat 6 an arm, one invocation each, no rebuild between them.

AMENDMENT to #10, written while run 1 of the series was on screen, because the rule I wrote two
paragraphs up would have thrown the series away for the wrong reason.

Run 1 (flag ON) read navStop=3/0: the teardown RAN three times and found a live route none of
them. By the letter of the pre-registration that is "the flag did not reach the behaviour, VOID".
It is not. Two things separate a broken FLAG from a broken GATE, and both say the flag is fine:

  - the runner PINS and READS BACK, and printed `PIN navStopOnTaskEnd=true`;
  - navStop`s first half is non-zero, so Nav.cancelAll executed. That is the "prove it ran" test.

The `live` half is what is wrong, and it is my own instrument being too narrow. isPathing() asks
whether a route is being FOLLOWED; the defect begins one step earlier, with a SEARCH still running
whose result lands after the task is gone -- the traced case exactly, where the route arrived two
seconds after "task FINISHED". So it reads false at the moment of teardown even when the bug is
present. Widened to ask the engines directly (PATHFINDER.active, the executor, MovementQueue).

THE SERIES CONTINUES on the outcome metric, which is unaffected. The distinction worth keeping: a
mechanism gate that CANNOT fire does not void a series, it voids ITSELF. What voids a series is a
gate that could have fired and did not.

ALSO, and it changes what this course is: run 1 failed a DIFFERENT way from the traced run. It
froze at [-0.14,-60,1.96] -- on the floor, not on a pillar -- with pfActive=true and path=-1 for
the last ~60 s, and scored 0. The traced run reached 8 by 29 s and pillared them away. Two failure
modes, which is what a mean of 4.32 with sd 3.6 was telling me all along and I read as noise.

PRE-REGISTRATION #11 -- mineStayOnSurface, REWRITTEN (2026-08-14). Result of pair 1 below it,
and the swapped replication was launched before the result was interpreted.

WHY THE OLD VERSION MEASURED NOTHING. It tested `check.getY() < feetY - 1`. That is relative to
where the bot STANDS, and the floor is always feetY-1, so the block under its own feet always
passed. Break it, fall one, the test re-anchors a level lower and passes the next one too. It
ratcheted down with the bot. The 0.85 sigma I recorded as "the idea does not help" was never a
test of the idea.

The rule now asks whether the bot is IN A HOLE -- solid ground on all four cardinals at its own
feet level -- rather than how far down it has got. Stateless, releases as soon as it is not
enclosed, cannot ratchet.

PAIR 1, interleaved (rule 4r), one invocation, fps 28.5-29.5 on every run of both arms:

    OFF (control):  5, 0, 0, 6, 0     mean 2.20  median 0  pass 0/5
    ON  (fix)    :  8, 8, 3, 3, 7     mean 5.80  median 7  pass 2/5
                                      delta +3.60 cobblestone, 2.02 sigma

The control arm ran FIRST in every pair, which is exactly the hole rule 4q was written about, so
this is a hypothesis and not yet a result. PAIR 2 swaps the positions: default flipped to true and
--pin-alt pins FALSE on alternate runs, so the ARM runs first. If the winner follows the flag it
survives; if it follows the position it was the stand.

WHAT IT DOES NOT CLAIM. The course is still RED. 2/5 is not green, and two runs in the arm scored
3. The shaft is one cause and the evidence says it is the big one; it is not the only one.

REGRESSION STILL OWED: mine_diamond, the course that must still dig DOWN. The fallback is written
(the restricted search runs first, an empty result retries unrestricted) but written is not
measured, and shipping a default flip without that is the kind of debt rule 4 forbids.

RESULT OF #11 -- PAIR 2 REFUTED PAIR 1. The flag goes back OFF (2026-08-14).

    pair 1, CONTROL first    off 2.20 (0/5 pass)   on 5.80 (2/5)   +3.60   2.02 sigma
    pair 2, ARM first        off 5.00 (3/5 pass)   on 5.20 (3/5)   +0.20   nothing
    pooled, n=10 an arm      off 3.60              on 5.50         +1.90   1.16 sigma

Both interleaved, one invocation each, 25-29 fps on every run of both arms. The winner follows the
POSITION, not the flag: whichever arm ran second scored better. That is precisely rule 4q, and it
caught a default flip I had ALREADY MADE on the strength of pair 1 -- reverted.

Worth stating plainly because I nearly did the opposite: pair 1 was not a lie and pair 2 is not the
truth. Two honest measurements disagree, so the answer is that the instrument cannot resolve the
question, and saying so IS the result (rule 4r #3).

WHAT IS NOT IN DOUBT. The shaft. Three traces show the bot mining the block under its own feet,
falling in, repeating, and ending at the bottom of a 1x1 hole where the only expandable move is up
-- so it towers out on the cobblestone it came for. The rewritten rule provably stops that. What is
unproven is that stopping it moves the SCORE, and the arm still failed half its runs (1, 0, 3, 3),
which says something else dominates.

SO THE RULER CHANGES, not the target. mine_stone now records `shaft=N deep, tower=N high` from the
world after every run: a YES/NO fact with no spread, against a gate whose sd is 3.6 on a mean of 4.
Two series have now died on that sd. The next pass reads the shaft counter, not the mean.

THE NO-SPREAD RULER ANSWERED, AND IT KILLED MY OWN FRAMING (2026-08-14).

First reading of `tower=N high` over six interleaved runs:

    flag=false   cobble 5, 0, 7     tower 1, 0, 0     towered 1/3
    flag=true    cobble 0, 2, 0     tower 6, 0, 0     towered 1/3

FOUR OF SIX RUNS BUILT NO TOWER AT ALL AND STILL SCORED 0-2. So the pillar is not the dominant
failure mode -- it is the mode of the runs I happened to trace, three times, which is how it came
to look like THE cause. Same lesson as rule 5 in a new costume: three traces are three samples.

AND THE PROBE ITSELF WAS WRONG FIRST TIME. It asked whether the spawn column was AIR and read
shaft=0 on the very run whose tower was six blocks high -- because the bot BACKFILLS the shaft on
its way out, so the hole is full of the cobblestone that came out of it. "Is it air" cannot tell
"never dug" from "dug and filled in". It now asks whether the STONE IS STILL THERE.

WHERE THE NEXT PASS GOES, and the repo already wrote this down once. scenarios_craft carries a
comment from the day mine_stone was built: the stand carves everything under the floor to air, so
a cobblestone dropped into a hole over the void hangs with onGround=false, the tracker refuses it
for ever and the pickup never starts. The arena was given three extra layers to fix that. The bot
digs THROUGH all four and mines the bottom one -- `Destroy block at 0,-64,-1` is in every trace --
and at that point the drops are over the void again. dropAsked=273 dropSeen=0 in the run that sat
ninety seconds on "Approach entity item / Tungsten pathfinding" and finished with nothing.

That is a mechanism, it is measurable per run (dropSeen against dropAsked), and it explains the
majority mode the pillar cannot. Read it before proposing anything else.

PRE-REGISTRATION #12 -- breakBanEscalates (2026-08-14). Written BEFORE the run.

THE MODE I HAD NEVER MEASURED, caught by adding an item dump to the tracer:

    cb=0/260992/0/0        cbAvoid -- a quarter of a million candidates refused
    breakFail=1/0/0        exactly ONE break-fail believed to be a claim
    bot frozen at (-4.26,-60,5.26) for the last 50 s, cobblestone=0

One failed break installs a ban on a 101x101x101 cube centred a block from the bot, which on a
13x13 arena is the whole world. There are no land claims on this stand, so every claim it has ever
made here is a false positive. This is the SAME signature the repo already recorded for
chop_canopy (cb=0/18456/0/0) and the rule ONE entry written from it.

WHY THE OBVIOUS FIX FAILED BEFORE. The radius was cut 50 -> 3 and reverted, and the note at the
constant says why it could not work: at ANY radius the ban is centred one block from the bot and
still covers everything inside its 4.5-block reach. Radius is the wrong dial.

THE CHANGE. How much ONE observation is allowed to imply. A refused break bans that BLOCK; three
distinct refusals inside the window ban the region as before. On genuinely protected land every
attempt is refused, so the wide ban still arrives within seconds -- only the cost of being wrong
once changes.

MECHANISM GATE: cbAvoid. It reads 260992 on a run this fires in and must collapse to near zero.
That is an effect the gate metric cannot come close to, which is the entire point after two series
died on the gate's sd of 3.6. breakFail gains a 4th field, breakBanWide, so the escalation path
proves it can still fire rather than being assumed dead.

PREDICTION: cbAvoid falls by orders of magnitude on the pinned arm. Whether the SCORE moves is a
separate question and I am not predicting it -- the previous two series say this course has more
than one failure mode, and removing one of three need not show up in the mean.

ARMS: interleaved, --repeat 8, one invocation.

RESULT OF #12 -- AND IT IS A NEGATIVE CONTROL, WHICH IS WORTH MORE THAN THE FIX (2026-08-14).

    control (off):  8, 0, 9, 8    mean 6.25   pass 3/4   towered 1/4
    arm     (on):   0, 0, 0       mean 0.00   pass 0/3   towered 3/3

AND THE FLAG NEVER EXECUTED. breakFail reads claimed=0 in every run of both arms, so
addTemporaryBreakAvoidance -- the only method the flag touches -- was never called once. The arm
and the control were running IDENTICAL code.

So an inert change "moved" this course by 6.25 cobblestone, 3/4 pass against 0/3. That is not a
result about the flag; it is the definitive measurement of what this course's score can and cannot
support, and it was obtained by accident from a series designed to test something else.

WHAT IT SETTLES, RETROSPECTIVELY:

  - #11 pair 1 (+3.60, 2.02 sigma) is fully explained without any effect at all. Retracting it on
    the order-swap was right, and this says it was not merely unproven but comfortably inside what
    an inert flag produces here.
  - Every A/B ever run on mine_stone at n<=5 an arm, including the four I ran today, is incapable
    of the claim it was built to make. Two series disagreeing was never a puzzle.
  - Rule 4i, in one line: the gate's statistic is not the measurement's statistic. Here the gate is
    BIMODAL -- a run either finishes in ~30 s with 8-9 and no tower, or towers and scores 0 -- so
    the mean is a coin-flip weighting of two outcomes and the sd of 3.6 was never noise around a
    central value at all.

THE MODE IS THE MEASUREMENT, NOT THE MEAN. Every zero-scoring run in this series has tower 5-6 and
every passing run has tower 0. dug=3/4 in ALL of them, passing and failing alike -- so digging into
the column is not what separates them. What separates them is whether the bot leaves the hole by
WALKING or by BUILDING. That is the question the next pass answers, and it is answerable per run
rather than per series.

#12 itself is unjudged: correct by inspection, never exercised. It needs a run in which a claim
actually fires (one diag run in four), so it stays off with its mechanism gate now printed.

#11 FINAL -- REFUTED ON THE DETERMINISTIC RULER AS WELL (2026-08-14).

    off   n=7   mean 5.43   pass 3/7   TOWERED 1/7
    on    n=7   mean 4.71   pass 3/7   TOWERED 2/7

Judged on the tower -- a fact read out of the world, not the count that a provably inert flag
already "moved" by 6.25 -- and it does not move. mineStayOnSurface stays off. The rewritten rule is
still better than the one that ratcheted, and it does keep the bot out of a 1x1 shaft; it just does
not decide this course.

AND THE SERIES KILLS THE FRAMING OUTRIGHT. Only 3 of 14 runs towered at all. Runs score zero with
tower=0, and one control run scored zero with dug=0 -- it never broke the spawn column at all.
Overall pass 6/14, which is the same 4.32-of-8 this course has always had. So across today:

    the zombie route      real, fixed, shipped     explains SOME zero runs
    the 1x1 shaft/tower   real                      3 of 14 runs
    the radius-50 ban     real, fix unexercised     1 of 4 diag runs
    ...and there are still zero-runs with none of the three.

Every one of those was, at the moment I found it, "the root cause". The register should read: this
course has a long tail of independent stalls, and the honest next step is to CLASSIFY every run
automatically -- towered / banned / never-dug / clean -- rather than propose a fourth root cause.

PRE-REGISTRATION #13 -- progressCheckIgnoresSearch (2026-08-14). Written BEFORE the run.

FOUND BY READING, NOT BY MEASURING, which is what the last five series should have been. Both
give-up paths in altoclef open with the same two lines:

    if (Nav.isPathing()) { progressChecker.reset(); }
    if (... && !progressChecker.check(mod)) { blacklist the target; try something else; }

isPathing() is TRUE while the pathfinder is merely LOOKING -- TungstenHelper.isActive() includes
PATHFINDER.active -- and a search that fails and restarts keeps it true for ever. So the reset
fires every tick and the branch beneath it CAN NEVER EXECUTE. The checker exists to notice "the
engine is busy and the body is not moving", and it was being reset for exactly that reason.

This is the invariant every failing trace shares, and I walked past it four times. The bot stands
on ONE SPOT for 50-90 s of a 120-second run, task reading "Approach entity item -- Tungsten
pathfinding (29s left)", countdown restarting as it expires. The drop is never blacklisted, the
wander never starts, mining never resumes, the run scores 0. Same two lines in MineOrCollectTask,
so the block path cannot give up either.

The machinery it disables is elaborate and CORRECT: three separate bugs were found and fixed inside
that blacklist branch on mine_diamond -- a ghost DISCARDED entity, a target never re-selected, a
limit of three exceeded 1920 times -- while this one line kept the whole block dead. Same silhouette
as the two most expensive defects here: a gate whose awake half could never fail, and a dodge whose
keys never reached the game.

MECHANISM GATE: navSearchOnly -- ticks where a search was running and no route was. It must be
LARGE, or the premise is wrong and the series says so before the outcome does.

PREDICTION: the pinned arm blacklists unreachable drops and resumes mining, so the stalls end and
the pass rate rises. I am NOT predicting the mean -- an inert flag already moved that by 6.25
(#12), so the mean is not evidence on this course at this n. What would falsify this cleanly is
navSearchOnly reading ~0.

RESULT OF #13 -- REFUTED BY ITS OWN MECHANISM GATE, BEFORE THE OUTCOME COULD TEMPT ME.

    false  n=6  mean 5.33  pass 3/6   searchOnly = 0,0,0,0,0,0
    true   n=5  mean 6.60  pass 4/5   searchOnly = 5,0,0,0,0

navSearchOnly is the ticks where a search was running and no route was. The premise needs it in the
HUNDREDS -- a 50-90 s stall at 20 tps -- and it reads 5 once and 0 otherwise. It CAN be non-zero,
which is the check that separates a real zero from a dead instrument, and it passes that.

So isPathing() is NOT perpetually true during the stall, and that reset line was never what kept
the give-up path dead. The reading of the source was correct about what the code DOES and wrong
about what happens at run time -- which is rule ZERO in this file, "prove by experiment that the
code runs", applied to a line I proved could not run and never checked whether its guard was true.

The outcome, 4/5 against 3/6, is exactly the kind of number #12 showed an INERT flag produces here
(3/4 against 0/3). It is not evidence and I am not banking it. Flag stays off.

ONE OBSERVATION WORTH KEEPING, stated as an observation and not a claim. The CONTROL arms of
today's three series -- all flags off, current HEAD -- pool to mean 5.59, 9 passes in 17, against
the shipped baseline of 4.32 (n=60) and its ~2-in-9. That is 1.28 sigma across sessions, so it
proves nothing by this file's own rules (4j: between-series drift is the confound). It is
consistent with navStopOnTaskEnd, the one behaviour change shipped ON today, and it needs a proper
same-session pinned pair before anyone repeats it as fact.

THE STANDING INSTRUCTION FOR THE NEXT PASS, now written for the second time and overridden once:
CLASSIFY THE RUNS FIRST. towered / banned / never-dug / clean. Every input is already in the
verdict. Five mechanisms have now been proposed for this course and four are refuted; the sixth
guess is worth less than knowing which of the four modes each failing run is in.

THE CLASSIFIER, RUN OVER 35 LOGGED RUNS -- no new runs needed, every field was already recorded.

    16  CLEAN PASS
    13  towered        <- 13 of the 19 FAILURES, and every one scores exactly 0
     5  partial
     1  banned

This reverses what I wrote from ab15 alone ("only 3 of 14 towered"). Over 35 runs the tower is the
dominant failure by a wide margin, and it is perfectly deterministic: tower>0 <=> cobble=0, and
every towered run has dug=3. Which is the lesson of rule 5 for the third time today -- ab15 was
fourteen samples and I read a rate off it.

PRE-REGISTRATION #14 -- fleePicksStandableSpot (2026-08-14). Written BEFORE the run.

WHAT BUILDS THE TOWER, found by reading AltoGoal end to end rather than guessing a sixth time:

    Vec3d away = new Vec3d(cx + dx/len*reach, maintainY, cz + dz/len*reach);

DestroyBlockTask flees the block it has just mined and passes THAT BLOCK'S Y as maintainY. So a bot
at the bottom of its pit is told "three blocks that way, at the depth I am digging" -- a point
inside solid stone with the void underneath it. Unreachable, so the search burns its budget and
restarts, which is the "Tungsten pathfinding (29s left)" cycle in every failing trace; and from a
1x1 shaft the only direction a best-effort route can expand is UP.

IT IS A PORT DEFECT, and the file says so about itself without noticing: upstream GoalRunAway is a
HEURISTIC over the whole search space -- any cell far enough away satisfies it -- so the search
picks a reachable one. AltoGoal.Flee's own note reads "fleeing is a DIRECTION, not a place... a
drive cannot [work with that] -- it steers at something". Collapsing the heuristic to one projected
coordinate is what destroyed the property that made it work.

THE CHANGE: walk outward along the away heading and take the first cell the bot could STAND in
(solid below, clear at feet and head), vertical spread before horizontal because climbing out of
the hole you are fleeing is usually one step. Falls back to the projected point when nothing is
standable, so no behaviour is lost.

MECHANISM GATE: fleeSpot=relocated/none. relocated must be NON-ZERO -- if the projected point was
already standable, the premise is wrong and the series says so before the outcome does. Note the
counter compares in XZ and against the scan base, never against away.y: that is NaN by this file's
convention when no height is named, and a comparison through NaN is false, which would have made
the gate silently unable to fire. That is the same defect as the gate I declared and never exposed.

NOT PREDICTING THE MEAN. #12 established that an inert flag moves it by 6.25 here. The prediction is
about the MODE: towered runs should fall from 13-in-19 toward zero on the pinned arm.

RESULT OF #15 -- gotoResumeNeedsRealTarget. mine_stone IS GREEN (2026-08-14).

    pair 1, control first    off 4.80 (2/5, towered 2/5)   on 9.00 (5/5, towered 0/5)
    pair 2, ARM first        off 6.60 (4/5, towered 1/5)   on 9.00 (5/5, towered 0/5)
    pooled n=10 an arm       off 5.70 sd 3.97 (6/10)       on 9.00 sd 0.00 (10/10)
                                                           +3.30, 2.63 sigma

Ten runs with the fix, ten passes, STANDARD DEVIATION ZERO -- every one scored exactly 9. The
winner follows the flag through the order swap, which is what killed the last headline. fps
27.8-29.8 on every run of both arms.

THE SIGMA IS THE LEAST INTERESTING NUMBER. The pre-registered mechanism gate was the tower in the
world afterwards, which has no spread: 3/10 -> 0/10. And the control arm stays BIMODAL (sd 3.97)
while the fixed arm is a constant. That is what removing a failure mode looks like, as opposed to a
mean wobbling -- which matters here more than anywhere, because #12 showed an INERT flag "moving"
this course by 6.25.

REGRESSIONS, all green:
    nav_flat nav_staircase nav_descend nav_break nav_wall2 nav_bridge   6/6 PASS
    mine_diamond chop_tree craft_stone_pickaxe                          3/3 PASS
nav_break is the important one -- it MINES and then continues, which is the exact path
resumeGotoAfterMining serves -- and mine_diamond is the course that must still dig DOWN.

HOW IT WAS FOUND, because the method is the transferable part. Six mechanisms were proposed for
this tower and five refuted, each by its own pre-registered gate. What ended it was three lines of
INSTRUMENT rather than a seventh guess: print the goal beside the route, then the goal the route was
ARMED for, then the goal's own inputs. The flee goal being served at the same instant reads
away=0.5,-60.0,-4.5 -- entirely sensible -- and I blamed it twice before the route was made to say
what it was actually aimed at: (0.5, 10.0, 0.5), a debug constant.

mine_diamond AGAINST THE SHIPPED FLAG -- UNRESOLVED AT THIS n, AND SAID SO (2026-08-14).

    on   diamonds 1, 1   0/2
    off  diamonds 2, 1   1/2      arm first, fps 29.2-29.5 throughout

Two runs an arm. 1/2 against 0/2 is a coin flip and cannot support "no regression" OR "regression";
the same build passed this course in isolation earlier the same day with the flag ON. So the honest
entry is that it is not resolved, not that it is clean -- which matters because I have shipped the
flag as a default.

What IS known about this course is older than today and unrelated to the flag: PickupDroppedItemTask
carries a measured note that the bot mines all three ores and collects none of them, closing to
1.35 / 2.45 / 3.57 blocks and never touching them. diamonds=1 is that defect, not a new one.

WATCH ITEM, not a blocker: if mine_diamond is still failing after the playthrough pass, A/B it at
n>=8 an arm before touching anything, because at 300 s a run that is 20 minutes of bench per arm.

PRE-REGISTRATION #16 -- barrenLockCountsAsFailure, WITH ITS PREMISE ALREADY CONFIRMED (2026-08-14).

THE PREMISE, tested first and cheaply, because a counter reading is not a load-sensitive
comparison and the box is at ~580% from another project:

    lock=1/0        one BARREN lock, ZERO productive, in a single 120 s mine_stone run

A barren lock is a 30-second exclusive navigation window that expired without the bot getting even
half a block closer to its target. One of those costs a quarter of this course's clock, on a course
that now PASSES -- and not one lock in the run was productive.

THE ARITHMETIC THAT MAKES IT MATTER. The @gamer playthrough stalls 160 seconds of daylight on
Mine And Collect: [[coal]]. 160 / 30 = 5.3 locks, and MAX_FAIL_COUNT is 5. So with this on, that
stall terminates at about 150 seconds instead of running to the end of the window, and the give-up
path -- progress checker, wander, blacklist -- gets to run for the first time.

THE INSTRUMENT WAS FIXED BEFORE IT WAS READ, and this is the third time today. The counter first
incremented only when the flag was ON, which makes "barren locks with the fix off" zero by
construction -- the number the entire premise rests on. Same defect as the stranded gate that was
declared and never exposed (voiding forty launches by its own rule) and as navSearchOnly reading 0
whether or not the bug was there. It now COUNTS always and only ACTS when flagged: the counter is
an observation, the flag is the behaviour.

STILL UNMEASURED AS AN EFFECT, and not shipped. Rule ZERO: the last confirming window came back
INVALID at 8 fps. What is established is that the mechanism fires; what is not is that stopping it
helps. Those are different claims and the register should not blur them.

#16 CORRECTED BEFORE IT COST A QUIET WINDOW: THE GUARD COULD NOT FIRE ON MOST COURSES (2026-08-14).

The parked fix reused MAX_FAIL_COUNT = 5. A barren lock costs THIRTY SECONDS, so five of them is
150 seconds of a bot standing still before anything reconsiders. Course durations on this bench:

    90, 120 (mine_stone), 150, 180, 240, 300

At 150 s the guard cannot fire on the first four AT ALL -- including mine_stone, the course it was
found on. I had parked a fix that no course I could run was capable of exercising, which is the
same defect as the gate whose awake half could never fail and as navSearchOnly, and would have
burned the next quiet window discovering it.

Barren locks now have their OWN limit, MAX_BARREN_LOCKS = 2, because they guard a different failure
from MAX_FAIL_COUNT: that one counts EXCEPTIONS, which are instant, so five is reasonable patience;
this one counts thirty-second holds. Sixty seconds of getting nowhere toward ONE target is already
generous and it fits inside every course on the bench. The streak is scoped to its target and
cleared when the target changes -- carrying it over would refuse navigation to a drop never tried,
which is the same bug PickupDroppedItemTask fixed for its wander radius.

VERIFIED ON A HEALTHY COURSE, flag pinned ON: lock=1/0. One barren lock, streak never reaches two,
guard does not fire. mine_stone no longer stalls since the tower fix, so it takes a single lock --
exactly the case where nothing should change, and nothing does.

THE EFFECT REMAINS UNMEASURED, and can only be measured where the stall is: the @gamer playthrough,
160 s on Mine And Collect: [[coal]], which needs a quiet box. Still off, still not shipped.

A RUN IN WHICH THE BUG DOES NOT HAPPEN CANNOT VALIDATE THE FIX FOR IT (2026-08-14).

The survival client came back above the floor (15.0 fps, a VALID window) and the counters read:

                          14-min run, flags OFF      8-min run, flags ON
    scanNoBreak           847,492                    0
    cbAvoid               842,176                    9,630
    breakFail claimed     2                          0
    lock barren           1                          0

That is a 99% collapse in exactly the number I pre-registered as the mechanism gate, and it is NOT
evidence. breakFail=0 says no break failed in the second run, so the escalating ban never had to
act; lock=0/0 says the bot never stalled, so the barren-lock guard never fired either. NEITHER FIX
WAS EXERCISED. The collapse is a run without the trigger.

Banking it would have been the most attractive mistake available today: the number is huge, it
points the right way, and it matches the prediction. The gate says "cbAvoid must collapse ON A RUN
WHERE THE BAN FIRES", and the second clause is the whole gate.

The two runs are not comparable anyway -- 8 minutes against 14, 15 fps against a healthier client,
a spawn 5000 blocks away and an inventory carried over from the previous run, so "wood" counted as
pre-existing and the ladder started mid-way. It reached first craft and crafting at 22.4s and no
further.

WHAT IS STILL OWED: a survival window in which breakFail>0, so the ban actually fires and the gate
can read. Until then the two flags are shipped on REGRESSION evidence only (craft 12/12, nav 6/6,
0 invalid) with the benefit unproven, which is what their javadoc says.

THE PLAYTHROUGH INSTRUMENT WAS BROKEN IN TWO PLACES, AND BOTH ARE FIXED AND VERIFIED (2026-08-14).

1. THE SPIRAL NEVER CAME BACK. The run index is monotonic across every run ever taken, 300 blocks a
   step, so the search only ever marched outward. Found at 968 -- about NINE THOUSAND BLOCKS from
   base. Three windows in one day started at 94,-44 then 1492,-5038 then 5392,-3538. Capped at 64
   steps, a ring of roughly 1200 blocks around the base.

2. EXHAUSTING THE SEARCH STARTED THE RUN ANYWAY. `while skipped < 8` fell out of the loop and used
   the LAST spawn -- the one it had just rejected -- printing only an informational line. That is
   what produced "items=0 at t=485s, HP 15 -> 5, still on rung one", which reads as a catastrophic
   regression and is a treeless biome. Now StandDown -> INVALID, like a starved client.

VERIFIED ON THE NEXT RUN, first try:

    fresh start #8: 892 150 -839
    start has 188 log blocks within 40

against eight treeless skips an hour earlier. The instrument finds a forest immediately now.

AND THE RUN STILL COULD NOT BE TAKEN: client at 7.0 fps, INVALID. The arena clients hold 28-29 and
the craft suite is 12/12; it is the heavier survival world that cannot start while another project
holds ~600% of this box. So acceptance criterion #1 stands where it stood: the ladder reached STONE
TOOLS at 328.9s on the last valid window, and whether the two shipped flags move it past coal is
still unmeasured.

⭐ WORTH KEEPING: both of these were INSTRUMENT bugs that manufacture false REDS, and each was one
line. Today they would have been read as "the playthrough regressed to nothing". Two of the three
nastiest defects this session were in the measuring apparatus, not the bot.

PREDICTING MY OWN FIX WILL MEASURE NOTHING, WRITTEN BEFORE THE SERIES ENDS (2026-08-15).

pathStartMustSucceed rests on find() refusing often enough to matter. Reading PathFinder's thread
teardown says it does not:

    active.set(false);      // first
    this.thread = null;     // second

find() refuses on `active.get() || thread != null`, so the only window in which a caller sees "not
busy" and is then refused is BETWEEN THOSE TWO STATEMENTS. Nanoseconds. Everywhere else the two
conditions agree, because the whole search runs with both set.

And the series agrees so far: findRefused = 0 on BOTH arms, two runs in. That zero is meaningful
now -- the counter was fixed to increment with the flag off -- so it is a real zero rather than one
by construction, which is the trap I fell into three times today.

So I expect the outcome to be flat and the gate to read 0. Saying it now rather than after, because
a prediction written afterwards is not a prediction. The fix is harmless -- it only acts when find()
genuinely declines -- but "harmless" is not "useful", and it should not be shipped as if the stall
were explained.

WHAT THIS DOES NOT EXCUSE: the 1.17-block park is still unexplained. The bot was outside the 1.0
stop distance with a drop it could see, and neither the pickup radius (refuted, 2/4 vs 4/4) nor this
accounts for it. That is where the next pass goes.

ONE THING NOTED AND DELIBERATELY NOT SHIPPED: the teardown would be strictly safer as
`thread = null` THEN `active.set(false)`, which closes the window entirely. It is two lines and
obviously correct -- and it cannot be shown to matter, so by rule ZERO's mirror it does not ship
today. Recorded here so the next person does not have to re-derive it.


RESULT: THE PREDICTION HELD, AND pathStartMustSucceed MEASURES NOTHING (2026-08-15).

    control  coal 3, 3, 3   3/3   findRefused = 0, 0, 0
    fix      coal 3, 3, 3   3/3   findRefused = 0, 0, 1

Flat, exactly as written above before the series ended. findRefused is a real zero now -- the
counter increments with the flag off since b9f452a2 -- so this is the gate answering, not the gate
being unable to fire.

AND THE SERIES CANNOT SAY MORE THAN THAT, for a reason worth naming: NOT ONE RUN STALLED. All six
scored 3/3. A fix for a stall cannot be judged by runs in which the stall did not occur, which is
the identical trap as this morning's 99% cbAvoid collapse on a window where no break ever failed.
The honest reading is "no effect observed, and no opportunity for one either".

So the flag stays OFF and unshipped. It is correct by inspection -- a caller should not act on a
success the callee never reported -- and it is not shown to matter. Those are different claims and
the register keeps them apart.

STILL UNEXPLAINED, and this is the open thread: the 1.17-block park. The bot was OUTSIDE the 1.0
stop distance with a drop it could see (tracker reporting it 2393 times) and did not close. Neither
the pickup radius (refuted, 2/4 against 4/4) nor the refused search accounts for it. Next pass
starts there, and the cheap reproduction exists: mine_coal with diag_pit.

CORRECTION TO THAT PREDICTION, BEFORE THE RESULT LANDS: RARITY IS NOT THE WHOLE ARGUMENT.

findRefused read 1 on a control run, so refusals DO occur -- rare, as a two-statement window
predicts, but not zero. I argued "this will measure nothing" from frequency alone, and that
reasoning is incomplete: what matters is rate TIMES cost, and the cost here is not one tick.

In the fresh-start branch a refusal means a THIRTY-SECOND lock is taken for a search that never
began. One such event is 30 s of a 180 s course -- seventeen per cent -- so a rate of about one per
run is not obviously negligible. In the other branch it costs a single tick and is negligible.

So the honest prediction is weaker than the one I wrote an hour ago: flat is still the most likely
outcome, but "rare" was doing work it cannot do on its own. The event is rare AND expensive, and
those pull in opposite directions.

Noting it now rather than after the numbers, because adjusting the reasoning once the result is
visible is how a prediction turns into a rationalisation.

RESULT OF pathStartMustSucceed: NO EFFECT, AND THE PREDICTION WAS WRITTEN FIRST (2026-08-15).

    control (off)  3, 3, 3, 2    3/4 pass  mean 2.75   findRefused 2 across 4 runs
    fix     (on)   3, 3, 3       3/3 pass  mean 3.00   findRefused 1 across 3 runs

Seven verdicts, not eight -- one run was lost when the earlier invocation was killed.

THE GATE ANSWERED HONESTLY IN BOTH DIRECTIONS, which is the point. findRefused is non-zero on both
arms, so the refusal is REAL: find() does decline, about 0.4 times a run. And the course passes 6 of
7 across both arms, so there is no failure for the fix to prevent. Rare, and nothing to catch.

I predicted "this will measure nothing" from PathFinder's teardown before the series ended --
`active.set(false)` then `thread = null`, a window two statements wide -- and then corrected the
reasoning, because rarity alone was not the argument: a refusal in the fresh-start branch costs a
thirty-second lock, so rate times cost mattered. The final numbers settle it: ~0.4 refusals a run,
no stalls to attribute to them.

FLAG STAYS OFF. The change is still correct -- a caller should not be told a search started when it
did not -- but correct is not the same as useful, and it must not ship as though the stall were
explained. It is not.

AND THE COURSE IS THE OTHER FINDING. mine_coal has now read 2/3, 4/4, 4/4 and 6/7 across four
series. It is NOT reliably red, so "red 1 in 3" was thin evidence and I built a hypothesis on it.
Anything measured here needs the failure to be reproducible first; right now it is not.

STILL UNEXPLAINED, and this is the seventh hypothesis to leave it standing: the bot parked 1.17
blocks from a drop it could see 2393 times, OUTSIDE the 1.0 stop distance, with no ban, no barren
lock and no refused search. None of the seven accounts for it.

THE BOW COURSES, NAMED BY THE COUNTERS ON THE FIRST RUN THAT PRINTED THEM (2026-08-15).

    bow_flee       bowShots=3 bowWild=6   noSol=0 aimTO=0 drawTO=0 facing=162 noRoom=9 bestMiss=0.34
    bow_flee_hard  bowShots=0 bowWild=0   every counter 0, bestMiss=-1.00, first_death=5.8s

⛔ AND I MISREAD ONE OF THEM FIRST, so it is recorded before the conclusion. `facing` is NOT a
refusal count -- BowShooter increments it as `facingTicks++` with the comment "the camera is claimed
for the whole shot -- this IS the kiting cost". 162 is TICKS, about 8 seconds of a 60-second run,
not 162 refused shots. I nearly built a fix on the counter's NAME. Caught by opening the file.

THE REAL ARITHMETIC CLOSES. bowNoRoom is the refusal counter -- "shots refused because a live flee
order had no distance to spare" -- and:

    ~20 requested = 3 aimed + 6 wild + 9 refused = 18

Nothing unaccounted for. noSol=0, aimTO=0, drawTO=0: ballistics found a solution EVERY time, the
turn always reached the cone, the draw never timed out. The bow subsystem is not broken.

SO THE COURSE FAILS ON SURVIVAL, NOT ON SHOOTING: deaths=4, avg dist 5.57 against a required 7. And
BowShooter's own javadoc already says why that is hard -- 1.47 blocks/s lost while facing, a
22-tick draw by vanilla construction, so "shooting that often and holding distance are mutually
unsatisfiable". That is a documented design tension, not an undiscovered defect.

bow_flee_hard is the other half and the register was RIGHT about it: it never shoots. But not
because shooting is broken -- every counter is zero including the never-computed bestMiss sentinel,
and first_death=5.8s. The bow is never engaged because the bot is dead before it could be.

⭐ THE INSTRUMENT PAID FOR ITSELF IN ONE RUN. Two courses had been red for weeks with "2 of ~20
requested" and no way to say why; printing six counters that already existed answered both, and
retired "one of them does not shoot at all" as true-but-for-the-wrong-reason.

THE FLEE NUMBERS ARE THE KNOWN OPERATING POINT, NOT A NEW DEFECT (2026-08-15).

    flee = held 89 / search 312 / ran 939 / plans 142 / driveTicks 346 / driveBlocked 0

On a ~1200-tick course that is 312 ticks -- 15.6 seconds, a quarter of the run -- standing still
with a search in flight, and 142 replans, one every eight ticks. It reads like an obvious defect,
and I was one edit from proposing replan-on-need.

IT IS ALREADY MEASURED, AND THE ALTERNATIVE LOST. The guard carries its own A/B:

    clock (current)     avg_dist 7.32 / 7.10 / 9.43    3 of 3 above the gate
    replan-on-need      avg_dist 6.11 / 4.84 / 8.39    1 of 3

and the note adds that search ticks stayed at 250-330 either way while plans halved -- "the searches
simply got longer instead of fewer, and the flee lost the thing the cadence was really providing, a
direction that stays fresh while the threat keeps moving". My 312 sits inside that range.

Fourth time today that reading an existing note stopped a wasted pass. The checklist already says
it -- read the CLOSED work before instrumenting, because closed often means "described and not yet
fixed" -- and here it was stronger than that: described, A/B'd, and the tempting arm refuted.

SO WHERE bow_flee ACTUALLY STANDS, with everything now measured rather than assumed:
  - shooting is fine: noSol=0, aimTO=0, drawTO=0, and 3 aimed + 6 wild + 9 refused accounts for ~20
  - the flee runs, at the better of the two cadences anyone has measured
  - md* counters cannot be read here at all (mdCalls=0, the defence chain does not tick)
  - and the bot still cannot hold 7 blocks: deaths 4-5, avg 5.57

BowShooter's own javadoc says why that may be unwinnable as posed: 1.47 blocks/s surrendered while
facing, a 22-tick draw by vanilla construction, so "shooting that often and holding distance are
mutually unsatisfiable". That is a COURSE DESIGN question -- can a kiting archer satisfy both gates
at once -- and not a bug to hunt. Filing it that way instead of opening an eighth hypothesis.


PREDICTION BEFORE THE RESULT: THE BREAK BAN LEAKS ACROSS RUNS (2026-08-15).

mine_coal showed cb=0/818/0/0 with breakFail=0/0/0/0/0 -- 818 refusals, no failed break, no ban
installed THIS run. Reading BotBehaviour end to end gives a mechanism that fits every part of that:

  1. `_extraAvoidBlockBreaking` is, in the code's own words, a "Persistent extra predicate (outside
     push/pop stack)". applyState() re-adds it every time, so push/pop cannot drop it.
  2. Only resetAvoidBlockBreakingExtra() clears it, called from WorldSurvivalChain when its
     60-second timer elapses.
  3. That timer is only consulted inside getPriority(), which only runs while the TASK RUNNER IS
     ACTIVE -- and between runs it is not (checklist RULE TWO: an idle bot ticks no chains).

So a ban installed late in run N is never cleared, run N+1 inherits it, and resetRunCounters wipes
breakFail to 0 -- producing exactly "hundreds of refusals with no failed break". Same shape as RULE
SEVEN's spectator leak, which survived a rebuild and two later runs and looked precisely like a
broken bot.

PREDICTION: the failing run reads avoidSrc with pred non-zero and the caller stamp naming
WorldSurvivalChain's addTemporaryBreakAvoidance -- NOT a fresh registration from the coal task.

WHAT WOULD REFUTE IT: pred=0 with set>0 (something protecting positions instead), or a caller stamp
naming any task other than the survival chain. Written now because a prediction made afterwards is
not a prediction, and this is the fourth mechanism proposed for these 818 refusals.


clearBansOnTaskEnd WAS NECESSARY BUT NOT SUFFICIENT, AND READING CAUGHT IT BEFORE THE A/B DID
(2026-08-16).

The fix clears `_extraAvoidBlockBreaking` when a task ends. Checking it rather than assuming:
resetAvoidBlockBreakingExtra() nulls the slot and calls applyState(), which rebuilds the live list
from the CURRENT STATE's toAvoidBreaking -- and readExtraState() fills that by copying the LIVE
list at push time:

    toAvoidBreaking = new ArrayList<>(settings.getBreakAvoiders());   // includes the extra

applyState() appends the extra to the live list every time, so ANY push taken while a ban is active
bakes a copy of that ban into the pushed state. From there the reset cannot reach it: it nulls the
slot and rebuilds from a list that now holds its own copy. MineAndCollectTask.onResourceStart pushes
on every resource task, so this is the ordinary path, not a corner.

Fixed by excluding the extra from the copy in readExtraState -- which is what makes BotBehaviour's
own comment ("Persistent extra predicate, outside push/pop stack") true -- and the same for the
placing twin.

⭐ THE POINT WORTH CARRYING: A FIX THAT MEASURES FLAT IS NOT ALWAYS REFUTED. IT IS SOMETIMES ONLY
HALF-INSTALLED. This was found by reading while the A/B for the first half was still running; had
the series come back flat first, the honest-looking conclusion would have been "the leak is not the
cause" and the real defect would have been buried under a refutation.

(Recorded here because the code change was swept into another worker's commit by `git add -A` --
checklist rule 4v -- so no commit message carries the reasoning. The comment at the site does.)


mine_coal IS A POOR GATE FOR THE BAN LEAK, AND THE REASON IS STRUCTURAL (2026-08-16).

    arm    coal   predsPresent  registered
    None     3          1            0        <- the leak, observed once
    true     3          0            0
    false    3          0            0
    true     3          0            0

The CONTROL arm reads predsPresent=0 as well, so the gate cannot discriminate. Third time today
that a series could not judge a fix because the bug did not occur in it.

THE REASON IS NOT BAD LUCK. The leak needs a ban INHERITED from an earlier run, which needs a break
to have FAILED in that earlier run -- and on a clean arena breaks do not fail. breakFail has read
0/0/0/0/0 in every mine_coal run tonight. So the trigger is rare by construction, and waiting for it
to appear in an A/B is waiting on something the course does not produce.

WHAT WOULD ACTUALLY TEST IT, and it needs no statistics at all: install a ban deliberately, then
read predsPresent at the START of the next run. One rcon-placed unbreakable block (bedrock in the
mining area) makes a break fail on demand; the ban installs; the run ends; the next run either
inherits a predicate or does not. That is a two-run deterministic check against a rare-event A/B
that has now spent six runs saying nothing.

⭐ THE GENERAL FORM, worth more than this instance: WHEN A FIX TARGETS A RARE TRIGGER, THE GATE MUST
CAUSE THE TRIGGER RATHER THAN WAIT FOR IT. Rule 4n says a criterion phrased as an absence can be
satisfied by things that are not success; this is its sibling -- a criterion that waits for a rare
event is satisfied by the event not happening, which looks identical to a fix that works.

CORRECTION: THE BAN CLEAR-UP IS FLAGGED, AND I SAID OTHERWISE (2026-08-16).

I committed the task-end ban clear-up describing it as "UNFLAGGED, unlike everything else shipped
today", and justified that at length. It is not. The tree reads:

    if (TungstenConfig.get().clearBansOnTaskEnd) {   // default false
        mod.getBehaviour().resetAvoidBlockBreakingExtra();

The claim came from reading my own diff with `tail -30`, which cut the `if` guard sitting above the
lines I looked at. Truncating a diff and then asserting what the code does is the same error I have
spent this session catching in other people's notes -- and the justification I wrote for shipping
unflagged was therefore an argument for something nobody had done.

The discipline held; only my description of it did not. Default off, and an interleaved A/B on
clearBansOnTaskEnd is running as this is written, which pays the measurement debt I said it owed.

ALSO OBSERVED, and not yet a finding: a run reads avoidSrc=0/0/1/0@-/- -- one predicate present,
zero registered, and BOTH stamps "-", including the persistent one added an hour ago. That is
expected rather than broken: the predicate was installed before the jar carrying the new stamp
existed, so nothing could have recorded it. The stamp cannot be judged until a ban is installed
under a build that has it, which is the "a counter at zero is ambiguous until you know it CAN be
non-zero" corollary from RULE ONE, applying to my own newest instrument.

RESULT OF clearBansOnTaskEnd: INCONCLUSIVE, AND THE GATE CANNOT SETTLE IT (2026-08-16).

    true   coal=3  predCount=1  registered=0
    false  coal=3  predCount=0  registered=0
    true   coal=3  predCount=0  registered=0
    false  coal=3  predCount=0  registered=0
    true   coal=3  predCount=1  registered=0

Five verdicts, ALL PASS on both arms, so the outcome cannot separate them -- mine_coal has now
passed 5/5, 6/7, 4/4 and 2/3 across four series and is simply not a red course.

⛔ AND THE MECHANISM COUNTER CANNOT SETTLE IT EITHER, which is the part worth keeping.
avoidPredCount is assigned inside shouldAvoidBreaking() -- it updates only when the predicate list
is CONSULTED. So it is a LAGGING indicator: if the clear happens after the last block test of the
run, the value judged at the end is the one from before the clear. predCount=1 on a flag-ON run
therefore does not mean the clear failed, and predCount=0 on a flag-OFF run does not mean there was
nothing to clear.

registered=0 on every run says no ban was installed during any of them, so the fix had nothing of
its own to clean up and the whole series was measuring an inherited leftover through a counter that
only refreshes on consultation.

FOURTH instrument limitation found today, and the same family as the other three: a number that
cannot answer the question being asked of it. To measure this properly the counter has to be
sampled at a defined moment -- read the list size directly at judge time rather than inheriting
whatever the last block test happened to leave behind.

So clearBansOnTaskEnd stays OFF and stays unmeasured. Its reasoning is sound and its evidence
(predCount>0 with registered=0 is an inherited predicate) still stands; what is missing is a bench
that can see the difference, and this one demonstrably cannot.
