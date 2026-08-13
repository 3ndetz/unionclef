"""PRE-REGISTRATION #6, WRITTEN BEFORE ITS DATA EXISTS (2026-08-13, combatApproachLatch).

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
