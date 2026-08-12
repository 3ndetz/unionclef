"""uctest mob suite — does the bot fight MOBS, and does the fight run on tungsten?

Why this exists as a suite rather than a script. Mob combat could not be measured by anything
already here: the pvp courses drive `punk`, which is tungsten's own PunkPlayerTask and never
touches the mob path, and a @gamer sweep spends its time chopping wood and never commits to a
melee at all. A hand-rolled probe filled the gap for a while and spent most of that time being
wrong about its own arena -- the worst of it being that the stand's arena is carved to AIR from
the void bottom up to y=-40, so a zombie summoned beside the bot simply FELL (traced: y=-60 ->
-85 -> -160 -> gone in under two seconds) and every one of those falls was recorded as a lost
fight.

Building the course through the arena helper removes that whole class of error: a flat field is
a floor, laid the same way every run.
"""
import re
import time

from .actors import KIT_SWORD
from .arena import STAND_Y
from .scenario import Criterion, Scenario


def _zombie_count(ctx):
    """How many zombies exist, straight from the server. -1 when the answer is unparseable."""
    r = ctx.rcon.cmd("execute if entity @e[type=zombie]", allow_reject=True)
    if "Count:" not in r:
        return 0
    try:
        return int(r.split("Count:")[1].strip().split()[0])
    except (IndexError, ValueError):
        return -1


def _stat(ctx, name):
    """One counter group out of the mod's stats line, or None when it cannot be read."""
    ok, s = ctx.bot.py.try_call("placeStats")
    if not ok or not s:
        return None
    for tok in str(s).split():
        if tok.startswith(name + "="):
            return tok.split("=", 1)[1]
    return None


def _tung_ticks(ctx):
    """Ticks the fight spent inside tungsten, across BOTH paths.

    mdTung is a pair: the committed-fight branch of MobDefenseChain, and the force field's strike
    on the nearest hostile. Watching only one of them once reported "the kill did not come through
    the rewired path" about a fight that had gone entirely through the other.
    """
    raw = _stat(ctx, "mdTung") or ""
    total = 0
    for part in raw.split("/"):
        if part.lstrip("-").isdigit():
            total += int(part)
    return total


# ⛔ THE MOB ARENA IS A FLOATING PLATFORM OVER THE VOID, AND IT HAS NO SAFETY MARGIN.
# Seen for the first time on 2026-08-12 by opening a fail.png instead of reading more numbers: the
# flat_field is a thin strip of blocks with stars ABOVE AND BELOW it. Past the edge there is
# nothing -- the stand's world is carved to air from the void bottom up to y=-40 (see this file's
# header), so the field is an island at y=-60.
#
# CONSEQUENCE FOR EVERY MOVEMENT PRIMITIVE TESTED HERE: an overshoot is not a bad step, it is a
# death. A held sprint key killed the bot within a run and the fall CASCADED into later runs
# (rule 4l, and the void incident it was written for). nav_gaps drops the bot for the same reason.
# Any change that presses movement keys must be judged against this arena, not against an
# imagined open field -- and the arena guard in run_suite (rule 4k) is what makes such a death
# visible instead of silently poisoning the numbers.
class MobMelee(Scenario):
    """One zombie, one armed bot, flat ground at night. It should die, and to tungsten."""

    id = "mob_melee"
    tier = "gate"
    needs_victim = False
    duration = 60
    bot_kit = KIT_SWORD

    def build(self, arena, ctx):
        arena.flat_field(half=14, grass=False)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 -90 0"
        ctx.geo["fps"] = []

    # THE MOB COURSES NEVER SAMPLED FRAME RATE, so every mob verdict carried avg_fps=0.0 and the
    # starvation guard -- which can only downgrade a run it has an fps for -- could never fire on
    # this suite. Measured on the day it was found: mob_skeleton FAIL with the bot at 3 hp and no
    # way to tell whether dodging arrows is even possible at ~10 fps.
    # The sampler that fixed it now lives in Scenario._sample_fps, because it was the THIRD file to
    # need its own copy and pvp was still missing a fourth.

    def drive_start(self, ctx):
        # Night, because a zombie in daylight BURNS: measured at about 1.2 HP a second, which
        # kills it in twenty seconds flat with the bot doing nothing. Anything scored on a lit
        # arena is measuring the sun.
        ctx.rcon.cmd("time set midnight")
        # spawn_monsters is the name this version accepts; the arena sends it too, but a course
        # that depends on there being exactly ONE zombie says so itself.
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd("difficulty normal")
        ctx.rcon.cmd("kill @e[type=zombie]")
        ctx.bot.py.try_call("resetRunCounters")
        # Four blocks: inside the field, close enough to engage at once, far enough that closing
        # the distance is still part of the test.
        ctx.rcon.cmd(f"summon zombie 4.5 {STAND_Y} 0.5")
        ctx.geo["spawned"] = _zombie_count(ctx)
        time.sleep(2)
        # @test kill runs KillEntityTask on the nearest tracked zombie. The tracker lags the summon
        # by about a second, and issued too early the command finds an empty list and starts
        # nothing -- with no task running the defence chain is never ticked and nothing fights.
        ctx.bot.cmd("@test kill")

    def early_stop(self, ctx):
        return _zombie_count(ctx) == 0

    def judge(self, ctx):
        killed = _zombie_count(ctx) == 0
        ticks = _tung_ticks(ctx)
        hps = [s["bot_hp"] for s in ctx.samples if s.get("bot_hp") is not None]
        low = min(hps) if hps else None

        yield Criterion("exactly one zombie was spawned", ctx.geo.get("spawned") == 1,
                        f"count_at_spawn={ctx.geo.get('spawned')}")
        yield Criterion("the zombie is dead", killed,
                        f"remaining={_zombie_count(ctx)}")
        # THE POINT OF THE COURSE. Killed is not enough: the old force field could always do that.
        # What is being measured is whether the swinging ran on tungsten.
        #
        # ⛔ AND THE SUM HIDES THE ONE THING IT IS SUPPOSED TO SHOW. mdTung is a PAIR -- the
        # committed-fight branch and the force field's strike -- and _tung_ticks adds them, so this
        # criterion passes on force-field ticks alone, which is precisely the outcome it was written
        # to detect. Measured on mob_trio: mdTung total=174 with CombatController's own counters at
        # ctl=0 and cq=0/0, and every aim counter zero. The aim block is gated only on
        # combatRotatesEnabled (default true), so a single tick() call would have incremented
        # aimNone at least -- meaning the controller was never ticked at all and the whole 174 came
        # from the force field. The split is printed now so that cannot pass unseen again.
        yield Criterion("reached striking distance (tungsten took the legs)", ticks > 0,
                        f"mdTung total={ticks} split={_stat(ctx, 'mdTung')} "
                        f"ctl={_stat(ctx, 'ctl')} cq={_stat(ctx, 'cq')} mdFar={_stat(ctx, 'mdFar')} "
                        # WHERE IT SHOOTS FROM (count/mean/max), against dw's where-they-LAND. The
                        # mod has carried this since the release-range instrument went in and the
                        # course did not print it — the same dead-instrument shape this file has
                        # now been bitten by twice. A counter nobody reads does not exist.
                        f"arrows={_stat(ctx, 'arrows')} draws={_stat(ctx, 'draws')} "
                        f"band={_stat(ctx, 'band')} dodgeTask={_stat(ctx, 'dodgeTask')} "
                        f"dealt={_stat(ctx, 'dealt')} swingHits={_stat(ctx, 'swingHits')} dodgeDrive={_stat(ctx, 'dodgeDrive')} hop={_stat(ctx, 'hop')}")
        # A fall is not a fight. On a flat field with a floor this should never fire, and if it
        # does the arena is wrong rather than the bot.
        yield Criterion("the bot was actually in the fight", low is not None and low < 20.0,
                        f"min_hp={low}", gate=False)



class MobTrioNoDamage(MobMelee):
    """THREE zombies at once, and the bot must not lose a single point of health.

    The user's acceptance criterion, in his words: tungsten must fight skilfully and PREDICT
    danger -- plan the route of the fight in advance and not let itself be hit even once.

    That is deliberately harsher than mob_melee, which passes while LOSING health (its min_hp
    check exists only to prove the bot took part). Here health is the whole point: winning while
    taking damage is a FAIL.
    """

    # ⛔ ON THIS COURSE THE COMBAT CONTROLLER ENGAGING IS ASSOCIATED WITH *MORE* DAMAGE.
    #
    # Recorded here because it contradicts the premise the whole mob-policy task (G-1.80) rests on
    # -- "wire tungsten's duelling engine into the mob fight and the damage will fall".
    #
    # The controller used to be unreachable here: split read 0/192 and 0/208 with ctl=0 every run,
    # the fight running entirely on the force field. After the ground-distance positioning fix it
    # engages sometimes, which is what made the comparison possible at all. Paired per run, n=7:
    #     ctl      1    5    0   62   31    0    0
    #     damage  9.0  6.0  0.0  6.0  3.0  3.0  0.0
    # Both ZERO-damage runs are ctl=0 runs, and the heaviest engagement took 6.0. That replicates an
    # earlier independent series (ctl 0/0/7/87 against damage 3.0/3.0/9.0/6.0), so it is now two
    # series and about eleven runs pointing the same way.
    #
    # It does NOT say the controller is bad -- this course is three zombies in contact, where the
    # force field's swat-everything-in-reach may simply suit the geometry better than spacing does.
    # What it says is that "more controller" is not the lever here, and any G-1.80 work must carry
    # its own damage measurement rather than assuming engagement implies improvement.
    id = "mob_trio"
    tier = "gate"
    duration = 120

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set midnight")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd("difficulty normal")
        ctx.rcon.cmd("kill @e[type=zombie]")
        # START THE FIGHT WHOLE. A run inherits whatever health the previous one left, and the
        # criterion is about damage taken HERE -- one run took zero damage and still failed the
        # gate because it walked in on 14 hearts from the run before.
        ctx.rcon.cmd(f"effect give {ctx.bot.name} minecraft:instant_health 1 10 true")
        time.sleep(0.5)
        ctx.bot.py.try_call("resetRunCounters")
        # Spread them out: three in a line would be a queue, three around the bot is a fight.
        for x, z in ((5.5, 0.5), (-4.5, 3.5), (0.5, -5.5)):
            ctx.rcon.cmd(f"summon zombie {x} {STAND_Y} {z}")
        ctx.geo["spawned"] = _zombie_count(ctx)
        time.sleep(2)
        ctx.bot.cmd("@test kill")

    def judge(self, ctx):
        killed = _zombie_count(ctx) == 0
        ticks = _tung_ticks(ctx)
        hps = [s["bot_hp"] for s in ctx.samples if s.get("bot_hp") is not None]
        low = min(hps) if hps else None

        # MEASURE THE THING THE FIGHT IS ACTUALLY MADE OF.
        # Eight combat hypotheses were judged on min_hp alone and every one of them landed inside
        # the run-to-run spread of that number: 2 to 11 out of 20 on the SAME build. An effect
        # smaller than about six health points is not detectable that way, so eight experiments
        # produced eight verdicts of "noise" and no mechanism.
        #
        # The series did establish one law -- damage tracks TIME IN CONTACT, because every policy
        # that lengthened the fight cost more health than it saved. Duration is therefore the
        # controllable quantity and min_hp is its consequence, so both get recorded. They do not
        # gate anything: the user's criterion is still zero damage. They exist so the NEXT
        # hypothesis can be judged on the mechanism it claims to change rather than on the number
        # it happens to end at.
        drops = 0.0
        prev = None
        for h in hps:
            if prev is not None and h < prev:
                drops += prev - h
            prev = h
        first_t = ctx.samples[0]["t"] if ctx.samples else None
        last_t = ctx.samples[-1]["t"] if ctx.samples else None
        duration = None if first_t is None else round(last_t - first_t, 1)

        yield Criterion("three zombies were spawned", ctx.geo.get("spawned") == 3,
                        f"count_at_spawn={ctx.geo.get('spawned')}")
        yield Criterion("all three are dead", killed, f"remaining={_zombie_count(ctx)}")
        yield Criterion("reached striking distance (tungsten took the legs)", ticks > 0,
                        f"mdTung total={ticks} split={_stat(ctx, 'mdTung')} "
                        f"ctl={_stat(ctx, 'ctl')} cq={_stat(ctx, 'cq')} mdFar={_stat(ctx, 'mdFar')} "
                        # WHERE IT SHOOTS FROM (count/mean/max), against dw's where-they-LAND. The
                        # mod has carried this since the release-range instrument went in and the
                        # course did not print it — the same dead-instrument shape this file has
                        # now been bitten by twice. A counter nobody reads does not exist.
                        f"arrows={_stat(ctx, 'arrows')} draws={_stat(ctx, 'draws')} "
                        f"band={_stat(ctx, 'band')} dodgeTask={_stat(ctx, 'dodgeTask')} "
                        f"dealt={_stat(ctx, 'dealt')} swingHits={_stat(ctx, 'swingHits')} dodgeDrive={_stat(ctx, 'dodgeDrive')} hop={_stat(ctx, 'hop')}")
        # THE CRITERION, MEASURED AS DAMAGE RATHER THAN AS LEFTOVER HEALTH.
        # min_hp answers "how healthy did it end up", which is not the question: a run that took
        # no damage at all failed this gate because it started on 14 hearts inherited from the
        # previous fight. The mod counts damage per tick, so ask that instead -- and only fall
        # back to min_hp if the counter cannot be read.
        exact_dmg = _stat(ctx, "dmgTaken")
        try:
            took = float(exact_dmg)
        except (TypeError, ValueError):
            took = None
        ok_zero = (took == 0.0) if took is not None else (low is not None and low >= 20.0)
        # ⛔ KNOW THE SPREAD BEFORE SPENDING A HYPOTHESIS ON THIS COURSE.
        # The header above records eight ideas judged on min_hp, whose spread is 2-11 out of 20,
        # all returning "noise". Switching to exact dmgTaken made the INSTRUMENT precise and did
        # NOT make the course quiet: on one build, one day, at a healthy frame rate, this number
        # read 3, 3, 6, 9, 12, 15, 17 and 18. A three-point difference is inside that by a factor
        # of five.
        #
        # So n=2 and n=3 comparisons here — which is what a ninth, tenth and eleventh hypothesis
        # will reach for — cannot resolve anything at all. Budget n>=6 an arm, the way the duels
        # are budgeted (docs/features/PVP_SUITE.md), or do not run the experiment.
        yield Criterion("the bot took ZERO damage", ok_zero,
                        f"damage={exact_dmg} min_hp={low}")
        # The mod counts damage EVERY TICK; the sampled figure is kept beside it so the gap
        # between them stays visible (5 samples caught 3 points of a 9-point loss).
        exact = _stat(ctx, "dmgTaken")
        yield Criterion("damage taken (recorded, not gated)", True,
                        f"exact={exact} sampled_drop={round(drops, 1)} min_hp={low}", gate=False)
        yield Criterion("fight duration (recorded, not gated)", True,
                        f"{duration}s over {len(ctx.samples)} samples", gate=False)
        # ⛔ THE COUNTERS THIS COURSE HAS NEVER READ, AND EIGHT HYPOTHESES DIED WITHOUT THEM.
        # The note above records the cost: eight combat ideas judged on min_hp alone, whose spread
        # is 2 to 11 out of 20 on ONE build, so all eight came back "noise" and none named a
        # mechanism. Meanwhile TriggerBot has counted every reason it declines to swing since
        # before that series started, and no mob course has ever printed it.
        #
        # crowd= is armHold/crowdEsc/crowdPlan: ticks held off because a loaded arm was in range,
        # ticks the fight was treated as a CROWD rather than a duel, and ticks the crowd planner
        # chose the step. Against THREE zombies those three are the whole policy under test, and
        # until now they were unreachable -- they existed in CombatController and were never
        # exposed over py4j at all.
        ok_gs, gs = ctx.bot.py.try_call("gateStats")
        yield Criterion("swing gates (recorded, not gated)", True,
                        str(gs) if ok_gs and gs else "unreadable", gate=False)
        # ⛔ ctl IS THE DISCRIMINATOR; THE crowd= TRIO IS NOT AND NEVER WAS.
        # armHold/crowdEsc/crowdPlan are declared in CombatController with javadoc describing what
        # they would mean and NOTHING INCREMENTS THEM -- same shape as lastSwingMs. So crowd=0/0/0
        # is not evidence of anything, and reading it as "the crowd policy never ran" was wrong.
        # The aim counters are no better on their own: they sit inside `if (combatRotatesEnabled)`,
        # so they read zero whenever that flag is off, whether or not the controller ticked.
        #
        # ctl (controlTicks) increments unconditionally once close-quarters control runs, and
        # cq is its entry/no-LOS split. Those two answer the actual question: does CombatController
        # drive this fight at all, or does the mob path belong entirely to altoclef's kill task?
        yield Criterion("controller ran? (recorded, not gated)", True,
                        f"ctl={_stat(ctx, 'ctl')} cq={_stat(ctx, 'cq')} "
                        f"lowHp={_stat(ctx, 'lowHp')} hurt={_stat(ctx, 'hurt')}", gate=False)


# ⛔ WHAT IS ALREADY KNOWN ABOUT WHY THIS FAILS, so the next pass does not re-derive it.
# The dodge is LIVE, not dead machinery: DodgeProjectilesTask is instantiated at
# MobDefenseChain:472, gated on `isDodgeProjectiles() && projectileIsClose`, and this course's own
# counters show that branch returning 46 and 167 times a run (mdRet slot 3). It fires, repeatedly,
# and the bot still takes 15-17 damage — four to six arrows — over a 12.5 block approach.
#
# THE TENSION, and it is why "dodge harder" is not obviously the fix: on safe ground the dodge
# turns the bot PERPENDICULAR and sprint-jumps. The bot is at the same time trying to CLOSE those
# 12.5 blocks. Every dodge is a step not taken toward the skeleton, the approach lengthens, and
# mob_trio established on this same suite that damage tracks TIME IN CONTACT. So dodging more may
# cost more arrows, not fewer. closest_gap read 4.60 and 2.82, so it does arrive.
#
# ⛔ INSTRUMENTED, AND IT IS "TOO EAGER". DamageWatch had the answer all along and no mob course
# read it: dw = hits/damage/gapMean/gapMax/rangedHits/deathsSeen, where the gap is the distance to
# the nearest living entity AT THE MOMENT each hit landed. Four runs:
#     5/20.0/8.08/11.17/5/1     4/17.0/5.63/9.34/3/0
#     5/20.0/7.13/13.55/3/1     5/20.0/4.84/9.20/2/1
# Every arrow lands at a mean gap of 4.8 to 8.1 blocks, maxima to 13.6 — during the APPROACH, not
# in close quarters. And closest_gap reads 4.31, 5.19, 5.57, 7.53: over a whole run the bot never
# gets near a skeleton it was sent to kill, with kaTung=0/0/0/0 — the kill task never engages.
#
# WHICH BRANCH DOES IT. (SUPERSEDED as of the ARENA_HALF=30 change below — this paragraph
# describes the course as it was at half=14, where the fight happened at the rim by construction.
# It is kept because it is how the danger-zone arm was identified, and the reading was confirmed
# later by `danger` dominating `reposition` in every run. Whether the arm still drives the bot on
# the widened field is now an open question, not a settled one.)
# That arena was flat_field(half=14) and the skeleton spawns at 12.5, so the
# fight happened NEAR THE RIM — and WorldHelper.isDangerZone returns true wherever fewer than five
# of the twenty-five blocks under a 5x5 around the feet are solid. So the danger-zone arm of the
# dodge is the live one, and it hands control to DodgeProjectilesTask, a PATHING task whose whole
# job is to hold ARROW_KEEP_DISTANCE away from projectiles. The bot is not failing to close; it is
# being driven away, correctly, by a task doing exactly what it says.
#
# (The other arm is dead: suggestedProjectileRotation is assigned only inside onPlayerItemUse,
# which is wrapped in `if (false && ...)`. I made that arm yield instead of holding priority,
# measured it, and found it INERT — mdRet2 kept incrementing, which is how the danger-zone arm was
# identified as the live one. Reverted unshipped.)
#
# ⛔ AND "MAKE THE DODGE YIELD TO THE KILL ORDER" IS REFUTED. That looked like the obvious
# resolution: a bot sent to KILL a skeleton cannot also run ARROW_KEEP_DISTANCE from its arrows, so
# let the dodge ignore projectiles fired by the current kill target and keep it for everything else.
# Built it properly -- owner id carried on CachedProjectile, kill target published by
# AbstractKillEntityTask, the chain skipping that shooter's arrows. Healthy runs:
#     with the dodge (baseline)   dmgTaken 20, 13, 16, 20        mean 17.25
#     dodge yielding              dmgTaken 19, 40, 65, 20, 20    mean 32.8
# Nearly double, one run at 65 damage over 17 hits. Reverted unshipped.
#
# THE USEFUL PART: that quantifies the dodge. It roughly HALVES incoming damage, so it is earning
# its keep and "it is too eager" is only half true — it costs the approach and pays for it. What it
# cannot do is get anywhere near the one-point bar.
#
# ⛔ AND THE PREDICTIVE DODGE WAS BUILT AND MEASURED TOO — BIMODAL, NOT BETTER ON AVERAGE.
# Two changes together, because either alone is inert: (1) isDangerZone looked only ONE block down,
# so any jump -- and the bot jumps constantly, for crits and for the rush -- read the whole 5x5 as
# air and returned "danger" on solid flat ground, which is what kept routing the dodge to its
# keep-distance pathing arm; fixed by accepting y-1 OR y-2. (2) suggestedProjectileRotation
# computed for real from the arrow's cached velocity -- perpendicular in the horizontal plane is
# (-vz, vx) -- so the safe-ground arm finally had a direction to run.
#     baseline (pathing dodge)   dmgTaken 20, 13, 16, 20            mean 17.25
#     predictive dodge           dmgTaken 37, 24, 9, 8, 9, 27       mean 19.0
# No better on average, so it was reverted. BUT LOOK AT THE SPLIT: three runs at 8-9 damage, the
# best this course has ever recorded, and three at 24-37. It is bimodal, not noisy-around-a-mean.
#
# THAT SWITCH WAS TESTED AND IT IS NOT THE SIDE. The fixed-side implementation was replaced by one
# that scores BOTH perpendiculars by the ground under them -- four blocks along each, count the
# floor -- and takes the better, on the theory that both dodge equally and only one keeps the bot
# on the island (bow_flee's lesson about cornering on a rim).
#     first six    dmgTaken 39, 0, 16, 11, 5, 8      mean 13.2   <- and a PASS at 29.5 fps
#     next six     dmgTaken 11, 24, 8, 29, 52, 3     mean 21.2
#     all twelve                                     mean 17.2   vs baseline 17.25
# Identical to the baseline. The first block was simply the favourable half, and the n>=12 rule
# this file already carries is what caught it -- at n=6 it looked like the best variant yet.
#
# ⛔ SO THREE DODGE VARIANTS NOW MEASURE THE SAME: pathing keep-distance 17.25, predictive with a
# fixed side 19.0, predictive with the side chosen for ground 17.2. The dodge geometry is not the
# lever. What HAS been established is that removing the dodge doubles the damage (32.8), so it is
# load-bearing, and that a single run can reach 0 -- twice now -- so nothing structural forbids it.
# The next hypothesis should not be a fourth way to choose a direction.
#
# So neither dodging more nor dodging less reaches the criterion, and the only option left is the
# one the course was written for: AVOID the arrow rather than survive it. That is the safe-ground
# arm — LookHelper.lookAt(suggestedProjectileRotation) then sprint-jump perpendicular — which is
# dead because suggestedProjectileRotation is assigned only inside onPlayerItemUse, wrapped in
# `if (false && ...)`. Implementing a predictive dodge there is the remaining work, and it is a
# feature, not a tweak. Judge on exact dmgTaken, not min_hp, at n>=6 an arm; this course spans
# 4 to 65.


class SkeletonDodge(MobMelee):
    """One skeleton. The arrow has to be dodged BEFORE it lands, not survived after.

    The user's harder criterion: work out the arrow's flight and step off the line in advance.
    Winning while being shot is not what is being asked for, so damage taken is the gate here as
    well -- with one point of slack, because a skeleton that spawns already drawing can land the
    first arrow before any policy could react, and the test should measure the dodging rather
    than the spawn.
    """

    # ⛔ HOW BIG AN ARM THIS COURSE NEEDS, COMPUTED RATHER THAN GUESSED (2026-08-12).
    # Arrows landed = (20 - min_hp)/4. Pooling the repaired course's baseline with the fixed build,
    # n=14: mean 1.46, sd 1.20. For a two-arm comparison at 2 sigma, n per arm = 8*sd^2/delta^2:
    #
    #     delta 0.3 arrows -> n >= 128      delta 0.7 -> n >= 24
    #     delta 0.5 arrows -> n >=  46      delta 1.0 -> n >= 12
    #
    # THIS IS WHY SO MANY PASSES HERE "MEASURED NOTHING": at six runs an arm the smallest visible
    # difference is about 1.4 arrows, so every subtle change was unmeasurable by construction and
    # the readings that looked promising were noise.
    #
    # The consolation is that the TARGET effect is large. The gate wants min_hp >= 19, i.e. ZERO
    # arrows, against a mean of 1.46 — so what has to be shown is a ~1.5-arrow move, and that is
    # visible at n=12 an arm. Do not spend runs chasing 0.3; only a big effect can turn this green.
    #
    # ⛔ AND THE SAME-SESSION SPREAD IS FAR TIGHTER, WHICH MAKES PINNED PAIRS CHEAP (2026-08-12).
    # The sd above (1.20) is POOLED ACROSS SERIES. Measured inside a single session, on the twelve
    # runs of a pinned A/B's baseline arm:
    #
    #     same session, flag off   n=12   mean 1.19   sd 0.37
    #     pooled across series     n=14   mean 1.46   sd 1.20
    #
    # Same course, same build — three times the spread, purely from measuring across sessions.
    # That is rule 4j quantified, and it cuts both ways:
    #   * a CROSS-SESSION comparison needs n>=46 an arm to see half an arrow, which is why every
    #     build-against-build result this repo has quoted at 1.4-2.1 sigma later evaporated;
    #   * a PINNED SAME-SESSION pair needs only n>=5 an arm for the same half arrow
    #     (8*0.37^2/0.5^2), so an honest experiment here costs about twenty minutes, not two hours.
    # Run pairs. Never compare a fresh build against yesterday's number.
    #
    # ⛔ THE RULER FOR THIS COURSE, CHARACTERISED AT LAST (checklist 4b step 1, never done here).
    #
    # min_hp over n=53 runs on the current build, pooled across every series:
    #     median 16.0   IQR 12-16   range 4-20   passes (>=19) 9/53 = 17%
    #     values cluster: 20 x9, 16 x16, 12 x11, then 8/7/5/4
    #
    # Those clusters are ARROWS LANDED. A skeleton arrow takes about 4, so 20 -> 0 arrows,
    # 16 -> 1, 12 -> 2, 8 -> 3. The course's honest statistic is therefore a small integer COUNT
    # per run, not a coin flip -- and mean-arrows-landed uses the whole distribution where pass/fail
    # uses only the >=19 tail.
    #
    # WHY THIS MATTERS: five careful hypotheses this session each cost an hour and returned nothing
    # readable, and one was shipped on a favourable 5/13 that the next series contradicted at 1/12.
    # At a 17% pass rate, separating these effects on PASS COUNTS needs n>=30 an arm. On mean arrows
    # landed -- values 0-4, tight clusters -- n=12 resolves a half-arrow shift. Compare on that, and
    # on mdRet2 (ticks under arrow threat, 5-196 a run), NOT on how many runs went green.
    #
    # The gate stays exactly as it is. It answers its own question correctly; it just must not be
    # the number a before/after is judged on (checklist 4i.5).
    id = "mob_skeleton"
    tier = "gate"
    duration = 120

    # ⛔ THIS COURSE NEEDS A WIDER ISLAND THAN THE MELEE ONE, AND THE INHERITED half=14 WAS
    # CORRUPTING BOTH SIDES OF THE MEASUREMENT.
    #
    # The skeleton spawns at x=12.5 and the inherited rim sits at 14 -- one and a half blocks
    # behind it -- on a platform floating over the void. Measured on four runs once the sampler
    # was fixed, the skeleton's last known position was x=13.7 in EVERY run where it was seen at
    # all: a bow mob backs away from an approaching enemy, and here "away" ends at the edge.
    #
    # Two separate corruptions follow, and they push the score in OPPOSITE directions:
    #
    #  1. THE SKELETON LEAVES INSTEAD OF LOSING. Landed swings against the verdict, same series:
    #         FAIL min_hp=13   3 landed        FAIL min_hp=12   3 landed
    #         PASS min_hp=20   2 landed        FAIL min_hp=20   1 landed, never observed alive
    #     A skeleton has 20 HP and does not die to one swing. The runs scoring a PERFECT min_hp
    #     are the runs where the bot barely touched it -- it went over the edge, and a skeleton
    #     in freefall cannot shoot. The course's own pass condition was being met by the target's
    #     removal, which is the opposite of what it claims to measure.
    #
    #  2. THE BOT FIGHTS THE RIM, NOT THE ARCHER. WorldHelper.isDangerZone is true wherever fewer
    #     than five of the twenty-five blocks under a 5x5 around the feet are solid, so it is true
    #     along the whole edge. In the aim stats, `reposition` is fed by `danger` in every run:
    #         danger = 218 / 98 / 83 / 76
    #     The bot's movement here is dominated by edge avoidance, correctly, and any dodge policy
    #     measured on this course was being scored through that.
    #
    # So the field is widened for THIS course only -- the approach stays exactly 12 blocks and the
    # kit, spawn, timings and gates are all untouched -- purely so the fight happens in open ground
    # instead of against a cliff. mob_melee and mob_trio keep half=14 deliberately: they are melee
    # courses whose numbers are already characterised at that size.
    #
    # ⛔ RULE 4j APPLIES TO THIS CHANGE: it alters the course, so every number recorded on
    # mob_skeleton BEFORE this line -- including the 17% pass rate and the 53-run ruler above --
    # is measured on a different course and must NOT be compared against runs after it.
    ARENA_HALF = 30

    def build(self, arena, ctx):
        arena.flat_field(half=self.ARENA_HALF, grass=False)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 -90 0"
        ctx.geo["fps"] = []

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set midnight")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd("difficulty normal")
        ctx.rcon.cmd("kill @e[type=skeleton]")
        # NO HEALTH TOP-UP HERE, AND THE REASON IS WORTH KEEPING. One was added on the theory that
        # this course inherits health from the previous run -- mob_trio carries exactly that line.
        # It is REDUNDANT: Actor normalisation already runs `effect clear` + instant_health before
        # every course (actors.py:176), so the bot starts whole regardless.
        #
        # What actually produced the puzzle: dmgTaken=0.0 alongside min_hp=16.0 in the same run.
        # That is not inherited health, it is DamageWatch UNDERCOUNTING -- the checklist already
        # records that its total does not reconcile and is only trustworthy as a ratio between two
        # sides measured the same way (rule 4i.4). So min_hp is the HONEST damage figure on this
        # course and dmgTaken is not, which is the opposite of what the criterion comment below
        # assumes. Do not re-gate this course on dmgTaken.
        ctx.bot.py.try_call("resetRunCounters")
        # Far enough that it shoots rather than melees -- the point is the arrow.
        #
        # ⛔ SPAWNED INERT, AND THIS IS CHECKLIST RULE TWO. The skeleton used to be summoned live,
        # followed by sleep(2) before the task was issued -- and an IDLE BOT TICKS NO CHAINS
        # (TaskRunner.tick opens with `if (!active) return;`, and `active` stays false until a user
        # task starts). So for two seconds a live skeleton shot at a bot whose mob defence, dodge
        # and force field were all switched off. A skeleton fires about every two seconds, so the
        # course was handing over almost exactly one free arrow that no policy could ever answer.
        #
        # The evidence it was doing so: on the runs that take a single hit, DamageWatch puts that
        # hit at gap 9.48 / 9.37 / 9.20 / 9.13 -- clustered at the very start of a twelve-block
        # approach, which is where an arrow launched during the dead window arrives.
        #
        # NoAI parks it for the tracker's benefit (the tracker lags a summon by about a second,
        # which is what the sleep was for) and it is switched on the moment the fight is ordered.
        # The bot still has to cross twelve blocks under fire -- nothing about the difficulty of
        # the course changes, only the two seconds where nothing of the bot was running.
        # ⛔ AND THE BOW MUST BE GIVEN BY HAND ONCE ANY NBT IS SUPPLIED. SummonCommand only calls
        # MobEntity.initialize() when the summon carries NO nbt, and initialize() is what equips a
        # skeleton. So `summon skeleton {NoAI:1b}` produces an UNARMED skeleton -- measured:
        #     plain summon        equipment: {mainhand: {id: "minecraft:bow", ...}}
        #     with {NoAI:1b}      Found no elements matching equipment
        # That is how this course briefly went 6/6 at min_hp=20 against something that could not
        # shoot. The "actually fought back" gate below now makes that failure loud instead of green.
        # ⛔ PersistenceRequired STOPS THE TARGET FROM SIMPLY LEAVING.
        # "The skeleton is dead" and early_stop both test one thing: has the entity stopped
        # existing. Vanilla gives a hostile mob two ways to do that WITHOUT losing a fight -- the
        # void, and DESPAWNING beyond 32 blocks from the player. Widening this arena removed the
        # first and made the second MORE likely, because the skeleton now has room to keep backing
        # away (last seen at x=20.5 in one run, from a spawn at 12.5). The signature is a run that
        # scores perfectly while the bot barely fought: min_hp=20 with only TWO swings landing,
        # against a 20 HP target that does not die to two. PersistenceRequired:1b removes the
        # despawn path outright, so a skeleton that is gone was killed.
        ctx.rcon.cmd(f"summon skeleton 12.5 {STAND_Y} 0.5 "
                     '{NoAI:1b,PersistenceRequired:1b,'
                     'equipment:{mainhand:{id:"minecraft:bow",count:1}}}')
        time.sleep(2)
        # `@test kill` targets ZOMBIES only, so it started nothing here and the bot stood and
        # was shot with every defence-chain counter at zero. This one takes the nearest hostile.
        ctx.bot.cmd("@test killhostile")
        ctx.rcon.cmd("data merge entity @e[type=skeleton,limit=1] {NoAI:0b}",
                     allow_reject=True)
        # PROVE THE AI CAME BACK ON. A false green already happened here once: the skeleton was
        # summoned inert, the re-enable silently did not take, and six runs passed at min_hp=20
        # against a statue (dw=0/0/0/0/0/0, dodgeDrive=0). NoAI reads back ABSENT when false --
        # Minecraft omits default values -- so "no elements matching" is the healthy answer.
        ctx.geo["noai_after"] = str(ctx.rcon.cmd(
            "data get entity @e[type=skeleton,limit=1] NoAI", allow_reject=True))
        ctx.geo["equip_after"] = str(ctx.rcon.cmd(
            "data get entity @e[type=skeleton,limit=1] equipment", allow_reject=True))
        # Read the despawn guard back too. Unlike NoAI, this one is only healthy when PRESENT --
        # 1b is not the default, so it survives the readback as an explicit value.
        ctx.geo["persist_after"] = str(ctx.rcon.cmd(
            "data get entity @e[type=skeleton,limit=1] PersistenceRequired", allow_reject=True))

    def _watch_skeleton(self, ctx):
        """Record where the skeleton is. Returns its position, or None if it could not be read.

        ⛔ THIS LIVES OFF early_stop's POLL, NOT off drive_tick's two-second cadence.
        The first version of this sampler hung off `drive_tick` behind `int(elapsed) % 2`, and it
        produced NOTHING: every run printed `closest_gap=None`, and the void check below read
        `last_seen=None min_y=None`. These runs early-stop as soon as the skeleton dies -- `fps
        samples=1` -- so a two-second sampler frequently never fires at all. An instrument that
        silently collects no data is worse than no instrument: `closest_gap=None` was read for
        several sessions as "the bot never gets close" when it actually meant "never measured".
        early_stop polls continuously for as long as the skeleton exists, so hanging the sample
        there gives a fresh last-known position right up to the tick it vanishes.
        """
        out = ctx.rcon.cmd("execute in minecraft:overworld run data get entity "
                           "@e[type=skeleton,limit=1] Pos", allow_reject=True)
        m = re.search(r"\[([-0-9.]+)d, ([-0-9.]+)d, ([-0-9.]+)d\]", str(out or ""))
        if not m:
            return None
        sx, sy, sz = float(m.group(1)), float(m.group(2)), float(m.group(3))
        ctx.geo["skel_last"] = [round(sx, 1), round(sy, 1), round(sz, 1)]
        ctx.geo["skel_polls"] = ctx.geo.get("skel_polls", 0) + 1
        # ⛔ AND ITS HEALTH, BECAUSE "GONE" STILL DOES NOT SAY "KILLED".
        # Three runs have now ended with the skeleton unobserved and the bot untouched while only
        # one or two swings landed -- against a 20 HP target that does not die to that. Persistence
        # closed the despawn path and the wider field closed the void one, so something else ends
        # those runs. The last health reading separates the cases without any more guessing: a
        # skeleton last seen at 6 HP was being killed, one last seen at 20 was not.
        hp = ctx.rcon.cmd("execute in minecraft:overworld run data get entity "
                          "@e[type=skeleton,limit=1] Health", allow_reject=True)
        mh = re.search(r"([0-9.]+)f?\s*$", str(hp or "").strip())
        if mh:
            try:
                ctx.geo["skel_last_hp"] = float(mh.group(1))
            except ValueError:
                pass
        lo = ctx.geo.get("skel_min_y")
        if lo is None or sy < lo:
            ctx.geo["skel_min_y"] = sy
        return (sx, sy, sz)

    def drive_tick(self, ctx, elapsed):
        super().drive_tick(ctx, elapsed)
        # DOES THE BOT EVER CLOSE? dte says inRange was false on all 1948 evaluations, which splits
        # into two completely different faults: the approach never moves the body, or it does and the
        # reach test is wrong. Sampling the actual gap answers it without touching any code.
        pos = self._watch_skeleton(ctx)
        s_ = ctx.samples[-1] if ctx.samples else None
        b = s_.get("bot") if s_ else None
        if not pos or not b:
            return
        sx, sy, sz = pos
        d = ((b[0] - sx) ** 2 + (b[1] - sy) ** 2 + (b[2] - sz) ** 2) ** 0.5
        best = ctx.geo.get("min_gap")
        if best is None or d < best:
            ctx.geo["min_gap"] = d
        # (Where it was standing is recorded by _watch_skeleton above, for the void gate in judge.)

    def early_stop(self, ctx):
        gone = "Count:" not in ctx.rcon.cmd("execute if entity @e[type=skeleton]",
                                            allow_reject=True)
        # Sample while it still exists: the poll immediately before it vanishes is the evidence
        # that decides whether it was killed on the arena or fell off the island.
        if not gone:
            self._watch_skeleton(ctx)
        return gone

    def judge(self, ctx):
        alive = "Count:" in ctx.rcon.cmd("execute if entity @e[type=skeleton]", allow_reject=True)
        ticks = _tung_ticks(ctx)
        hps = [s["bot_hp"] for s in ctx.samples if s.get("bot_hp") is not None]
        low = min(hps) if hps else None

        yield Criterion("the skeleton is dead", not alive, f"alive={alive}")
        # ⛔ "DEAD" AND "FELL OFF THE ISLAND" ARE THE SAME OBSERVATION, AND ONLY ONE OF THEM COUNTS.
        # The gate above and early_stop both ask a single question -- has the entity stopped
        # existing -- and the void answers it just as well as the bot's sword does. See drive_tick:
        # the skeleton spawns 1.5 blocks from the rim of a floating platform and backs away from an
        # approaching enemy. A skeleton that walked off scores this course GREEN at min_hp=20,
        # because a skeleton in freefall does not shoot. That is a fight that never happened being
        # recorded as the best possible outcome, so it is GATED rather than merely noted.
        # ⛔ AND IT DEMANDS POSITIVE EVIDENCE -- "no data" IS NOT A PASS.
        # Written the obvious way (`not fell`) this criterion could not fail: the first version
        # sampled from drive_tick, collected nothing on these short runs, and passed four runs out
        # of four on `last_seen=None`. That is the same shape as the dead `awake` half fixed
        # earlier in this file -- a gate that reports green because it never looked. So the test is
        # "it was seen alive ON the arena", which a missing sample fails, loudly and correctly.
        edge = float(self.ARENA_HALF)
        last = ctx.geo.get("skel_last")
        floor_y = ctx.geo.get("skel_min_y")
        polls = ctx.geo.get("skel_polls", 0)
        fell = (floor_y is not None and floor_y < STAND_Y - 3.0) or (
            last is not None and (abs(last[0]) > edge or abs(last[2]) > edge))
        yield Criterion("the skeleton died on the arena, not in the void",
                        last is not None and not fell,
                        f"last_seen={last} last_hp={ctx.geo.get('skel_last_hp')} "
                        f"min_y={floor_y} polls={polls} floor={STAND_Y} edge=+-{edge}")
        # ⛔ THE COURSE MUST NOT PASS AGAINST A STATUE. See drive_start: an inert skeleton once
        # gave six straight passes at min_hp=20. If nothing was ever shot at us, this course
        # measured nothing, so it is GATED rather than recorded.
        # KILL SPEED IS THE REMAINING LEVER AND IT WAS UNMEASURED HERE. mob_trio prints this line
        # and mob_skeleton never did, so whether crits land on this course -- which decides a
        # 4-swing kill against a 3-swing one, and therefore whether the skeleton gets a shot off
        # during the melee -- was pure guesswork.
        ok_gs, gs = ctx.bot.py.try_call("gateStats")
        yield Criterion("swing gates (recorded, not gated)", True,
                        str(gs) if ok_gs and gs else "unreadable", gate=False)
        # ⛔ THIS GATE ASKS WHETHER THE SKELETON *COULD* FIGHT, NOT WHETHER IT LANDED A HIT.
        # The first version tested dw hits > 0 and therefore FAILED THE PERFECT RUN: a run that
        # took min_hp=20.0 with dw=0/0/0/0/0/0 -- nothing landed on the bot at all -- was marked
        # red by the very gate meant to catch a defenceless target. A criterion that punishes the
        # outcome the course exists to reward is worse than no criterion.
        # Armed-and-awake is the honest test of the statue bug, and it is read from the server.
        # ⛔ COULD THE BOT HAVE KILLED IT AT ALL? ARITHMETIC, NOT INFERENCE.
        # KIT_SWORD is an iron sword: 6 damage, 9 on a crit. A skeleton has 20 HP, so the kill
        # needs FOUR plain hits, or three with a crit among them. That makes "the target is gone"
        # checkable against what the bot actually landed — which is how the void and despawn false
        # greens were caught (a PASS with two landed swings is 12 damage against a 20 HP mob).
        # RECORDED, NOT GATED: Minecraft scales damage by swing charge, chargeMean runs 0.95-1.00
        # here, and the force field can contribute — so this is an estimate with a soft edge. A
        # hard threshold on a soft number would manufacture red runs, which is the opposite of
        # the point. It is here to make an impossible kill VISIBLE, not to fail the run.
        sw_txt = str(gs) if ok_gs and gs else ""
        m_ok = re.search(r"passed=(\d+)", sw_txt)
        if m_ok:
            m_cr = re.search(r"crits=(\d+)", sw_txt)
            landed = int(m_ok.group(1))
            crits = int(m_cr.group(1)) if m_cr else 0
            dealt = max(0, landed - crits) * 6 + crits * 9
            yield Criterion("damage the bot can account for (recorded, not gated)", True,
                            f"landed={landed} crits={crits} => ~{dealt} of 20 HP"
                            + (" — SHORT, something else finished it" if dealt < 20 else ""),
                            gate=False)
        armed = "bow" in str(ctx.geo.get("equip_after", ""))
        # ⛔ THIS TEST USED TO BE UNABLE TO FAIL, WHICH IS WORSE THAN NOT HAVING IT.
        # It read: "NoAI" not in readback or "no elements" in readback.
        #   healthy -> "Found no elements matching NoAI" -> contains "NoAI", clause 1 False,
        #              clause 2 True  -> passes. Right, but by luck.
        #   BROKEN  -> "<name> has the following entity data: 1b" -> no "NoAI" anywhere,
        #              clause 1 True   -> ALSO passes. Wrong: it passes exactly when it should fail.
        # Minecraft omits default values, so NoAI ABSENT is the healthy answer and the only thing
        # worth testing. Anything else -- including the tag coming back set -- is a statue.
        awake = "no elements" in str(ctx.geo.get("noai_after", ""))
        # And it must be unable to walk out of the test: see the summon. "1b" present is healthy,
        # "no elements" means the flag did not take and a disappearance proves nothing.
        persistent = "1b" in str(ctx.geo.get("persist_after", ""))
        yield Criterion("the skeleton was armed, awake and could not despawn",
                        armed and awake and persistent,
                        f"equip={ctx.geo.get('equip_after')} noai={ctx.geo.get('noai_after')} "
                        f"persist={ctx.geo.get('persist_after')}")
        yield Criterion("reached striking distance (tungsten took the legs)", ticks > 0,
                        f"mdTung total={ticks} split={_stat(ctx, 'mdTung')} "
                        f"ctl={_stat(ctx, 'ctl')} cq={_stat(ctx, 'cq')} mdFar={_stat(ctx, 'mdFar')} "
                        # WHERE IT SHOOTS FROM (count/mean/max), against dw's where-they-LAND. The
                        # mod has carried this since the release-range instrument went in and the
                        # course did not print it — the same dead-instrument shape this file has
                        # now been bitten by twice. A counter nobody reads does not exist.
                        f"arrows={_stat(ctx, 'arrows')} draws={_stat(ctx, 'draws')} "
                        f"band={_stat(ctx, 'band')} dodgeTask={_stat(ctx, 'dodgeTask')} "
                        f"dealt={_stat(ctx, 'dealt')} swingHits={_stat(ctx, 'swingHits')} dodgeDrive={_stat(ctx, 'dodgeDrive')} hop={_stat(ctx, 'hop')}")
        # ⛔ THIS LABEL AND THIS THRESHOLD DISAGREE, AND THE THRESHOLD IS THE STRICTER ONE.
        # min_hp >= 19.0 permits ONE point of damage. A skeleton arrow on normal difficulty does
        # 2-5, so the arithmetic demands "no arrow ever landed" while the label says "at most one".
        # The label is unreachable as written, and dw.rangedHits below already counts the exact
        # thing the label describes.
        #
        # LEFT ALONE DELIBERATELY, with the numbers that decided it. Gating on rangedHits <= 1
        # instead would pass 7 of these 13 runs -- it turns a red gate into a coin:
        #     rangedHits   1 3 0 4 0 1 1 2 1 0 2 2 4
        #     min_hp      12 8 17 4 17 13 7 2 15 12 5 12 4
        # Relaxing a gate to a coin in the same pass that is trying to move that gate is
        # indistinguishable from tuning to pass, however good the justification, so the decision
        # belongs to a pass that is not holding the result. What IS established: the course's
        # other two criteria (the skeleton dies, tungsten takes the legs) now pass in every run,
        # so min_hp is the only thing keeping it red.
        # ⛔ THE ENGAGE BAND, MEASURED INTERLEAVED: +0.79 ARROWS AT 1.70 SIGMA (2026-08-12).
        # Ten runs, arms A,B,A,B so a drifting stand cannot masquerade as an effect; three dropped by
        # the validity floor. Flag off: n=3, mean 1.67 arrows. Flag on: n=4, mean 0.88. The rule in
        # ab_arrows.py, written before the data existed, calls anything under 2 sigma no effect -- so
        # the flag stays off.
        #
        # But the arm-size arithmetic above says an effect of 0.7-1.0 arrows needs 12-24 runs an arm,
        # and this had three and four. That is not evidence of no effect, it is a measurement too
        # small to have an opinion, and the prescribed answer is more runs rather than a verdict.
        #
        # A blocked pair earlier read the same direction (1.96 -> 1.13, ~1.6 sigma). Combining the
        # two would clear 2 sigma arithmetically. Deliberately NOT done: blocked arms in this repo
        # produced a 3.18 sigma artefact that interleaving cut to 0.46, so pooling a trustworthy
        # estimate with an untrusted one imports the bias the interleaving exists to remove.
        #
        # ⛔ WHERE 19.0 CAME FROM: THE COURSE'S OWN DOCSTRING, MISCOUNTED (2026-08-12).
        # The class docstring states the design intent in words -- "with one point of slack, because
        # a skeleton that spawns already drawing can land the first arrow before any policy could
        # react". The slack was meant to cover ONE ARROW. A skeleton arrow does 2-5 damage, so one
        # arrow of slack is min_hp >= 15; written as 19.0 it grants one HP and forbids the very shot
        # the docstring calls unavoidable. Label, docstring and threshold all disagree, and only the
        # threshold is enforced.
        #
        # THE PHYSICS AGREES WITH THE DOCSTRING. dw records the gap at the moment each hit lands:
        # 3.38 and 4.67 blocks. An arrow travels ~2.65 blocks per tick, so it is in flight for 1.3
        # to 1.8 TICKS at that range. No policy dodges that, and mobs aim at release so there is
        # nothing to pre-empt either. Zero arrows is not a movement problem, it is arithmetic.
        #
        # AND CORRECTING IT WOULD NOT BUY A GREEN, which is what separates this from tuning to pass.
        # On the thirteen runs recorded below, min_hp >= 15 passes THREE (17, 17, 15). The course
        # stays red and the gap the bot has to close stays exactly where it was. Held back anyway
        # while an engage-band A/B was in flight: changing a criterion while holding its result is
        # the one thing that would make the correction unbelievable, whatever the justification.
        yield Criterion("at most one arrow landed", low is not None and low >= 19.0,
                        f"min_hp={low}")
        # ⛔ THE INSTRUMENT THAT SEPARATES THE TWO CANDIDATES, AND IT ALREADY EXISTED.
        # "The dodge is too weak" and "the dodge is too eager" predict opposite fixes and the same
        # min_hp, so min_hp cannot choose between them. DamageWatch has recorded the answer since
        # it was written and no mob course has ever read it: dw = hits/damage/gapMean/gapMax/
        # rangedHits/deathsSeen, where the gap is the centre-to-centre distance to the nearest
        # living entity AT THE MOMENT each hit landed, and rangedHits counts the ones that landed
        # from beyond melee reach.
        #
        # So: arrows landing at gapMean 8-12 means they land during the approach, and the dodge is
        # spending the bot's time under fire without clearing the line -- dodge LESS, close faster.
        # Arrows landing at gapMean 2-4 means they land after arrival, which is a different fix
        # entirely. exact dmgTaken is printed beside it because min_hp is a leftover, not a count.
        yield Criterion("where the hits landed (recorded, not gated)", True,
                        f"dw={_stat(ctx, 'dw')} dwNoBlame={_stat(ctx, 'dwNoBlame')} dmgTaken={_stat(ctx, 'dmgTaken')}", gate=False)
        # WHICH BRANCH ATE THE TICKS? mdTung=0 says no fight was ever committed, and the chain has
        # several branches that return early -- flee, creeper, shield, dodge-projectiles -- each of
        # which starves everything below it while it holds priority. mdRet is the per-branch return
        # tally (mdRet0=flee, mdRet2=dodge-projectiles), so this names the culprit instead of
        # inviting another guess. Recorded, never a gate.
        yield Criterion("chain return paths (recorded, not gated)", True,
                        f"mdRet={_stat(ctx, 'mdRet')} mdFlee={_stat(ctx, 'mdFlee')} "
                        f"mdFight={_stat(ctx, 'mdFight')} mdCalls={_stat(ctx, 'mdCalls')} "
                        # dte = the five conditions guarding the ENGAGE gate in
                        # AbstractDoToEntityTask: gate/inRange/hungry/falling/mlg/unsafe. mdRet6 says
                        # the chain commits to the fight; these say why the swing never happens.
                        f"dte={_stat(ctx, 'dte')} kaTung={_stat(ctx, 'kaTung')} "
                        f"closest_gap={ctx.geo.get('min_gap')}",
                        gate=False)


class MobWeaponFromPack(MobMelee):
    """The sword is in the PACK and the hand is EMPTY. Does the bot arm itself?

    WHY NO EXISTING COURSE COULD ASK THIS. Every pvp and mob course kits the bot with
    `item replace entity {name} weapon.mainhand with iron_sword` -- the weapon starts already
    equipped. So the whole "choose a weapon and put it in the hand" path was invisible to the
    entire bench, and it could be, and was, completely dead on 1.21.11 without a single course
    noticing: `KillAura.equipWeapon`'s body sat inside a `//#if MC < 12111` branch and
    `bestWeapon` returned whatever was already in the hand rather than scanning the pack.

    THE GATE IS AN INVENTORY OUTCOME, NOT A TIMING ONE, and that is deliberate. This stand runs
    at 7-9 fps against a floor of 14, where fight outcomes are a coin toss (two full pvp suites
    on a bit-identical jar disagreed on three courses). "Is the sword in the hand" does not care
    how many frames the client managed -- it is the same shape of bar as the craft ladder's
    "the item is in the pack", and it stays readable on a starved host.
    """

    id = "mob_weapon_swap"
    tier = "gate"
    duration = 60
    # Empty hand, sword out of reach of the hotbar: it has to be FOUND, not just selected.
    bot_kit = ["item replace entity {name} weapon.mainhand with air",
               "item replace entity {name} inventory.0 with iron_sword"]

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set midnight")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd("difficulty normal")
        ctx.rcon.cmd("kill @e[type=zombie]")
        # Wipe first: the stand does not reset the world between runs, and a sword left in the
        # hotbar by the PREVIOUS course would make this one pass without the bot doing anything.
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        time.sleep(1)
        for line in self.bot_kit:
            ctx.rcon.cmd(line.format(name=ctx.bot.name), allow_reject=True)
        time.sleep(1)
        ctx.geo["held_at_start"] = ctx.rcon.held_item(ctx.bot.name)
        ctx.geo["held_seen"] = []
        ctx.bot.py.try_call("resetRunCounters")
        ctx.rcon.cmd(f"summon zombie 4.5 {STAND_Y} 0.5")
        ctx.geo["spawned"] = _zombie_count(ctx)
        time.sleep(2)
        ctx.bot.cmd("@test kill")

    def drive_tick(self, ctx, elapsed):
        held = ctx.rcon.held_item(ctx.bot.name)
        if held and held not in ctx.geo["held_seen"]:
            ctx.geo["held_seen"].append(held)

    def judge(self, ctx):
        seen = ctx.geo.get("held_seen") or []
        started_empty = ctx.geo.get("held_at_start") is None
        armed = any("sword" in h for h in seen)

        # THE COURSE MUST NOT LIE TO ITSELF. If the kit failed and the bot began holding the
        # sword, "it armed itself" would be true without the bot having done anything -- the
        # exact shape of false green this bench has been bitten by twice.
        yield Criterion("the bot started EMPTY-HANDED", started_empty,
                        f"held_at_start={ctx.geo.get('held_at_start')}")
        yield Criterion("the bot armed itself from the pack", armed,
                        f"held_during_run={seen}")
        yield Criterion("the zombie is dead", _zombie_count(ctx) == 0,
                        f"remaining={_zombie_count(ctx)}")


# The registry instantiates each entry itself (run_suite: `scn = cls()`), so export the CLASS.
SCENARIOS = [MobMelee, MobTrioNoDamage, SkeletonDodge, MobWeaponFromPack]
