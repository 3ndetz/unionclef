"""uctest scenario base — setup -> drive -> sample -> verdict, with the shared
detectors (freeze, stand-still-near-target, self-fall vs knockback-fall) and
the retry-once flake policy (flakiness != regression, CHECKLIST 5.4)."""
import time

from .arena import FLOOR_Y


class Criterion:
    def __init__(self, name, ok, detail="", gate=True):
        self.name, self.ok, self.detail, self.gate = name, bool(ok), detail, gate

    def as_dict(self):
        return {"name": self.name, "ok": self.ok, "detail": self.detail,
                "gate": self.gate}


class Ctx:
    """Live state of one scenario run: actors, geometry, samples, detectors."""

    def __init__(self, bot, victim, rcon, art, log=print):
        self.bot, self.victim, self.rcon, self.art, self.log = bot, victim, rcon, art, log
        self.geo = {}
        self.samples = []
        self.freeze_windows = 0
        self.standstill_windows = 0
        self.self_falls = 0
        self.knockback_falls = 0
        self.first_contact = None
        self.ranged_hits = 0
        self._last_move_t = time.time()
        self._last_move_pos = None
        self._below = False
        self.t0 = time.time()

    # -- sampling ----------------------------------------------------------
    def sample(self, floor_y=FLOOR_Y, contact_dist=2.5, track_bridge=False):
        now = time.time() - self.t0
        bp = self.bot.pos()
        vp = self.victim.pos() if self.victim else None
        rec = {
            "t": round(now, 1),
            "bot": bp, "bot_hp": self.bot.health(),
            "bot_hurt": self.rcon.hurt_time(self.bot.name),
            "victim": vp,
            "victim_hp": self.victim.health() if self.victim else None,
            "victim_hurt": self.rcon.hurt_time(self.victim.name) if self.victim else None,
            "k": self.rcon.score(self.bot.name, "k"),
            "d": self.rcon.score(self.bot.name, "d"),
            "victim_d": (self.rcon.score(self.victim.name, "d")
                         if self.victim else 0),
        }
        if bp and vp:
            rec["dist"] = round(sum((a - b) ** 2 for a, b in zip(bp, vp)) ** 0.5, 2)
            if rec["dist"] < contact_dist and self.first_contact is None:
                self.first_contact = now
                self.log(f"  first contact at {now:.1f}s")
        if track_bridge:
            ok, placed = self.bot.py.try_call("bridgePlaced")
            rec["bridge_placed"] = placed if ok else None
        self._detect(rec, floor_y)
        self.samples.append(rec)
        self.art.sample(rec)
        return rec

    def _detect(self, rec, floor_y):
        bp = rec.get("bot")
        if not bp:
            return
        now = time.time()
        # freeze: no displacement > 0.05 for 6s. Target-aware: a stall only
        # matters when the bot is AWAY from its objective (stuck) — holding still
        # on a caught/paused target (chase) is correct, not a freeze. The RW-1
        # "stands still NEAR the target" case is caught by standstill_windows.
        caught = rec.get("dist") is not None and rec["dist"] < 3
        if self._last_move_pos is None or \
                sum(abs(a - b) for a, b in zip(bp, self._last_move_pos)) > 0.05:
            self._last_move_pos = bp
            self._last_move_t = now
        elif now - self._last_move_t > 6 and not caught:
            self.freeze_windows += 1
            self._last_move_t = now
            self.log(f"  WARNING freeze window #{self.freeze_windows} at {bp}")
        # stand-still near target (RW-1): ~no displacement for 4 consecutive
        # samples while the target is within 4 blocks -> one window (then the
        # counter re-arms, so windows are non-overlapping)
        prev = self.samples[-1] if self.samples else None
        step = sum(abs(a - b) for a, b in zip(prev["bot"], bp)) \
            if prev and prev.get("bot") else 1.0
        if rec.get("dist") is not None and rec["dist"] < 4 and step < 0.075:
            self._still_count = getattr(self, "_still_count", 0) + 1
            if self._still_count >= 4:
                self.standstill_windows += 1
                self._still_count = 0
        else:
            self._still_count = 0
        # fall attribution: dropped below floor - 2
        below = bp[1] < floor_y - 2
        if below and not self._below:
            hurt_recent = any((s.get("bot_hurt") or 0) > 0
                              for s in self.samples[-2:])
            if hurt_recent:
                self.knockback_falls += 1
                self.log("  fall: knockback")
            else:
                self.self_falls += 1
                self.log("  fall: SELF (walked off)")
        self._below = below

    # -- aggregates for judging -------------------------------------------
    def dists(self, since=0.0):
        return [s["dist"] for s in self.samples
                if s.get("dist") is not None and s["t"] >= since]

    def avg_dist(self, since=0.0):
        d = self.dists(since)
        return sum(d) / len(d) if d else None

    def duration(self):
        return self.samples[-1]["t"] if self.samples else 0.0

    def kills(self):
        return max((s["k"] for s in self.samples), default=0)

    def deaths(self):
        return max((s["d"] for s in self.samples), default=0)

    def survival_criterion(self, limit=0):
        """'Our bot must not die' gate — for scenarios where the opponent is NOT
        a symmetric threat (a fleeing runner, a slowed chaser we kite). A run
        that scores one kill while dying four times is a LOSS, and without this
        the suite called it a PASS (user 2026-07-24 — allround: 1 kill, 4
        deaths, reported as PASS)."""
        d = self.deaths()
        return Criterion(f"bot deaths <= {limit}", d <= limit, f"deaths={d}")

    def exchange_criterion(self):
        """For MUTUAL duels (both bots run the same engine): the bar is winning
        the exchange, kills >= deaths. Demanding 0 deaths against an identical
        opponent would measure luck; losing the exchange is a real failure."""
        k, d = self.kills(), self.deaths()
        return Criterion("won the exchange (kills >= deaths)", k >= d,
                         f"kills={k} deaths={d}")

    def victim_damage(self):
        """Damage dealt to the victim, summed across respawns."""
        return sum(a for _, a, _ in self.hp_drop_events())

    def hp_drop_events(self, who="victim", min_dist=None):
        """[(t, amount, dist)] hp-drop events; min_dist filters for ranged
        attribution (a drop while the fighters were far apart = arrow)."""
        key = f"{who}_hp"
        events, prev = [], None
        for s in self.samples:
            hp = s.get(key)
            if hp is None:
                continue
            if prev is not None and hp < prev:
                d = s.get("dist")
                if min_dist is None or (d is not None and d > min_dist):
                    events.append((s["t"], prev - hp, d))
            prev = hp
        return events

    def first_hit(self):
        ev = self.hp_drop_events()
        return ev[0][0] if ev else None

    def arrow_hits(self, min_dist=8):
        """Damage that can only be an arrow: the victim took a hit while far
        away AND stayed inside the arena. Plain hp-drop counting scored a
        victim's fall damage as 12 'arrow hits' out of 6 shots."""
        out = []
        for t, amount, dist in self.hp_drop_events(who="victim",
                                                   min_dist=min_dist):
            sample = min(self.samples, key=lambda s: abs(s["t"] - t))
            vp = sample.get("victim")
            if vp and vp[1] >= FLOOR_Y - 1 and amount <= 12:
                out.append((t, amount, dist))
        return out

    def deaths_of(self, who="victim"):
        """Death count of either actor, read from the scoreboard samples."""
        if who == "bot":
            return self.deaths()
        return max((s.get("victim_d", 0) or 0 for s in self.samples), default=0)

    def victim_left_arena(self, floor_y=FLOOR_Y):
        """Did the victim drop out of the arena? Position-based on purpose: the
        victim dying to our ARROWS is the scenario succeeding, so a death count
        cannot be the signal here."""
        return any(s["victim"][1] < floor_y - 3
                   for s in self.samples if s.get("victim"))

    def max_place_rate(self, window=2):
        """Max bridge blocks placed per second over any `window` samples."""
        vals = [(s["t"], s["bridge_placed"]) for s in self.samples
                if s.get("bridge_placed") is not None]
        best = 0.0
        for i in range(len(vals) - window):
            t0, p0 = vals[i]
            t1, p1 = vals[i + window]
            if t1 > t0:
                best = max(best, (p1 - p0) / (t1 - t0))
        return best

    def chat_errors(self):
        """Verify-with-logs: unknown commands / errors in recent chat."""
        bad = ("unknown command", "command not found", "no such command")
        return [l for l in self.bot.recent_chat(30)
                if any(b in l.lower() for b in bad)]


class Scenario:
    id = "base"
    tier = "gate"              # gate = red blocks the suite; info = recorded only
    duration = 60
    needs_victim = True
    settings = {}              # ;settings pins for the bot
    bot_kit = []
    victim_kit = []
    arena_half = 40
    regen = False
    # Which stand server this scenario runs on: the flat determinism world by
    # default, "gamer" = the REAL world-generator server (uctest-gamer-server,
    # normal terrain, seed 12345) for benches that must not happen on a
    # hand-built strip.
    world = "flat"
    builds_arena = True        # False = play the world as generated (real terrain)

    def build(self, arena, ctx):
        raise NotImplementedError

    def drive_start(self, ctx):
        raise NotImplementedError

    def drive_tick(self, ctx, t):
        pass

    def drive_stop(self, ctx):
        ctx.bot.stop_all()
        if ctx.victim:
            ctx.victim.stop_all()

    def early_stop(self, ctx):
        return False

    def sample_kwargs(self):
        return {}

    def judge(self, ctx):
        raise NotImplementedError

    # -- shared run loop ---------------------------------------------------
    def run(self, ctx):
        self.drive_start(ctx)
        ctx.t0 = time.time()
        shot_taken = False
        while time.time() - ctx.t0 < self.duration:
            time.sleep(1)
            ctx.sample(**self.sample_kwargs())
            self.drive_tick(ctx, time.time() - ctx.t0)
            if not shot_taken and time.time() - ctx.t0 > self.duration / 2:
                ctx.bot.py.screenshot(ctx.art.path("mid_run.png"))
                shot_taken = True
            if self.early_stop(ctx):
                ctx.log("  early stop (objective reached)")
                break
        self.drive_stop(ctx)
        crits = list(self.judge(ctx))
        errs = ctx.chat_errors()
        crits.append(Criterion("no command errors in chat", not errs,
                               "; ".join(errs[:3])))
        return crits


def is_flake(exc_or_crits):
    """Retry-once policy: transport/warm-up failures only, never a clean red."""
    if isinstance(exc_or_crits, Exception):
        text = str(exc_or_crits).lower()
        return any(k in text for k in ("py4j", "timed out", "in game"))
    return False
