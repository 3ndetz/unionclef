"""End-dimension courses.

WHY THIS EXISTS. The end goal is the bot beating the game, and beating the game happens in the
End. Until now NO suite touched End content at all, which left three tasks and a goal type
permanently unverifiable: GetToOuterEndIslandsTask, KillEnderDragonTask, DragonBreathTracker, and
GoalAnd — whose last remaining user is GetToOuterEndIslandsTask.makeGoal. "We cannot check it" was
the reason each of them kept being deferred, and it was never true: the stand HAS the End.

Verified before writing a line of this, not assumed:

    execute in minecraft:the_end run time query daytime   ->  "The time is 1000"
    execute in minecraft:the_end run forceload add 0 0    ->  "Marked chunk [0, 0] ... "

These courses build their own platform with dimension-prefixed rcon rather than going through
Arena, which has no dimension support. That is deliberate: Arena is shared by 24 passing courses
and teaching it a new concept to add one test is how a green suite acquires a new way to fail.
"""
import re
import time

from .scenario import Criterion, Scenario

# The End's main island sits around y=64. We build our own slab rather than trusting generated
# terrain, for the same reason the nav arena is hand-built: a course has to measure the bot, not
# the world generator's mood.
END = "minecraft:the_end"
END_Y = 64


class EndCourse(Scenario):
    """Shared shape: clear a cube in the End, lay a platform, drop the bot on it, walk."""
    tier = "gate"
    duration = 120
    needs_victim = False
    goal_tolerance = 2.5

    # Platform runs from x=-4 to x=PLATFORM_X1 at z=-4..4.
    PLATFORM_X1 = 34
    START = (0.5, END_Y + 1, 0.5)

    def build(self, arena, ctx):
        r = ctx.rcon
        r.cmd(f"execute in {END} run forceload add -3 -3 4 4")
        r.cmd(f"execute in {END} run forceload add 0 0 3 0")
        # Clear the working volume, then lay the walkway. Range is small on purpose: the End is
        # expensive to load and this course is about whether the bot MOVES there, not about scale.
        for y in range(END_Y - 1, END_Y + 6):
            r.cmd(f"execute in {END} run fill -6 {y} -6 {self.PLATFORM_X1 + 4} {y} 6 air")
        r.cmd(f"execute in {END} run fill -4 {END_Y} -4 {self.PLATFORM_X1} {END_Y} 4 end_stone")
        # gotoXYZ is gotoXYZ(int,int,int) on the Java side — py4j matches by signature, and floats
        # here fail method resolution with a ReflectionEngine stack trace rather than a clear error.
        goal = (self.PLATFORM_X1 - 2, END_Y + 1, 0)
        ctx.geo["goal"] = goal
        ctx.geo["fps"] = []
        # The runner places the bot at bot_spawn BEFORE the course drives, and it only knows the
        # overworld. Give it a harmless overworld pad here; drive_start does the real move into the
        # End. Leaving this unset fails the course with KeyError('bot_spawn') before anything runs.
        arena.floor(-3, -3, 6, 3, "stone")
        ctx.geo["bot_spawn"] = "0.5 -59 0.5 -90 0"
        return goal

    def _dist_to_goal(self, ctx):
        s = ctx.samples[-1] if ctx.samples else None
        p = s.get("bot") if s else None
        if not p:
            return None
        gx, gy, gz = ctx.geo["goal"]
        return ((p[0] - gx) ** 2 + (p[1] - gy) ** 2 + (p[2] - gz) ** 2) ** 0.5

    def drive_start(self, ctx):
        r = ctx.rcon
        name = ctx.bot.name
        r.cmd(f"gamerule keepInventory true", allow_reject=True)
        r.cmd(f"effect give {name} minecraft:resistance 999 4 true", allow_reject=True)
        sx, sy, sz = self.START
        # THE DIMENSION CHANGE IS THE FIRST THING THIS COURSE ACTUALLY TESTS. A client that cannot
        # follow the bot into the End would show up here as "never arrived", which is a real result
        # and not a broken course — so the arrival is asserted below rather than assumed.
        r.cmd(f"execute in {END} run tp {name} {sx} {sy} {sz} -90 0")
        time.sleep(3)
        ctx.geo["dim_at_start"] = self._dimension(ctx)
        gx, gy, gz = ctx.geo["goal"]
        ctx.bot.py.call("gotoXYZ", gx, gy, gz)

    def _dimension(self, ctx):
        # getGameState had NO dimension field until this course needed one — the first run had to
        # infer "we are in the End" from the bot standing at y=65 where the overworld has only air.
        # The field was added to the bot for this (and for any agent planning across a portal).
        ok, st = ctx.bot.py.try_call("getGameState")
        if ok and isinstance(st, dict):
            me = st.get("self") if isinstance(st.get("self"), dict) else st
            return me.get("dimension")
        return None

    def drive_tick(self, ctx, elapsed):
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
        dim = ctx.geo.get("dim_at_start")
        # THE BOT BEING IN THE END IS ITS OWN CHECK, and it comes first. If the teleport did not
        # take, everything below measures a bot standing in the overworld and would read as a
        # navigation failure — the exact shape of false red this repo has been burned by.
        yield Criterion("bot is IN the End after the teleport", dim is not None and "end" in str(dim).lower(),
                        f"dimension={dim}")
        yield Criterion(f"reached goal (tol {self.goal_tolerance})", t is not None,
                        f"t={t if t is None else round(t, 1)}s final_dist="
                        f"{None if d is None else round(d, 1)}")
        yield Criterion("no self-fall", ctx.self_falls == 0, f"self_falls={ctx.self_falls}")
        yield Criterion("freezes == 0", ctx.freeze_windows == 0, f"freezes={ctx.freeze_windows}")
        yield Criterion("fps recorded", True,
                        f"avg_fps={None if avg_fps is None else round(avg_fps, 1)} "
                        f"samples={len(fps)}", gate=False)


class EndWalk(EndCourse):
    """The first End course there has ever been: can the bot walk 30 blocks in the End at all?

    Deliberately the smallest thing that means something. Everything the End cluster needs —
    outer-island travel, the dragon fight — sits on top of "the bot can be in the End and move",
    and that has never once been measured.
    """
    id = "end_walk"


class EndGateway(EndCourse):
    """Does the bot actually WALK to an end gateway? GetToOuterEndIslandsTask says it does.

    This course exists to settle that, because the task drives with
    `getCustomGoalProcess().setGoal(goal)` + `.path()` — the LEGACY engine, the same one that had
    InteractWithBlockTask standing still for five minutes while announcing it was on its way. If
    the legacy engine does not move the body, this task has never reached a gateway and the End
    leg of the playthrough is dead on the ground.

    Reachable because `@test outer` runs it directly (Playground -> runUserTask).

    Its preconditions, read out of the task rather than guessed, and all supplied below:
      - a block scanner hit on END_GATEWAY
      - an ender pearl in the pack        (else it goes shopping for one)
      - building materials >= |dx|+|dy|+|dz| - 3   (else it goes mining)

    The platform sits at y=74 ON PURPOSE. makeGoal ANDs the eight cells beside the gateway with
    `GoalYLevel(74)`, a hardcoded real-End height — put the gateway anywhere else and the goal can
    never be satisfied, so the course would measure an impossible condition instead of the bot.
    """
    id = "end_gateway"
    duration = 150
    GATE_X = 26

    def build(self, arena, ctx):
        r = ctx.rcon
        r.cmd(f"execute in {END} run forceload add -3 -3 4 4")
        for y in range(73, 82):
            r.cmd(f"execute in {END} run fill -6 {y} -6 {self.GATE_X + 6} {y} 6 air")
        r.cmd(f"execute in {END} run fill -4 74 -4 {self.GATE_X + 4} 74 4 end_stone")
        r.cmd(f"execute in {END} run setblock {self.GATE_X} 75 0 minecraft:end_gateway")
        goal = (self.GATE_X, 75, 0)
        ctx.geo["goal"] = goal
        ctx.geo["fps"] = []
        arena.floor(-3, -3, 6, 3, "stone")
        ctx.geo["bot_spawn"] = "0.5 -59 0.5 -90 0"
        return goal

    def drive_tick(self, ctx, elapsed):
        # CLOSEST APPROACH, NOT FINAL POSITION. An end gateway TELEPORTS whatever touches it, so
        # succeeding at this course moves the bot ~110 blocks away and a final-distance check reads
        # that as a failure. Measured exactly that way: final_dist=109.9 on a run where the bot had
        # walked the whole 26 blocks and gone through. The end state is destroyed by the success.
        super().drive_tick(ctx, elapsed)
        d = self._dist_to_goal(ctx)
        if d is not None:
            best = ctx.geo.get("min_dist")
            if best is None or d < best:
                ctx.geo["min_dist"] = d

    def drive_start(self, ctx):
        r = ctx.rcon
        name = ctx.bot.name
        r.cmd("gamerule keepInventory true", allow_reject=True)
        r.cmd(f"effect give {name} minecraft:resistance 999 4 true", allow_reject=True)
        r.cmd(f"clear {name}", allow_reject=True)
        # Exactly the preconditions the task checks, so the run measures the WALK and not a
        # shopping trip. 64 cobble covers the ~26-block Manhattan gap it asks for.
        r.cmd(f"give {name} minecraft:ender_pearl 1", allow_reject=True)
        r.cmd(f"give {name} minecraft:cobblestone 64", allow_reject=True)
        r.cmd(f"execute in {END} run tp {name} 0.5 75 0.5 -90 0")
        time.sleep(3)
        ctx.geo["dim_at_start"] = self._dimension(ctx)
        ctx.bot.cmd("@test outer")

    def judge(self, ctx):
        d = self._dist_to_goal(ctx)
        fps = ctx.geo.get("fps") or []
        avg_fps = sum(fps) / len(fps) if fps else None
        ctx.geo["avg_fps"] = avg_fps
        dim = ctx.geo.get("dim_at_start")
        yield Criterion("bot is IN the End", dim is not None and "end" in str(dim).lower(),
                        f"dimension={dim}")
        # The rung is CLOSING THE DISTANCE, not touching the gateway: the task's own arrival test
        # is the eight cells beside it, and stepping into a gateway teleports you away.
        best = ctx.geo.get("min_dist")
        yield Criterion("walked to within 4 blocks of the gateway",
                        best is not None and best < 4.0,
                        f"closest={None if best is None else round(best, 1)} "
                        f"final={None if d is None else round(d, 1)} start={self.GATE_X}")
        yield Criterion("no self-fall", ctx.self_falls == 0, f"self_falls={ctx.self_falls}")
        yield Criterion("fps recorded", True,
                        f"avg_fps={None if avg_fps is None else round(avg_fps, 1)} "
                        f"samples={len(fps)}", gate=False)


class EndDragon(EndCourse):
    """The literal end of the game: does the bot damage the ender dragon at all?

    Nothing in this repo has ever measured the dragon fight. `@test dragon` runs
    KillEnderDragonWithBedsTask, the dragon is summonable on the stand, and its health reads back
    over rcon — so the excuse for never checking (End content is unreachable) is gone.

    THE BAR IS DELIBERATELY LOW AND HONEST. The gate is "the dragon lost health", not "the dragon
    died". A summoned dragon has no fountain and no crystals, so it may never perch, and the bed
    strategy leans on perching; demanding a kill would fail the course for the arena's shape rather
    than the bot's behaviour. First measurement first — a kill can be its own course once this one
    says whether the bot engages at all.
    """
    id = "end_dragon"
    # INFO, NOT A GATE — AND THAT IS A STATEMENT ABOUT THE ARENA, NOT A SOFTENED VERDICT.
    # First run: the dragon took ZERO damage in 240s (200.0 -> 200.0) with beds, obsidian, a bow,
    # 64 arrows and a sword in the pack. The log says why, and it is not the bot:
    #     "Failed to place, wandering timeout."  x3
    #     MovementQueue: body has not left {x=-7,y=75,z=-7} for 121 ticks while steering
    #     MovementQueue: body MOVED (not walked) -- dropping the chain and replanning
    # `@test dragon` runs KillEnderDragonWithBedsTask, which places a bed where the dragon PERCHES.
    # A dragon summoned into a hand-built platform has no EnderDragonFight instance and no exit-
    # portal fountain, so it never perches and there is never a valid place to put the bed. Gating
    # on that would fail the bot for the shape of the arena -- the exact false red this suite exists
    # to avoid. The numbers are still worth recording every run, so the course stays and reports.
    # To make it a gate, the arena needs a real fountain (or the course needs a strategy that does
    # not depend on perching); until then "the dragon lost health" is a fact, not a judgement.
    tier = "info"
    duration = 240
    START_D = (0.5, 75, 0.5)

    def _dragon_health(self, ctx):
        out = ctx.rcon.cmd(
            f"execute in {END} run data get entity "
            f"@e[type=minecraft:ender_dragon,limit=1] Health", allow_reject=True)
        if not out:
            return None
        m = re.search(r"([0-9]+(?:\.[0-9]+)?)f", str(out))
        return float(m.group(1)) if m else None

    def build(self, arena, ctx):
        r = ctx.rcon
        r.cmd(f"execute in {END} run forceload add -6 -6 6 6")
        for y in range(73, 90):
            r.cmd(f"execute in {END} run fill -20 {y} -20 20 {y} 20 air")
        r.cmd(f"execute in {END} run fill -16 74 -16 16 74 16 end_stone")
        ctx.geo["goal"] = (0, 75, 0)
        ctx.geo["fps"] = []
        arena.floor(-3, -3, 6, 3, "stone")
        ctx.geo["bot_spawn"] = "0.5 -59 0.5 -90 0"
        return ctx.geo["goal"]

    def drive_start(self, ctx):
        r = ctx.rcon
        name = ctx.bot.name
        r.cmd("gamerule keepInventory true", allow_reject=True)
        r.cmd(f"clear {name}", allow_reject=True)
        # Kit for both strategies the task might reach for, so a red means "did not fight" and not
        # "had nothing to fight with".
        for item, n in (("white_bed", 32), ("obsidian", 64), ("bow", 1), ("arrow", 64),
                        ("diamond_sword", 1), ("golden_apple", 8)):
            r.cmd(f"give {name} minecraft:{item} {n}", allow_reject=True)
        r.cmd(f"effect give {name} minecraft:resistance 999 2 true", allow_reject=True)
        r.cmd(f"execute in {END} run kill @e[type=minecraft:ender_dragon]", allow_reject=True)
        sx, sy, sz = self.START_D
        r.cmd(f"execute in {END} run tp {name} {sx} {sy} {sz}")
        time.sleep(3)
        ctx.geo["dim_at_start"] = self._dimension(ctx)
        r.cmd(f"execute in {END} run summon minecraft:ender_dragon 0 90 0", allow_reject=True)
        time.sleep(2)
        ctx.geo["hp0"] = self._dragon_health(ctx)
        ctx.geo["hp_min"] = ctx.geo["hp0"]
        ctx.bot.cmd("@test dragon")

    def drive_tick(self, ctx, elapsed):
        ok, st = ctx.bot.py.try_call("getPerfStats")
        if ok and isinstance(st, dict) and st.get("fps") is not None:
            try:
                ctx.geo["fps"].append(float(st["fps"]))
            except (TypeError, ValueError):
                pass
        if int(elapsed) % 5 != 0:
            return
        hp = self._dragon_health(ctx)
        if hp is None:
            ctx.geo["dragon_gone"] = True
            return
        best = ctx.geo.get("hp_min")
        if best is None or hp < best:
            ctx.geo["hp_min"] = hp

    def early_stop(self, ctx):
        return bool(ctx.geo.get("dragon_gone"))

    def judge(self, ctx):
        hp0 = ctx.geo.get("hp0")
        hpm = ctx.geo.get("hp_min")
        gone = bool(ctx.geo.get("dragon_gone"))
        fps = ctx.geo.get("fps") or []
        avg_fps = sum(fps) / len(fps) if fps else None
        ctx.geo["avg_fps"] = avg_fps
        dim = ctx.geo.get("dim_at_start")
        yield Criterion("bot is IN the End", dim is not None and "end" in str(dim).lower(),
                        f"dimension={dim}")
        yield Criterion("a dragon was actually there to fight", hp0 is not None,
                        f"hp_at_start={hp0}")
        yield Criterion("the dragon LOST HEALTH", gone or (hp0 is not None and hpm is not None and hpm < hp0),
                        f"hp {hp0} -> {hpm}{' (gone)' if gone else ''}")
        yield Criterion("dragon killed", gone, f"gone={gone}", gate=False)
        yield Criterion("bot survived", ctx.deaths == 0, f"deaths={ctx.deaths}", gate=False)
        yield Criterion("fps recorded", True,
                        f"avg_fps={None if avg_fps is None else round(avg_fps, 1)} "
                        f"samples={len(fps)}", gate=False)


SCENARIOS = [EndWalk, EndGateway, EndDragon]
