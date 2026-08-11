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
        yield Criterion("reached striking distance (tungsten took the legs)", ticks > 0,
                        f"mdTung total={ticks}")
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
                        f"mdTung total={ticks}")
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
# WHICH BRANCH DOES IT. This arena is flat_field(half=14) and the skeleton spawns at 12.5, so the
# fight happens NEAR THE RIM — and WorldHelper.isDangerZone returns true wherever fewer than five
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

    id = "mob_skeleton"
    tier = "gate"
    duration = 120

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set midnight")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd("difficulty normal")
        ctx.rcon.cmd("kill @e[type=skeleton]")
        ctx.bot.py.try_call("resetRunCounters")
        # Far enough that it shoots rather than melees -- the point is the arrow.
        ctx.rcon.cmd(f"summon skeleton 12.5 {STAND_Y} 0.5")
        time.sleep(2)
        # `@test kill` targets ZOMBIES only, so it started nothing here and the bot stood and
        # was shot with every defence-chain counter at zero. This one takes the nearest hostile.
        ctx.bot.cmd("@test killhostile")

    def drive_tick(self, ctx, elapsed):
        super().drive_tick(ctx, elapsed)
        # DOES THE BOT EVER CLOSE? dte says inRange was false on all 1948 evaluations, which splits
        # into two completely different faults: the approach never moves the body, or it does and the
        # reach test is wrong. Sampling the actual gap answers it without touching any code.
        if int(elapsed) % 2 != 0:
            return
        out = ctx.rcon.cmd("execute in minecraft:overworld run data get entity "
                           "@e[type=skeleton,limit=1] Pos", allow_reject=True)
        m = re.search(r"\[([-0-9.]+)d, ([-0-9.]+)d, ([-0-9.]+)d\]", str(out or ""))
        s_ = ctx.samples[-1] if ctx.samples else None
        b = s_.get("bot") if s_ else None
        if not m or not b:
            return
        sx, sy, sz = float(m.group(1)), float(m.group(2)), float(m.group(3))
        d = ((b[0] - sx) ** 2 + (b[1] - sy) ** 2 + (b[2] - sz) ** 2) ** 0.5
        best = ctx.geo.get("min_gap")
        if best is None or d < best:
            ctx.geo["min_gap"] = d

    def early_stop(self, ctx):
        return "Count:" not in ctx.rcon.cmd("execute if entity @e[type=skeleton]",
                                            allow_reject=True)

    def judge(self, ctx):
        alive = "Count:" in ctx.rcon.cmd("execute if entity @e[type=skeleton]", allow_reject=True)
        ticks = _tung_ticks(ctx)
        hps = [s["bot_hp"] for s in ctx.samples if s.get("bot_hp") is not None]
        low = min(hps) if hps else None

        yield Criterion("the skeleton is dead", not alive, f"alive={alive}")
        yield Criterion("reached striking distance (tungsten took the legs)", ticks > 0,
                        f"mdTung total={ticks}")
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
                        f"dw={_stat(ctx, 'dw')} dmgTaken={_stat(ctx, 'dmgTaken')}", gate=False)
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
