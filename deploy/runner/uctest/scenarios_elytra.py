"""uctest elytra suite — the flight checks of tools/elytra/bench in the suite's own shape.

Three courses, each one thing the elytra work claims (docs/features/ELYTRA_TUNGSTEN.md):

  elytra_rings    six 7x7 rings 60 blocks apart, heights and sides varied: through all of them in
                  order, none touched: at most 3.2 off centre (the opening's half 3.5, less the body's 0.3)
  elytra_500      take-off from the pad, 500 blocks east, land within 30 of the target
  elytra_follow   the victim flies a five-point route on the elytra, the bot follows it: how far
                  behind it stays and how many rockets that costs against the leader's

The bot flies with the solver (flySettings solver=true), the leader in elytra_follow with the
plain pilot, as a player would fly a route. Flights are minutes of real game time, not seconds,
so each course lets its flight finish (early_stop on the flight's own status) rather than run a
fixed clock. Everything the courses build lives in the open air east of the arena and is cleared
again in cleanup().
"""
import statistics

from .arena import STAND_Y
from .scenario import Criterion, Scenario

KIT = ["give {name} minecraft:elytra", "give {name} minecraft:firework_rocket 64"]
SPAWN = f"0.5 {STAND_Y} 0.5 -90 0"   # facing east, along +x


class ElytraCourse(Scenario):
    """Shared shape: a pad to take off from, a flight started in drive_start, its status polled
    every second; the course is over when the flight is."""
    tier = "gate"
    needs_victim = False
    duration = 150
    arena_half = 40
    bot_kit = KIT

    def build(self, arena, ctx):
        arena.floor(-4, -4, 4, 4, "stone")
        arena.marker(0, 0, "lime")
        ctx.geo["bot_spawn"] = SPAWN

    def _status(self, ctx):
        ok, st = ctx.bot.py.try_call("flyStatus")
        return st if ok and isinstance(st, dict) else {}

    def drive_tick(self, ctx, elapsed):
        st = self._status(ctx)
        if st:
            ctx.geo["status"] = st
        if st.get("phase") in ("done", "failed") and ctx.geo.get("over_at") is None:
            ctx.geo["over_at"] = elapsed

    def early_stop(self, ctx):
        return ctx.geo.get("over_at") is not None

    def arrived(self, ctx):
        return ctx.geo.get("status", {}).get("phase") == "done"

    def drive_stop(self, ctx):
        ctx.bot.py.try_call("flyStop")


class ElytraRings(ElytraCourse):
    """Six rings, concrete frames round a 7x7 opening, planes square to the way east."""
    id = "elytra_rings"
    RINGS = [(80, 18, 0), (140, 24, 6), (200, 16, -6), (260, 26, 2), (320, 20, -2), (380, 14, 0)]

    def _rings(self):
        floor = STAND_Y - 1
        return [(x, floor + dy, z) for x, dy, z in self.RINGS]

    def build(self, arena, ctx):
        super().build(arena, ctx)
        rc = ctx.rcon
        # Outside the arena: load it first, or every fill there is "not loaded" and the course
        # is empty air.
        rc.cmd("forceload add 60 -16 400 16")
        for x, y, z in self._rings():
            rc.cmd(f"fill {x} {y - 4} {z - 4} {x} {y + 4} {z + 4} minecraft:orange_concrete")
            rc.cmd(f"fill {x} {y - 3} {z - 3} {x} {y + 3} {z + 3} minecraft:air")
        ctx.geo["rings"] = self._rings()

    def drive_start(self, ctx):
        ctx.bot.py.call("flySettings", True, None, None, None)
        pts = ";".join(f"{x + 0.5},{y + 0.5},{z + 0.5}" for x, y, z in ctx.geo["rings"])
        ctx.bot.py.call("flyRings", pts)

    def judge(self, ctx):
        st = ctx.geo.get("status", {})
        miss = st.get("ringMiss") or []
        n = len(self.RINGS)
        yield Criterion("flight done", st.get("phase") == "done",
                        f"phase={st.get('phase')} reason={st.get('reason')}", load_sensitive=False)
        yield Criterion(f"through all {n} rings", len(miss) == n, f"ringMiss={miss}", load_sensitive=False)
        # 3.5 is the opening's half, 0.3 the body's: off centre by more than 3.2 is the frame hit.
        yield Criterion("none touched (<= 3.2 off centre)", len(miss) == n and max(miss) <= 3.2,
                        f"max={max(miss) if miss else None}", load_sensitive=False)
        yield Criterion("rockets", True, f"rocketsUsed={st.get('rocketsUsed')}", gate=False)

    def cleanup(self, ctx):
        rc = ctx.rcon
        for x, y, z in ctx.geo.get("rings", []):
            rc.cmd(f"fill {x} {y - 4} {z - 4} {x} {y + 4} {z + 4} minecraft:air", allow_reject=True)
        rc.cmd("forceload remove 60 -16 400 16", allow_reject=True)


class Elytra500(ElytraCourse):
    """Take-off from the pad and 500 blocks east over the flat world, landing on its floor."""
    id = "elytra_500"
    TARGET = (500, 0)

    def drive_start(self, ctx):
        ctx.bot.py.call("flySettings", True, None, None, None)
        ctx.bot.py.call("flyTo", self.TARGET[0], STAND_Y, self.TARGET[1])

    def judge(self, ctx):
        st = ctx.geo.get("status", {})
        p = ctx.bot.pos() or [0, 0, 0]
        off = ((p[0] - self.TARGET[0]) ** 2 + (p[2] - self.TARGET[1]) ** 2) ** 0.5
        yield Criterion("flight done", st.get("phase") == "done",
                        f"phase={st.get('phase')} reason={st.get('reason')}", load_sensitive=False)
        yield Criterion("landed within 30 of the target", off < 30, f"off={off:.1f}", load_sensitive=False)
        yield Criterion("rockets", True, f"rocketsUsed={st.get('rocketsUsed')}", gate=False)


class ElytraFollow(ElytraCourse):
    """The victim leads a five-point route on the plain pilot; the bot follows it on the solver.

    Gates: the median distance to the leader while both glide is at most 15 blocks, and the bot
    spends at most three rockets more than the leader. Both numbers are what a player flying
    with the bot would notice: a follower 40 blocks back is not flying with you, and one burning
    twice your rockets is not one you can take along in survival."""
    id = "elytra_follow"
    needs_victim = True
    victim_kit = KIT
    duration = 180
    ROUTE = "160,60;280,0;160,-60;40,0;160,60"
    MEDIAN_GAP = 15
    EXTRA_ROCKETS = 3

    def build(self, arena, ctx):
        super().build(arena, ctx)
        arena.floor(-4, 6, 4, 14, "stone")
        ctx.geo["victim_spawn"] = f"0.5 {STAND_Y} 10.5 -90 0"

    def drive_start(self, ctx):
        ctx.geo["gaps"] = []
        ctx.victim.py.call("flySettings", False, None, None, None)
        ctx.victim.py.call("flyRoute", self.ROUTE, False, 0)
        ctx.bot.py.call("flySettings", True, None, None, None)
        ctx.bot.py.call("flyFollow", ctx.victim.name)

    def drive_tick(self, ctx, elapsed):
        ok, lead = ctx.victim.py.try_call("flyStatus")
        lead = lead if ok and isinstance(lead, dict) else {}
        ctx.geo["lead_status"] = lead or ctx.geo.get("lead_status", {})
        st = self._status(ctx)
        if st:
            ctx.geo["status"] = st
        b, v = ctx.bot.pos(), ctx.victim.pos()
        # Measured only while the leader is in the air on its way: the take-off run and the
        # landing are not following.
        if b and v and lead.get("phase") not in (None, "done", "failed", "idle", "takeoff", "landing"):
            ctx.geo["gaps"].append(sum((b[i] - v[i]) ** 2 for i in range(3)) ** 0.5)
        if lead.get("phase") in ("done", "failed") and ctx.geo.get("over_at") is None:
            ctx.geo["over_at"] = elapsed

    def drive_stop(self, ctx):
        ctx.bot.py.try_call("flyStop")
        ctx.victim.py.try_call("flyStop")

    def judge(self, ctx):
        gaps = ctx.geo.get("gaps", [])
        lead = ctx.geo.get("lead_status", {})
        st = ctx.geo.get("status", {})
        med = statistics.median(gaps) if gaps else None
        mine, theirs = st.get("rocketsUsed"), lead.get("rocketsUsed")
        yield Criterion("leader flew the route", lead.get("phase") == "done",
                        f"phase={lead.get('phase')} reason={lead.get('reason')}", load_sensitive=False)
        yield Criterion(f"median gap <= {self.MEDIAN_GAP}", med is not None and med <= self.MEDIAN_GAP,
                        f"median={None if med is None else round(med, 1)} max="
                        f"{None if not gaps else round(max(gaps), 1)} samples={len(gaps)}",
                        load_sensitive=True)
        yield Criterion(f"rockets <= leader + {self.EXTRA_ROCKETS}",
                        mine is not None and theirs is not None and mine <= theirs + self.EXTRA_ROCKETS,
                        f"bot={mine} leader={theirs}", load_sensitive=False)
        yield Criterion("leader's rockets as the bot saw them", True,
                        f"seen={st.get('leaderRockets')} leader={theirs}", gate=False)


SCENARIOS = {c.id: c for c in (ElytraRings, Elytra500, ElytraFollow)}
