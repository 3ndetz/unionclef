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

    def drive_tick(self, ctx, elapsed):
        """Sample the client's frame rate, so a starved run can be TOLD from a broken one.

        This was missing, and it cost a diagnosis. Only the nav courses sampled fps, so every craft
        verdict carried avg_fps=None -- and run_suite's starvation guard, which marks a run INVALID
        below the healthy line, CANNOT FIRE on a value it never receives. A guard that can never
        fire is the mirror image of a check that can never fail, and this suite had one all day.

        It bit immediately: craft_stone_pickaxe went red twice while another project's containers
        were taking ~500% of this box, with the bot stuck at one spot and "Failed exploring." x11.
        Nothing in that verdict could distinguish a host problem from a code regression, which is
        precisely what RULE ZERO exists to prevent.
        """
        ok, st = ctx.bot.py.try_call("getPerfStats")
        if ok and isinstance(st, dict) and st.get("fps") is not None:
            try:
                ctx.geo.setdefault("fps", []).append(float(st["fps"]))
            except (TypeError, ValueError):
                pass

    def _publish_fps(self, ctx):
        """Hand the average to run_suite, which is where the starvation guard reads it."""
        fps = ctx.geo.get("fps") or []
        ctx.geo["avg_fps"] = (sum(fps) / len(fps)) if fps else None

    def drive_start(self, ctx):
        # Daylight and no monsters: this course is about the inventory, not about surviving.
        ctx.geo["fps"] = []
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
        self._publish_fps(ctx)
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
        self._publish_fps(ctx)
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
        self._publish_fps(ctx)
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
        self._publish_fps(ctx)
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
        self._publish_fps(ctx)
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
        self._publish_fps(ctx)
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
        self._publish_fps(ctx)
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
        self._publish_fps(ctx)
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


class ChopTree(CraftTable):
    """One tree, ten blocks away, and a clock on it.

    TODOS #37 says felling a single log takes MINUTES while the bot stands in a forest, and that
    has only ever been seen through @gamer -- a ten-minute window on a survival world that this
    machine often cannot run at all. Wood is the first rung of the whole playthrough, so a slow
    chop taxes every run behind it.

    A tree is four setblocks. Put one on the flat arena and the question costs ninety seconds and
    answers under any load.

    NO TOOL IN THE KIT, ON PURPOSE. Bare hands fell oak perfectly well, and this course is about the
    chop-and-collect loop rather than about tool selection, which has its own unfinished item.
    """

    id = "chop_tree"
    duration = 120
    bot_kit = []
    TREE_X = 10

    def build(self, arena, ctx):
        arena.flat_field(half=20, grass=False)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 -90 0"

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        # A trunk five high, with a cap of leaves so the block scanner sees a tree and not a pillar.
        for dy in range(5):
            ctx.rcon.cmd(f"setblock {self.TREE_X} {STAND_Y + dy} 0 minecraft:oak_log",
                         allow_reject=True)
        ctx.rcon.cmd(f"fill {self.TREE_X - 1} {STAND_Y + 5} -1 {self.TREE_X + 1} {STAND_Y + 5} 1 "
                     f"minecraft:oak_leaves", allow_reject=True)
        ctx.geo["fps"] = []
        ctx.geo["first_log_at"] = None
        time.sleep(1)
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        ctx.bot.cmd("@get oak_log 4")

    def drive_tick(self, ctx, elapsed):
        super().drive_tick(ctx, elapsed)
        # WHEN the first log lands separates "cannot reach the tree" from "chops slowly" -- two
        # different defects that a pass/fail on the count cannot tell apart.
        if ctx.geo.get("first_log_at") is None and _count(ctx, "oak_log") > 0:
            ctx.geo["first_log_at"] = elapsed

    def early_stop(self, ctx):
        return _count(ctx, "oak_log") >= 4

    def judge(self, ctx):
        self._publish_fps(ctx)
        logs = _count(ctx, "oak_log")
        first = ctx.geo.get("first_log_at")
        yield Criterion("four logs in the pack", logs >= 4,
                        f"logs={logs} firstLogAt="
                        f"{'never' if first is None else format(first, '.1f') + 's'}")
        yield Criterion("time to the FIRST log (recorded)", True,
                        f"firstLogAt={'never' if first is None else format(first, '.1f') + 's'}",
                        gate=False)


class ChopCanopy(ChopTree):
    """The trap that made wood cost 219 seconds: a log CLOSE and HIGH against one FAR and reachable.

    chop_tree shows the plain case is fast, so it cannot see the defect TODOS #37 describes. This
    builds the actual shape instead. Three blocks away and seven up sits a canopy log on a bare
    stem, with nothing to climb; twelve blocks away stands an ordinary trunk the bot can simply walk
    to and break.

    The scanner ranks candidates by BaritoneHelper.calculateGenericHeuristic, and upstream prices a
    block of climb at one stair step -- correct for an A* heuristic, which must underestimate, and
    wrong for a comparison, which must not. Under the old pricing the canopy scored 32.8 against the
    trunk's 42.8, so the bot chose what it could not reach, walked at it until the move checker gave
    up, blacklisted that log and picked the next canopy log one block over: 29 "unreachable"
    verdicts in fifteen minutes.

    The repricing -- a climb costs a jump PLUS the block you must place under yourself -- was made
    on that reasoning and has never been tested by a course. This is that course, and it can fail:
    if the ranking is wrong the bot goes for the canopy and gets nothing.
    """

    id = "chop_canopy"
    duration = 150
    CANOPY_X = 3
    TRUNK_X = 12

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        # THE BAIT: close, high, and standing on nothing the bot can climb.
        for dy in (7, 8):
            ctx.rcon.cmd(f"setblock {self.CANOPY_X} {STAND_Y + dy} 0 minecraft:oak_log",
                         allow_reject=True)
        # THE HONEST OPTION: further away, on the ground, walk up and break it.
        for dy in range(4):
            ctx.rcon.cmd(f"setblock {self.TRUNK_X} {STAND_Y + dy} 0 minecraft:oak_log",
                         allow_reject=True)
        ctx.rcon.cmd(f"fill {self.TRUNK_X - 1} {STAND_Y + 4} -1 {self.TRUNK_X + 1} {STAND_Y + 4} 1 "
                     f"minecraft:oak_leaves", allow_reject=True)
        ctx.geo["fps"] = []
        ctx.geo["first_log_at"] = None
        time.sleep(1)
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        ctx.bot.cmd("@get oak_log 2")

    def early_stop(self, ctx):
        return _count(ctx, "oak_log") >= 2

    def _stat(self, ctx, key):
        """One counter out of placeStats, read at judge time rather than sampled.

        POLLING A PER-RUN COUNTER IS A RACE, AND IT NEARLY COST A FALSE CONCLUSION. This course can
        finish in fifteen seconds when the bot ignores the bait, so a sampler ticking every fifteen
        seconds may see mqNull=0 for a run in which the branch fired and reset. Reading it here,
        once, at the end of the run and before the next scenario zeroes it, is the only honest way
        to say whether a code path ran.
        """
        ok, stats = ctx.bot.py.try_call("placeStats")
        if not ok or not stats:
            return None
        for part in str(stats).split():
            if part.startswith(key + "="):
                try:
                    return int(part.split("=", 1)[1])
                except ValueError:
                    return None
        return None

    def judge(self, ctx):
        self._publish_fps(ctx)
        logs = _count(ctx, "oak_log")
        first = ctx.geo.get("first_log_at")
        shown = "never" if first is None else format(first, ".1f") + "s"
        nulls = self._stat(ctx, "mqNull")
        yield Criterion("two logs, with a reachable trunk and an unreachable bait", logs >= 2,
                        f"logs={logs} firstLogAt={shown} mqNull={nulls}")
        # RECORDED: whether the null-route refusal actually FIRED on this run. A pass with mqNull=0
        # means the bot never got trapped under the bait at all, and says nothing about the fix.
        yield Criterion("null routes refused (did the fix run?)", True,
                        f"mqNull={nulls}", gate=False)
        # RECORDED: what the WANDER path did on this run. The failures freeze at one spot for two
        # minutes after "Search gave up", so the question is whether the recovery moves the body at
        # all when it matters. wanderMoved counts ground covered only on ticks where that task was
        # the one running, so nothing else can flatter it.
        moved = self._stat(ctx, "wanderMoved")
        yield Criterion("ground covered while wandering (cm)", True,
                        f"wanderMoved={moved} wanderTicks={self._stat(ctx, 'wander')}", gate=False)
        # RECORDED: what the give-up machinery SAW. The escape needs eleven checker trips; if the
        # checker is satisfied by a bot crawling, failPeak stays near zero and no amount of
        # unsealing the exit can matter. This is the reading that decides the next move.
        ok, stats = ctx.bot.py.try_call("placeStats")
        chk = ""
        if ok and stats:
            for part in str(stats).split():
                if part.startswith("wanderChk=") or part.startswith("wanderFail="):
                    chk += " " + part
        yield Criterion("give-up machinery (checker ok/trip, failCounter peak)", True,
                        chk.strip() or "n/a", gate=False)
        # RECORDED: what the block filter saw. If the reachable trunk is being discarded as
        # unreachable alongside the bait, the search has no candidate and every downstream repair
        # is beside the point.
        scan = ""
        if ok and stats:
            for part in str(stats).split():
                if part.startswith("scan="):
                    scan = part
        yield Criterion("block filter (accepted/unreachable/unbreakable)", True,
                        scan or "n/a", gate=False)
        # RECORDED: WHICH term of canBreak refused. Reading blamed the wrong one once already.
        cb = ""
        if ok and stats:
            for part in str(stats).split():
                if part.startswith("cb="):
                    cb = part + "  (hardness/avoid/plausible/reach)"
        yield Criterion("canBreak refusals by term", True, cb or "n/a", gate=False)
        # RECORDED: against chop_tree's 7.8s on the plain case. A large gap here means the ranking
        # sent the bot at the canopy first and it recovered only after blacklisting.
        yield Criterion("time to the first log, versus 7.8s unbaited", True,
                        f"firstLogAt={shown}", gate=False)


class MineDiamond(CraftTable):
    """The rung above iron, and the first that REQUIRES the right tool.

    Diamond ore drops nothing to a stone pickaxe -- the game simply refuses it -- so this is the
    cheapest course that asks whether the bot picks and holds a tool good enough for the block in
    front of it. That matters because the tool-tier code carries a port stub: ToolMaterialVer throws
    on 1.21.11 and MineAndCollectTask's mid-mining swap compares mining speed rather than tiers.
    Nothing on the ladder has needed a MINIMUM tool until now.

    The ore is laid in the floor rather than hidden at depth: this asks about the tool, not about
    caving, and the two want separate courses.
    """

    id = "mine_diamond"
    duration = 180
    bot_kit = ["give {name} iron_pickaxe 1"]

    def build(self, arena, ctx):
        arena.flat_field(half=12, grass=False)
        y = STAND_Y - 1
        ctx.rcon.cmd(f"fill -14 {y - 3} -14 14 {y - 1} 14 minecraft:stone", allow_reject=True)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 -90 0"

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        # Three ores in the floor, a few blocks apart, all within easy reach.
        for x in (4, 6, 8):
            ctx.rcon.cmd(f"setblock {x} {STAND_Y - 1} 0 minecraft:diamond_ore", allow_reject=True)
        ctx.geo["fps"] = []
        time.sleep(1)
        for line in self.bot_kit:
            ctx.rcon.cmd(line.format(name=ctx.bot.name), allow_reject=True)
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        ctx.bot.cmd("@get diamond 2")

    def early_stop(self, ctx):
        return _count(ctx, "diamond") >= 2

    def judge(self, ctx):
        self._publish_fps(ctx)
        got = _count(ctx, "diamond")
        yield Criterion("two diamonds in the pack", got >= 2,
                        f"diamonds={got} pickaxe={_has(ctx, 'iron_pickaxe')}")
        # RECORDED: the pickaxe surviving says the bot used the right tool rather than punching the
        # ore, which drops nothing and would look identical to "never found it".
        yield Criterion("the iron pickaxe is still held", True,
                        f"pickaxe={_has(ctx, 'iron_pickaxe')}", gate=False)


class EscapeLava(CraftTable):
    """Standing in lava, with water three steps one way and dry ground the other.

    Written BEFORE porting EscapeFromLavaTask, because that port has a trap: the old goal scores
    water at -100 in its heuristic -- escaping INTO water is strongly preferred, since water puts the
    fire out -- while its isInGoal is merely "not lava, not lava-adjacent". Port it as "nearest
    non-lava cell" and a burning bot walks to dry ground while water sits three blocks away.

    So this course does not just ask "did it survive". It records WHICH WAY the bot went, because
    surviving by luck on a short pool and surviving by choosing the water are different behaviours
    and only one of them is the one being preserved.
    """

    # ⛔ STATUS: THE GATE IS NOW HONEST; THE SETUP IS NOT YET WORKING.
    # Third run reports FAIL with "entered=False minHp=20.0" -- the correct verdict on exactly the
    # run that previously read PASS. The bot sits on its spawn point untouched, so the teleport into
    # the pool is not taking effect, and the course now says so instead of congratulating itself.
    # That is a COURSE failure, not a bot one, and no claim about lava escape follows from it.
    #
    # Next: find why `tp` does not land the bot in the pool. Likely candidates, in order --
    # the harness re-places the bot at bot_spawn AFTER drive_start; the rcon tp is rejected; or the
    # bot is moved by its own idle behaviour before the first tick is sampled. Check the timeline's
    # first samples: they say where the bot actually was, second by second.
    #
    # (History kept deliberately: this course produced one false RED from its own arena and one
    # false GREEN from its exit condition before reaching the state above. A new gate is not to be
    # trusted until it has failed for the RIGHT reason at least once -- which it now has.)
    #
    # ⛔ (earlier) SECOND ATTEMPT PASSED WITHOUT TESTING ANYTHING.
    # Rebuilt run: "PASS, hp=20.0 x=10.5 z=10.5 minHp=20.0". The bot is standing on the SPAWN POINT,
    # took no damage at all, and early_stop fired instantly because x > 1.5 was already true there.
    # It never entered the lava. That is a check that CANNOT FAIL -- precisely the defect being
    # removed from this bench all day, reproduced in a course written to hunt it.
    # Two things must change before any verdict here means anything:
    #   1. the course must CONFIRM the bot is in the hazard before it starts judging (wait for the
    #      first point of damage, or for the position to be inside the pool), otherwise the tp is
    #      assumed rather than observed;
    #   2. early_stop must be relative to where the bot STARTED, not to absolute coordinates that
    #      the spawn point happens to satisfy.
    # Until then this course is a placeholder. It has now produced one false red (its own arena) and
    # one false green (this), which is a fair summary of how easy it is to build a bad gate.
    #
    # ARENA REBUILT AFTER ITS FIRST RUN MEASURED ITSELF. The original filled lava across -1..1 with
    # the spawn at 0.5, so every respawn dropped the bot BACK in and one death became a loop; and
    # the middle of a 3x3 pool is near-instant death, leaving no window in which an escape could be
    # SEEN even if it worked. Same error as mine_stone this morning, where a one-block floor over
    # the void made gathering look broken.
    #
    # Now: a two-wide pool, the bot at its EDGE with dry ground one step east, and the spawn moved
    # well clear so a death ends the run instead of restarting it inside the hazard.
    #
    # And the directions are deliberate. Dry ground is EAST (one step); water is SOUTH (six). A
    # "nearest non-lava cell" port walks east; one that keeps the old goal's -100 preference for
    # water goes south. That is the whole reason this course exists before the port.
    id = "escape_lava"
    duration = 90
    bot_kit = []
    WATER_Z = -7

    def build(self, arena, ctx):
        arena.flat_field(half=14, grass=False)
        ctx.geo["bot_spawn"] = f"10.5 {STAND_Y} 10.5 -90 0"

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        y = STAND_Y - 1
        # Spawn point well clear of the hazard: a death must END the run, not restart it in lava.
        ctx.rcon.cmd(f"spawnpoint {ctx.bot.name} 10 {STAND_Y} 10", allow_reject=True)
        ctx.rcon.cmd(f"fill -1 {y} -1 0 {y} 1 minecraft:lava", allow_reject=True)
        ctx.rcon.cmd(f"fill -1 {y} {self.WATER_Z} 1 {y} {self.WATER_Z + 1} minecraft:water",
                     allow_reject=True)
        ctx.geo["fps"] = []
        ctx.geo["min_hp"] = 20.0
        ctx.geo["entered"] = False
        time.sleep(1)
        ctx.bot.py.try_call("resetRunCounters")
        # At the pool's edge, not its centre: one step east is dry.
        ctx.rcon.cmd(f"tp {ctx.bot.name} 0.5 {STAND_Y} 0.5", allow_reject=True)

    def drive_tick(self, ctx, elapsed):
        super().drive_tick(ctx, elapsed)
        hp = ctx.bot.health()
        if hp is not None:
            ctx.geo["min_hp"] = min(ctx.geo.get("min_hp", 20.0), float(hp))
        # OBSERVE THE HAZARD, DO NOT ASSUME IT. The previous version trusted the teleport and passed
        # with the bot standing on its spawn point, untouched. Either being inside the pool or
        # losing health proves it actually happened.
        x, z = self._pos(ctx)
        inside = -1.5 <= x <= 0.9 and -1.5 <= z <= 1.5
        if inside or (hp is not None and float(hp) < 20.0):
            ctx.geo["entered"] = True

    def _pos(self, ctx):
        try:
            p = ctx.bot.pos()
            return float(p[0]), float(p[2])
        except (TypeError, ValueError, IndexError):
            return 0.0, 0.0

    def early_stop(self, ctx):
        # Only after the hazard is CONFIRMED, or the spawn point itself satisfies the exit.
        if not ctx.geo.get("entered"):
            return False
        x, z = self._pos(ctx)
        return (x > 1.5 or z < -1.5) and (ctx.bot.health() or 0) > 0

    def judge(self, ctx):
        self._publish_fps(ctx)
        hp = ctx.bot.health()
        x, z = self._pos(ctx)
        alive = hp is not None and float(hp) > 0
        clear = x > 1.5 or z < -1.5
        entered = bool(ctx.geo.get("entered"))
        # A SETUP FAILURE MUST NOT READ AS A BOT SUCCESS. If the bot never got into the lava there
        # is nothing to escape, and the honest verdict is red on the COURSE, not green on the bot.
        yield Criterion("the bot actually entered the lava", entered,
                        f"entered={entered} minHp={ctx.geo.get('min_hp')}")
        yield Criterion("alive and clear of the lava", entered and alive and clear,
                        f"hp={hp} x={x:.1f} z={z:.1f} minHp={ctx.geo.get('min_hp')}")
        # RECORDED, NOT GATED: dry ground is EAST (+x, one step), water is SOUTH (-z, six). This is
        # the number that shows whether a port kept the old goal's strong preference for water.
        went = "water(south)" if z < -1.5 else ("dry(east)" if x > 1.5 else "nowhere")
        yield Criterion("which way it went", True, f"{went} x={x:.1f} z={z:.1f}", gate=False)


# The registry instantiates each entry itself (run_suite: `scn = cls()`), so export the CLASS.
SCENARIOS = [CraftTable, CraftWoodPickaxe, CraftStonePickaxe, MineStone, SmeltIron,
             CraftIronPickaxe, WanderRecovery, CraftAtDistantTable,
             ChopTree, ChopCanopy, MineDiamond, EscapeLava]
