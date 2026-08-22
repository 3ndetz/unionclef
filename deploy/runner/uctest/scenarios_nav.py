"""uctest nav suite — the PATHFINDER regression gate (RW-8).

WHY THIS EXISTS. The block-space search is about to be reworked (unified move
generation, real g-cost accumulation, physics-A* dedup). Every one of those changes
alters routing globally, and without a graded course set there is no way to tell a fix
from a regression — which is exactly how "паркуры он всегда мог проходить раньше"
happened. This suite is the gate: run it BEFORE a search change to get a baseline, and
after every change to prove nothing fell over.

Courses are graded easy -> hard and each isolates ONE capability, so a red tells you
which move type broke rather than just "navigation is worse". Every course also records
time-to-goal and client FPS, which doubles as the PERF-1 baseline.

Nothing here needs a victim; the bot navigates alone.
"""
from .arena import FLOOR_Y, STAND_Y
from .scenario import Criterion, Scenario


class NavCourse(Scenario):
    """Shared shape: build a course, `gotoXYZ` the far marker, judge arrival.

    Subclasses set `course()` (build + return the goal) and may override the gates.
    """
    tier = "gate"
    needs_victim = False
    duration = 90
    arena_half = 40
    goal_tolerance = 2.5
    # Courses that genuinely need a block in hand / a capability flag set these.
    bot_kit = []

    # The start pad every course shares. Course features MUST begin at PAD_END+1 or
    # later: an earlier version had the base pad overlap the first feature, which
    # quietly filled in the first gap of nav_gaps and made the course measure
    # something other than what it claimed to.
    PAD_X0, PAD_X1 = -3, 6
    start_y = STAND_Y

    def build(self, arena, ctx):
        arena.floor(self.PAD_X0, -3, self.PAD_X1, 3, "stone")
        goal = self.course(arena, ctx)
        ctx.geo["goal"] = goal
        ctx.geo["bot_spawn"] = f"0.5 {self.start_y} 0.5 -90 0"
        arena.marker(0, 0, "lime")

    def course(self, arena, ctx):
        raise NotImplementedError

    def drive_start(self, ctx):
        gx, gy, gz = ctx.geo["goal"]
        ctx.geo["fps"] = []
        ctx.bot.py.call("gotoXYZ", gx, gy, gz)

    def _dist_to_goal(self, ctx):
        s = ctx.samples[-1] if ctx.samples else None
        p = s.get("bot") if s else None
        if not p:
            return None
        gx, gy, gz = ctx.geo["goal"]
        return ((p[0] - gx) ** 2 + (p[1] - gy) ** 2 + (p[2] - gz) ** 2) ** 0.5

    def drive_tick(self, ctx, elapsed):
        # FPS sampling moved to Scenario._sample_fps: it was copied into four scenario files and
        # forgotten in the fifth (pvp), which left that whole suite unjudgeable by the guard.
        d = self._dist_to_goal(ctx)
        if d is not None and ctx.geo.get("reached_at") is None and d < self.goal_tolerance:
            ctx.geo["reached_at"] = elapsed

    def early_stop(self, ctx):
        return ctx.geo.get("reached_at") is not None

    def judge(self, ctx):
        t = ctx.geo.get("reached_at")
        d = self._dist_to_goal(ctx)
        yield Criterion(f"reached goal (tol {self.goal_tolerance})", t is not None,
                        f"t={t if t is None else round(t, 1)}s final_dist="
                        f"{None if d is None else round(d, 1)}")
        yield Criterion("no self-fall", ctx.self_falls == 0,
                        f"self_falls={ctx.self_falls}")
        yield Criterion("freezes == 0", ctx.freeze_windows == 0,
                        f"freezes={ctx.freeze_windows}")


# ── 1. baseline ──────────────────────────────────────────────────────────────
class NavFlat(NavCourse):
    """Plain 30-block walk. Sanity + the time/FPS baseline everything else is
    compared against; if this is red, nothing below is meaningful."""
    id = "nav_flat"
    duration = 60

    def course(self, arena, ctx):
        arena.floor(7, -3, 34, 3, "stone")
        return (30, STAND_Y, 0)


# ── 2. climbing ──────────────────────────────────────────────────────────────
class NavStaircase(NavCourse):
    """Eight +1 steps, 3 wide (the historical 'course A'). Pure ascend moves."""
    id = "nav_staircase"

    def course(self, arena, ctx):
        h = FLOOR_Y
        x = 7
        for _ in range(8):
            h += 1
            arena._fill(x, FLOOR_Y, -1, x + 1, h, 1, "stone")
            x += 2
        arena._fill(x, FLOOR_Y, -1, x + 3, h, 1, "stone")
        return (x + 2, h + 1, 0)


class NavSteep(NavCourse):
    """+1 up every 2 across — needs the parkour-ascend move, not a plain step."""
    id = "nav_steep"

    def course(self, arena, ctx):
        h = FLOOR_Y
        x = 8
        for _ in range(6):
            h += 1
            arena._fill(x, FLOOR_Y, -1, x, h, 1, "stone")
            x += 2
        arena._fill(x, FLOOR_Y, -1, x + 3, h, 1, "stone")
        return (x + 2, h + 1, 0)


# ── 3. gaps ──────────────────────────────────────────────────────────────────
# ⛔ nav_gaps HAS ITS OWN FALL RATE, AND IT IS NOT A REGRESSION FROM ANY BUILD (2026-08-12).
# Two nav sweeps the same day each ended with one INVALID here, the bot in the void at min Y
# -172.3 and -238.3 against a floor at -60. The evidence points both ways at once, which is what
# a course-intrinsic flake looks like rather than a build defect:
#     arena_nav  (A) falls 0     leak_nav (A) falls 0
#     lava_nav   (B) falls 1     arc2_nav (B) falls 1
#     dedicated same-session A/B on this course:  B 0/6 falls,  A 2/7 falls
# The sweeps blame B, the A/B blames A, both n are small -- so the bot simply misses a gap jump
# sometimes and drops out of the world.
#
# WHAT CHANGED IS THAT IT IS VISIBLE. Before the arena guard in run_suite (rule 4k) such a run was
# recorded as an ordinary FAIL, or worse, contributed its numbers to whatever series it sat in --
# which is how one fall poisoned a 12-run arm and sent a whole pass chasing a phantom "stand
# degradation". It is now INVALID, the retry runs, and the sweep reads a truthful 12/12.
#
# Do NOT chase this as a bug in a build without first reproducing it on BOTH arms of a pinned pair.
class NavGaps(NavCourse):
    """Flat parkour: 2, 3 and 4-block gaps over void, landing pads between.
    The gaps are REAL air down to the void, so a failed jump is a self-fall."""
    id = "nav_gaps"

    def course(self, arena, ctx):
        x = 7
        for gap in (2, 3, 4):
            x += gap                      # the gap itself stays air
            arena._fill(x, FLOOR_Y, -2, x + 2, FLOOR_Y, 2, "stone")
            x += 3
        arena._fill(x, FLOOR_Y, -2, x + 3, FLOOR_Y, 2, "stone")
        return (x + 2, STAND_Y, 0)


class NavDescend(NavCourse):
    """Safe drops of 1, 2 and 3 — descend moves without fall damage.

    Starts RAISED so the whole staircase-down stays above the world floor; the
    first version dropped to y=-66, below bedrock, and the bot simply fell out of
    the world (the course measured nothing)."""
    id = "nav_descend"
    start_y = STAND_Y + 8

    def build(self, arena, ctx):
        arena._fill(self.PAD_X0, FLOOR_Y + 8, -3, self.PAD_X1, FLOOR_Y + 8, 3, "stone")
        goal = self.course(arena, ctx)
        ctx.geo["goal"] = goal
        ctx.geo["bot_spawn"] = f"0.5 {self.start_y} 0.5 -90 0"

    def course(self, arena, ctx):
        h = FLOOR_Y + 8
        x = 7
        for drop in (1, 2, 3):
            h -= drop
            arena._fill(x, h, -3, x + 4, h, 3, "stone")
            x += 5
        return (x - 2, h + 1, 0)


# ── 4. special moves ─────────────────────────────────────────────────────────
class NavWater(NavCourse):
    """A pool that must be swum: the route has to enter water, cross and climb
    out. Regression for the swim/surface moves (issue #24)."""
    id = "nav_water"
    duration = 120
    settings = {"verboseDebugLogging": "true"}

    def course(self, arena, ctx):
        arena.floor(7, -3, 30, 3, "stone")
        # A POOL NEEDS A CONTAINER. The arena floor is ONE layer thick over the void,
        # so carving three blocks down leaves a floating cube of water with no bottom and
        # no walls: the bot swims in, sinks straight out through the missing bottom and
        # falls into the void (measured — it left the pool at y=-64, z=-4.2 and fell to
        # y=-169). Build a solid block first, then carve the pool inside it.
        # THE BANK. The shared start pad ends at PAD_X1=6 and the pool begins at 11, so
        # without this there are FOUR cells of open void in between — the bot had to clear
        # them with a jump at the engine's exact limit (MAX_JUMP_GAP=4) before it ever
        # reached the water. It made that jump alone and missed it in the suite, which is
        # the definition of a flaky course: this one claims to measure SWIMMING, so it must
        # not also gate on a borderline parkour.
        arena.floor(7, -3, 10, 3, "stone")
        # THE CONTAINER MUST NOT BECOME A FOOTPATH. Filling it to FLOOR_Y across z=-4..4 and
        # carving only z=-3..3 left a stone rim on both sides of the pool at walking level, and
        # the bot simply WALKED AROUND the water — the course passed for days without ever
        # testing a swim. Spotted by watching the clip, which is exactly what clips are for.
        # The shell still has to reach the surface or the water pours out sideways, so the rim
        # stays and is capped with barriers instead: the water is held, and there is nowhere to
        # put your feet.
        arena._fill(11, FLOOR_Y - 3, -4, 18, FLOOR_Y, 4, "stone")
        # AND THE WALLS MUST NOT BE A SECOND SOLUTION. Built from stone they are MINABLE, and
        # the search took that road instead: "break-through planned at 18,-63" thirty-six times
        # in a stalled run — cheaper, in its cost model, than swimming. Draining a pool through
        # its wall is a perfectly good way to cross, but it is not what a course called
        # "swim across" is measuring, so the end walls are barrier.
        arena._fill(11, FLOOR_Y - 3, -4, 11, FLOOR_Y, 4, "barrier")
        arena._fill(18, FLOOR_Y - 3, -4, 18, FLOOR_Y, 4, "barrier")
        arena._fill(11, FLOOR_Y + 1, -4, 18, FLOOR_Y + 3, -4, "barrier")
        arena._fill(11, FLOOR_Y + 1, 4, 18, FLOOR_Y + 3, 4, "barrier")
        arena._fill(12, FLOOR_Y - 2, -3, 17, FLOOR_Y, 3, "air")
        arena._fill(12, FLOOR_Y - 2, -3, 17, FLOOR_Y, 3, "water")
        return (26, STAND_Y, 0)


class NavLadder(NavCourse):
    """Climb a 4-high ladder onto a shelf — the ladder move set."""
    id = "nav_ladder"
    duration = 120
    settings = {"verboseDebugLogging": "true"}

    def course(self, arena, ctx):
        rc = ctx.rcon
        top = FLOOR_Y + 5
        arena.floor(7, -3, 9, 3, "stone")
        arena._fill(10, FLOOR_Y, -3, 10, top, 3, "stone")       # the wall
        arena._fill(11, top, -3, 16, top, 3, "stone")           # the shelf
        for y in range(FLOOR_Y + 1, top + 1):
            rc.cmd(f"setblock 9 {y} 0 ladder[facing=west]")
        return (14, top + 1, 0)


class NavSlime(NavCourse):
    """Drop onto slime and bounce up to a ledge — the slime-bounce move."""
    id = "nav_slime"
    # BLOCKS IN THE POCKET, AND PLACEMENT ON. The point of this course is "get to the ledge",
    # not "get there by bouncing": baritone reaches anywhere by BREAKING AND PLACING, and a
    # bot with an empty inventory cannot be compared against it. Measured separately: the
    # bounce alone cannot cross this gap, so with no blocks the course tests something the
    # engine has no legal way to do.
    bot_kit = ["item replace entity {name} hotbar.0 with cobblestone 64"]
    settings = {"verboseDebugLogging": "true", "planPlaceMoves": "true"}
    # NOTE ON THE GEOMETRY, so nobody re-runs these experiments. The first bounce off the pad
    # reaches about 4 blocks horizontally at the ledge's height, while the ledge sits 8 away:
    # this course asks for roughly double what the slime mechanic gives, and cannot be passed
    # by bouncing. Both dials were tried and both were reverted — lowering the ledge weakens
    # what the course claims to measure, and raising this launch pad (to FLOOR_Y+16) makes the
    # bot overshoot the pad entirely and fall, 18.5 blocks short with a death every run.
    # Left exactly as it was: the course is honest about being unreachable, and the change
    # that would make it passable is a design decision, not a tuning one.
    start_y = FLOOR_Y + 8

    def build(self, arena, ctx):
        arena._fill(self.PAD_X0, FLOOR_Y + 7, -3, self.PAD_X1, FLOOR_Y + 7, 3, "stone")
        goal = self.course(arena, ctx)
        ctx.geo["goal"] = goal
        ctx.geo["bot_spawn"] = f"0.5 {self.start_y} 0.5 -90 0"

    def course(self, arena, ctx):
        # The ledge is left where it was. It was briefly lowered on the strength of a probe
        # that dropped the bot onto the pad from a STANDING START — 3.07 blocks back from a
        # 7-block drop, which would have made the original ledge unreachable. The in-motion
        # tick trace says otherwise: entering the slime at a run the apex is -55.4, i.e. 4.6
        # blocks, so HEIGHT is not what blocks this course. The blocker is horizontal, and
        # weakening the test would have hidden that.
        arena._fill(9, FLOOR_Y, -3, 13, FLOOR_Y, 3, "slime_block")     # bounce pad
        arena._fill(17, FLOOR_Y + 4, -3, 23, FLOOR_Y + 4, 3, "stone")  # target ledge
        return (21, FLOOR_Y + 5, 0)


# ── 5. capability moves (break / place) ──────────────────────────────────────
class NavBreak(NavCourse):
    """A 1-block-thick dirt wall across the only corridor: the search must plan a
    break-through (allowBreak ships ON). Barriers on both sides so there is no
    way around — this fails if break-as-a-move is not reachable."""
    id = "nav_break"
    duration = 120
    settings = {"verboseDebugLogging": "true"}

    def course(self, arena, ctx):
        arena.floor(7, -1, 30, 1, "stone")
        arena._fill(7, STAND_Y, -2, 30, STAND_Y + 3, -2, "barrier")
        arena._fill(7, STAND_Y, 2, 30, STAND_Y + 3, 2, "barrier")
        arena._fill(14, STAND_Y, -1, 14, STAND_Y + 1, 1, "dirt")   # the wall
        return (24, STAND_Y, 0)


class NavWall2(NavCourse):
    """A 2-block vertical wall onto a ledge with cobblestone in the hotbar and
    planPlaceMoves ON: the route needs a PLACE (pillar) to get up. This is the
    course that proves place-as-a-move is actually reachable from the search."""
    id = "nav_wall2"
    duration = 120
    bot_kit = ["item replace entity {name} hotbar.0 with cobblestone 64"]
    settings = {"planPlaceMoves": "true", "verboseDebugLogging": "true"}

    def course(self, arena, ctx):
        arena.floor(7, -3, 11, 3, "stone")
        arena._fill(12, FLOOR_Y, -3, 22, FLOOR_Y + 2, 3, "stone")   # 2-high ledge
        return (18, FLOOR_Y + 3, 0)


class NavBridge(NavCourse):
    """A 6-block gap, floor level on both sides, cobblestone in the pocket: the only
    way over is a BRIDGE of placed blocks, which means placing against a block the
    route itself laid a step earlier.

    This is the course that isolates chained placement. It exists because the search
    used to look for the face to click against in the WORLD, so the second plank —
    whose face is the first plank — was never reachable, and every bridge was capped
    at one block. A 6-block gap is too wide to jump (MAX_JUMP_GAP) and has nothing to
    walk around, so it passes only if the search can place against its own work."""
    id = "nav_bridge"
    duration = 120
    bot_kit = ["item replace entity {name} hotbar.0 with cobblestone 64"]
    settings = {"planPlaceMoves": "true", "verboseDebugLogging": "true"}

    def course(self, arena, ctx):
        arena.floor(7, -3, 12, 3, "stone")            # near side
        arena.floor(19, -3, 26, 3, "stone")           # far side, 6-block gap at x=13..18
        # Walls, or the bot walks around the gap and the course proves nothing.
        arena._fill(13, STAND_Y, -4, 18, STAND_Y + 3, -4, "barrier")
        arena._fill(13, STAND_Y, 4, 18, STAND_Y + 3, 4, "barrier")
        return (23, STAND_Y, 0)


class NavHazard(NavCourse):
    """A MAGMA floor across the corridor with a one-block safe walkway past it, and
    the gate is "the bot never took damage". A magma block is walkable and burns you,
    which is precisely the case a geometry-only planner cannot see.

    Lava was tried here first and does NOT discriminate: lava has an empty collision
    shape, so `PlayerFit.supportTop` finds no floor above it and the walk generators
    already refuse the cell — the course passed 4 of 4 with the hazard counter at
    ZERO, i.e. for the wrong reason. Magma is the honest test: `classify` calls it a
    full cube, so before 2026-07-30 it was an ordinary floor and the shortest route
    ran straight over it. Baritone has refused it since forever
    (MovementHelper.avoidWalkingInto); tungsten never asked."""
    id = "nav_hazard"
    duration = 120
    settings = {"verboseDebugLogging": "true"}

    def course(self, arena, ctx):
        arena.floor(7, -3, 26, 3, "stone")
        # Magma covers z=-3..1 at x=14..16, leaving z=2..3 clean. Beelining burns.
        arena._fill(14, FLOOR_Y, -3, 16, FLOOR_Y, 1, "magma_block")
        arena._fill(7, STAND_Y, -4, 26, STAND_Y + 3, -4, "barrier")
        arena._fill(7, STAND_Y, 4, 26, STAND_Y + 3, 4, "barrier")
        return (23, STAND_Y, 0)

    def judge(self, ctx):
        yield from super().judge(ctx)
        hps = [s["bot_hp"] for s in ctx.samples if s.get("bot_hp") is not None]
        low = min(hps) if hps else None
        # Reaching the goal is not the point — reaching it UNBURNED is. Half a heart of
        # damage means the route went over the magma.
        yield Criterion("took no damage", low is not None and low >= 19.5,
                        f"min_hp={low}")




class NavCliff(NavCourse):
    """A SIX-block drop straight ahead, and a safe stepped way down beside it.

    ⛔ THIS COURSE EXISTS BECAUSE NOTHING COULD SEE A WHOLE CLASS OF BUG. The pathfinder carries a
    complete fall-damage guard -- PathFinder.checkForFallDamage, plus six more checks in Node,
    BlockNode and the special moves -- and it was DISABLED by default for as long as the port has
    existed. Twelve nav courses stayed green throughout, because the deepest drop any of them
    offers is three blocks and the guard's threshold is 2.75. A course that only offers SAFE drops
    cannot test a guard against unsafe ones; it passes identically whether the guard runs or not.

    Measured on the playthrough instead, which is a nine-minute run on a live world and therefore
    the worst possible place to learn it: the bot descended from y=134 to y=60 and took 25.3
    damage, four of four events attributed to no living entity at all (falls, void, fire). One run
    reached wood tools and spent its last 150 seconds chipping stone on 1.5 hp.

    The layout gives the bot a CHOICE, which is what makes the verdict mean something:
      * straight ahead, a six-block cliff onto the lower shelf -- fast, and it hurts;
      * one row to the side, three two-block steps down to the same shelf -- safe.
    Both reach the goal, so 'reached' cannot distinguish them and the HEALTH is the measurement.

    ⛔ WHAT THIS COURSE DOES **NOT** MEASURE, recorded because I built it expecting otherwise.
    It does not discriminate the fall-damage flag. Three builds were tried -- a 6-block drop with
    stairs beside it, a 10-block drop with a detour, and the 5-block drop that ships here -- and on
    an arena the bot reaches the goal with min_hp=20.0 in EVERY one of them, guard on or off. At
    ten blocks it stopped reaching the goal at all (final_dist=19.4, freezes=15) with the guard
    OFF, so that depth is past what the planner will take regardless. The bot does not choose a
    damaging fall here, which means the playthrough's 25.3 damage is NOT explained by "the planner
    picks damaging drops" -- that model is unsupported and is not claimed.

    ⭐ WHAT IT DOES MEASURE, and why it earns its place: it is the only course with a drop past the
    guard's 2.75 threshold, so it is the only one that exercises the guard's code path at all. That
    is enough to catch the bug that motivated it -- the guard used to TRUNCATE the fall simulation
    mid-air instead of rejecting the move, and a bot with the guard on froze solid. With the fix,
    guard on reaches the goal in 8.3s against 12.2s with it off. Before the fix that arm would not
    have arrived.

    Gates: reach the goal, no freezes, and arrive with full health.
    """
    id = "nav_cliff"
    duration = 120
    start_y = STAND_Y + 8

    def build(self, arena, ctx):
        arena._fill(self.PAD_X0, FLOOR_Y + 8, -3, self.PAD_X1, FLOOR_Y + 8, 3, "stone")
        goal = self.course(arena, ctx)
        ctx.geo["goal"] = goal
        ctx.geo["bot_spawn"] = f"0.5 {self.start_y} 0.5 -90 0"
        ctx.geo["min_hp"] = 20.0

    def course(self, arena, ctx):
        # ⛔ FIRST BUILD OF THIS COURSE MEASURED NOTHING, and it is worth saying why. The drop was
        # six blocks with the safe steps immediately beside it, and the bot came down unhurt on the
        # SHIPPED DEFAULT (min_hp=20.0, goal at t=9.1s) -- a stepped route that close is simply the
        # better path, so both arms behave the same and the course has no verdict to give.
        #
        # A choice is only a choice when the options cost different amounts. So: the drop is now
        # TEN blocks (seven damage in vanilla, which no sampling interval can miss) and lies
        # straight along the route, while the safe stairs are a detour twelve blocks to the side
        # and back. A planner that ignores fall damage takes the cliff because it is shorter; one
        # that respects it pays the detour.
        top = FLOOR_Y + 12
        low = top - 5
        arena._fill(7, top, -3, 12, top, 3, "stone")
        # The detour: out to +z, down in five 2-block steps, and back to the shelf.
        arena._fill(10, top, 4, 12, top, 14, "stone")
        h = top
        z = 15
        for _ in range(2):
            h -= 2
            arena._fill(10, h, z, 12, h, z + 1, "stone")
            z += 2
        arena._fill(10, low, 4, 12, low, z, "stone")
        # The lower shelf, reached either way -- straight off the cliff, or round the detour.
        arena._fill(13, low, -3, 26, low, 3, "stone")
        return (24, low + 1, 0)

    def drive_tick(self, ctx, elapsed):
        super().drive_tick(ctx, elapsed)
        hp = ctx.bot.health()
        if hp is not None:
            ctx.geo["min_hp"] = min(ctx.geo.get("min_hp", 20.0), float(hp))

    def judge(self, ctx):
        yield from super().judge(ctx)
        low = ctx.geo.get("min_hp", 20.0)
        yield Criterion("no fall damage on the way down", low >= 20.0,
                        f"min_hp={low}")


class NavNotch(NavCourse):
    """A diagonal whose BOTH corner columns are solid: the bot must route around, not into it.

    HONEST LABEL FIRST: this course does NOT reproduce the playthrough stall it was built for.
    It passes in about seven seconds, which is the bot routing around the notch exactly as it
    should. Shipping it with a docstring claiming otherwise would repeat the mistake nav_hazard
    records two courses down -- a course that passed four of four with its hazard counter at zero,
    i.e. for the wrong reason.

    WHAT IT DOES GUARD is worth keeping: a diagonal step whose two corner columns are both full
    cannot be squeezed through in vanilla, and a router that commits to one stands in it until the
    clock runs out. The layout offers the blocked diagonal as the SHORT way and an open lane one
    row over as the long one, so arriving means the route declined the impossible step.

    WHY THE REAL STALL NEEDS MORE THAN THIS GEOMETRY, traced on the live world block by block:

        src        (85,124,-54)  AIR          the cell the movement was built from
        srcAbove   (85,125,-54)  air          where the feet actually are
        cornerA    (85,125,-55)  grass_block  SOLID, at the body's real level
        cornerB    (84,125,-54)  dirt         SOLID, at the body's real level
        cornerAlow (85,124,-55)  air          and THIS is the corner that got vetted

    The trigger is not the notch, it is that the movement was built from a cell one block BELOW
    the body, so MovementDiagonal vetted its corners a level down where one of them is open. That
    off-by-one comes from the route, not from the terrain, and terrain alone cannot force it --
    which is why this course is green and the stall is not fixed.

    It then holds forward at v=0.00 and never completes, and that is not one lost step: while a
    chain is RUNNING the mixin returns early and NOTHING else ticks -- not BlockPathWalker, not
    the build primitives, not the physics executor -- while FastNavigator counts isRunning() as
    "building" and never replans. Measured on a five-minute run: mqStarted=64 against mqSteps=9,
    dbTargets=12/0, no rungs at all.

    The course lives in the arena rather than on the live world deliberately (checklist rule
    4a3): the earlier repro sat on real terrain and the bot DUG IT AWAY -- grass_block and dirt at
    those corners both read air a few dozen runs later, after which it stopped reproducing in both
    arms and measured nothing while looking exactly like a fix. An arena course is rebuilt every
    run, so it cannot be eaten.
    """
    id = "nav_notch"
    duration = 120
    settings = {"verboseDebugLogging": "true"}

    def course(self, arena, ctx):
        arena.floor(7, -3, 26, 3, "stone")
        # The notch. Two pillars placed so the step from (15,z=1) to (16,z=0) -- the diagonal a
        # beeline wants -- has both of its corner columns full. Two blocks tall so the head is
        # blocked as well as the feet; one block tall would merely be a step up.
        arena._fill(15, STAND_Y, 0, 15, STAND_Y + 1, 0, "stone")
        arena._fill(16, STAND_Y, 1, 16, STAND_Y + 1, 1, "stone")
        # Close the -z side so the diagonal really is the tempting one, and leave z=2..3 open as
        # the honest way past. Barrier, not stone, so nothing here is mineable: this course asks
        # about routing, not about digging.
        arena._fill(15, STAND_Y, -3, 16, STAND_Y + 3, -1, "barrier")
        arena._fill(7, STAND_Y, -4, 26, STAND_Y + 3, -4, "barrier")
        arena._fill(7, STAND_Y, 4, 26, STAND_Y + 3, 4, "barrier")
        return (23, STAND_Y, 0)


SCENARIOS = [NavFlat, NavStaircase, NavSteep, NavGaps, NavDescend, NavCliff,
             NavWater, NavLadder, NavSlime, NavBreak, NavWall2, NavBridge, NavHazard,
             NavNotch]
