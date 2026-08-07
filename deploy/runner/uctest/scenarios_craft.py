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


# The registry instantiates each entry itself (run_suite: `scn = cls()`), so export the CLASS.
SCENARIOS = [CraftTable, CraftWoodPickaxe]
