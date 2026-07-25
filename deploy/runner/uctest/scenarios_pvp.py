"""uctest pvp suite — the scenario catalogue (docs/features/PVP_SUITE.md).

Chase, bow-flee, bridge-assault, narrow/high bedwars bridges, edge duels,
ranged + melee integration. Gate scenarios block the suite; info scenarios
record today's honest capability without blocking (promoted after the
corresponding rework lands)."""
import time

from .actors import KIT_BOW, KIT_BRIDGER, KIT_SWORD
from .arena import FLOOR_Y, STAND_Y
from .scenario import Criterion, Scenario


def _dist_xz(pos, x, z):
    return ((pos[0] - x) ** 2 + (pos[2] - z) ** 2) ** 0.5


class MeleeBasic(Scenario):
    """Mutual close combat in tall grass — the original freeze case, upgraded
    to a target that fights back (RW-1)."""
    id = "melee_basic"
    duration = 60
    settings = {"combatMovementsEnabled": "true"}
    bot_kit = KIT_SWORD
    victim_kit = KIT_SWORD

    def build(self, arena, ctx):
        arena.flat_field(half=14, grass=True)
        ctx.geo["bot_spawn"] = f"-5.5 {STAND_Y} 0.5 -90 0"
        ctx.geo["victim_spawn"] = f"9.5 {STAND_Y} 0.5 90 0"

    def drive_start(self, ctx):
        ctx.bot.py.call("punk", ctx.victim.name)
        ctx.victim.py.call("punk", ctx.bot.name)

    def early_stop(self, ctx):
        return ctx.kills() >= 1

    def judge(self, ctx):
        fh = ctx.first_hit()
        yield Criterion("first hit <= 15s", fh is not None and fh <= 15,
                        f"first_hit={fh}")
        yield Criterion("damage >= 8", ctx.victim_damage() >= 8,
                        f"damage={ctx.victim_damage():.1f}")
        yield ctx.exchange_criterion()   # mutual punk — winning the trade is the bar
        yield Criterion("freezes == 0", ctx.freeze_windows == 0,
                        f"freezes={ctx.freeze_windows}")
        yield Criterion("stand-still near target <= 2",
                        ctx.standstill_windows <= 2,
                        f"windows={ctx.standstill_windows}")


class EdgeDuel(Scenario):
    """5x5 platform over void — RW-1 'both keep footing 1 block from drop'."""
    id = "edge_duel"
    duration = 60
    settings = {"combatMovementsEnabled": "true"}
    bot_kit = KIT_SWORD
    victim_kit = KIT_SWORD

    def build(self, arena, ctx):
        arena.edge_platform(half=2)
        ctx.geo["bot_spawn"] = f"-1.5 {STAND_Y} -1.5 45 0"
        ctx.geo["victim_spawn"] = f"1.5 {STAND_Y} 1.5 -135 0"

    def drive_start(self, ctx):
        ctx.bot.py.call("punk", ctx.victim.name)
        ctx.victim.py.call("punk", ctx.bot.name)

    def judge(self, ctx):
        yield Criterion("kill >= 1", ctx.kills() >= 1, f"kills={ctx.kills()}")
        yield ctx.exchange_criterion()          # mutual duel: must not lose it
        yield Criterion("self-falls == 0", ctx.self_falls == 0,
                        f"self={ctx.self_falls} knockback={ctx.knockback_falls}")


class NarrowBridgeDuel(Scenario):
    """Two islands + 1-wide bridge over void (bedwars walkway). Spawns force
    the fight ONTO the bridge."""
    id = "narrow_bridge_duel"
    duration = 90
    settings = {"combatMovementsEnabled": "true"}
    bot_kit = KIT_SWORD
    victim_kit = KIT_SWORD

    def build(self, arena, ctx):
        a, b, ax, bx = arena.two_islands(gap=9, island_half=2, bridge_width=1)
        ctx.geo.update(bot_spawn=a, victim_spawn=b, edge_a=ax, edge_b=bx)

    def drive_start(self, ctx):
        ctx.bot.py.call("punk", ctx.victim.name)
        ctx.victim.py.call("punk", ctx.bot.name)

    def judge(self, ctx):
        yield Criterion("kill >= 1", ctx.kills() >= 1, f"kills={ctx.kills()}")
        yield ctx.exchange_criterion()          # mutual duel: must not lose it
        yield Criterion("self-falls == 0", ctx.self_falls == 0,
                        f"self={ctx.self_falls} knockback={ctx.knockback_falls}")


class ChaseFlat(Scenario):
    """Victim loops a rectangle on baritone @goto; tungsten @follow must
    catch via corner-cutting (RW-9 flat tier)."""
    id = "chase_flat"
    duration = 90
    WAYPOINTS = [(-10, -10), (10, -10), (10, 10), (-10, 10)]

    def build(self, arena, ctx):
        arena.flat_field(half=20)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 0 0"
        ctx.geo["victim_spawn"] = f"-10.5 {STAND_Y} -10.5 0 0"
        ctx.geo["wp"] = 0

    def drive_start(self, ctx):
        x, z = self.WAYPOINTS[0]
        ctx.victim.cmd(f"@goto {x} {STAND_Y} {z}")
        ctx.bot.cmd(f"@follow {ctx.victim.name}")

    def drive_tick(self, ctx, t):
        vp = ctx.samples[-1].get("victim") if ctx.samples else None
        if not vp:
            return
        x, z = self.WAYPOINTS[ctx.geo["wp"] % 4]
        if _dist_xz(vp, x + 0.5, z + 0.5) < 2.5:
            ctx.geo["wp"] += 1
            nx, nz = self.WAYPOINTS[ctx.geo["wp"] % 4]
            ctx.victim.cmd(f"@goto {nx} {STAND_Y} {nz}")

    def judge(self, ctx):
        # "catches" = made contact (RW-9's definition) + trails the victim
        # closely. The victim loops NON-STOP, so a chaser can never hold <~4
        # avg on a flat loop; <7 separates catching-and-trailing (~5-6) from
        # never-catching (falls to 10-12). Terrain uses the same idea via
        # contact-made only.
        yield Criterion("contact <= 45s",
                        ctx.first_contact is not None and ctx.first_contact <= 45,
                        f"contact={ctx.first_contact}")
        avg = ctx.avg_dist(since=max(0, ctx.duration() - 30))
        yield Criterion("avg dist (last 30s) < 7 (non-stop looper)",
                        avg is not None and avg < 7, f"avg={avg}")
        yield Criterion("freezes == 0", ctx.freeze_windows == 0,
                        f"freezes={ctx.freeze_windows}")


class ChaseTerrain(Scenario):
    """RW-9 bench on REAL generated terrain (gamer-server, seed 12345 — hills,
    trees, water, cliffs; NOT a hand-built strip). The victim is sent running
    on baritone toward a far point; our bot must CATCH it with tungsten and
    KILL it. This is the user's definition of the bench."""
    id = "chase_terrain"
    duration = 180
    world = "gamer"            # real world generator
    builds_arena = False       # play the terrain as generated
    bot_kit = ["item replace entity {name} weapon.mainhand with iron_sword"]
    victim_kit = []
    settings = {"combatMovementsEnabled": "true"}
    RUN_DIST = 140             # how far the runner is sent, in blocks

    def build(self, arena, ctx):
        # Start both at the world spawn area; the terrain is whatever generated.
        # A fixed offset gives the runner a head start along +x.
        rc = ctx.rcon
        rc.cmd("gamerule pvp true")
        rc.cmd("gamerule immediate_respawn true")
        rc.cmd("difficulty peaceful")   # isolate the chase from mob interference
        rc.cmd("time set day")
        rc.cmd("weather clear")
        rc.cmd("forceload add -200 -200 200 200")
        # Deterministic placement on the real surface. `spreadplayers` was used
        # here and silently failed ("Incorrect argument for command"), so the bots
        # were never relocated and every run restarted from wherever the previous
        # one left them — three "the chase is broken" results were measured from a
        # bot standing in the same stuck spot. Probe the column instead.
        # Scan DOWNWARD from the sky for the first solid block: that is the
        # surface. Scanning up from below finds the roof of the first cave and
        # drops the bot underground (measured: spawned at y=50 in a cavern, fell,
        # died — the run was garbage before the chase even started).
        sx, sz = 0, 0
        sy = None
        for y in range(200, 45, -1):
            if "Test passed" not in rc.cmd(f"execute if block {sx} {y} {sz} air"):
                sy = y + 1
                break
        if sy is None:
            sy = 100
        rc.cmd(f"tp {ctx.bot.name} {sx}.5 {sy} {sz}.5")
        rc.cmd(f"tp {ctx.victim.name} {sx + 6}.5 {sy} {sz}.5")
        time.sleep(2)
        bp = ctx.bot.pos() or [sx, sy, sz]
        ctx.geo["bot_spawn"] = f"{bp[0]:.1f} {bp[1]:.1f} {bp[2]:.1f}"
        ctx.geo["victim_spawn"] = f"{bp[0] + 6:.1f} {bp[1]:.1f} {bp[2]:.1f}"
        ctx.geo["goal"] = (int(bp[0]) + self.RUN_DIST, int(bp[1]), int(bp[2]))

    def drive_start(self, ctx):
        gx, gy, gz = ctx.geo["goal"]
        # runner: plain baritone @goto over real terrain — it will climb, swim,
        # walk around obstacles on its own.
        ctx.victim.cmd(f"@goto {gx} {gy} {gz}")
        time.sleep(1.0)
        # chaser: tungsten punk = approach (pathfinder) + combat when in reach
        ctx.bot.py.call("punk", ctx.victim.name)

    def drive_tick(self, ctx, t):
        # keep the runner running: baritone finishes/aborts on rough ground, so
        # re-issue the goal periodically (it is the prey, it must never idle)
        if int(t) % 20 == 0 and ctx.samples:
            gx, gy, gz = ctx.geo["goal"]
            ctx.victim.cmd(f"@goto {gx} {gy} {gz}")

    def early_stop(self, ctx):
        return ctx.kills() >= 1

    def judge(self, ctx):
        yield Criterion("caught the runner (contact <= 120s)",
                        ctx.first_contact is not None and ctx.first_contact <= 120,
                        f"contact={ctx.first_contact}")
        yield Criterion("killed the runner", ctx.kills() >= 1,
                        f"kills={ctx.kills()}")
        yield ctx.survival_criterion()
        yield Criterion("freezes <= 1", ctx.freeze_windows <= 1,
                        f"freezes={ctx.freeze_windows}")


class BowFlee(Scenario):
    """Flee + shoot back (kiting): runAwayPlayer keeps distance while
    shootArrowAt fires every 3s. INFO until the kite primitive lands: audit
    confirmed RunAwayTask's executor owns the camera and overrides BowShooter
    aim every tick (no arbiter), so flee+shoot cannot work today — this
    scenario records the gap and gets promoted with the kite lever."""
    id = "bow_flee"
    tier = "info"
    duration = 60
    bot_kit = KIT_BOW
    victim_kit = KIT_SWORD
    chaser_slowness = True

    def build(self, arena, ctx):
        arena.flat_field(half=20)
        ctx.geo["bot_spawn"] = f"-12.5 {STAND_Y} 0.5 -90 0"
        ctx.geo["victim_spawn"] = f"12.5 {STAND_Y} 0.5 90 0"

    def drive_start(self, ctx):
        if self.chaser_slowness:
            ctx.rcon.cmd(f"effect give {ctx.victim.name} slowness 999 0 true")
        ctx.victim.py.call("punk", ctx.bot.name)
        ctx.bot.py.call("runAwayPlayer", ctx.victim.name, 12.0)
        ctx.geo["last_shot"] = 0.0

    def drive_tick(self, ctx, t):
        if t - ctx.geo["last_shot"] >= 3.0:
            ctx.geo["last_shot"] = t
            ctx.bot.py.try_call("shootArrowAt", ctx.victim.name)

    def judge(self, ctx):
        hits = ctx.hp_drop_events(who="victim", min_dist=5)
        yield Criterion("survived (0 deaths)", ctx.deaths() == 0,
                        f"deaths={ctx.deaths()}")
        yield Criterion("arrow hits >= 2", len(hits) >= 2, f"hits={len(hits)}")
        yield Criterion("self-falls == 0", ctx.self_falls == 0,
                        f"self={ctx.self_falls}")
        avg = ctx.avg_dist()
        yield Criterion("avg dist >= 7", avg is not None and avg >= 7,
                        f"avg={avg}")


class BowFleeHard(BowFlee):
    """Equal speed — records the real capability gap (info tier)."""
    id = "bow_flee_hard"
    tier = "info"
    chaser_slowness = False

    def judge(self, ctx):
        hits = ctx.hp_drop_events(who="victim", min_dist=5)
        first_death = next((s["t"] for s in ctx.samples if s.get("d", 0) > 0),
                           None)
        yield Criterion("survive >= 30s",
                        first_death is None or first_death >= 30,
                        f"first_death={first_death}")
        yield Criterion("arrow hits >= 1", len(hits) >= 1, f"hits={len(hits)}")


class RangedMoving(Scenario):
    """Six aimed shots at a strafing target across a 24-block lane."""
    id = "ranged_moving"
    duration = 45
    bot_kit = KIT_BOW

    LANE_X = 16          # the strafing lane, well inside the floor
    STRAFE_Z = 6

    def build(self, arena, ctx):
        # floor must cover the WHOLE strafe lane: the first version sent the
        # victim to x=24 on a +-20 floor, so it walked off the edge and died in
        # the void — and its fall damage was then counted as arrow hits.
        arena.flat_field(half=24)
        ctx.geo["bot_spawn"] = f"-8.5 {STAND_Y} 0.5 -90 0"
        ctx.geo["victim_spawn"] = f"{self.LANE_X}.5 {STAND_Y} -{self.STRAFE_Z}.5 90 0"
        ctx.geo["strafe_pos"] = True
        ctx.geo["last_shot"] = -10.0
        ctx.geo["shots"] = 0

    def drive_start(self, ctx):
        ctx.victim.cmd(f"@goto {self.LANE_X} {STAND_Y} {self.STRAFE_Z}")

    def drive_tick(self, ctx, t):
        vp = ctx.samples[-1].get("victim") if ctx.samples else None
        want = self.STRAFE_Z if ctx.geo["strafe_pos"] else -self.STRAFE_Z
        if vp and abs(vp[2] - want) < 2:
            ctx.geo["strafe_pos"] = not ctx.geo["strafe_pos"]
            nz = self.STRAFE_Z if ctx.geo["strafe_pos"] else -self.STRAFE_Z
            ctx.victim.cmd(f"@goto {self.LANE_X} {STAND_Y} {nz}")
        if ctx.geo["shots"] < 6 and t - ctx.geo["last_shot"] >= 6.0:
            ctx.geo["last_shot"] = t
            ctx.geo["shots"] += 1
            ctx.bot.py.try_call("shootArrowAt", ctx.victim.name)

    def judge(self, ctx):
        hits = ctx.arrow_hits(min_dist=8)
        yield Criterion("hits >= 2/6 (vanilla spread)",
                        2 <= len(hits) <= ctx.geo["shots"],
                        f"hits={len(hits)} shots={ctx.geo['shots']}")
        # the victim dying to our ARROWS is the scenario working; only a fall out
        # of the arena would invalidate the measurement
        yield Criterion("victim never fell out of the arena",
                        not ctx.victim_left_arena(),
                        f"victim_deaths={ctx.deaths_of('victim')} (arrow kills are fine)")


class BridgeAssault(Scenario):
    """No bridge exists: bridgeTo across a 9-gap void, cross, then punk the
    defender. Placement rate recorded as the anti-cheaty proxy (RW-2/RW-3)."""
    id = "bridge_assault"
    duration = 120
    settings = {"combatMovementsEnabled": "true"}
    bot_kit = KIT_BRIDGER
    victim_kit = KIT_SWORD
    defended = False

    def build(self, arena, ctx):
        a, b, ax, bx = arena.two_islands(gap=9, island_half=3, bridge_width=0)
        ctx.geo.update(bot_spawn=a, victim_spawn=b, edge_a=ax, edge_b=bx,
                       crossed_at=None, punk_started=False)

    def drive_start(self, ctx):
        # bridgeTo (godbridge) places the block IN HAND — select cobblestone
        # (slot 0) first; the audit noted bridgeTo does not auto-equip.
        ctx.bot.py.call("selectHotbar", 0)
        ctx.bot.py.call("bridgeTo", ctx.geo["edge_b"] + 2, STAND_Y, 0)
        if self.defended:
            ctx.geo["last_def_shot"] = 0.0

    def sample_kwargs(self):
        return {"track_bridge": True}

    def drive_tick(self, ctx, t):
        bp = ctx.samples[-1].get("bot") if ctx.samples else None
        if bp and ctx.geo["crossed_at"] is None and \
                bp[0] >= ctx.geo["edge_b"] and bp[1] >= FLOOR_Y - 0.5:
            ctx.geo["crossed_at"] = t
            ctx.log(f"  crossed the gap at {t:.1f}s")
        if ctx.geo["crossed_at"] is not None and not ctx.geo["punk_started"]:
            ctx.geo["punk_started"] = True
            ctx.bot.py.call("selectHotbar", 1)  # sword for melee
            ctx.bot.py.call("punk", ctx.victim.name)
        if self.defended and t - ctx.geo.get("last_def_shot", 0) >= 4.0:
            ctx.geo["last_def_shot"] = t
            ctx.victim.py.try_call("shootArrowAt", ctx.bot.name)

    def early_stop(self, ctx):
        return ctx.kills() >= 1

    def judge(self, ctx):
        crossed = ctx.geo["crossed_at"]
        yield Criterion("crossed <= 60s", crossed is not None and crossed <= 60,
                        f"crossed={crossed}")
        yield Criterion("kill <= 120s", ctx.kills() >= 1, f"kills={ctx.kills()}")
        yield Criterion("self-falls == 0", ctx.self_falls == 0,
                        f"self={ctx.self_falls}")
        placed = max((s.get("bridge_placed") or 0 for s in ctx.samples),
                     default=0)
        yield Criterion("bridge blocks >= 8", placed >= 8, f"placed={placed}")
        rate = ctx.max_place_rate()
        yield Criterion("placement rate <= 6 blocks/s (anti-cheaty proxy)",
                        rate <= 6, f"max_rate={rate:.1f}/s", gate=False)


class BridgeAssaultDefended(BridgeAssault):
    """Crossing under arrow fire (info tier until stable)."""
    id = "bridge_assault_defended"
    tier = "info"
    duration = 150
    victim_kit = KIT_SWORD + ["give {name} bow 1", "give {name} arrow 64"]
    defended = True

    def judge(self, ctx):
        crossed = ctx.geo["crossed_at"]
        yield Criterion("crossed under fire", crossed is not None,
                        f"crossed={crossed}")
        hp = min((s["bot_hp"] for s in ctx.samples
                  if s.get("bot_hp") is not None), default=None)
        yield Criterion("survived with >= 8 hp", hp is not None and hp >= 8,
                        f"min_hp={hp}")
        yield Criterion("kill <= 150s", ctx.kills() >= 1, f"kills={ctx.kills()}")


class AllRound(Scenario):
    """Ranged-to-melee integration, composed from primitives (the agent-style
    drive): shootArrowAt while the closing enemy is far, punk once he is
    inside 10 blocks. Benches both halves + the switch."""
    id = "allround"
    duration = 120
    settings = {"combatMovementsEnabled": "true"}
    # slot 0 = bow (shootArrowAt needs it IN HAND), slot 1 = sword, + arrows
    bot_kit = ["item replace entity {name} hotbar.0 with bow",
               "item replace entity {name} hotbar.1 with iron_sword",
               "give {name} arrow 64"]
    victim_kit = KIT_SWORD

    def build(self, arena, ctx):
        arena.flat_field(half=24)
        ctx.geo["bot_spawn"] = f"-19.5 {STAND_Y} 0.5 -90 0"
        ctx.geo["victim_spawn"] = f"19.5 {STAND_Y} 0.5 90 0"
        ctx.geo["melee_started"] = False
        ctx.geo["last_shot"] = -10.0

    def drive_start(self, ctx):
        ctx.bot.py.call("selectHotbar", 0)  # bow in hand for the ranged phase
        ctx.victim.py.call("punk", ctx.bot.name)

    def drive_tick(self, ctx, t):
        if ctx.geo["melee_started"]:
            return
        dist = ctx.samples[-1].get("dist") if ctx.samples else None
        if dist is None:
            return
        if dist > 10:
            if t - ctx.geo["last_shot"] >= 2.5:
                ctx.geo["last_shot"] = t
                ctx.bot.py.try_call("shootArrowAt", ctx.victim.name)
        else:
            ctx.geo["melee_started"] = True
            ctx.bot.py.call("selectHotbar", 1)  # sword for melee (punk swings the held item)
            ctx.bot.py.call("punk", ctx.victim.name)

    def early_stop(self, ctx):
        return ctx.kills() >= 1

    def judge(self, ctx):
        ranged = ctx.hp_drop_events(who="victim", min_dist=8)
        yield Criterion("ranged hit while far >= 1", len(ranged) >= 1,
                        f"ranged_hits={len(ranged)}")
        yield Criterion("kill", ctx.kills() >= 1, f"kills={ctx.kills()}")
        yield ctx.survival_criterion()   # 1 kill / 4 deaths is a LOSS, not a pass
        yield Criterion("freezes == 0", ctx.freeze_windows == 0,
                        f"freezes={ctx.freeze_windows}")


class SlabHole(Scenario):
    """REAL-1 regression: a wall whose only opening is 1.5 blocks tall (a bottom
    slab caps a 1-block hole). A 1.8-tall player cannot fit. The block-space
    search used to plan straight through it — passability was an XZ AREA test
    with the height discarded — and the bot stalled at the wall. It must route
    AROUND (the wall is open past z=+/-6) and never enter the fake opening."""
    id = "slab_hole"
    duration = 75
    needs_victim = False
    arena_half = 30

    def build(self, arena, ctx):
        arena.flat_field(half=20, wall=False)
        rc = ctx.rcon
        rc.cmd(f"fill 8 {STAND_Y} -6 8 {STAND_Y + 4} 6 stone")   # the wall
        rc.cmd(f"setblock 8 {STAND_Y} 0 air")                    # 1-block hole
        rc.cmd(f"setblock 8 {STAND_Y + 1} 0 stone_slab[type=bottom]")  # capped -> 1.5
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 90 0"
        ctx.geo["goal"] = (16, STAND_Y, 0)

    def drive_start(self, ctx):
        gx, gy, gz = ctx.geo["goal"]
        ctx.bot.py.call("gotoXYZ", gx, gy, gz)

    def early_stop(self, ctx):
        p = ctx.samples[-1].get("bot") if ctx.samples else None
        return bool(p and p[0] > 13.5)

    def judge(self, ctx):
        xs = [s["bot"][0] for s in ctx.samples if s.get("bot")]
        zs = [s["bot"][2] for s in ctx.samples if s.get("bot")]
        inside = any(6.5 < x < 9.5 and abs(z) < 1.5 for x, z in zip(xs, zs))
        crossed = any(x > 12 for x in xs)
        yield Criterion("never entered the impossible 1.5-high opening", not inside,
                        f"max_x={max(xs) if xs else None}")
        yield Criterion("routed around and crossed the wall", crossed,
                        f"max_x={max(xs) if xs else None}")
        yield Criterion("freezes == 0", ctx.freeze_windows == 0,
                        f"freezes={ctx.freeze_windows}")


SCENARIOS = [MeleeBasic, EdgeDuel, NarrowBridgeDuel, ChaseFlat, ChaseTerrain,
             BowFlee, BowFleeHard, RangedMoving, BridgeAssault,
             BridgeAssaultDefended, AllRound, SlabHole]
