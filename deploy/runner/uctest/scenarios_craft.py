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

    # FRAME RATE IS SAMPLED BY Scenario._sample_fps, FOR EVERY SUITE.
    #
    # It used to be sampled here, and the comment that lived at this spot said why it had to be:
    # only nav sampled fps, so every craft verdict carried avg_fps=None, and run_suite's starvation
    # guard CANNOT FIRE on a value it never receives. A guard that can never fire is the mirror
    # image of a check that can never fail, and this suite had one all day. It bit immediately --
    # craft_stone_pickaxe went red twice while another project's containers took ~500% of this box.
    #
    # That reasoning was right and the placement was wrong. Copying it here fixed craft and left
    # mob, end and pvp with the same hole; each was then found separately, days apart, by the same
    # symptom. pvp was still missing it three fixes later. The tick loop shared by every scenario
    # is the only place a sampler cannot be forgotten in.

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
        # THE BAN THAT EMPTIES THE MINABLE LIST, counted at last. breakFailClaimed is the
        # "block did not turn to air -> treat as a private area -> ban a radius-50 cube for 60s"
        # decision; breakFailOutOfReach is the half that the reach test already refuses. Both
        # existed as counters with no reader -- the sixth dead instrument found today.
        ok_bf, bf_stats = ctx.bot.py.try_call("placeStats")
        parts = []
        if ok_bf and bf_stats:
            for tok in str(bf_stats).split():
                # navStop=ran/live is the mechanism gate for navStopOnTaskEnd. The `live` half is
                # the defect itself counted: a route still driving at the moment the goal stopped
                # existing. It is what turned this course from "the bot cannot mine" into "the bot
                # mines its eight in 29 s and then spends them pillaring", so it prints whether the
                # flag is on or off -- with the flag off it must read 0/0, which is the control.
                # cb=hardness/avoid/plausible/reach is the MECHANISM GATE for breakBanEscalates,
                # and it was missing from this list while being the number the whole diagnosis
                # turned on: one traced run read cb=0/260992/0/0, a quarter of a million candidates
                # refused by the ban that one failed break installed. A gate metric that is not
                # printed is not a gate -- the same defect as declaring `stranded` a gate and never
                # exposing it, which voided a 40-launch series by its own rule.
                if (tok.startswith("breakFail=") or tok.startswith("stranded=")
                        or tok.startswith("navStop=") or tok.startswith("cb=")
                        or tok.startswith("fleeSpot=")):
                    parts.append(tok)
        # stranded= is the mechanism gate for unstuckWhenGoalButNoPath. It was DECLARED as
        # that gate in pre-registration #8 and then not exposed at all, which made the
        # 40-launch series VOID by its own rule -- there was no way to tell whether the flag
        # had fired. Printed here so that cannot happen to it twice.
        yield Criterion("break-fail bans / stranded rescues (recorded, not gated)", True,
                        " ".join(parts) if parts else "unread", gate=False)
        # DID THE BOT BURY ITSELF? A METRIC WITH NO SPREAD, WHICH IS WHY IT IS HERE.
        #
        # The gate -- cobblestone gathered -- carries an sd of 3.6 on a mean near 4, so it cannot
        # resolve a 2-block difference without ~18 runs an arm, and two interleaved series have
        # already disagreed on exactly that (+3.60 then +0.20). Meanwhile the mechanism under study
        # is a YES/NO fact about the world that survives the run: three traces show the bot mining
        # the block under its own feet, falling in, repeating, and ending at the bottom of a 1x1
        # shaft from which the only expandable move is UP -- so it towers out on the very
        # cobblestone it came for and finishes with an empty pack on top of a column.
        #
        # Checklist 4b #4: prefer a metric with no spread when one exists. This is that metric.
        # Recorded, never gated -- a course must not start failing on a diagnostic.
        # ASK WHAT IS MISSING, NOT WHAT IS AIR. The first version of this probe tested the column
        # for air and read shaft=0 on a run whose tower was six blocks high -- because the bot
        # BACKFILLS the shaft on its way out of it, so by the time the run ends the hole it dug is
        # full of the cobblestone it dug out. "Is it air" cannot tell "never dug" from "dug and
        # filled in". "Is the stone still there" can, and that is the question anyway.
        dug = 0
        for dy in range(1, 5):
            probe = ctx.rcon.cmd(f"execute if block 0 {STAND_Y - dy} 0 minecraft:stone",
                                 allow_reject=True).lower()
            if "passed" not in probe:
                dug += 1
        tower = 0
        for dy in range(0, 8):
            probe = ctx.rcon.cmd(f"execute if block 0 {STAND_Y + dy} 0 minecraft:cobblestone",
                                 allow_reject=True).lower()
            if "passed" not in probe:
                break
            tower = dy + 1
        yield Criterion("the spawn column afterwards (recorded, not gated)", True,
                        f"dug={dug}/4 of the spawn column, tower={tower} high", gate=False)


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

    # WINDOW WIDENED FOR A DEGRADED HOST, WITHOUT HIDING SLOWNESS.
    # These three are the only courses that go INVALID at 9-10 fps while the other nine pass, which
    # makes it a property of the COURSE, not of the machine: a client at 60% speed simply cannot
    # finish inside a window sized for a healthy one. Nothing is lost by widening it, because the
    # timing is recorded SEPARATELY (firstLogAt, escaped_at, dxToTable) -- a bot that has become
    # slow still shows it there, while a bot that cannot do the thing at all still fails the gate.
    id = "craft_at_distant_table"
    duration = 300
    bot_kit = ["give {name} oak_planks 3", "give {name} stick 2"]
    TABLE_X = 28

    def build(self, arena, ctx):
        arena.flat_field(half=40, grass=False)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 -90 0"

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        # "THE ONLY CRAFTING TABLE IN THE WORLD" WAS A COMMENT, NOT A FACT.
        # The stand's world is not wiped between runs, so tables left by earlier courses survive.
        # Measured with a counter on the lookup: tbl=6075/6075@44 -- a table is found on EVERY
        # lookup, at 44 blocks, while this course places its own at 28. And 40 is the threshold in
        # getCostToMakeNew, so a table at 44 counts as too far: the bot decides to CRAFT one and
        # cannot, because the kit here is deliberately a plank short. The course was failing on its
        # own leftovers.
        # Clear the whole arena of tables first; the assertion below then checks the sweep worked.
        ctx.rcon.cmd(f"fill -60 {STAND_Y - 2} -60 60 {STAND_Y + 4} 60 minecraft:air "
                     f"replace minecraft:crafting_table", allow_reject=True)
        time.sleep(1)
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
        # RECORDED: how often the station lookup actually finds a table, as a fraction of lookups.
        # The @N is the distance from the PLAYER AT THAT INSTANT, not from spawn -- so a large N means
        # the bot had wandered off, NOT that it locked onto a leftover. An earlier note here read it
        # the second way and was wrong; the sweep above is what rules leftovers out.
        ok, stats = ctx.bot.py.try_call("placeStats")
        tbl = ""
        if ok and stats:
            for part in str(stats).split():
                if part.startswith("tbl=") or part.startswith("bs="):
                    tbl = (tbl + " " + part).strip()
        yield Criterion("station lookup hit rate (found/asked, @dist-from-player)", True,
                        tbl or "n/a", gate=False)


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
    duration = 210
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


# ⛔ mine_stone IS FLAKY AT ABOUT ONE RUN IN THREE, AND EVERY SWEEP HID IT.
# Measured 2026-08-11 on two builds, three runs each at a healthy frame rate:
#     pre-mover build   PASS, FAIL, PASS
#     with the mover    PASS, FAIL, FAIL
# It fails at least once in both arms, so the mover fix is neither convicted nor cleared by this —
# one run of difference at n=3 separates nothing. What IS established is that the course is a coin
# and every single-run craft sweep before today recorded it as a clean PASS, because a sweep runs it
# once. Take n>=6 before reading anything into a mine_stone verdict.


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
    duration = 300
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
        got = _count(ctx, "diamond")
        yield Criterion("two diamonds in the pack", got >= 2,
                        f"diamonds={got} pickaxe={_has(ctx, 'iron_pickaxe')}")
        # RECORDED: the pickaxe surviving says the bot used the right tool rather than punching the
        # ore, which drops nothing and would look identical to "never found it".
        yield Criterion("the iron pickaxe is still held", True,
                        f"pickaxe={_has(ctx, 'iron_pickaxe')}", gate=False)
        # THIS COURSE'S RECORDED FAILURE IS AN APPROACH THAT NEVER TOUCHES THE DROP -- "closest
        # approach 1.35, 2.45 and 3.57 blocks, never collected, three ores of three". entityReleased
        # is how many times a tungsten search that was moving nothing got released so the approach
        # could be replanned, so it is the one number that says whether that fix ran here at all.
        # Reading zero with the flag ON means the stall being blamed is a different one.
        ok, stats = ctx.bot.py.try_call("placeStats")
        parts = [t for t in str(stats or "").split()
                 if t.startswith(("entityReleased=", "scan=", "lock=", "navStop=", "drop="))]
        yield Criterion("approach counters (recorded, not gated)", True,
                        (" ".join(parts) if parts else "unread"), gate=False)


class GotoThenMine(CraftTable):
    """Walk somewhere with `;goto`, then mine -- does the bot stay, or wander back?

    WHY THIS EXISTS. Two defects on this ladder have now had the same shape: a stale destination
    resumed after a mining segment. The first was TungstenMod.TARGET still holding its debug
    initialiser of y=10, which sent the bot up a cobblestone tower it built from its own haul. The
    second is a goto that has COMPLETED: FastNavigator stops, but TARGET keeps the destination and
    the "a real goto was requested" flag keeps saying yes, so the next mining segment re-arms the
    navigator toward a place the bot already reached.

    Neither was catchable here, because NO course on this bench issues `;goto` and then mines. The
    first was found by tracing a failure and reading a log line; the second only by reading. A class
    of bug that two separate instances have belonged to deserves a course rather than a third lucky
    read -- which is the same argument that produced mine_coal, and that one caught a real stall on
    its first outing.

    THE SHAPE. Send the bot 20 blocks out with `;goto`, wait for it to arrive, then ask for
    cobblestone that is under its FEET where it now stands. A bot that mines where it is passes. A
    bot that walks back toward the old goto destination fails, and the distance it ends at says so.

    The gate is deliberately POSITION, not just the item: collecting the cobblestone proves mining
    works, and staying put is the thing this course exists to measure. Both are checked, and the
    position one is what the stale-target bugs break.
    """

    id = "goto_then_mine"
    duration = 150
    bot_kit = ["give {name} stone_pickaxe 1"]
    GOTO_X = 20

    def build(self, arena, ctx):
        arena.flat_field(half=26, grass=False)
        y = STAND_Y - 1
        ctx.rcon.cmd(f"fill -28 {y - 3} -28 28 {y - 1} 28 minecraft:stone", allow_reject=True)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 -90 0"

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        ctx.geo["fps"] = []
        time.sleep(1)
        for line in self.bot_kit:
            ctx.rcon.cmd(line.format(name=ctx.bot.name), allow_reject=True)
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        # A REAL ;goto, through the tungsten command, because that is what sets TungstenMod.TARGET
        # and marks it as a genuine destination. Driving with @get would not arm the thing under test.
        prefix = ctx.bot.py.try_call("tungstenPrefix")[1] or ";"
        ctx.bot.chat(f"{prefix}goto {self.GOTO_X} {STAND_Y} 0")
        # Give it time to walk 20 blocks and ARRIVE -- arrival is the state that was never cleared.
        for _ in range(30):
            time.sleep(2)
            pos = ctx.rcon.entity_pos(ctx.bot.name)
            if pos and abs(pos[0] - self.GOTO_X) < 3:
                break
        ctx.geo["arrived_x"] = (ctx.rcon.entity_pos(ctx.bot.name) or [0, 0, 0])[0]
        ctx.bot.cmd("@get cobblestone 3")

    def early_stop(self, ctx):
        return _count(ctx, "cobblestone") >= 3

    def judge(self, ctx):
        got = _count(ctx, "cobblestone")
        pos = ctx.rcon.entity_pos(ctx.bot.name) or [999, 0, 0]
        arrived = ctx.geo.get("arrived_x", 0)
        # DID IT STAY? Mining happens where the bot stands, so a bot that mined here is still here.
        # Walking back toward the goto origin is the failure this course was written for, and the
        # distance from where it ARRIVED is the number that shows it.
        drift = abs(pos[0] - arrived)
        yield Criterion("three cobblestone in the pack", got >= 3,
                        f"cobblestone={got} arrivedX={arrived:.1f} finalX={pos[0]:.1f}")
        yield Criterion("it mined where it stood, not back at the start", drift < 8.0,
                        f"drift={drift:.1f} blocks from where the goto ended")
        ok, stats = ctx.bot.py.try_call("placeStats")
        parts = [t for t in str(stats or "").split()
                 if t.startswith(("pdEnter=", "navStop=", "lock=", "avoidSrc="))]
        yield Criterion("drive counters (recorded, not gated)", True,
                        " ".join(parts) if parts else "unread", gate=False)


class MineCoal(CraftTable):
    """The rung the PLAYTHROUGH actually dies on, brought onto a bench that works.

    A 14-minute @gamer window reached wood, first craft, crafting, wood tools and stone tools -- and
    then stopped for 160 seconds of daylight on

        Gathering resource: [minecraft:coal] -> Mine And Collect: [[coal]]

    with every drive counter at zero: pdEnter+0, dbTick+0, mqStart+0. The task was ticking and
    finding nothing it was allowed to mine, because two failed breaks had banned two 101x101x101
    cubes: breakFail=2/0/0/0 with cb=90/842176/5318/103, i.e. 842,176 candidates refused.

    That wall could only be measured on the survival world, which needs a quiet machine and has not
    had one -- the client sits at 7-8 fps against a floor of 12 while another project holds the box.
    So the rung comes to the arena instead, where the client holds 28. This is the repo's own rule
    about unmeasurable capabilities, applied to a rung rather than a port stub: do not audit it by
    reading, write the course that needs it and let it fail.

    THE ORE IS PLACED FAR, ON PURPOSE. mine_stone's is underfoot, so it never tests the join between
    navigation and mining -- and that join is what a break ban destroys, by emptying the minable list
    while the bot is walking to it. Eight, fourteen and twenty blocks out means the bot must path,
    arrive, and mine, three times.

    WHAT A FAILURE HERE WOULD MEAN, stated in advance so the result cannot be reinterpreted after the
    fact: if this goes red with cb's second field in the hundreds of thousands, the playthrough wall
    reproduces on a cheap bench and can be worked without a quiet machine. If it goes green, the wall
    is specific to the survival world -- real terrain, real distances -- and the arena cannot stand in
    for it, which is worth knowing before another day is spent trying.

    <h2>FIRST RESULT, 3 runs: 2 PASS / 1 FAIL -- and the fork above resolves the SECOND way</h2>

    <pre>
      PASS  coal=3  scan=400   lock=0/0  cb=0/0/0/0  breakFail=0/0/0/0   early stop, ~30 s
      FAIL  coal=0  scan=3771  lock=1/0  cb=0/0/0/0  breakFail=0/0/0/0   full 180 s
      PASS  coal=3  scan=350   lock=0/0  cb=0/0/0/0  breakFail=0/0/0/0   early stop, ~30 s
    </pre>

    THE BAN DOES NOT REPRODUCE HERE. cb is zero across all three and no break ever failed, so the
    playthrough's 842,176-candidate wall belongs to the survival world -- real terrain, real
    distances, and whatever makes a break fail out there -- and this arena cannot stand in for it.
    That was worth one course to learn rather than another day of trying.

    WHAT DID REPRODUCE is a different stall, and cheaply: the failing run is a FULL-DURATION ZERO
    carrying one barren lock and a block search that ran ten times harder than the passing ones
    (scan 3771 against 400). One barren lock does not trip MAX_BARREN_LOCKS = 2, so the guard
    correctly stayed out of it -- this is not that failure mode either. A 1-in-3 red on a 180-second
    course is a far better instrument for the coal rung than a 14-minute survival window that needs a
    quiet machine, and it is what the next pass on this rung should use.
    """

    id = "mine_coal"
    duration = 180
    bot_kit = ["give {name} stone_pickaxe 1"]

    def build(self, arena, ctx):
        arena.flat_field(half=24, grass=False)
        y = STAND_Y - 1
        ctx.rcon.cmd(f"fill -26 {y - 3} -26 26 {y - 1} 26 minecraft:stone", allow_reject=True)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 -90 0"

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        # Three ores at increasing distance, each needing a walk rather than a reach.
        for x, z in ((8, 0), (14, 4), (20, -4)):
            ctx.rcon.cmd(f"setblock {x} {STAND_Y - 1} {z} minecraft:coal_ore", allow_reject=True)
        ctx.geo["fps"] = []
        time.sleep(1)
        for line in self.bot_kit:
            ctx.rcon.cmd(line.format(name=ctx.bot.name), allow_reject=True)
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        ctx.bot.cmd("@get coal 3")

    def early_stop(self, ctx):
        return _count(ctx, "coal") >= 3

    def judge(self, ctx):
        got = _count(ctx, "coal")
        yield Criterion("three coal in the pack", got >= 3,
                        f"coal={got} pickaxe={_has(ctx, 'stone_pickaxe')}")
        # THE BAN COUNTERS ARE THE POINT OF THIS COURSE, so they are printed whatever the verdict.
        # cb=hardness/avoid/plausible/reach -- the second field is the no-break ban, and it read
        # 842,176 on the playthrough window this course exists to reproduce. breakFail is
        # claimed/outOfReach/buried/wide: `claimed` is a failed break believed to be a land claim,
        # and on a bench with no claims at all every one of them is a false positive.
        ok, stats = ctx.bot.py.try_call("placeStats")
        parts = [t for t in str(stats or "").split()
                 if t.startswith(("cb=", "breakFail=", "scan=", "lock=", "navStop=", "entityReleased=",
                                  "avoidSrc="))]
        # A POSITIONAL COUNTER GETS MISREAD, AND THIS ONE WAS -- TWICE IN AN HOUR, BY TWO PEOPLE.
        # avoidSrc=0/0/1/0@- is set/pred/preds/registered@caller, and both of its first readings
        # took "1" for a refusal count rather than a predicate COUNT, which sent one conclusion into
        # a commit message and a second into the register before either was caught. The fields stay
        # positional because scripts parse them; the LEGEND travels with the line so a human reading
        # a verdict does not have to remember the order or go and find the format string.
        legend = ("  [cb=hardness/avoid/plausible/reach  avoidSrc=setHits/predHits/predsPresent/"
                  "registeredThisRun@lastCaller  lock=barren/productive/findRefused"
                  "  breakFail=claimed/outOfReach/buried/wide/-]")
        yield Criterion("ban and lock counters (recorded, not gated)", True,
                        (" ".join(parts) if parts else "unread") + legend, gate=False)


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

    # ⛔⛔⛔ THE "CORROBORATED" FINDING BELOW IS WITHDRAWN — THE TASK SYSTEM WAS SWITCHED OFF.
    # TaskRunner.tick() begins `if (!active) return;` and `active` stays false until a user task
    # starts. This course issued NO command, so no chain ticked at all -- survival included -- and
    # surv=0/0 was measuring an idle bot, not a broken reflex. Three witnesses agreed with each
    # other and all three were answering the wrong question.
    # Fixed by ordering a harmless gather before the teleport, which makes the runner active.
    #
    # ⛔ (WITHDRAWN) THE COURSE IS AN INSTRUMENT NOW, AND THE ANSWER IS CORROBORATED FROM THREE SIDES:
    #   * the block at 0,-60,0 is confirmed minecraft:lava (read back through py4j, not assumed);
    #   * 24 timeline samples across 90s all read [0.5, -60.0, 0.5] -- the bot NEVER MOVES;
    #   * hp is pinned at 16 with deaths=0, so it is alive and stable, not dying and not fleeing.
    # The trigger is NOT suppressed this time: minecraft:resistance cuts damage but is invisible to
    # isInLavaOhShit, which only excludes FIRE_RESISTANCE. So the bot is in lava, in danger by the
    # chain's own definition, with ninety seconds to act -- and does nothing.
    #
    # THAT is a dead survival path, and it is now safe to say so. The earlier version of this claim
    # was withdrawn because fire resistance had switched the branch off; this one survives that
    # objection by construction.
    #
    # ⛔ (WITHDRAWN, kept for the record) THE FIRE RESISTANCE DISABLED THE TRIGGER.
    # WorldSurvivalChain.isInLavaOhShit reads:
    #     player.isInLava() && !player.hasStatusEffect(FIRE_RESISTANCE)
    # so a fire-resistant bot is DELIBERATELY not considered to be in danger -- which is correct
    # behaviour, since it is not. Granting the effect to remove the physics confound switched off
    # the very branch under test. "The bot never leaves the lava" therefore says NOTHING about lava
    # escape; it says the escape was never asked for.
    #
    # This is the sharpest version of today's recurring lesson: the control that makes a measurement
    # cleaner can also make it meaningless, and the only way to know is to read what the code does
    # with that control.
    #
    # THE COURSE STILL NEEDS A SURVIVABLE WINDOW WITHOUT SUPPRESSING THE TRIGGER. Options, none of
    # them tried: start the bot with high health and armour; use a single lava block and accept the
    # three-second window as the real question; or grant resistance ONLY after the first sample, so
    # the trigger fires and then the bot has time to act on it.
    #
    # ⛔ (WITHDRAWN) THE INSTRUMENT WORKS NOW, AND ITS FIRST CLEAN ANSWER IS A REAL DEFECT:
    #   entered=True (by position)  deaths=0 (fire resistance held)  x=0.5 z=0.5  "nowhere"
    # Given ninety seconds and no burning, THE BOT NEVER LEAVES THE LAVA. It sits at the exact
    # coordinates it was placed. That is not "too slow to escape" and not "died before it could" --
    # both of those confounds were removed on purpose -- it is the escape never happening at all.
    #
    # It took six runs to be able to say that, and every one of the six was my own instrument
    # failing rather than the bot: a pool that respawned the bot into itself, an exit condition the
    # spawn point satisfied, a corpse counting as an escape, a death counter sampled too late, a
    # racy health-based entry check, and a lava block that replaced the FLOOR so the bot fell into
    # the void the stand carves underneath. Each was found by asking what the number could mean
    # other than what I wanted it to mean.
    #
    # ⛔ (history) STATUS AFTER FIVE RUNS: HONEST BUT NOT YET AN INSTRUMENT.
    # It no longer passes falsely -- three separate false-green mechanisms have been closed:
    #   * "not in the lava" was true of a CORPSE that respawned somewhere clear;
    #   * the death COUNTER read 0 because the last sample was taken before the bot died;
    #   * the exit condition was satisfied by the spawn point's own coordinates.
    # Escape is now defined as "out AND still nearby" (1.5 < distance < 8), which a respawn fifteen
    # blocks away cannot satisfy.
    #
    # WHAT IS STILL WRONG: entry detection is RACY. The bot goes 20 hp to 4 in about a second and
    # dies in three, while the harness samples once a second -- so whether any sample catches the
    # damage is luck, and `entered` flips between runs on identical setups.
    # AND THE DEEPER QUESTION IS UNANSWERED: with roughly three seconds of life in a lava source
    # block, it is not established that ANY escape logic could get out in time. Until that is known,
    # a red here cannot be read as "the bot cannot escape lava" -- it may be "no bot could".
    #
    # NEXT, IN ORDER: (1) give the bot fire resistance for the run, or use a shallower hazard, so
    # there IS a survivable window and the question becomes about behaviour rather than physics;
    # (2) take entry from the FIRST timeline sample's position rather than from a health dip.
    #
    # ⛔ (earlier) DIAGNOSED FROM THE TIMELINE: THE TELEPORT WORKS. THE POOL IS UNSURVIVABLE.
    #   t=1.0  [0.5, -61.9, 0.5]  hp 4.0    <- in the lava, SUBMERGED (y is below the floor)
    #   t=4.5  [10.5, -60.0, 10.5] hp 20.0  <- dead, respawned on the spawn point
    # The bot goes 20 hp to 4 inside one second and dies in about three and a half. `entered` read
    # False only because drive_tick polls over rcon and its first sample landed AFTER the respawn:
    # the death is faster than the instrument, which is why min_hp said 20 while the timeline said 4.
    #
    # So there are two separate faults, and neither is the bot's:
    #   1. the bot SINKS -- lava is not solid, so a teleport into the pool submerges it, and
    #      submerged lava damage leaves no window for any escape logic to act in;
    #   2. the course samples slower than the hazard kills, so it cannot even observe what happened.
    # The fix for both is the same shape: the bot must stand at the pool's EDGE with its feet in a
    # single lava block and solid ground one step away -- the situation a bot actually walks into --
    # and the entry check should read the harness timeline (already sampled every second) rather
    # than poll rcon on its own slower schedule.
    #
    # ⛔ (earlier) STATUS: THE GATE IS NOW HONEST; THE SETUP IS NOT YET WORKING.
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
        # ONE BLOCK OF LAVA, NOT A POOL. Submerging the bot leaves no window for any escape logic;
        # a single source block at floor level puts its feet in the hazard with solid ground one
        # step away in every direction -- the situation a bot actually walks into.
        # LAVA ON THE FLOOR, NOT INSTEAD OF IT. Replacing the floor block let the bot sink through
        # (y reached -61.9) into the air the stand carves under the arena, and the death recorded as
        # deaths=1 was a VOID fall, not a burn -- the same trap that made mine_stone look broken this
        # morning. The floor stays solid; the lava sits in the space the bot occupies.
        ctx.rcon.cmd(f"setblock 0 {STAND_Y} 0 minecraft:lava", allow_reject=True)
        ctx.rcon.cmd(f"fill -1 {y} {self.WATER_Z} 1 {y} {self.WATER_Z + 1} minecraft:water",
                     allow_reject=True)
        ctx.geo["fps"] = []
        ctx.geo["min_hp"] = 20.0
        ctx.geo["entered"] = False
        time.sleep(1)
        ctx.bot.py.try_call("resetRunCounters")
        # GIVE THE BOT A TASK FIRST, OR NOTHING IS RUNNING AT ALL.
        # TaskRunner.tick() opens with `if (!active) return;`, and `active` is false until a user
        # task starts. An idle bot therefore ticks NO chains -- survival included -- so the earlier
        # version of this course measured a switched-off task system and read it as "the bot does
        # not escape lava". It was never asked to. A harmless gathering order makes the runner
        # active; the survival chain outranks it the moment the bot is in danger.
        ctx.bot.cmd("@get oak_log 1")
        time.sleep(2)
        # At the pool's edge, not its centre: one step east is dry.
        ctx.rcon.cmd(f"tp {ctx.bot.name} 0.5 {STAND_Y} 0.5", allow_reject=True)
        # RESISTANCE, NOT FIRE RESISTANCE — AND THE DIFFERENCE IS THE WHOLE COURSE.
        # A survivable window is needed, or a red only means "nothing could have escaped in three
        # seconds". But fire resistance cannot buy it: WorldSurvivalChain.isInLavaOhShit reads
        #     player.isInLava() && !player.hasStatusEffect(FIRE_RESISTANCE)
        # so granting it switches OFF the branch under test -- correctly, since such a bot is in no
        # danger. An earlier version of this course did exactly that and "measured" a bot that was
        # never asked to escape.
        # minecraft:resistance cuts the DAMAGE and is invisible to that check, so the trigger still
        # fires and the bot simply has longer to act on it. Amplifier 4 is 80% off, turning three
        # seconds into roughly fifteen: enough to separate "did not try" from "ran out of time".
        ctx.rcon.cmd(f"effect give {ctx.bot.name} minecraft:resistance 120 4 true",
                     allow_reject=True)

    def drive_tick(self, ctx, elapsed):
        super().drive_tick(ctx, elapsed)
        hp = ctx.bot.health()
        if hp is not None:
            ctx.geo["min_hp"] = min(ctx.geo.get("min_hp", 20.0), float(hp))
        # ENTRY IS A POSITION, NOT A HEALTH DIP. Health-based detection was racy -- the damage
        # window was shorter than the sampling interval, so `entered` flipped between runs on
        # identical setups. Where the bot WAS is recorded every sample and cannot be missed.
        first = (ctx.samples or [None])[0]
        if first and first.get("bot"):
            fx, fz = float(first["bot"][0]), float(first["bot"][2])
            if -0.6 <= fx <= 1.4 and -0.6 <= fz <= 1.4:
                ctx.geo["entered"] = True
        # (kept as a second witness) OBSERVE THE HAZARD FROM THE HARNESS TIMELINE.
        # The previous version polled health over rcon and missed the whole episode: the bot went
        # 20 hp to 4 in one second and died in three and a half, and our first sample landed after
        # the respawn. ctx.samples is already collected every second by the harness, so ask it.
        for sample in (ctx.samples or []):
            shp = sample.get("bot_hp")
            if shp is not None and float(shp) < 20.0:
                ctx.geo["entered"] = True
                break
        x, z = self._pos(ctx)
        if -0.6 <= x <= 1.4 and -0.6 <= z <= 1.4:
            ctx.geo["entered"] = True
        # LATCH THE ESCAPE WHEN IT HAPPENS, DO NOT JUDGE WHERE THE BOT ENDED UP.
        # With --no-early-stop the run continues after the escape and the bot wanders on, so a test
        # of the FINAL position called a successful escape a failure (ended 14 blocks out, past the
        # window that exists to exclude a respawn). The escape is an EVENT; record it when seen.
        if ctx.geo.get("entered") and self._escaped(ctx) and not ctx.geo.get("escaped_at"):
            ctx.geo["escaped_at"] = round(elapsed, 1)
            ctx.geo["escaped_pos"] = (round(x, 1), round(z, 1))

    def _pos(self, ctx):
        try:
            p = ctx.bot.pos()
            return float(p[0]), float(p[2])
        except (TypeError, ValueError, IndexError):
            return 0.0, 0.0

    def _escaped(self, ctx):
        """Out of the lava and STILL NEARBY — which a corpse respawning across the map is not.

        Every earlier version of this test could be satisfied by dying: the bot burned, respawned at
        10.5,10.5 with full health, and "not in the lava any more" was true of the corpse's
        replacement. Death even outran the death COUNTER, because the last sample was taken before
        it happened. Distance closes that: an escape leaves the bot a step or two away, never
        fifteen blocks off at the spawn point.
        """
        x, z = self._pos(ctx)
        d = (x * x + z * z) ** 0.5
        return 1.5 < d < 8.0

    def early_stop(self, ctx):
        if not ctx.geo.get("entered"):
            return False
        return self._escaped(ctx) and (ctx.bot.health() or 0) > 0

    def judge(self, ctx):
        hp = ctx.bot.health()
        x, z = self._pos(ctx)
        alive = hp is not None and float(hp) > 0
        clear = bool(ctx.geo.get("escaped_at")) or self._escaped(ctx)
        entered = bool(ctx.geo.get("entered"))
        # A CORPSE THAT RESPAWNED SOMEWHERE CLEAR IS NOT AN ESCAPE.
        # The previous version passed on exactly that: the bot burned to death, respawned at
        # 10.5,10.5 with full health, and "alive and clear" read hp=20 and x=10.5 as success. The
        # harness counts deaths in its timeline, so ask it rather than inferring from the corpse.
        deaths = 0
        for sample in (ctx.samples or []):
            try:
                deaths = max(deaths, int(sample.get("d") or 0))
            except (TypeError, ValueError):
                pass
        survived = deaths == 0
        # A SETUP FAILURE MUST NOT READ AS A BOT SUCCESS. If the bot never got into the lava there
        # is nothing to escape, and the honest verdict is red on the COURSE, not green on the bot.
        yield Criterion("the bot actually entered the lava", entered,
                        f"entered={entered} minHp={ctx.geo.get('min_hp')}")
        yield Criterion("survived it (never died)", entered and survived,
                        f"deaths={deaths} entered={entered}")
        yield Criterion("alive and clear of the lava", entered and survived and alive and clear,
                        f"hp={hp} x={x:.1f} z={z:.1f} deaths={deaths}")
        # RECORDED, NOT GATED: dry ground is EAST (+x, one step), water is SOUTH (-z, six). This is
        # the number that shows whether a port kept the old goal's strong preference for water.
        # Judge the direction at the ESCAPE, not at the end of a run the bot kept walking through.
        ex, ez = ctx.geo.get("escaped_pos", (x, z))
        went = "water(south)" if ez < -1.5 else ("dry(east)" if ex > 1.5 else "nowhere")
        yield Criterion("which way it went", True,
                        f"{went} at={ctx.geo.get('escaped_at')}s pos=({ex},{ez})", gate=False)


class PickupDrop(CraftTable):
    """Walk to ONE dropped item and touch it. Nothing else -- no ore, no crafting, no tools.

    WHY THIS EXISTS. Five passes on the drop-approach freeze were measured on mine_diamond, and
    every one of them drowned: that course fails 25-30% of the time and its noise floor is a full
    run in eight -- a flag that provably did NOTHING (entityReleased=0/0 on every run) moved it
    6/8 to 5/8. Four A/Bs at eight runs each, eighty minutes apiece, and not one could separate a
    real effect from the spread. The problem was never the hypotheses; it was asking a noisy
    course a question it cannot answer.

    So this reproduces the captured geometry and nothing else. A failing mine_diamond run was
    caught with the task chain recorded, and it froze at (6.7,-61.0,0.4) at t=8.5s -- motionless
    for the remaining ~290 seconds -- sitting in:

        Mine And Collect -> Pickup Dropped Items -> Approach entity   "Tungsten pathfinding (21s left)"

    with the drop lying ONE BLOCK BELOW it in the hole it had just mined. The recorded 1.17-block
    parking case has the same shape: ore at (14,-61,4), bot stopped at (14.79,-60.00,5.03).

    THE PAIR IS THE POINT. pickup_pit puts the drop at the bottom of a one-deep pit, so reaching it
    needs a step DOWN and back out. pickup_flat puts the same drop on open ground eight blocks away.
    Same distance, same item, same task -- the only difference is the step. If flat is green and pit
    is red, the step into a pit is the defect and any fix has a sharp instrument to prove itself on.
    If BOTH are green, this geometry is not what freezes the bot and the five passes were chasing
    the wrong shape, which is worth knowing before a sixth.

    ⛔⛔ RETRACTION, SAME DAY. The first outing read 4/4 flat against 0/4 pit and I called it a
    complete separation. IT IS NOT ONE. A later run of the UNCHANGED flat arm went 0/2, and the
    counter had been saying so the whole time: idrop=0/0/0/0 on every run of both arms means
    EntityTracker.getClosestItemDrop is NEVER CALLED. Nothing in the bot pursues these drops at
    all. The flat passes were vanilla CONTACT pickup -- the drop sits at (8.5, 0.5), squarely on
    the eastward line out of spawn, so a bot wandering that way collects it by walking over it.
    pickup_side puts the same drop perpendicular to that line and fails, which is the check that
    should have been written before the claim.

    So the honest reading of all three courses is ONE finding, and it is bigger than the pit:

        @get diamond 1 never asks whether a diamond is already lying on the ground.

    et=1246/1246 -- the tracker holds the drop, grounded, on every tick of the run. drop=1246/0 --
    MineAndCollectTask asked about drops 1246 times and itemDropped() said no every time, because
    it was asking about LOGS: the task went down the craft-an-iron-pickaxe branch and spent the run
    hunting wood on a stone arena ("Wander for Infinity blocks"). The diamond it was sent to fetch
    lay eight blocks away the entire time and was never once considered.

    WHAT THESE COURSES ARE GOOD FOR, stated honestly: they are a sharp, 60-second test of whether
    the acquisition path consults existing drops. All three should go green when it does. They are
    NOT, as first claimed, a test of stepping into a pit -- that question cannot even be asked
    until something pursues the drop in the first place.
    Deliberately short and cheap: 60 seconds, one item, no mining. A fix that works should show at
    n=4 instead of needing sixteen runs to maybe show at all.
    """

    id = "pickup_flat"
    duration = 60
    pit = False
    ledge = False
    drop_x = 8.5
    drop_z = 0.5

    def build(self, arena, ctx):
        arena.flat_field(half=24, grass=False)
        y = STAND_Y - 1
        ctx.rcon.cmd(f"fill -26 {y - 3} -26 26 {y - 1} 26 minecraft:stone", allow_reject=True)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 -90 0"

    def drive_start(self, ctx):
        ctx.rcon.cmd("time set day")
        ctx.rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
        ctx.rcon.cmd("gamerule randomTickSpeed 0", allow_reject=True)
        ctx.rcon.cmd(f"clear {ctx.bot.name}", allow_reject=True)
        ctx.rcon.cmd("kill @e[type=item]", allow_reject=True)
        # THE PIT IS ONE BLOCK OF THE FLOOR REMOVED, so the drop rests a block below the surface
        # and the bot has to step down to touch it -- the geometry of every captured freeze.
        if self.pit:
            ctx.rcon.cmd(f"setblock {int(self.drop_x)} {STAND_Y - 1} {int(self.drop_z)} minecraft:air",
                         allow_reject=True)
        # A FELLED LOG DOES NOT LAND ON THE FLOOR. It rests on a stump, on leaves, on whatever is
        # under the tree -- so the playthrough's rung-zero drop is ELEVATED, the mirror of the pit.
        if self.ledge:
            ctx.rcon.cmd(f"setblock {int(self.drop_x)} {STAND_Y} {int(self.drop_z)} minecraft:stone",
                         allow_reject=True)
        drop_y = (STAND_Y - 1) if self.pit else ((STAND_Y + 1) if self.ledge else STAND_Y)
        ctx.rcon.cmd(
            f'summon minecraft:item {self.drop_x} {drop_y} {self.drop_z} '
            f'{{Item:{{id:"minecraft:diamond",count:1}},PickupDelay:0s}}', allow_reject=True)
        ctx.geo["fps"] = []
        time.sleep(2)
        # PROVE THE DROP EXISTS BEFORE BLAMING THE BOT. A summon whose syntax the server rejected
        # would leave nothing to collect, and the course would read as a navigation failure --
        # exactly the false red this repo has paid for before.
        found = ctx.rcon.cmd("execute if entity @e[type=item,distance=..40]", allow_reject=True)
        ctx.geo["drop_ok"] = "1" in str(found or "") or "Test passed" in str(found or "")
        ctx.bot.py.try_call("resetRunCounters")
        time.sleep(1)
        ctx.bot.cmd("@get diamond 1")

    def early_stop(self, ctx):
        return _count(ctx, "diamond") >= 1

    def judge(self, ctx):
        got = _count(ctx, "diamond")
        yield Criterion("the drop was summoned", bool(ctx.geo.get("drop_ok")),
                        f"drop_ok={ctx.geo.get('drop_ok')} -- a rejected summon is an INVALID run,"
                        f" not a navigation failure")
        yield Criterion("the diamond is in the pack", got >= 1,
                        f"diamond={got} pit={self.pit} at=({self.drop_x},{self.drop_z})")
        ok, stats = ctx.bot.py.try_call("placeStats")
        parts = [t for t in str(stats or "").split()
                 if t.startswith(("entityReleased=", "drop=", "scan=", "lock=", "navStop=",
                                  "et=", "idrop=", "rt="))]
        yield Criterion("approach counters (recorded, not gated)", True,
                        (" ".join(parts) if parts else "unread"), gate=False)


class PickupDropSide(PickupDrop):
    """The flat drop moved OFF the line the bot walks, to check the control is a control.

    pickup_flat passes -- and idrop=0/0/0/0 says EntityTracker.getClosestItemDrop is never called
    in either course, so nothing in the bot ever pursued that drop. It sits at (8.5, 0.5), exactly
    on the eastward line from spawn, so the pass may be nothing but vanilla contact pickup while
    the bot walks past on other business.

    This is the same drop at the same distance, perpendicular to that line. If it FAILS, the flat
    arm was incidental and the pit/flat contrast measures "does the bot happen to walk over it",
    not "does it go and get it" -- which would make the pair honest but far weaker than claimed.
    If it PASSES, there is real pursuit and the pit is a genuine navigation failure.
    """

    id = "pickup_side"
    drop_x = 0.5
    drop_z = 8.5


class PickupDropLedge(PickupDrop):
    """The drop resting one block UP, on a pillar -- the mirror of the pit, and the playthrough case.

    pickup_pit is fixed, so a drop BELOW the floor is collected. A felled log does not lie on the
    floor either: it rests on the stump or on leaves, one or more blocks up. The playthrough's
    failing run died at rung ZERO on exactly that -- <Mine And Collect: [[...log...]]> ->
    <Pickup Dropped Items> -> <Approach entity> "Tungsten pathfinding...", never collecting the
    wood it had just chopped, at 26 fps with the machine quiet.

    Same item, same distance, same task as pickup_flat. The only difference is one block of step UP.
    """

    id = "pickup_ledge"
    ledge = True


class PickupDropPit(PickupDrop):
    """The same drop, at the bottom of a one-deep pit: the step is the only difference."""

    id = "pickup_pit"
    pit = True

# The registry instantiates each entry itself (run_suite: `scn = cls()`), so export the CLASS.
SCENARIOS = [CraftTable, CraftWoodPickaxe, CraftStonePickaxe, MineStone, SmeltIron,
             CraftIronPickaxe, WanderRecovery, CraftAtDistantTable,
             ChopTree, ChopCanopy, MineDiamond, MineCoal, GotoThenMine, EscapeLava,
             PickupDrop, PickupDropSide, PickupDropLedge, PickupDropPit]
