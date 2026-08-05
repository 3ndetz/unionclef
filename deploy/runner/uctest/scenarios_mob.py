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
        yield Criterion("the fight ran on tungsten", ticks > 0, f"mdTung total={ticks}")
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
        yield Criterion("the fight ran on tungsten", ticks > 0, f"mdTung total={ticks}")
        # THE CRITERION. Not "survived", not "mostly fine" -- untouched.
        yield Criterion("the bot took ZERO damage", low is not None and low >= 20.0,
                        f"min_hp={low}")
        # The mod counts damage EVERY TICK; the sampled figure is kept beside it so the gap
        # between them stays visible (5 samples caught 3 points of a 9-point loss).
        exact = _stat(ctx, "dmgTaken")
        yield Criterion("damage taken (recorded, not gated)", True,
                        f"exact={exact} sampled_drop={round(drops, 1)} min_hp={low}", gate=False)
        yield Criterion("fight duration (recorded, not gated)", True,
                        f"{duration}s over {len(ctx.samples)} samples", gate=False)


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
        ctx.bot.cmd("@test kill")

    def early_stop(self, ctx):
        return "Count:" not in ctx.rcon.cmd("execute if entity @e[type=skeleton]",
                                            allow_reject=True)

    def judge(self, ctx):
        alive = "Count:" in ctx.rcon.cmd("execute if entity @e[type=skeleton]", allow_reject=True)
        ticks = _tung_ticks(ctx)
        hps = [s["bot_hp"] for s in ctx.samples if s.get("bot_hp") is not None]
        low = min(hps) if hps else None

        yield Criterion("the skeleton is dead", not alive, f"alive={alive}")
        yield Criterion("the fight ran on tungsten", ticks > 0, f"mdTung total={ticks}")
        yield Criterion("at most one arrow landed", low is not None and low >= 19.0,
                        f"min_hp={low}")


# The registry instantiates each entry itself (run_suite: `scn = cls()`), so export the CLASS.
SCENARIOS = [MobMelee, MobTrioNoDamage, SkeletonDodge]
