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
        yield Criterion("contact <= 45s",
                        ctx.first_contact is not None and ctx.first_contact <= 45,
                        f"contact={ctx.first_contact}")
        avg = ctx.avg_dist(since=max(0, ctx.duration() - 30))
        yield Criterion("avg dist (last 30s) < 4",
                        avg is not None and avg < 4, f"avg={avg}")
        yield Criterion("freezes == 0", ctx.freeze_windows == 0,
                        f"freezes={ctx.freeze_windows}")


class ChaseTerrain(Scenario):
    """RW-9 bench: victim ping-pongs a deterministic terrain strip on
    baritone; tungsten @follow MUST catch on rough ground."""
    id = "chase_terrain"
    duration = 120

    def build(self, arena, ctx):
        x0, x1, h_end = arena.terrain_strip()
        ctx.geo["bot_spawn"] = f"{x0 - 2}.5 {STAND_Y} 0.5 -90 0"
        ctx.geo["victim_spawn"] = f"{x0 + 6}.5 {STAND_Y + 1} 0.5 -90 0"
        ctx.geo.update(x0=x0, x1=x1, h_end=h_end, target_far=True)

    def drive_start(self, ctx):
        ctx.geo["target_far"] = True
        ctx.victim.cmd(f"@goto {ctx.geo['x1']} {STAND_Y + ctx.geo['h_end']} 0")
        ctx.bot.cmd(f"@follow {ctx.victim.name}")

    def drive_tick(self, ctx, t):
        vp = ctx.samples[-1].get("victim") if ctx.samples else None
        if not vp:
            return
        gx = ctx.geo["x1"] if ctx.geo["target_far"] else ctx.geo["x0"]
        if abs(vp[0] - gx) < 2.5:
            ctx.geo["target_far"] = not ctx.geo["target_far"]
            nx = ctx.geo["x1"] if ctx.geo["target_far"] else ctx.geo["x0"]
            ny = STAND_Y + (ctx.geo["h_end"] if ctx.geo["target_far"] else 0)
            ctx.victim.cmd(f"@goto {nx} {ny} 0")

    def judge(self, ctx):
        yield Criterion("contact <= 90s",
                        ctx.first_contact is not None and ctx.first_contact <= 90,
                        f"contact={ctx.first_contact}")
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

    def build(self, arena, ctx):
        arena.flat_field(half=20)
        ctx.geo["bot_spawn"] = f"0.5 {STAND_Y} 0.5 -90 0"
        ctx.geo["victim_spawn"] = f"24.5 {STAND_Y} -6.5 90 0"
        ctx.geo["strafe_pos"] = True
        ctx.geo["last_shot"] = -10.0
        ctx.geo["shots"] = 0

    def drive_start(self, ctx):
        ctx.victim.cmd(f"@goto 24 {STAND_Y} 6")

    def drive_tick(self, ctx, t):
        vp = ctx.samples[-1].get("victim") if ctx.samples else None
        if vp and abs(vp[2] - (6.5 if ctx.geo["strafe_pos"] else -6.5)) < 2:
            ctx.geo["strafe_pos"] = not ctx.geo["strafe_pos"]
            nz = 6 if ctx.geo["strafe_pos"] else -6
            ctx.victim.cmd(f"@goto 24 {STAND_Y} {nz}")
        if ctx.geo["shots"] < 6 and t - ctx.geo["last_shot"] >= 6.0:
            ctx.geo["last_shot"] = t
            ctx.geo["shots"] += 1
            ctx.bot.py.try_call("shootArrowAt", ctx.victim.name)

    def judge(self, ctx):
        hits = ctx.hp_drop_events(who="victim", min_dist=8)
        yield Criterion("hits >= 2/6 (vanilla spread)", len(hits) >= 2,
                        f"hits={len(hits)} shots={ctx.geo['shots']}")


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
    bot_kit = KIT_SWORD + ["give {name} bow 1", "give {name} arrow 64"]
    victim_kit = KIT_SWORD

    def build(self, arena, ctx):
        arena.flat_field(half=20)
        ctx.geo["bot_spawn"] = f"-13.5 {STAND_Y} 0.5 -90 0"
        ctx.geo["victim_spawn"] = f"13.5 {STAND_Y} 0.5 90 0"
        ctx.geo["melee_started"] = False
        ctx.geo["last_shot"] = -10.0

    def drive_start(self, ctx):
        ctx.victim.py.call("punk", ctx.bot.name)

    def drive_tick(self, ctx, t):
        if ctx.geo["melee_started"]:
            return
        dist = ctx.samples[-1].get("dist") if ctx.samples else None
        if dist is None:
            return
        if dist > 10:
            if t - ctx.geo["last_shot"] >= 4.0:
                ctx.geo["last_shot"] = t
                ctx.bot.py.try_call("shootArrowAt", ctx.victim.name)
        else:
            ctx.geo["melee_started"] = True
            ctx.bot.py.call("punk", ctx.victim.name)

    def early_stop(self, ctx):
        return ctx.kills() >= 1

    def judge(self, ctx):
        ranged = ctx.hp_drop_events(who="victim", min_dist=8)
        yield Criterion("ranged hit while far >= 1", len(ranged) >= 1,
                        f"ranged_hits={len(ranged)}")
        yield Criterion("kill", ctx.kills() >= 1, f"kills={ctx.kills()}")
        yield Criterion("freezes == 0", ctx.freeze_windows == 0,
                        f"freezes={ctx.freeze_windows}")


SCENARIOS = [MeleeBasic, EdgeDuel, NarrowBridgeDuel, ChaseFlat, ChaseTerrain,
             BowFlee, BowFleeHard, RangedMoving, BridgeAssault,
             BridgeAssaultDefended, AllRound]
