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
        # FPS/engine sampling doubles as the PERF-1 baseline. Cheap and read-only.
        ok, st = ctx.bot.py.try_call("getPerfStats")
        if ok and isinstance(st, dict) and st.get("fps") is not None:
            try:
                ctx.geo["fps"].append(float(st["fps"]))
            except (TypeError, ValueError):
                pass
        d = self._dist_to_goal(ctx)
        if d is not None and ctx.geo.get("reached_at") is None and d < self.goal_tolerance:
            ctx.geo["reached_at"] = elapsed

    def early_stop(self, ctx):
        return ctx.geo.get("reached_at") is not None

    def judge(self, ctx):
        t = ctx.geo.get("reached_at")
        d = self._dist_to_goal(ctx)
        fps = ctx.geo.get("fps") or []
        avg_fps = sum(fps) / len(fps) if fps else None
        ctx.geo["avg_fps"] = avg_fps
        yield Criterion(f"reached goal (tol {self.goal_tolerance})", t is not None,
                        f"t={t if t is None else round(t, 1)}s final_dist="
                        f"{None if d is None else round(d, 1)}")
        yield Criterion("no self-fall", ctx.self_falls == 0,
                        f"self_falls={ctx.self_falls}")
        yield Criterion("freezes == 0", ctx.freeze_windows == 0,
                        f"freezes={ctx.freeze_windows}")
        # Reported, never a gate: FPS on a software-GL container is not a pass/fail
        # number, it is a trend line to compare before/after a perf change against.
        yield Criterion("fps recorded", True,
                        f"avg_fps={None if avg_fps is None else round(avg_fps, 1)} "
                        f"samples={len(fps)}", gate=False)


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
        # carve a 6-wide, 3-deep pool and fill it
        arena._fill(12, FLOOR_Y - 2, -3, 17, FLOOR_Y, 3, "air")
        arena._fill(12, FLOOR_Y - 2, -3, 17, FLOOR_Y, 3, "water")
        return (26, STAND_Y, 0)


class NavLadder(NavCourse):
    """Climb a 4-high ladder onto a shelf — the ladder move set."""
    id = "nav_ladder"

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
    settings = {"verboseDebugLogging": "true"}
    start_y = FLOOR_Y + 8

    def build(self, arena, ctx):
        arena._fill(self.PAD_X0, FLOOR_Y + 7, -3, self.PAD_X1, FLOOR_Y + 7, 3, "stone")
        goal = self.course(arena, ctx)
        ctx.geo["goal"] = goal
        ctx.geo["bot_spawn"] = f"0.5 {self.start_y} 0.5 -90 0"

    def course(self, arena, ctx):
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


SCENARIOS = [NavFlat, NavStaircase, NavSteep, NavGaps, NavDescend,
             NavWater, NavLadder, NavSlime, NavBreak, NavWall2]
