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


def _stat(ctx, name, actor=None):
    """One counter out of the mod's stats line, or None when it cannot be read.

    `actor` defaults to the bot. Pass ctx.victim to read the OPPONENT's copy: in a mutual duel
    both fighters run this mod, so the victim's counters are the other half of the ledger."""
    ok, s = (actor or ctx.bot).py.try_call("placeStats")
    if not ok or not s:
        return None
    for tok in str(s).split():
        if tok.startswith(name + "="):
            return tok.split("=", 1)[1]
    return None


def _ledger(ctx):
    """Blows and damage taken by EACH fighter, from the same instrument on both sides.

    Kills-minus-deaths is the criterion, and it is a terrible instrument for a difference: about
    27 exchanges decide a duel, so the margin carries a standard deviation near 2.7 and no series
    this bench can afford resolves a shift smaller than that. Every "effect" measured on these
    courses has been smaller than their own spread.

    DamageWatch counts from the CLIENT TICK, so it runs on the victim exactly as it runs on the
    bot, and a duel produces ~50 blows per side instead of ~13 deaths. Read it as a RATIO of the
    two sides rather than as an absolute: the class's own javadoc records that the total does not
    reconcile with deaths x 20, so it undercounts somewhere -- but it undercounts the same way on
    both fighters, and a shared bias cancels in a ratio while it does not in a total."""
    out = []
    for who, actor in (("bot", ctx.bot), ("victim", ctx.victim)):
        dw = _stat(ctx, "dw", actor)
        if not dw:
            out.append(f"{who}=?")
            continue
        f = dw.split("/")
        # dw = hits/damage/gapMean/gapMax/rangedHits/deathsSeen
        out.append(f"{who} blows={f[0]} dmg={f[1]} deaths={f[5] if len(f) > 5 else '?'}")
    ctx.log("  ledger: " + " | ".join(out))


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
        # WITNESS FOR THE SWORD-ONLY DISENGAGE, as fired/declined. The first number is ticks spent
        # kiting a wounded bot back out of reach -- a retreat justified by "out there the bow is
        # the weapon" -- and THIS KIT HAS NO BOW, so it must read 0. The second is the ticks where
        # that retreat would have fired and was refused for having nothing to shoot with.
        #
        # The second number exists because the first cannot carry the claim alone: 0 says the
        # branch stopped firing, not how much it used to take. The only "before" figure available
        # was measured in another context entirely, and quoting it for these courses would be the
        # subsample error this checklist has a rule about. fired+declined is what fired used to be,
        # on the same course and the same jar. Recorded, not gated.
        # standOff is the CONTROL for the stand-off guard, and this course is where it must read
        # near ZERO: an open field is where backing off works, so the guard should almost never
        # refuse the band change here. A large number on melee_basic would mean the guard reached a
        # course it was never meant to touch, and any change in this course's margin would be its
        # fault rather than the platform fix's.
        ctx.log(f"  lowHp={_stat(ctx, 'lowHp')} standOff={_stat(ctx, 'standOff')} "
                f"hurt={_stat(ctx, 'hurt')}")
        _ledger(ctx)
        yield ctx.exchange_criterion()   # mutual punk — winning the trade is the bar
        yield Criterion("freezes == 0", ctx.freeze_windows == 0,
                        f"freezes={ctx.freeze_windows}")
        yield Criterion("stand-still near target <= 2",
                        ctx.standstill_windows <= 2,
                        f"windows={ctx.standstill_windows}")


class EdgeDuel(Scenario):
    """5x5 platform over void — RW-1 'both keep footing 1 block from drop'."""
    id = "edge_duel"
    scores_own_falls = True   # gates self-falls itself; knockback falls are normal here
    # ⛔ THIS COURSE WAS A MIRROR, AND IT WAS BEING USED AS A REGRESSION GUARD.
    # victim_settings defaults to {}, so without this line the opponent ran the SAME build, the
    # same settings and the same kit -- and a symmetric engine change went to both sides and
    # cancelled. Expected margin 0 by construction, pass rate a coin flip with ties passing.
    #
    # It cost a whole conclusion on 2026-08-10: n=8 here returned median -1.5 and 3 passes against
    # a 4/4 baseline, that was read as a confirmed regression from a combat change, and a fix was
    # designed and shipped in response to a number the course could not produce. The 4/4 was luck
    # (~2% at that rate) and the 3/8 was the coin.
    #
    # melee_basic and narrow_bridge_duel have carried this pin for exactly this reason, and
    # TungstenConfig's javadoc spells it out. edge_duel simply never got it.
    #
    # NOTE FOR ANYONE COMPARING: this makes the course ASYMMETRIC, so its history from before this
    # line is not a baseline for anything measured after it. That history was a coin flip anyway.
    victim_settings = {"combatReachControl": "false"}
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
        # Frame-gated for the reason measured on allround: aim is delivered once per FRAME, so at
        # ~5 fps the crosshair gets five corrections a second and swings stop landing. This course
        # WAS voided correctly at 5.11 fps, but only by luck — the guard inspects FAILED gates, and
        # that run happened to land its kill. Had it not, one unflagged gate would have certified it.
        yield Criterion("kill >= 1", ctx.kills() >= 1, f"kills={ctx.kills()}",
                        load_sensitive=True)
        # WITNESS ON A GUARD COURSE. edge_duel is one of the eight bowless courses, so the
        # wounded-retreat predicate reaches it -- but this probe never printed the counter, so
        # when the course failed the exchange there was no way to tell whether the branch had
        # even fired here. A control that cannot report the quantity it is controlling for is
        # not a control. The 5x5 platform runs to ~5.6 blocks on the diagonal, i.e. past REACH,
        # so "it barely fires on a small platform" was an assumption and is now a measurement.
        # standOff counts the ticks where the cooldown stand-off was refused because the bot could
        # not have walked backwards anyway. On THIS course it is the whole point: a 5x5 platform is
        # where the stand-off degenerated into standing still. Near zero here would mean the fix
        # never fired and any change is something else.
        ctx.log(f"  lowHp={_stat(ctx, 'lowHp')} standOff={_stat(ctx, 'standOff')}")
        _ledger(ctx)
        # WHY THE TRADE IS STILL LEVEL RATHER THAN WON. After the stand-off fix this course sits at
        # a mean margin of -0.27 against the baseline engine, i.e. a coin flip, and a gate of
        # "kills >= deaths" against an equal opponent passes about half the time by construction.
        # Winning it needs the engine to be BETTER, not equal, and the trigger already counts every
        # reason it declines to click -- its own comment says exactly one of them is the answer.
        # allround has printed these for a while and this course, which is where the duel is
        # cleanest, never did.
        #
        # ⛔ READ THE MEANS AS MEANS OVER REJECTIONS. angleMean and reachMean accumulate only inside
        # their failure branches, so they describe the swings that were REFUSED, not all swings.
        # Misread twice already.
        ok_gs, gs = ctx.bot.py.try_call("gateStats")
        ctx.log(f"  gates: {gs if ok_gs else 'UNREADABLE'}")
        ok_cs2, cs2 = ctx.bot.py.try_call("closeStats")
        ctx.log(f"  close: {cs2 if ok_cs2 else 'UNREADABLE'}")
        yield ctx.exchange_criterion()          # mutual duel: must not lose it
        # self-falls is NOT flagged: low fps is a plausible cause (nav_ladder self-falls at 9.4-9.9
        # fps, origin still open) but that is a correlation, not the measurement the aim case has.
        # Flag it when someone measures the mechanism, not before.
        yield Criterion("self-falls == 0", ctx.self_falls == 0,
                        f"self={ctx.self_falls} knockback={ctx.knockback_falls}")


class NarrowBridgeDuel(Scenario):
    """Two islands + 1-wide bridge over void (bedwars walkway). Spawns force
    the fight ONTO the bridge."""
    id = "narrow_bridge_duel"
    scores_own_falls = True   # gates self-falls itself; knockback falls are normal here
    # The opponent fights on the BASELINE engine so this duel measures our changes
    # rather than cancelling them — see Scenario.victim_settings.
    victim_settings = {"combatReachControl": "false"}
    # NO SPRINT ASYMMETRY HERE, checked so it is not raised again. VoidGuard sets
    # nearVoid = voidWithin(pos, 3, 3), permanently true on a 1-wide bridge, so sprint is off
    # for the whole course -- but protect() is gated on PunkPlayerTask.isActive()
    # (MixinClientPlayerEntity:197-201), NOT on combatReachControl, and drive_start punks BOTH
    # fighters. So the handicap is symmetric and the pin below does not buy the victim a sprint
    # the bot cannot use.
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
        # RIM EXPOSURE IS PRINTED HERE ON PURPOSE. On a bridge the rim is ALWAYS behind, so the
        # knockback guard could hold the bot in permanent retreat and cost it the exchange -- a
        # mechanism invisible on edge_duel's 5x5 platform, where a centred fighter has clear
        # ground at its back. Read this before blaming or clearing the guard. Recorded, not
        # gated: it is a diagnostic, and a number that gates nothing cannot be gamed into green.
        ok, rim = ctx.bot.py.try_call("rimAtBackTicks")
        # THIS COURSE WAS CALLED BIMODAL: it either engaged (14 kills, rimBack=29) or barely
        # fought at all (0-1 kills, rimBack=0) on the SAME jar. rimBack turned out to be a witness
        # to whether the fight happened, not a cause of losing it -- the guard firing correlates
        # with WINNING. So print what separates the modes instead of guessing again: how close the
        # fighters ever got, and how long it took them to reach each other.
        # WHAT THE PROBE ACTUALLY CAUGHT (2026-08-10, two runs): NEITHER mode. Both engaged hard
        # -- kills 12 and 11, rimBack 33 and 91 -- and both LOST the trade (deaths 15 and 17).
        # "Barely fights" did not appear. So the live failure here is a losing exchange, not a
        # no-show, and the no-show is a separate mode still waiting to be caught.
        ds = [s["dist"] for s in ctx.samples if s.get("dist") is not None]
        closest = min(ds) if ds else None
        # First moment they were within a sword's reach; None means they never met.
        # The 3.0 that used to sit here was GUESSED, and it reported met_at=None on a run with
        # TWELVE kills -- a threshold below the distance the bot actually kills at cannot witness
        # a meeting. Read the band from the mod like the bow_flee probe does: it is calibrated
        # from the blows (mean 4.25, max 5.35), and asking for it means it can only be wrong once.
        okb, band = ctx.bot.py.try_call("fleeReachBand")
        band = band if okb else 5.5
        met = next((s["t"] for s in ctx.samples
                    if s.get("dist") is not None and s["dist"] <= band), None)
        # lowHp: same witness as melee_basic — this kit has no bow either, so the wounded-retreat
        # branch must not fire. See the note there.
        ctx.log(f"  rimBack={rim if ok else 'ABSENT'} closest={closest} met_at={met}"
                f" lowHp={_stat(ctx, 'lowHp')}")
        # The ledger matters most HERE. This course carries the largest spread in the suite -- six
        # healthy runs read +3, -2, -3, +1, -4, +1 -- and every mechanism tried on it has been
        # smaller than that. Blows are ~50 a side per run against ~27 deciding events, so they are
        # the only statistic on this bridge with a chance of resolving anything.
        _ledger(ctx)
        # Same frame-gated aim as melee_basic; see the note there.
        yield Criterion("kill >= 1", ctx.kills() >= 1, f"kills={ctx.kills()}",
                        load_sensitive=True)
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
    # ⛔ THIS COURSE IS SLOW TO START AND A ZERO-BYTE timeline.jsonl IS NOT A STALL.
    # It is the only pvp course with world="gamer" and builds_arena=False: the clients move to the
    # REAL world-generator server and then land is probed for. Generated chunks on a client
    # rendering in software at 10-30 fps take minutes to arrive, and until the drive loop starts
    # nothing is sampled — so the artifact dir sits there with an empty timeline, looking exactly
    # like a hang.
    #
    # It was read as one on 2026-08-12 and the whole suite was killed at 8 minutes, losing the
    # remaining seven courses. Both mechanisms proposed for the "hang" were then checked and
    # neither held: every harness call is bounded (sh 30s, batch 30s, screenshot 40s, rcon 20s),
    # and uctest-gamer-server was up and healthy the whole time. Slow, not stuck.
    #
    # MEASURED on the re-run, so the number is known rather than estimated:
    #     15:33  dir created, timeline.jsonl 0 bytes
    #     15:33-15:42  NINE MINUTES at zero bytes — and the rest of the artifacts tree went quiet
    #                  in stretches too, so "the suite is writing" is not evidence either
    #     15:42  339 bytes, then 1737 — drive loop entered, course ran normally
    # The kill landed at eight minutes, i.e. one minute before it would have started working.
    #
    # If you suspect this course, TIME IT rather than killing it, and judge by whether the file
    # GROWS, not by whether it exists. Note also that "the suite is alive" and "THIS COURSE is
    # alive" are different claims: during this setup phase both can be silent, so silence proves
    # nothing in either direction. Only completion does.
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
        # A kill needs swings to land, and landing needs the crosshair on target — frame-gated for
        # the reason measured on allround (aim is delivered once per frame).
        yield Criterion("killed the runner", ctx.kills() >= 1,
                        f"kills={ctx.kills()}", load_sensitive=True)
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
    scores_own_falls = True   # gates self-falls itself; knockback falls are normal here
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
        # WHAT THE AVERAGE HIDES. The flee fix holds the MEAN separation at 9.3-9.4, comfortably
        # past the 3-block melee reach, and the bot still dies ~5 times. A mean cannot explain
        # that; only the minimum can. So print how close the chaser ever got and how many
        # samples sat inside reach -- if the gap collapses periodically, the deaths are catches
        # and the objective is right but not tight enough; if it never collapses, the deaths are
        # not melee at all and this course is red for some other reason entirely.
        # DRIFT ABORTS ARE NOT THE CAUSE, settled over 50 recorded runs: r=0.23 between
        # "Path stopped: drift" counts and deaths, with a run at 0 aborts / 10 deaths and
        # another at 0 aborts / 0 deaths. The path abort is real and worth fixing on its own
        # terms, but it explains a few percent of the deaths here and nothing more. Do not
        # widen driftThreshold expecting this course to go green.
        ds = [s["dist"] for s in ctx.samples if s.get("dist") is not None]
        closest = round(min(ds), 2) if ds else None
        # 3.6 to match the mod counter. Vanilla melee reaches the HITBOX, not the centre, and a
        # player box is 0.6 wide -- 3.0 was my guess and it under-counted. These two numbers print
        # side by side on one line, so a band mismatch between them is a lie told twice.
        # 5.5, tracking the mod's band. This drifted TWICE: 3.0 vs 3.6 first, now 3.6 vs 5.5,
        # each time because the mod side was corrected and this line was not. Both numbers print on
        # one line under the same word, so a mismatch here is a lie told twice per run. If the mod's
        # band changes again, change this with it -- the reach is calibrated from the blows
        # (mean 4.25, max 5.35), not from either of these constants.
        # READ the band from the mod, never restate it. It drifted twice tonight because it lived
        # in two places; asking for it means it can only ever be wrong in one.
        okb, band = ctx.bot.py.try_call("fleeReachBand")
        band = band if okb else 5.5
        caught = sum(1 for d in ds if d <= band)
        # Per-tick, from the mod: the 1 Hz series above cannot see this course. The victim
        # carries only a sword, so every death is a catch, yet the sampler has reported 0-in-reach
        # on runs with six of them.
        okr, rt = ctx.bot.py.try_call("fleeReachTicks")
        okn, nt = ctx.bot.py.try_call("fleeNearTicks")
        _, rd = ctx.bot.py.try_call("fleeReachDrawingTicks")
        _, nd = ctx.bot.py.try_call("fleeNearDrawingTicks")
        _, rs = ctx.bot.py.try_call("fleeReachSprintTicks")
        _, ra = ctx.bot.py.try_call("fleeReachAwayTicks")
        _, ft = ctx.bot.py.try_call("bowFacingTicks")
        _, fd = ctx.bot.py.try_call("fleeDriveTicks")
        _, fh = ctx.bot.py.try_call("fleeHeldTicks")
        _, ii = ctx.bot.py.try_call("fleeIdleInactive")
        _, nt2 = ctx.bot.py.try_call("fleeIdleNoThreat")
        _, ct = ctx.bot.py.try_call("fleeClientTicks")
        _, ntt = ctx.bot.py.try_call("fleeNearThreatTicks")
        _, stl = ctx.bot.py.try_call("fleeStillNearThreat")
        _, se = ctx.bot.py.try_call("fleeStillExecutor")
        _, ss = ctx.bot.py.try_call("fleeStillSearch")
        _, sn = ctx.bot.py.try_call("fleeStillNobody")
        _, mq = ctx.bot.py.try_call("fleeStillMoveQueue")
        _, kd = ctx.bot.py.try_call("fleeStillKeysDown")
        _, tch = ctx.bot.py.try_call("fleeStillTouching")
        _, mr = ctx.bot.py.try_call("fleeStillMaxRadius")
        _, rs = ctx.bot.py.try_call("fleeStillRadiusSum")
        _, sag = ctx.bot.py.try_call("fleeStalledAfterGuard")
        _, kag = ctx.bot.py.try_call("fleeKeysAfterGuard")
        # DAMAGE ACCOUNTING. Exposure is now 8.5 ticks a run and the bot still dies 4-5 times --
        # about two exposed ticks per death, where a sword needs three or four blows to kill. So
        # the damage must be accumulating across brief contacts rather than being dealt in the
        # last one. dw counts blows taken and total damage; both are already tracked mod-side.
        okdw, dwh = ctx.bot.py.try_call("dwHits")
        _, dwd = ctx.bot.py.try_call("dwDamage")
        _, hdm = ctx.bot.py.try_call("hitDistMax")
        _, hds = ctx.bot.py.try_call("hitDistSum")
        _, hdn = ctx.bot.py.try_call("hitDistN")
        _, fsp = ctx.bot.py.try_call("fleeSprintTicks")
        _, rim = ctx.bot.py.try_call("fleeAtRimTicks")
        _, nsb = ctx.bot.py.try_call("fleeNoSprintBow")
        _, nso = ctx.bot.py.try_call("fleeNoSprintOther")
        _, nst = ctx.bot.py.try_call("fleeNoSprintTurning")
        _, nsu = ctx.bot.py.try_call("fleeNoSprintUnexplained")
        _, nsh = ctx.bot.py.try_call("fleeNoSprintHungry")
        _, nsk = ctx.bot.py.try_call("fleeNoSprintSneak")
        _, nsc = ctx.bot.py.try_call("fleeNoSprintCollide")
        _, ct2 = ctx.bot.py.try_call("fleeCollideThreat")
        _, cmr = ctx.bot.py.try_call("fleeCollideMaxRadius")
        ctx.log(f"  closest={closest} samples_in_reach={caught}/{len(ds)}"
                f" | reachTicks={rt if okr else 'ABSENT'}(drawing {rd})"
                f" nearTicks={nt if okn else 'ABSENT'}(drawing {nd})"
                f" | of in-reach: sprinting {rs}, moving-away {ra}"
                f" | bow owned camera {ft} ticks | flee drove {fd}, held {fh},"
                f" idle-inactive {ii}, idle-nothreat {nt2} of {ct} client ticks"
                f" | near-threat {ntt}, MOTIONLESS {stl} (exec {se}, search {ss}, nobody {sn},"
                f" of which moveQueue {mq}, KEYS DOWN {kd}, TOUCHING {tch},"
                f" stallRadius max {mr}, sum {rs} tenths)"
                f" | AFTER GUARD stalled {sag}, keys still down {kag}"
                f" | blows taken {dwh if okdw else 'ABSENT'}, damage {dwd}"
                f" | hit dist max {hdm}, mean {round(hds/hdn,1) if hdn else 0} hundredths over {hdn}"
                f" | sprint {fsp} of {fd} drive ticks | rimTime {rim} (no-sprint: bow {nsb}, other {nso} of which turning {nst}, refused {nsu} incl. hungry {nsh}, sneaking {nsk}, colliding {nsc} of which threat {ct2}, maxR {cmr})")
        # DO NOT MARK THE SURVIVAL CRITERION load_sensitive. It looks like a candidate -- runs
        # get judged at 10 fps, below the 14.0 floor -- but the data says the opposite of the
        # intuition: across 34 recorded runs r(fps, deaths) = +0.47, and runs ABOVE the floor
        # averaged 6.25 deaths against 4.20 below it. A starved client does not kill this bot,
        # it flatters it, because the chaser slows down too. Flagging this criterion would stop
        # the course failing honestly on exactly the healthy runs where it fails hardest.
        # (n=4 above the floor, so the gap is thin -- the SIGN is what matters here, not the size.)
        #
        # A run where the fight never happened must not be able to score a clean sheet.
        yield Criterion("the fight actually happened", ctx.engagement_happened(),
                        f"closest={closest} samples={len(ds)}")
        yield Criterion("survived (0 deaths)", ctx.deaths() == 0,
                        f"deaths={ctx.deaths()}")
        # Landing an arrow on a MOVING target is the most frame-sensitive thing this suite asks for:
        # the bow must be aimed while both fighters run, and aim arrives once per frame. Left
        # unflagged this course certified its own sub-floor runs, which is where G-1.67 was reading
        # its numbers from.
        yield Criterion("arrow hits >= 2", len(hits) >= 2, f"hits={len(hits)}",
                        load_sensitive=True)
        # HITS=0 IS THREE DIFFERENT FAULTS WEARING ONE NUMBER: never drew, drew and missed, or hit
        # while the detector above looked past it. Counting the arrows actually LOOSED separates
        # them. Measured from the chat logs of two earlier runs before this line existed --
        # bow_flee released 0 of ~20 requested shots, bow_flee_hard released 2 -- so this counter
        # has a value to prove itself against on the very first run that prints it.
        # bowShots counts only the AIMED releases. An aborted draw is not a cancelled shot —
        # vanilla fires on key release past ~3 ticks of draw — so bowWild is the rest of the
        # arrows, thrown wherever the camera happened to point. Reading bowShots alone made
        # "6 of ~20" look like "14 never happened" when it means "14 were thrown away".
        yield Criterion("arrows actually loosed (recorded, not gated)", True,
                        f"bowShots={_stat(ctx, 'bowShots')} bowWild={_stat(ctx, 'bowWild')}"
                        f" requested~{int(self.duration / 3)}",
                        gate=False)
        # ⛔ AND WHY THE OTHER EIGHTEEN NEVER HAPPENED. The line above says HOW MANY arrows were
        # loosed and cannot say why the rest were not, so every reading of it has ended in a guess.
        # Both bow courses measured bowShots=2 of ~20 requested in the last sweep -- 90% of shot
        # requests producing no arrow -- and the counters that split that reason ALREADY EXIST in
        # placeStats and were simply never printed here:
        #   bowNoSol   no firing solution -- ballistics could not reach the target
        #   bowAimTO   the turn never got inside the draw cone, so the bow was never drawn
        #   bowDrawTO  drawn and fully charged, but no tick ever predicted a hit
        #   bowFacing / bowNoRoom / bowRestart   the remaining refusals
        # Those four have completely different fixes. This is the third diagnostic today found to
        # exist and not be exposed -- a gate that is not printed is not a gate.
        yield Criterion("why the rest never loosed (recorded, not gated)", True,
                        f"noSol={_stat(ctx, 'bowNoSol')} aimTO={_stat(ctx, 'bowAimTO')}"
                        f" drawTO={_stat(ctx, 'bowDrawTO')} facing={_stat(ctx, 'bowFacing')}"
                        f" noRoom={_stat(ctx, 'bowNoRoom')} restart={_stat(ctx, 'bowRestart')}"
                        f" bestMiss={_stat(ctx, 'bowBestMiss')}",
                        gate=False)

        # ⛔ AND THE COUNTERS FOR WHAT ACTUALLY FAILS. The bow split answered its own question on
        # its first run -- ~20 requested = 3 aimed + 6 wild + 9 refused, with noSol/aimTO/drawTO all
        # zero -- so the shooting is not broken. Both courses fail on DISTANCE: bow_flee at deaths=4
        # and avg 5.57 against a required 7, bow_flee_hard at first_death=5.8s with the bow never
        # engaged at all. That is the flee, and its counters exist and were never printed here
        # either. Same one-line move, now aimed by the diagnosis rather than at random.
        #   flee=...      the flee state machine's own tallies
        #   fleeStuck     the flee ran and could not get anywhere
        #   fleeShooter   it recognised a SHOOTER as the thing to run from
        #   mdFar         the defence chain's far-range decisions
        # ⛔ md* COUNTERS ARE STRUCTURALLY ZERO ON THIS COURSE -- DO NOT PUT THEM HERE.
        # The first version of this line printed mdFlee, mdBow, mdFar, mdFleeStuck and
        # mdFleeShooter, and every one read 0. Querying the mod directly says why: mdCalls=0.
        # MobDefenseChain.getPriority() never ticks on the bow courses, so everything hanging off
        # it cannot be non-zero, and "the flee never recognised a shooter" would have been read
        # straight out of an instrument that was switched off. That is the checklist entry about
        # dmgTaken=0.0 on a course where the bot died five times, and rule 4u's cousin: zero by
        # construction, chain-gated rather than flag-gated. Caught within minutes of adding it, by
        # asking the discriminating question the checklist names -- what does mdCalls read.
        #
        # flee= is tungsten-side and DOES tick here, so it is the half worth printing. mdCalls is
        # printed beside it so the zero can never be misread as a finding again.
        yield Criterion("did the flee even run (recorded, not gated)", True,
                        f"flee={_stat(ctx, 'flee')} mdCalls={_stat(ctx, 'mdCalls')}"
                        f" (md* are 0 here by construction: the defence chain does not tick)",
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
        # bowShots counts only the AIMED releases. An aborted draw is not a cancelled shot —
        # vanilla fires on key release past ~3 ticks of draw — so bowWild is the rest of the
        # arrows, thrown wherever the camera happened to point. Reading bowShots alone made
        # "6 of ~20" look like "14 never happened" when it means "14 were thrown away".
        yield Criterion("arrows actually loosed (recorded, not gated)", True,
                        f"bowShots={_stat(ctx, 'bowShots')} bowWild={_stat(ctx, 'bowWild')}"
                        f" requested~{int(self.duration / 3)}",
                        gate=False)
        # ⛔ AND WHY THE OTHER EIGHTEEN NEVER HAPPENED. The line above says HOW MANY arrows were
        # loosed and cannot say why the rest were not, so every reading of it has ended in a guess.
        # Both bow courses measured bowShots=2 of ~20 requested in the last sweep -- 90% of shot
        # requests producing no arrow -- and the counters that split that reason ALREADY EXIST in
        # placeStats and were simply never printed here:
        #   bowNoSol   no firing solution -- ballistics could not reach the target
        #   bowAimTO   the turn never got inside the draw cone, so the bow was never drawn
        #   bowDrawTO  drawn and fully charged, but no tick ever predicted a hit
        #   bowFacing / bowNoRoom / bowRestart   the remaining refusals
        # Those four have completely different fixes. This is the third diagnostic today found to
        # exist and not be exposed -- a gate that is not printed is not a gate.
        yield Criterion("why the rest never loosed (recorded, not gated)", True,
                        f"noSol={_stat(ctx, 'bowNoSol')} aimTO={_stat(ctx, 'bowAimTO')}"
                        f" drawTO={_stat(ctx, 'bowDrawTO')} facing={_stat(ctx, 'bowFacing')}"
                        f" noRoom={_stat(ctx, 'bowNoRoom')} restart={_stat(ctx, 'bowRestart')}"
                        f" bestMiss={_stat(ctx, 'bowBestMiss')}",
                        gate=False)

        # ⛔ AND THE COUNTERS FOR WHAT ACTUALLY FAILS. The bow split answered its own question on
        # its first run -- ~20 requested = 3 aimed + 6 wild + 9 refused, with noSol/aimTO/drawTO all
        # zero -- so the shooting is not broken. Both courses fail on DISTANCE: bow_flee at deaths=4
        # and avg 5.57 against a required 7, bow_flee_hard at first_death=5.8s with the bow never
        # engaged at all. That is the flee, and its counters exist and were never printed here
        # either. Same one-line move, now aimed by the diagnosis rather than at random.
        #   flee=...      the flee state machine's own tallies
        #   fleeStuck     the flee ran and could not get anywhere
        #   fleeShooter   it recognised a SHOOTER as the thing to run from
        #   mdFar         the defence chain's far-range decisions
        # ⛔ md* COUNTERS ARE STRUCTURALLY ZERO ON THIS COURSE -- DO NOT PUT THEM HERE.
        # The first version of this line printed mdFlee, mdBow, mdFar, mdFleeStuck and
        # mdFleeShooter, and every one read 0. Querying the mod directly says why: mdCalls=0.
        # MobDefenseChain.getPriority() never ticks on the bow courses, so everything hanging off
        # it cannot be non-zero, and "the flee never recognised a shooter" would have been read
        # straight out of an instrument that was switched off. That is the checklist entry about
        # dmgTaken=0.0 on a course where the bot died five times, and rule 4u's cousin: zero by
        # construction, chain-gated rather than flag-gated. Caught within minutes of adding it, by
        # asking the discriminating question the checklist names -- what does mdCalls read.
        #
        # flee= is tungsten-side and DOES tick here, so it is the half worth printing. mdCalls is
        # printed beside it so the zero can never be misread as a finding again.
        yield Criterion("did the flee even run (recorded, not gated)", True,
                        f"flee={_stat(ctx, 'flee')} mdCalls={_stat(ctx, 'mdCalls')}"
                        f" (md* are 0 here by construction: the defence chain does not tick)",
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
    scores_own_falls = True   # gates self-falls itself; knockback falls are normal here
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

    # ⛔ SUPERSEDED — AT PLAYABLE FRAME RATES THESE DEATHS ARE LOST FIGHTS, NOT VOID FALLS.
    # Re-measured from the server log on the 29 fps jar: tester1 slain 25 / fell out of the world 4,
    # tester2 slain 14 / fell 4. Falls are 14% of the bot's deaths, and it is losing a SYMMETRIC duel
    # (both iron_sword, no scenario in this suite issues armour, 206 damage over 40 hits = four blows
    # kill) by roughly 25:14. The section below is kept because it is correct for the run it describes
    # -- 4-8 fps, where the guard lost the tick and both fighters drifted off the rim -- but reading it
    # as the current cause sends a pass at the void guard instead of at the exchange. Check the
    # SERVER LOG before believing either framing -- it is the one source that names the cause of
    # each death.
    #
    # CORRECTION, because the first version of this note got it wrong: I called hits=0/0/0/0 and
    # dmgTaken=0.0 "provably dead counters" and used them to distrust voidEntries. They are not
    # dead. hits= is MobDefenseChain.mdHitFront/Back/Left/Right and dmgTaken= is
    # MobDefenseChain.mdDamageTaken -- MOB-defence counters, correctly zero in a player-vs-player
    # duel. voidEntries is a DamageWatch field, the same live family as dw= (ticked from the client
    # precisely so it survives courses where altoclef's chain loop never runs), so it is trustworthy.
    # Note it read 0 while the log showed 4 falls for tester1 in the same window; counters reset per
    # run and the log window spanned two, so check both and do not assume either is lying.
    #
    # ORIGINAL NOTE (measured 2026-08-09, at 4-8 fps):
    # This course was read for a whole pass as an AIM failure ("27 swings of 556, 79 deg off"). Two
    # things were wrong with that. The swing numbers came off runs at 4-8 fps against a 14.0 floor
    # that was not voiding this course (fixed in f56a511). And the gate that actually fails is
    # `bot deaths <= 0`, which is NOT frame-noise — so it is worth reading, and it says this:
    #
    #     [07:22:45] tester1 fell out of the world
    #     [07:22:45] tester2 fell out of the world      <- the OPPONENT falls too
    #
    # Straight from the server log, repeating every few seconds. flat_field(half=24) is a 48x48
    # platform surrounded by void and the duel drifts off the edge. punkStats agrees from the other
    # side: voidHold=406 — the void guard engaged four hundred times and still did not hold.
    # At 4.1 fps the same run scored kills=7 and landed=44 swings, so the offensive half is fine.
    #
    # BEFORE TOUCHING THE AIM AGAIN, note that BOTH bots fall. That points at the guard losing the
    # tick to whoever drives combat movement (the "one owner of the tick" pitfall that has already
    # bitten this repo twice) rather than at a targeting bug.
    def build(self, arena, ctx):
        arena.flat_field(half=24)
        ctx.geo["bot_spawn"] = f"-19.5 {STAND_Y} 0.5 -90 0"
        ctx.geo["victim_spawn"] = f"19.5 {STAND_Y} 0.5 90 0"
        ctx.geo["melee_started"] = False
        ctx.geo["last_shot"] = -10.0

    def drive_start(self, ctx):
        ctx.bot.py.call("selectHotbar", 0)  # bow in hand for the ranged phase
        ctx.victim.py.call("punk", ctx.bot.name)

    # ⛔ WHAT THIS COURSE ACTUALLY SAYS, ONCE ALL FOUR SUSPECTS ARE PRICED (2026-08-20).
    # Both fighters, same run, same jar, from the counters rather than from a theory:
    #
    #     swings issued   bot 71   victim 74      (equal)
    #     weaponMean      bot 75   victim 75      (equal -- and 75 is the sword; the bow theory
    #                                              is dead on both sides, meleeBow 7/394 vs 0/424)
    #     chargeMean      bot 0.999 victim 0.999  (equal -- undercharge is dead)
    #     crits           bot 23   victim 25      (equal -- the aim arbiter did close this)
    #
    # A fully charged iron_sword is 6, a crit 9, so 71 swings with 23 crits is 495 hp if every
    # one connects. The ledger says the bot removed 203.1 -- 41%. The bot loses ~340 hp over 17
    # deaths against the victim's 519 theoretical, ~66%, and even discounting the 14% of deaths
    # the server log attributes to falls it stays clearly above ours.
    #
    # So the gap is CONNECTION, not weapon, charge or crits: the bot's swings go out at the same
    # rate and arrive far less often. The one configured difference between the two fighters is
    # victim_settings -- combatReachControl is OFF for the opponent and ON for us.
    #
    # PRE-REGISTERED before the runs, so the reading cannot be picked after seeing them:
    #   metric   dealt / swings issued, from the hp-drop ledger. Continuous, and far tighter
    #            than kills-minus-deaths, whose sd is about 2.7 on ~27 exchanges.
    #   arms     --repeat 6 --pin-alt combatReachControl=false (interleaved, rule 4r); pins go
    #            to the BOT only, so arm B is the bot fighting on the opponent's own settings.
    #   call     if arm B's per-swing damage clearly exceeds arm A's, reach control is costing
    #            the connection and is the fix. If the two arms are indistinguishable, reach
    #            control is exonerated and the cause is elsewhere -- record that and move on.

    # ⛔ AND NEITHER IS THE WOUNDED KITE, WHICH IS LIVE ONLY HERE. This is the one pvp kit with a
    # bow, so hasRangedOption is true and CombatController's low-health branch actually FIRES --
    # lowHp reads 56/0 and 75/0 here against 0/xx everywhere else, i.e. 56-75 ticks a run spent
    # breaking contact to use a bow. Given the control above says the bow is worth nothing on this
    # course, that looked like G-1.73's defect one level down: a predicate asking "is there a bow"
    # where the question is "is the bow worth more than the fight".
    # Control: arrows removed from bot_kit, so hasRangedOption is false and the branch declines
    # (lowHp 0/49, 0/68, 0/54 confirms it). Three runs at 25.8-28.7 fps: 6:8, 5:7, 6:9 -- margin
    # -2, -2, -3, indistinguishable from -2, -2, -2 with the kite live. REFUTED.
    #
    # ⛔ AND A THIRD SUSPECT DIED THE MOMENT ITS NUMBERS WERE SPLIT BY BUILD. The ledger shows the
    # bot taking more damage per blow than it deals (172/33 = 5.2 against the victim's 132/31 =
    # 4.3, identical iron swords), which looked like a CRIT RATE asymmetry -- and the crit hop is
    # movement, the one thing combatReachControl changes about this bot and not its opponent.
    # Crits per passed swing, both fighters, same runs:
    #     before the aim arbiter   bot 0.46 0.34 0.31   victim 0.58 0.54 0.60
    #     after  the aim arbiter   bot 0.52 0.50 0.50   victim 0.50 0.32 0.41
    # The gap was real and the ARBITER ALREADY CLOSED IT -- the bot now crits slightly more than
    # its opponent. It was never the flag: a bot standing still to shoot does not bunny-hop, so it
    # swung flat-footed. Quoting the range across both builds (critWindowSwings "4-10 against
    # 8-15") hides that completely, which is how this nearly became a fourth investigation.
    #
    # ⛔ THE RANGED PHASE IS NOT WHAT LOSES THIS COURSE. MEASURED, DO NOT RE-DERIVE.
    # After the aim arbiter landed, the margin sat at -2, -2, -2 where it had been about -4, and
    # the obvious next suspect was the bow economy: a draw blocks sprinting, so shooting on the
    # approach should cost the ground that decides the fight.
    #
    # Control run with this constant raised to 999, so the drive never enters the ranged phase at
    # all -- four runs, three at healthy fps: 6:8, 6:9, 6:8, 6:8, i.e. -2, -3, -2, -2.
    # Indistinguishable from the with-bow numbers. Deleting the bow economy ENTIRELY changes
    # nothing, so the residual is the melee itself, on a flat field, against the same engine --
    # which is exactly where melee_basic reads dead even at 5:5 under the identical asymmetry.
    # Kills per minute: allround 3, melee_basic 5; deaths per minute 4 and 5. The bot dies at the
    # same rate on both and kills less on this one.
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
        # PUNK STAYS ON THROUGH THE RANGED PHASE. Started once, here, so the bot closes the whole
        # time instead of standing still between arrows. The idempotent re-issue is cheap and
        # survives a respawn, which is what silently ended the pursuit before.
        if not ctx.geo.get("punk_running"):
            ctx.geo["punk_running"] = True
            ctx.bot.py.call("punk", ctx.victim.name)
        if ctx.geo["melee_started"]:
            # Back to range — a respawn puts the fighters apart again, and the course is meant
            # to measure the bow phase every round, not only the first one.
            #
            # ⛔ THE punkStop THAT USED TO BE HERE IS GONE, AND IT WAS THE COURSE'S WHOLE DEFICIT.
            # Read from BOTH fighters' counters on the same runs (n=3, healthy fps):
            #     punk called   bot 201-396   victim 1375-1404
            #     combat ticks  bot 221-287   victim  417-475
            #     swings passed bot  24- 29   victim   39- 43
            # The bot was not out-fought, it was out-TICKED three to seven times over: it stopped
            # pursuing every time it wanted an arrow, while an opponent that never stops walked in
            # with the initiative.
            #
            # It was there for a real reason — two writers on WindMouseRotation in one tick means
            # last-writer-wins, so a shot taken while punk was live never converged. That is now
            # fixed in the ENGINE, not worked around here: CombatController yields the camera while
            # BowShooter.isAimCritical(), exactly as PathExecutor already did, and keeps producing
            # movement keys. Walking at someone and aiming at them want the same yaw.
            #
            # So this is not the drive being rewritten to pass. It is the drive using a composition
            # the primitives refused to support until today, which is the point of the toolkit.
            ctx.geo["melee_started"] = False
            ctx.bot.py.call("selectHotbar", 0)
        if t - ctx.geo["last_shot"] >= 2.5:
            ctx.geo["last_shot"] = t
            ctx.bot.py.try_call("shootArrowAt", ctx.victim.name)

    def early_stop(self, ctx):
        # ⛔ DO NOT END A 120s COURSE AT THE FIRST KILL.
        # melee_basic had exactly this line and it was fixed there: "stopping at the first kill
        # ended runs after about five seconds and left TWO samples to judge a fight from, which is
        # why this course looked bimodal". allround kept the naive form, so every run ended in
        # roughly twenty seconds -- samples=3 on a two-minute course, kills=1 in every run by
        # construction, and every ratio anyone computed against "120 s of ticks" was wrong by
        # about a factor of six. (Measured today: punk called=363, follow called=322 -- read as
        # ~3 Hz against a 120 s run, they are ~18 Hz against the ~20 s the run actually lasted.)
        #
        # It also mis-states the GATE. `bot deaths <= 0` is a quantity that accumulates over a
        # fight; judging it over the sprint to the first kill measures something else entirely.
        #
        # NOT applied to chase_terrain or bridge_assault, which carry the same line: for those two
        # the first kill may legitimately BE the task ("catch it and kill it", "break through and
        # kill"). Decide what each course means to measure before copying this across.
        return ctx.kills() >= 1 and (time.time() - ctx.t0) > self.duration / 2

    def judge(self, ctx):
        # BOTH OF THESE ARE FRAME-GATED, AND SAYING SO HERE IS THE POINT OF THE FLAG.
        # This course ran at 4.79 fps and came out `invalid: false`, because only survival_criterion
        # carried the flag: `all(load_sensitive)` needs EVERY failed gate to declare itself, so two
        # unflagged gates were enough to certify a run the floor should have thrown out. melee_basic
        # at 5.11 fps was correctly voided the same evening — same stand, same minute, opposite
        # verdict — and a whole pass was then spent explaining allround's combat numbers as a bot
        # defect. They were a frame rate.
        #
        # WHY AIMING IS LOAD-SENSITIVE, measured 2026-08-09 rather than assumed. Rotation reaches the
        # client as accumulated mouse pixels consumed in MixinMouse#updateMouse, which fires once per
        # FRAME, not per tick. Sampling yaw during the fight: the head moved on 9 of 59 ticks, in
        # steps of ~9.7 deg spaced ~4 ticks apart — exactly 20 tps / 5 fps. So at this frame rate the
        # bot gets ~5 aim corrections per second against a sprinting opponent, and the swing gate
        # reads angleMean 68-79 deg. A kill needs swings to land and a ranged hit needs the bow on a
        # moving target; neither can be met when the crosshair is only allowed to move five times a
        # second. That is the definition of this flag: a low frame rate could plausibly cause it.
        ranged = ctx.hp_drop_events(who="victim", min_dist=8)
        # WHY THE BOT NEVER ARRIVES. Measured n=3: only 58-74 of ~2400 ticks reach COMBAT mode,
        # the rest sit in APPROACH with the follow task live and never restarting. The follow
        # chain is follow -> trail -> live direct-steer -> leap -> enterCombat(dist < 3.4), and
        # FollowEntityTask already counts which gate eats the chase -- steer/leap/cooldown/
        # losBlocked against the ticks it got. chaseStats() has existed and been exposed over
        # py4j all along and NO course reads it, so the question "which of the five conditions
        # blocks the last half-block" has been answerable and unanswered. Recorded, not gated.
        ok_cs, cs = ctx.bot.py.try_call("chaseStats")
        ctx.log(f"  chase: {cs if ok_cs else 'UNREADABLE'}")
        # AND THE GATE ABOVE THE CHASE. enterCombat() needs `dist < 3.4 AND hasLOS`, and this
        # repo already records that hasLOS is false for whole fights -- closeQuarters returns on
        # its first line when !hasLOS, "proven by ctlTotal=0 against lowHpTicks=149". The four
        # counters that separate "findBestAimPoint never ran" from "every sample was blocked"
        # exist (losCalls/losClosest/losSample/losNone) and, like chaseStats, no course printed
        # them. cq= is the entry/no-LOS split for the same question one layer down.
        ctx.log(f"  los={_stat(ctx, 'los')} cq={_stat(ctx, 'cq')} ctl={_stat(ctx, 'ctl')}")
        # THE ONE THING THIS COURSE SHARES WITH edge_duel AND melee_basic DOES NOT: a 40x40 field
        # with a VOID border. standOff says whether the cooldown stand-off is being refused here --
        # i.e. whether fights are drifting to the rim where backing off is unsafe. It reads 0 on
        # melee_basic (an open island) and 14-207 on a 5x5 platform, so a large number here would
        # say this arena behaves like the platform and point the residual at the rim rather than at
        # the melee. Both fighters, because only the bot carries the setting.
        ctx.log(f"  standOff={_stat(ctx, 'standOff')} lowHp={_stat(ctx, 'lowHp')}"
                f" | victim standOff={_stat(ctx, 'standOff', ctx.victim)}")
        _ledger(ctx)
        yield Criterion("ranged hit while far >= 1", len(ranged) >= 1,
                        f"ranged_hits={len(ranged)}", load_sensitive=True)
        yield Criterion("kill", ctx.kills() >= 1, f"kills={ctx.kills()}",
                        load_sensitive=True)
        # ⛔ THIS WAS survival_criterion() -- "bot deaths <= 0" -- AND NO BUILD COULD EVER PASS IT.
        # The victim punks back for the whole 120 s with the same engine and an iron sword, no
        # scenario in this suite issues armour, and 206 damage over 40 hits means four blows kill.
        # Deaths are the currency of this course: the honest full-length baseline is 5:6 and 4:10,
        # and the sweep that prompted this read 4:10 and 6:8. A gate the course's own design makes
        # unreachable is the mirror of a check that cannot fail, and worth exactly as little.
        #
        # The right criterion was already written down HERE, in the bench's own docstrings.
        # survival_criterion says it is "for scenarios where the opponent is NOT a symmetric
        # threat (a fleeing runner, a slowed chaser we kite)"; exchange_criterion says
        # "demanding 0 deaths against an identical opponent would measure luck". The comment at
        # the top of this class had already concluded the same thing from the server log --
        # "it is losing a SYMMETRIC duel ... by roughly 25:14". Three statements of the answer,
        # none of them applied.
        #
        # THIS DOES NOT TURN THE COURSE GREEN, and it is not meant to. On the current build the
        # margins are -6 and -2, so it still fails -- but now it fails for something a build can
        # change, and the bot carries a BOW the opponent does not, so kills >= deaths is if
        # anything a generous bar here.
        yield ctx.exchange_criterion()   # mutual duel, and the bot has the ranged advantage
        # HOW MANY TIMES DID IT ACTUALLY CONNECT. Read off a timeline by hand for the first time
        # today, and it is the sharpest number this course has: over a full run the bot landed
        # FOUR swings (lifetimeHits 105 -> 109) while dying twice. A sword swings ~1.6/s, so ~13 s
        # of contact should be nearer twenty. The course reported "kills=1 deaths=2" and said
        # nothing about that, so nobody could see the fight was lost on output rather than luck.
        # Recorded, never a gate: it is a count, so unlike a timing gate it stays readable at the
        # 5-9 fps this stand runs at.
        # ⛔ "landed" IS A LIE OF NAMING AND THE READOUT MUST NOT REPEAT IT. TriggerBot does
        # `lifetimeHits++` immediately BEFORE `attackEntity`, so the number counts swings ISSUED --
        # misses, swings out of reach and swings into air included -- and `crits` counts swings
        # made in a crit condition, not crit damage delivered. Read as hits, it produced a
        # paradox that consumed a whole pass: "both sides connect about seventy times and the bot
        # crits more, yet converts eleven kills against seventeen deaths".
        #
        # DAMAGE is the quantity that decides this course, and the mod has counted it all along --
        # `dealt` in placeStats, which this readout simply never printed. Printing it costs nothing
        # and is the difference between measuring the fight and measuring the button presses.
        # TWO INSTRUMENTS ON THE SAME EVENT, WHICH IS THE POINT. `dealt` is damage OUR swings
        # removed, read off the bot; `dw` is damage TAKEN, read off each fighter -- and _ledger
        # above already prints the taken half for both sides. In a duel the bot's dealt must
        # come out near the victim's dw damage, because they describe one exchange from either
        # end. Agreement validates both; a gap means one of them is wired to something that is
        # not the fight, which is exactly the failure this readout was built to catch.
        _d = _stat(ctx, "dealt") or ""
        _bits = _d.split("/")
        _mine, _seen, _ticks = (_bits + ["?", "?", "?"])[:3]
        _vdw = (_stat(ctx, "dw", ctx.victim) or "").split("/")
        _vtook = _vdw[1] if len(_vdw) > 1 else "?"
        _hits = _stat(ctx, "swingHits") or "0"
        try:
            _per = f"{float(_mine) / int(_hits):.2f}" if int(_hits) else "?"
        except (ValueError, ZeroDivisionError):
            _per = "?"
        _note = ("  LEDGER NEVER TICKED -- a zero here is wiring, not a fight"
                 if _ticks in ("0", "?") else "")
        yield Criterion("damage DEALT vs the victim's damage TAKEN (recorded, not gated)", True,
                        f"dealt={_mine} seen={_seen} hits={_hits} perHit={_per} ticks={_ticks}"
                        f" | victim took {_vtook}{_note}", gate=False)
        # WHAT IS IN THE HAND WHEN WE SWING. TriggerBot has counted this for a while and, like
        # `dealt`, no course ever printed it -- so the theory written at the swing site (this
        # course puts a BOW in slot 0, WeaponSelector rechecks only every 20 ticks, and every
        # respawn starts holding it, so part of each life is fought with a 1-damage weapon)
        # has never actually been read. chargeMean is here as the CONTROL: it came back 1.000,
        # which is what killed the undercharged-swing theory, and it must stay there -- if it
        # drifts, the damage figure above has a second cause and neither can be quoted alone.
        yield Criterion("weapon in hand at the swing (recorded, not gated)", True,
                        f"weaponMean={_stat(ctx, 'weaponMean')} noWeapon={_stat(ctx, 'noWeapon')}"
                        f" chargeMean={_stat(ctx, 'chargeMean')}"
                        f" | victim weaponMean={_stat(ctx, 'weaponMean', ctx.victim)}"
                        f"   [WeaponSelector's scale, NOT damage: iron_sword reads 75.00]", gate=False)
        yield Criterion("swings ISSUED (recorded, not gated)", True,
                        f"issued={ctx.landed_swings()} critCond={ctx.crit_swings()}"
                        f"   [issued counts attacks SENT, not hits -- see comment]", gate=False)
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
        # WHICH GATE REFUSES THE SWING. 200-330 ticks inside combat producing FOUR landed swings is
        # a hit-rate problem, and TriggerBot already counts every reason it declines to click:
        # click / cooldown / reach / angle / los. Its own comment says "exactly one of these is the
        # answer". gateStats() has existed all along with no callers — the FOURTH such counter this
        # session (mdFleeStuck, getShotsFired, punkStats were the others).
        ok, gs = ctx.bot.py.try_call("gateStats")
        yield Criterion("swing gates (recorded, not gated)", True,
                        str(gs) if ok and gs else "unreadable", gate=False)
        # ⛔ AND THE SAME COUNTERS FROM THE OPPONENT, WHICH IS RUNNING THIS MOD TOO.
        # The bot's own line says reach refuses 178 of 285 evaluations -- it is out of range on
        # 62% of the ticks it could have swung on. That number is USELESS alone: both fighters
        # chase with the same code, so 62% might simply be what this arena costs. What decides
        # whether it is a defect is whether the VICTIM's share is the same or lower.
        #
        # This is the ledger lesson applied to the trigger: DamageWatch had been counting on the
        # opponent all along and no probe read it, and reading it turned a coin-flip margin into a
        # 3.2 sigma result the same afternoon. gateStats and punkStats are in exactly that position
        # -- exposed over py4j, running on both clients, and read on one.
        okv, gsv = ctx.victim.py.try_call("gateStats")
        yield Criterion("swing gates, VICTIM (recorded, not gated)", True,
                        str(gsv) if okv and gsv else "unreadable", gate=False)
        okvp, psv = ctx.victim.py.try_call("punkStats")
        yield Criterion("punk task, VICTIM (recorded, not gated)", True,
                        str(psv) if okvp and psv else "unreadable", gate=False)
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
