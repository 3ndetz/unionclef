"""uctest craft suite — can the bot turn logs into a thing, on a cheap world?

WHY THIS EXISTS. Crafting was only ever measured through `@gamer`, and @gamer is an expensive
instrument for it: the survival world holds 17-19 fps on a healthy machine and 8-10 on a busy one,
and a window is ten minutes. So a one-line question -- "does the grid guard let a craft finish" --
cost ten minutes and, on a loaded machine, could not be answered at all.

Crafting needs no survival world. On the flat arena the client holds 35-43 fps even while another
project is hammering this box, so these courses answer in seconds under any load.

WHAT THEY GATE. Holding the item. Not "the task ran", not "the screen opened" -- the rung is the
item in the pack, the same bar the playthrough ladder uses.
"""
import time

from .arena import STAND_Y
from .scenario import Criterion, Scenario


def _count(ctx, needle):
    """How many of anything whose id contains `needle` are in the pack."""
    ok, inv = ctx.bot.py.try_call("getInventoryFull")
    if not ok or not inv:
        return 0
    total = 0
    try:
        for slot in dict(inv).get("slots") or []:
            sd = dict(slot)
            if sd.get("empty"):
                continue
            if needle in str(sd.get("item") or sd.get("name") or ""):
                total += int(sd.get("count", 0) or 0)
    except (TypeError, ValueError):
        return 0
    return total


def _has(ctx, needle):
    """Is anything whose id contains `needle` in the pack?"""
    ok, inv = ctx.bot.py.try_call("getInventoryFull")
    if not ok or not inv:
        return False
    try:
        for slot in dict(inv).get("slots") or []:
            sd = dict(slot)
            if sd.get("empty"):
                continue
            if needle in str(sd.get("item") or sd.get("name") or ""):
                return True
    except (TypeError, ValueError):
        return False
    return False


class CraftTable(Scenario):
    """Logs in the pack, a crafting table out of them.

    The whole of the second rung in one question, and the exact path the playthrough was looping
    on: planks from logs in the 2x2 grid, then a table from planks. If the grid guard clears
    ingredients another task placed, this never finishes.
    """

    id = "craft_table"
    tier = "gate"
    needs_victim = False
    duration = 90
    bot_kit = ["give {name} oak_log 16"]

    def build(self, arena, ctx):
        arena.flat_field(half=6, grass=False)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 -90 0"

    # A ONE-BLOCK FLOOR OVER THE VOID IS NOT A WORLD, IT IS A TRAP FOR THE MEASUREMENT.
    # mine_stone failed for a reason that turned out to be the arena's: the stand carves everything
    # under the floor to air, so breaking a floor block leaves a hole with the void beneath it. The
    # cobblestone drops INTO that hole and hangs there -- traced from the tracker itself:
    #   ETITEM cobblestone pos=-0.26,-60.43,-0.59 block=-1,-61,-1 onGround=false self=false d1..d3=false
    # Nothing solid within three blocks, so the tracker's "is it on the ground" test refuses it
    # forever, the pickup never starts and the pack stays empty. That is arguably correct behaviour
    # for an item hanging over a void; it is simply not what this course means to ask.
    # Courses that mine therefore lay several layers, the way any real ground has them.

    def drive_start(self, ctx):
        # Daylight and no monsters: this course is about the inventory, not about surviving.
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        # START WITH AN EMPTY HAND AND AN EMPTY GRID.
        # The stand's world is not wiped between runs, so a previous run's leftovers ride along --
        # and something sitting in the 2x2 before any craft claims it is exactly what starts the
        # clear-the-grid carousel this course is here to measure. Clearing then re-giving makes the
        # start state a fact rather than an inheritance.
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        time.sleep(1)
        for line in self.bot_kit:
            ctx.rcon.cmd(line.format(name=ctx.bot.name), allow_reject=True)
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        ctx.bot.cmd("@get crafting_table")

    def early_stop(self, ctx):
        return _has(ctx, "crafting_table")

    def judge(self, ctx):
        got_planks = _has(ctx, "planks")
        got_table = _has(ctx, "crafting_table")
        yield Criterion("the bot holds a crafting table", got_table,
                        f"table={got_table} planks={got_planks}")
        # RECORDED, NOT GATED: planks are the halfway point, and knowing whether a failure stopped
        # before or after them is the difference between "the grid never filled" and "the table
        # recipe never ran".
        yield Criterion("planks were made along the way", True,
                        f"planks={got_planks}", gate=False)


class CraftWoodPickaxe(CraftTable):
    """One rung further: sticks and planks into a wooden pickaxe.

    Needs the table placed and used, so it exercises the 3x3 path as well as the 2x2 one -- which
    is where the two grids, and the guard that spans them, meet.
    """

    id = "craft_wood_pickaxe"
    duration = 150

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        ctx.bot.cmd("@get wooden_pickaxe")

    def early_stop(self, ctx):
        return _has(ctx, "wooden_pickaxe")

    def judge(self, ctx):
        yield Criterion("the bot holds a wooden pickaxe", _has(ctx, "wooden_pickaxe"),
                        f"table={_has(ctx, 'crafting_table')} sticks={_has(ctx, 'stick')}")


class CraftStonePickaxe(CraftTable):
    """The third rung, with the stone handed over rather than mined.

    Mining is a separate skill and has its own courses; what this asks is whether the crafting chain
    keeps working one level deeper -- logs to planks to sticks, cobble and sticks to a stone pickaxe,
    through the table. The rung above wooden tools on the playthrough ladder.
    """

    id = "craft_stone_pickaxe"
    duration = 180
    bot_kit = ["give {name} oak_log 16", "give {name} cobblestone 16"]

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        time.sleep(1)
        for line in self.bot_kit:
            ctx.rcon.cmd(line.format(name=ctx.bot.name), allow_reject=True)
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        ctx.bot.cmd("@get stone_pickaxe")

    def early_stop(self, ctx):
        return _has(ctx, "stone_pickaxe")

    def judge(self, ctx):
        yield Criterion("the bot holds a stone pickaxe", _has(ctx, "stone_pickaxe"),
                        f"table={_has(ctx, 'crafting_table')} sticks={_has(ctx, 'stick')}")


class MineStone(CraftTable):
    """Gathering, not crafting: a pickaxe and a stone floor, and eight cobblestone asked for.

    The craft courses hand over their materials on purpose, so nothing above tests the step that
    actually feeds them. This does, and it is the cheapest possible version of it: the arena floor
    IS stone, so there is no searching, no travel and no terrain -- just break, pick up, repeat.
    It also exercises DestroyBlockTask under the gates that isPathing now closes while the body is
    walking, which until today could only be checked through a ten-minute survival window.
    """

    id = "mine_stone"
    duration = 120
    bot_kit = ["give {name} wooden_pickaxe 1"]

    def build(self, arena, ctx):
        arena.flat_field(half=6, grass=False)
        # Three more layers under the surface: mining one block still leaves ground below it.
        y = STAND_Y - 1
        ctx.rcon.cmd(f"fill -8 {y - 3} -8 8 {y - 1} 8 minecraft:stone", allow_reject=True)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 -90 0"

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        time.sleep(1)
        for line in self.bot_kit:
            ctx.rcon.cmd(line.format(name=ctx.bot.name), allow_reject=True)
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        ctx.bot.cmd("@get cobblestone 8")

    def early_stop(self, ctx):
        return _count(ctx, "cobblestone") >= 8

    def judge(self, ctx):
        got = _count(ctx, "cobblestone")
        yield Criterion("eight cobblestone in the pack", got >= 8, f"cobblestone={got}")
        yield Criterion("the pickaxe survived (recorded)", True,
                        f"pickaxe={_has(ctx, 'wooden_pickaxe')}", gate=False)


class SmeltIron(CraftTable):
    """The rung above stone: a furnace, fuel and ore in, an iron ingot out.

    Smelting is a whole subsystem the ladder has never reached on a cheap world -- build the
    furnace, put it down, load it, wait, take the result -- and every step of it is inventory and
    container work rather than terrain. Materials are handed over for the same reason the other
    craft courses hand them over: this asks whether the SMELT works, not whether the bot can find
    iron.
    """

    id = "smelt_iron"
    duration = 240
    bot_kit = ["give {name} oak_log 16", "give {name} cobblestone 16",
               "give {name} raw_iron 4", "give {name} coal 8"]

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        time.sleep(1)
        for line in self.bot_kit:
            ctx.rcon.cmd(line.format(name=ctx.bot.name), allow_reject=True)
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        ctx.bot.cmd("@get iron_ingot")

    def early_stop(self, ctx):
        return _has(ctx, "iron_ingot")

    def judge(self, ctx):
        yield Criterion("the bot holds an iron ingot", _has(ctx, "iron_ingot"),
                        f"furnace={_has(ctx, 'furnace')} rawIron={_count(ctx, 'raw_iron')}")


class CraftIronPickaxe(CraftTable):
    """The fifth rung, and the first that needs TWO subsystems to work in sequence.

    Everything below hands over what it needs and asks for one thing. This one cannot be satisfied
    without smelting first -- there is no iron in the kit, only raw iron -- and then crafting the
    result at a table. It is the shortest course that fails if EITHER half breaks, which is the
    whole reason to have it: the rungs below can all pass while the join between them does not.

    On the playthrough ladder this is the step that ends the stone age, so it is also the last
    cheap rung. Everything past it (diamonds, the nether) needs a real world.
    """

    id = "craft_iron_pickaxe"
    duration = 300
    bot_kit = ["give {name} oak_log 16", "give {name} cobblestone 16",
               "give {name} raw_iron 4", "give {name} coal 8"]

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        time.sleep(1)
        for line in self.bot_kit:
            ctx.rcon.cmd(line.format(name=ctx.bot.name), allow_reject=True)
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        ctx.bot.cmd("@get iron_pickaxe")

    def early_stop(self, ctx):
        return _has(ctx, "iron_pickaxe")

    def judge(self, ctx):
        got = _has(ctx, "iron_pickaxe")
        ingots = _count(ctx, "iron_ingot")
        yield Criterion("the bot holds an iron pickaxe", got,
                        f"pickaxe={got} ingots={ingots} rawIron={_count(ctx, 'raw_iron')}")
        # RECORDED, NOT GATED: whether the smelt half finished tells you which side to read when
        # this goes red -- ingots present means the join or the craft, ingots absent means the melt.
        yield Criterion("ingots were smelted along the way", True,
                        f"ingots={ingots}", gate=False)


class WanderRecovery(CraftTable):
    """Can the bot go and LOOK for something that is not in front of it?

    Not a crafting course; it is here because the craft ladder is what found the defect. Every
    "I cannot find it" path in the bot -- 80 call sites -- falls back to TimeoutWanderTask, and on
    1.21.11 that task issued no movement whatsoever: its only instruction went to baritone's
    explore process, which stopped driving the body when tungsten became the default. Nothing on
    the other courses notices, because there is nothing to get lost from on a six-block platform.

    So: ask for wood on a field with no trees. AbstractDoToClosestObjectTask finds nothing, hands
    over to TimeoutWanderTask(true), and the only question that matters is whether the body MOVES.

    The field is deliberately large. The stand carves everything under the floor to air, so on the
    usual six-block platform "wandering" means walking into the void -- the course would measure a
    fall, not a search.
    """

    id = "wander_recovery"
    duration = 150
    bot_kit = []

    def build(self, arena, ctx):
        arena.flat_field(half=45, grass=False)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 -90 0"

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        self._start = ctx.bot.pos()
        ctx.bot.cmd("@get oak_log")

    def early_stop(self, ctx):
        return self._wander_moved(ctx) > 15

    def _wander_moved(self, ctx):
        """Blocks covered on ticks where TimeoutWanderTask was the task running.

        Not the same question as "how far did the bot get", which the first version of this course
        asked and the A/B threw out: the unfixed build travelled FURTHER (38.0 against 26.8) because
        the search's own approach and the shimmy walk the body regardless. This number can only be
        raised by the wander task itself, so a build where wandering issues no movement reads ~0.
        """
        ok, stats = ctx.bot.py.try_call("placeStats")
        if not ok or not stats:
            return 0.0
        for part in str(stats).split():
            if part.startswith("wanderMoved="):
                try:
                    return int(part.split("=", 1)[1]) / 100.0
                except ValueError:
                    return 0.0
        return 0.0

    def _travelled(self, ctx):
        """How far from the start, horizontally. Y is ignored on purpose: falling is not searching.

        rcon hands coordinates back as TEXT (see actors.position_y, which floats them before use).
        Subtracting those directly raises, the except swallows it, and the course reads 0.0 -- a
        silent zero that fails for the wrong reason and looks exactly like a bot that did not move.
        """
        try:
            now = ctx.bot.pos()
            if not now or not self._start:
                return 0.0
            dx = float(now[0]) - float(self._start[0])
            dz = float(now[2]) - float(self._start[2])
            return (dx * dx + dz * dz) ** 0.5
        except (TypeError, ValueError, IndexError, AttributeError):
            return 0.0

    def judge(self, ctx):
        under_wander = self._wander_moved(ctx)
        overall = self._travelled(ctx)
        yield Criterion("the bot covered ground while WANDERING (15+ blocks)", under_wander > 15,
                        f"wanderMoved={under_wander:.1f} overallMoved={overall:.1f}")
        # RECORDED, NOT GATED, AND THE REASON THIS COURSE WAS REWRITTEN: total displacement passes
        # on a build where the wander branch moves nothing at all, because other tasks walk the
        # body. Keep it visible so the two numbers can be compared, but never gate on it.
        yield Criterion("overall displacement (NOT evidence on its own)", True,
                        f"overallMoved={overall:.1f}", gate=False)


class CraftAtDistantTable(CraftTable):
    """The table is 28 blocks away and the bot cannot build another one.

    Every craft course so far puts the table under the bot's feet, so nothing on the ladder asks
    whether the bot can WALK to a station it needs. InteractWithBlockTask's out-of-reach branch
    steers `getCustomGoalProcess()` -- the LEGACY engine -- and prints "Getting to our goal" while
    doing it; the failing craft_iron_pickaxe run showed exactly that line. Whether that means the
    bot cannot approach is NOT established, and this course is here to settle it rather than let a
    reading decide (the same reading was wrong about wandering).

    THE KIT IS EXACT ON PURPOSE. Three planks and two sticks is precisely a wooden pickaxe and NOT
    a crafting table (which needs four planks), so the bot cannot dodge the question by building its
    own table where it stands. It either reaches the one provided or it fails.
    """

    id = "craft_at_distant_table"
    duration = 180
    bot_kit = ["give {name} oak_planks 3", "give {name} stick 2"]
    TABLE_X = 28

    def build(self, arena, ctx):
        arena.flat_field(half=40, grass=False)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 -90 0"

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        # The only crafting table in the world, well out of reach.
        ctx.rcon.cmd(f"setblock {self.TABLE_X} {STAND_Y} 0 minecraft:crafting_table",
                     allow_reject=True)
        time.sleep(1)
        for line in self.bot_kit:
            ctx.rcon.cmd(line.format(name=ctx.bot.name), allow_reject=True)
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        ctx.bot.cmd("@get wooden_pickaxe")

    def early_stop(self, ctx):
        return _has(ctx, "wooden_pickaxe")

    def judge(self, ctx):
        got = _has(ctx, "wooden_pickaxe")
        try:
            now = ctx.bot.pos()
            reached = abs(float(now[0]) - self.TABLE_X)
        except (TypeError, ValueError, IndexError):
            reached = -1
        yield Criterion("the bot holds a wooden pickaxe", got,
                        f"pickaxe={got} planks={_count(ctx, 'planks')} sticks={_count(ctx, 'stick')}")
        # RECORDED: how close it got to the table separates "never set off" from "walked there and
        # the craft failed" -- two completely different defects that look identical in the pack.
        yield Criterion("distance from the table at the end", True,
                        f"dxToTable={reached:.1f}", gate=False)


# The registry instantiates each entry itself (run_suite: `scn = cls()`), so export the CLASS.
SCENARIOS = [CraftTable, CraftWoodPickaxe, CraftStonePickaxe, MineStone, SmeltIron,
             CraftIronPickaxe, WanderRecovery, CraftAtDistantTable]
