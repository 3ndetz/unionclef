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


# The registry instantiates each entry itself (run_suite: `scn = cls()`), so export the CLASS.
SCENARIOS = [CraftTable, CraftWoodPickaxe, CraftStonePickaxe, MineStone, SmeltIron]
