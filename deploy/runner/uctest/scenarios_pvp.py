import time
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


def _stat(ctx, name):
    """One counter out of the mod's stats line, or None when it cannot be read."""
    ok, s = ctx.bot.py.try_call("placeStats")
    if not ok or not s:
        return None
    for tok in str(s).split():
        if tok.startswith(name + "="):
            return tok.split("=", 1)[1]
    return None


class MeleeBasic(Scenario):
    """Mutual close combat in tall grass — the original freeze case, upgraded
    to a target that fights back (RW-1)."""
    id = "melee_basic"
    # The opponent fights on the BASELINE engine so this duel measures our changes
    # rather than cancelling them — see Scenario.victim_settings.
    victim_settings = {"combatReachControl": "false"}
    duration = 60
    settings = {"combatMovementsEnabled": "true", "verboseDebugLogging": "true"}
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
        # DO NOT END THE RUN ON THE FIRST KILL. Sampling is a py4j round trip, so it happens
        # every few seconds; stopping at the first kill ended runs after about five seconds
        # and left TWO samples to judge a fight from, which is why this course looked bimodal.
        # Give it at least half the duration so there is something to measure.
        return ctx.kills() >= 1 and (time.time() - ctx.t0) > self.duration / 2

    def judge(self, ctx):
        # JUDGE BY SWINGS THE BOT LANDED, not by the victim's HP going down. This arena is a
        # platform over void: a fall reads as "damage dealt", regeneration interleaves with
        # the drops, and the bot was scoring kills with ZERO swings on the mod's own counter.
        fs = ctx.first_swing_time()
        yield Criterion("first landed swing <= 15s", fs is not None and fs <= 15,
                        f"first_swing={fs}", load_sensitive=True)
        yield Criterion("landed >= 3 swings", ctx.landed_swings() >= 3,
                        f"swings={ctx.landed_swings()} crits={ctx.crit_swings()}")
        # Kept for the record, no longer a gate: it cannot attribute.
        yield Criterion("victim hp dropped >= 8 (unattributed)", True,
                        f"damage={ctx.victim_damage():.1f}", gate=False)
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
    # The opponent fights on the BASELINE engine so this duel measures our changes
    # rather than cancelling them — see Scenario.victim_settings.
    victim_settings = {"combatReachControl": "false"}
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
                        f"contact={ctx.first_contact}", load_sensitive=True)
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
    # verboseDebugLogging ON: without it the planner's own summary line is gated off, and a
    # silent channel was once read as "the planner never ran". Confirm the channel is alive
    # before treating an absence as evidence.
    settings = {"combatMovementsEnabled": "true", "verboseDebugLogging": "true"}
    RUN_DIST = 140             # how far the runner is sent, in blocks

    # Dry, walkable ground. NOTE what is NOT here: clay and mud. They are swamp
    # and riverbed floors — picking the first "land" column landed the whole
    # bench in a bog, where the bot waded, bounced in pits and the run measured
    # nothing useful ("там или болото какое-то или вода").
    LAND_BLOCKS = ("grass_block", "stone", "dirt", "coarse_dirt", "podzol",
                   "sand", "gravel", "sandstone", "snow_block", "moss_block",
                   "rooted_dirt", "terracotta")

    def _find_land(self, rc):
        """Find a real LAND surface to start on, and prove it.

        The world spawn of this seed is an OCEAN — the repo already knew that
        ("спавн в ОКЕАНЕ — бот утонул") and a naive 'first non-air block from the
        sky' probe stops at the WATER surface, which is how both bots ended up
        spawned in the sea for a whole 'chase' run. Water/lava are not ground:
        scan candidate columns until the surface block is genuinely walkable,
        and return its name so the run records what it stood on.
        """
        # Search a grid of candidates and, for each, the direction whose corridor is
        # driest. One start cell being dry means nothing if the runner's route goes
        # through a bog.
        for radius in (0, 96, 192, 288, 384, 480, 576):
            for dx, dz in ((1, 0), (0, 1), (-1, 0), (0, -1),
                           (1, 1), (-1, -1), (1, -1), (-1, 1)):
                x, z = dx * radius, dz * radius
                found = self._probe_column(rc, x, z)
                if found is None:
                    continue
                y, block = found
                for rdx, rdz in ((1, 0), (0, 1), (-1, 0), (0, -1)):
                    if self._corridor_is_dry(rc, x, z, rdx, rdz):
                        self.run_dir = (rdx, rdz)
                        return x, z, y, block
        raise RuntimeError("no dry land route found for the chase bench")

    def _probe_column(self, rc, x, z):
        """First surface block from the sky; (standY, name) if it is dry land.
        Coarse scan then refine — a 1-block scan of the whole column costs ~95
        rcon round trips and made a grid search take minutes.

        The column MUST be force-loaded first: in an unloaded chunk `execute if
        block` simply does not pass, which my earlier version read as "solid" and
        so every distant candidate looked like a wall at y=150 and the search
        reported 'no dry land anywhere'."""
        rc.cmd(f"forceload add {x - 8} {z - 8} {x + 8} {z + 8}")
        top = None
        for y in range(150, 55, -4):
            if "Test passed" not in rc.cmd(f"execute if block {x} {y} {z} air"):
                top = y
                break
        if top is None:
            return None
        for y in range(top + 3, top - 1, -1):        # refine upward edge
            if "Test passed" not in rc.cmd(f"execute if block {x} {y} {z} air"):
                top = y
                break
        for block in self.LAND_BLOCKS:
            if "Test passed" in rc.cmd(f"execute if block {x} {top} {z} minecraft:{block}"):
                if ("Test passed" in rc.cmd(f"execute if block {x} {top+1} {z} air")
                        and "Test passed" in rc.cmd(f"execute if block {x} {top+2} {z} air")):
                    return top + 1, block
        return None

    def _corridor_is_dry(self, rc, x, z, rdx, rdz):
        """Sample the run corridor in one direction: dry land the whole way."""
        for step in range(30, self.RUN_DIST + 1, 30):
            cx, cz = x + rdx * step, z + rdz * step
            if self._probe_column(rc, cx, cz) is None:
                return False
        return True

    def build(self, arena, ctx):
        # Start both on real LAND (never the ocean spawn); the terrain is whatever
        # generated. A fixed offset gives the runner a head start along +x.
        rc = ctx.rcon
        rc.cmd("gamerule pvp true", allow_reject=True)  # not a gamerule; server.properties
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
        sx, sz, sy, ground = self._find_land(rc)
        ctx.geo["ground"] = ground   # the verdict line read ground=None in every recorded run
        ctx.log(f"  chase start: ({sx}, {sy}, {sz}) on {ground}")
        rdx, rdz = getattr(self, "run_dir", (1, 0))
        rc.cmd(f"tp {ctx.bot.name} {sx}.5 {sy} {sz}.5")
        rc.cmd(f"tp {ctx.victim.name} {sx + rdx * 6}.5 {sy} {sz + rdz * 6}.5")
        time.sleep(2)
        bp = ctx.bot.pos() or [sx, sy, sz]
        ctx.geo["bot_spawn"] = f"{bp[0]:.1f} {bp[1]:.1f} {bp[2]:.1f}"
        ctx.geo["victim_spawn"] = f"{bp[0] + 6:.1f} {bp[1]:.1f} {bp[2]:.1f}"
        ctx.geo["goal"] = (int(bp[0]) + rdx * self.RUN_DIST, int(bp[1]),
                           int(bp[2]) + rdz * self.RUN_DIST)

    HEAD_START_S = 6.0

    def drive_start(self, ctx):
        gx, gy, gz = ctx.geo["goal"]
        # runner: plain baritone @goto over real terrain — it will climb, swim,
        # walk around obstacles on its own.
        ctx.victim.cmd(f"@goto {gx} {gy} {gz}")
        # A HEAD START makes this a chase. Without it the prey was still standing
        # when the chaser reached it and the "bench" was decided in four seconds.
        time.sleep(self.HEAD_START_S)
        # chaser: tungsten punk = approach (pathfinder) + combat when in reach
        ctx.bot.py.call("punk", ctx.victim.name)

    RE_GOAL_EVERY = 20.0   # seconds of WALL CLOCK
    PROBE_EVERY = 5.0

    def drive_tick(self, ctx, t):
        # ⛔ A MODULO ON A REAL-VALUED CLOCK IS A LOTTERY, NOT A SCHEDULE. One sample
        # iteration costs ~6.7 s here (nine rcon round trips plus two py4j), so `int(t) % 20`
        # fired on 0.9% of drive ticks across 122 recorded runs — about 0.23 times per run.
        # In MOST runs the prey was therefore NEVER re-tasked: @goto finished or aborted on
        # rough ground, the runner stopped, and "caught the runner" was decided against a
        # STANDING target. Gate on elapsed time instead.
        if t - ctx.geo.get("last_regoal", 0.0) >= self.RE_GOAL_EVERY and ctx.samples:
            ctx.geo["last_regoal"] = t
            gx, gy, gz = ctx.geo["goal"]
            ctx.victim.cmd(f"@goto {gx} {gy} {gz}")
        # record whether the bot is actually swimming — a chase measured in the
        # sea is not a chase, and this is what proves the setup was sound
        p = ctx.samples[-1].get("bot") if ctx.samples else None
        if p and t - ctx.geo.get("last_probe", -1e9) >= self.PROBE_EVERY:
            ctx.geo["last_probe"] = t
            ctx.geo["water_probes"] = ctx.geo.get("water_probes", 0) + 1
            probe = ctx.rcon.cmd(
                f"execute if block {int(p[0])} {int(p[1])} {int(p[2])} water")
            if "Test passed" in probe:
                ctx.geo["water_hits"] = ctx.geo.get("water_hits", 0) + 1
                ctx.geo["swam"] = True

    def early_stop(self, ctx):
        return ctx.kills() >= 1

    def judge(self, ctx):
        # SETUP SANITY FIRST. A whole 'chase' run was once measured with both bots
        # swimming in the ocean — the numbers were meaningless and the clip was an
        # embarrassment. If the run did not happen on land, nothing else it says
        # counts, so this criterion is reported before the behavioural ones.
        swam = ctx.geo.get("swam", False)
        probes = ctx.geo.get("water_probes", 0)
        # A CHECK THAT NEVER RAN IS NOT A PASS. `swam` defaults to False, so while the probe
        # was on the modulo lottery above this criterion went green whenever it never fired.
        # SETUP SANITY, NOT A PURITY TEST. The intent recorded above is "a whole run measured in
        # the sea is meaningless" — crossing a stream mid-chase is not that. Gating on ANY water
        # sample failed a run where the bot caught AND killed the runner with a 4.4-block gap and
        # zero freezes, which is the bench calling a good run bad. So it gates on the run being
        # MOSTLY in water, and still requires that the probe actually ran — a check that never
        # executed must not pass by default, which is the defect this criterion had.
        hits = ctx.geo.get("water_hits", 0)
        mostly_water = probes >= 3 and hits > probes / 2
        yield Criterion("ran on LAND (not mostly in water)",
                        probes >= 3 and not mostly_water,
                        f"ground={ctx.geo.get('ground')} swam={swam} "
                        f"water={hits}/{probes} probes")

        # DID THE PREY ACTUALLY RUN? Without this, everything the chase claims can be true of
        # a target that stopped moving in the first ten seconds.
        vps = [s["victim"] for s in ctx.samples if s.get("victim")]
        run_len = sum(((a[0] - b[0]) ** 2 + (a[2] - b[2]) ** 2) ** 0.5
                      for a, b in zip(vps, vps[1:]))
        yield Criterion("the runner actually ran (>= 30 blocks)", run_len >= 30,
                        f"runner_path={run_len:.1f}")

        # HOW CLOSE DID IT GET? Reported, never a gate. Without this the course is
        # pass/fail on "contact within 120 s" and nothing else, so a change that halves the
        # gap looks identical to one that does nothing — which is how several passes were
        # spent here with no number to move. The nav courses have `final_dist` for exactly
        # this reason; the chase had no equivalent.
        gaps = [((s["bot"][0] - s["victim"][0]) ** 2
                 + (s["bot"][1] - s["victim"][1]) ** 2
                 + (s["bot"][2] - s["victim"][2]) ** 2) ** 0.5
                for s in ctx.samples
                if s.get("bot") and s.get("victim")]
        yield Criterion("gap to runner (reported)", True,
                        f"min={round(min(gaps), 1) if gaps else None} "
                        f"last={round(gaps[-1], 1) if gaps else None} "
                        f"samples={len(gaps)}", gate=False)
        # A kill IS contact — the 1 Hz position sampling can miss the moment the
        # gap closes (measured: the bot killed the runner at t=4.4 s and the
        # contact detector still reported None).
        caught = (ctx.first_contact is not None and ctx.first_contact <= 120) \
            or ctx.kills() >= 1
        yield Criterion("caught the runner (contact <= 120s)", caught,
                        f"contact={ctx.first_contact} kills={ctx.kills()}", load_sensitive=True)
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
        # HITS=0 IS THREE DIFFERENT FAULTS WEARING ONE NUMBER: never drew, drew and missed, or hit
        # while the detector above looked past it. Counting the arrows actually LOOSED separates
        # them. Measured from the chat logs of two earlier runs before this line existed --
        # bow_flee released 0 of ~20 requested shots, bow_flee_hard released 2 -- so this counter
        # has a value to prove itself against on the very first run that prints it.
        yield Criterion("arrows actually loosed (recorded, not gated)", True,
                        f"bowShots={_stat(ctx, 'bowShots')} requested~{int(self.duration / 3)}",
                        gate=False)
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
                        f"first_death={first_death}", load_sensitive=True)
        yield Criterion("arrow hits >= 1", len(hits) >= 1, f"hits={len(hits)}")
        # Same reading as bow_flee, and the pair is the whole point: this course releases arrows
        # (2 per run in the logs) while bow_flee releases none, which is what says the fault is
        # "cannot shoot while MOVING" rather than "cannot shoot".
        yield Criterion("arrows actually loosed (recorded, not gated)", True,
                        f"bowShots={_stat(ctx, 'bowShots')} requested~{int(self.duration / 3)}",
                        gate=False)


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
                        f"crossed={crossed}", load_sensitive=True)
        yield Criterion("kill <= 120s", ctx.kills() >= 1, f"kills={ctx.kills()}", load_sensitive=True)
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
        yield Criterion("kill <= 150s", ctx.kills() >= 1, f"kills={ctx.kills()}", load_sensitive=True)


class AllRound(Scenario):
    """Ranged-to-melee integration, composed from primitives (the agent-style
    drive): shootArrowAt while the closing enemy is far, punk once he is
    inside 10 blocks. Benches both halves + the switch."""
    id = "allround"
    # The opponent fights on the BASELINE engine so this duel measures our changes
    # rather than cancelling them — see Scenario.victim_settings.
    victim_settings = {"combatReachControl": "false"}
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

    MELEE_AT = 12.0

    def drive_tick(self, ctx, t):
        # ⛔ THE PHASE DECISION WAS MADE ON A ~7.5 s STALE DISTANCE, AND IT LATCHED.
        # `ctx.samples[-1]["dist"]` is whatever the last sample saw, and one sample iteration
        # costs about 7.5 s of blocking rcon here — a sprinting victim covers some forty blocks
        # in that time. So the bot stood with a BOW in hand, with punk not yet started, while a
        # sword bot walked up and killed it: a death handed over by the harness, counted against
        # the bot on a course whose gate is "deaths <= 0". And `melee_started` never cleared, so
        # after a respawn the ranged phase never came back.
        # Read the distance FRESH, and let the phase follow it in both directions.
        bp, vp = ctx.rcon.entity_pos(ctx.bot.name), ctx.rcon.entity_pos(ctx.victim.name)
        if not bp or not vp:
            return
        dist = ((bp[0] - vp[0]) ** 2 + (bp[1] - vp[1]) ** 2 + (bp[2] - vp[2]) ** 2) ** 0.5
        if dist <= self.MELEE_AT:
            if not ctx.geo["melee_started"]:
                ctx.geo["melee_started"] = True
                ctx.bot.py.call("selectHotbar", 1)   # punk swings the HELD item
                ctx.bot.py.call("punk", ctx.victim.name)
            return
        if ctx.geo["melee_started"]:
            # Back to range — a respawn puts the fighters apart again, and the course is meant
            # to measure the bow phase every round, not only the first one.
            ctx.geo["melee_started"] = False
            ctx.bot.py.try_call("punkStop")
            ctx.bot.py.call("selectHotbar", 0)
        if t - ctx.geo["last_shot"] >= 2.5:
            ctx.geo["last_shot"] = t
            ctx.bot.py.try_call("shootArrowAt", ctx.victim.name)

    def early_stop(self, ctx):
        return ctx.kills() >= 1

    def judge(self, ctx):
        ranged = ctx.hp_drop_events(who="victim", min_dist=8)
        yield Criterion("ranged hit while far >= 1", len(ranged) >= 1,
                        f"ranged_hits={len(ranged)}")
        yield Criterion("kill", ctx.kills() >= 1, f"kills={ctx.kills()}")
        yield ctx.survival_criterion()   # 1 kill / 4 deaths is a LOSS, not a pass
        # HOW MANY TIMES DID IT ACTUALLY CONNECT. Read off a timeline by hand for the first time
        # today, and it is the sharpest number this course has: over a full run the bot landed
        # FOUR swings (lifetimeHits 105 -> 109) while dying twice. A sword swings ~1.6/s, so ~13 s
        # of contact should be nearer twenty. The course reported "kills=1 deaths=2" and said
        # nothing about that, so nobody could see the fight was lost on output rather than luck.
        # Recorded, never a gate: it is a count, so unlike a timing gate it stays readable at the
        # 5-9 fps this stand runs at.
        yield Criterion("swings landed (recorded, not gated)", True,
                        f"landed={ctx.landed_swings()} crits={ctx.crit_swings()}", gate=False)
        # WHERE THE PUNK TASK SPENDS ITS TICKS. This course drives with `punk`, which is
        # tungsten's PunkPlayerTask — NOT MobDefenseChain and NOT AbstractKillEntityTask.
        #
        # I first printed mdTung/kaTung/dte here and got 0/0, 0/0/0/0, 0/0/0/0/0/0 and very nearly
        # read it as "the combat engine never ran". Those counters belong to code paths this course
        # never touches, so their zeros mean nothing — the identical mistake as nav's pdEnter=0 two
        # hours earlier, made again because I picked counters without checking which path the
        # course actually uses.
        #
        # punkStats() is the right instrument, it has existed all along, and NOTHING called it.
        ok, ps = ctx.bot.py.try_call("punkStats")
        yield Criterion("punk task (recorded, not gated)", True,
                        str(ps) if ok and ps else "unreadable", gate=False)
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
        return self.arrived(ctx)

    def arrived(self, ctx):
        # Through the base hook, so the freeze detector learns the goal was reached even under
        # --no-early-stop — which is exactly the mode where early_stop is never consulted.
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
