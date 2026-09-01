# Archive: combat measurement methodology (2026-08-11/12)

Archived 2026-09-01 from `docs/ai/progress.md` per the >500-line rule in `docs/ai/readme.md`. Content moved verbatim, not edited.

## SESSION 2026-08-12 (night) — the root cause found, and the model behind twelve fixes refuted

INVESTIGATE — instrument, never argue. Eight counters built: release range (arrows=n/mean/max), bow
draw (draws=n/ticks/max/gap), exposure (band=), the four-way swing split (ready=far/near +
wait=far/near), key ownership (held=fwd/sprint/strafe), and the arrow-avoidance task's grip
(dodgeTask=). Two of them were BORN DEAD and read zero while the bot was visibly being shot —
caught before either was quoted.

WHAT THEY ESTABLISHED, in order, each step forced by the previous one:

1. The skeleton does NOT shoot at the approach. 2-3 shots a fight, ALL released from 4.3-6.3
   blocks. Eight movement hypotheses had been aimed at a phase that does not exist.
2. Every shot is preceded by a 20-tick draw beginning at 6.0-9.3 blocks. Acting on it failed,
   because a vanilla mob aims where the target IS at release — it does not lead. DODGING is closed
   as a family; at five blocks an arrow crosses in under two ticks.
3. Nine ready-swing ticks in ten are spent out of reach. Not the dodge (16%), not closing speed
   (a flag moved the ratio 0.165 -> 0.161), not stolen legs (the bot asks forward 95-100% of those
   ticks and the keys DO reach the game).
4. It travels DIAGONALLY: a strafe key is held in ~55% of them. A 45-degree diagonal leaves ~70% of
   the speed pointing at the target — 3.9 blocks/s against a skeleton retreating at ~5.
5. ROOT CAUSE: combat only ticks inside 4.5 blocks (`inRange`), while the killing band is 2.5-7.0.
   129-253 ticks inside the band against 62-89 gate evaluations — for more than half its time under
   fire the fight is not running at all. Every hypothesis today addressed the other 40%.

THE MODEL THAT DIED — and it underpinned three of the fixes: "fewer band ticks means fewer arrows".
Refuted in BOTH directions: suppressing the orbit cut exposure 33% and made arrows WORSE
(1.38 -> 1.92); engaging across the band raised exposure 73% and made them BETTER (1.96 -> 1.13,
1.6 sigma). Arrows landed is not a function of time under fire.

THE CEILING, from measured constants: 14 ticks closing + 50 swinging = a 64-tick floor ~ 1.3 shots,
and a close shot lands 50-70%. A gate demanding ZERO arrows is a COIN at ~40% for perfect play.
12/12 was never available on this course, and saying so is part of the result.

METHOD FIXED, and it invalidates the repo's history: A/B arms ran as BLOCKS, confounding the flag
with session drift. The same flag read -0.60, +1.92 (3.18 sigma — above the acceptance bar) and
-0.31 across three blocked pairs; interleaved it settled at 0.46. `--pin-alt` now alternates
A,B,A,B. Rules 4n-4r written. Every pre-2026-08-12 "measured improvement" is unverified.

ASSESS — thirteen hypotheses closed, zero score movement, and five of my own claims withdrawn:
the pvp "hang", the course being unmeasurable at 9.7 fps, `danger` coming from the rim, arm sizing
from one session's sd, and a 3.18-sigma result that a proper design turned into 0.46.

## SESSION 2026-08-12 (later) — the course was invalid, and the retreat stage was on all fight

INVESTIGATE: started by applying this repo's own rule 4k for the first time — opening `fail.png`
instead of reading more counters. The arena turned out to be a **floating island over the void**,
which is not visible in any number the suite prints.

That one look unlocked the chain:

1. **The course scored its BEST result when the fight did not happen.** "The skeleton is dead" and
   `early_stop` both test *is the entity gone*, and vanilla offers two ways to be gone without
   losing: falling off the island (last seen x=13.7 with the rim at 14, every run) and despawning
   past 32 blocks. Both also stop it shooting, so `min_hp` read 20. Proof was landed swings against
   a 20 HP target: the PASSes had 1-2 landed, the honest fights had 3.
   Found only because the position sampler — dead since it was written, printing `closest_gap=None`
   for several sessions — was moved off `drive_tick`'s 2 s cadence onto `early_stop`'s poll.
   Fixed: own arena at half=30, `PersistenceRequired:1b`, and a void gate that demands POSITIVE
   evidence (spelled `not fell` it passed 4/4 on `last_seen=None` — the dead-`awake` shape again).
   Honest baseline on the repaired course: **1/6**.

2. **`danger` still dominated `reposition` on the widened field** (69/144/170/222/244, 1984 once),
   ten blocks clear of any edge — so "the bot reacts to the rim" was wrong. Reading the estimate
   found the real cause: `simulateKnockback` integrates 15 ticks of gravity with **no collision**.
   Replaying it: the arc peaks at +1.153 and ends at **-2.331**, and `fallHeight` is then measured
   from that sunken point. On solid ground it lands inside blocks and reads 0 — invisible. On a thin
   slab it is 2.33 below the floor with void underneath, so `DANGER_BATTLE` was engaged EVERY TICK,
   steering the bot along `getRetreatPath()` — away from the target — for the whole fight.
   Fixed by letting the simulated body land. Measured, same course, same arena both arms:
   `danger` 69-1984 -> **0 in all six**, `reposition` 39-92 -> **0**, aim owner `enemy` 0-9 -> 55-76.
   **Score 1/6 -> 1/6.** A real defect, and not the one keeping this red — both halves recorded.

3. **Where the damage actually comes from**, fixed build, n=6: arrows land at `gapMean` 3.79-5.03,
   `gapMax` 6.30, with `dodgeDrive` 10-39. The dodge fires and cannot beat the flight time at four
   blocks. So the lever is not the dodge; it is not being in that band. Mechanism: sprint is cut
   inside REACH so the blow lands unsprinted, but a skeleton retreats at a walking bot's speed, so
   the tick the bot slows it falls back into the 3-6 band. Written behind
   `combatHoldContactOnShooter` (default OFF, unmeasured) — drops sprint at the SWING instead of at
   REACH, scoped to `RangedAttackMob` so zombies and duels are byte-identical.

AUDIT — two of six "defects" from the earlier code-reading pass were defects only in the reading
(the shooter guard's radius, and registering `ProjectileDodge` in `tungsten$driving`). Both closed
with the reasoning left at the site. Worth remembering the next time a reading pass yields a list.

Rules added: **4n** (a gate testing an ABSENCE can be satisfied by things that are not success) and
**4o** (a check cycle is seconds, a bench course is minutes — stop polling; written from my own
loop this session).

## SESSION 2026-08-11/12 — mob_skeleton: from 0/N to passing, and four wrong headlines

INVESTIGATE: the course had never passed since it was written. Instrumented the swing gate, the
interact gate, the crit hop gate-by-gate, and the mdTung split (committed fight vs force field).
Three of those counters turned out to be reading things nobody had checked.

PLAN: one root cause per pass, judged on the bench, reverting anything that did not measure.

IMPLEMENT — what survives scrutiny (mechanism proven to EXECUTE, not a delta):

1. **The arrow dodge never reached the keys.** `MobDefenseChain` pressed SPRINT/FORWARD/JUMP from
   altoclef's task runner, which ticks BEFORE `MovementQueue`, and `Movement.update()` releases every
   key then presses its own. Every tick the walker drove the approach — i.e. every tick of an
   approach — the dodge was wiped before the game read it. Fixed with `ProjectileDodge`, a primitive
   at the final-word position. Proven by `dodgeDrive` going from structurally-zero to 42/20/204/9/34/30.
   **This had silently invalidated four earlier dodge experiments**, all filed as refuted.
   Same pitfall (P1) the flee keys already paid for once: "22 hits against 23 — it had never run".
2. **You cannot outrun an arrow.** `isInDanger` + the flee branch bid 70, out-bidding the fight
   branch's 65, so an arrow that landed made the bot RUN instead of closing — and running gains a
   shooter exactly what it wants. Declined for `RangedAttackMob` (a property, not a mob name).
   Damage 17.5 -> 9.62; `mdRet3` 0 in 13/13. Released as 0.83.0.
3. **Three bench defects.** The course gave the bot a free arrow during a 2 s window where
   `TaskRunner` was inactive (RULE TWO). `summon skeleton {NoAI:1b}` produces an UNARMED skeleton
   because `SummonCommand` only calls `initialize()` with no NBT — the course scored **6/6 green
   against a statue**. And the gate added to catch that failed the PERFECT run, because it tested
   whether the skeleton landed a hit rather than whether it could fight.
4. **The ruler.** min_hp clusters ARE arrows landed (20=0, 16=1, 12=2, 8=3). Characterised at n=53.
   Pass counts showed 17/17/25% over runs where arrows moved 1.53 -> 1.10.

UNPROVEN, kept on mechanism only and marked as such at the site: ground-distance positioning,
point-blank sidestep, cooldown stand-off for shooters (no effect at n=40).

CLOSED, with numbers at the site — do not re-open: reducing the bunny hop (three branches, all
worse); making movement predictable (steady orbit, worse) — **the bot's jitter is a defence, not a
bug**; the crit thread (crits already land); "more controller" on mob_trio (engagement goes with MORE
damage, two series); the player band for a ranged mob; MOB_PRESS_DISTANCE / MOB_MIN_CENTRE_GAP.

THE METHOD FAILURE, now checklist rule 4j: four headlines softened or vanished on more data because
the arms were BUILT HOURS APART and this course's variance lives between series (same build: 0.77,
0.90, 1.18 arrows). Cure: flag the thing under test, A/B it with `--pin` in one session, and PROVE
the pin reaches the behaviour by reading it back.

RESULT: mob_skeleton 0/N -> passing regularly; damage 17.5 -> 4.50. Still RED on its gate (zero
arrows demanded, median run takes one). Baselines pvp 12/12, nav 12/12, craft 10-12/12, mob 2/4.


