"""uctest scenario base — setup -> drive -> sample -> verdict, with the shared
detectors (freeze, stand-still-near-target, self-fall vs knockback-fall) and
the retry-once flake policy (flakiness != regression, CHECKLIST 5.4)."""
import time

from .arena import FLOOR_Y


class Criterion:
    """One check in a verdict.

    ``load_sensitive`` says whether a LOW FRAME RATE could plausibly cause this check to fail — "did
    the bot manage it in time / react fast enough". The starvation guard may only downgrade a run to
    INVALID when EVERY failed gate says yes, so getting this wrong on one gate makes a whole starved
    run read as a bot failure.

    IT USED TO BE DECIDED BY MATCHING THIS NAME AGAINST KEYWORDS IN run_suite.py, and that failed
    closed: word a gate differently and its load-sensitivity silently became False. The list was
    built for nav, extended for craft, extended again for mob -- three patches in one session -- and
    a sweep found whole classes still outside it, especially timed pvp gates like
    "first landed swing <= 15s" and "kill <= 120s". Declaring it HERE means a new gate answers the
    question where it is written, while the author still knows the answer.

    The keyword list survives as a fallback so no existing verdict moves; new gates should set this
    flag instead of relying on their wording.
    """

    def __init__(self, name, ok, detail="", gate=True, load_sensitive=None):
        self.name, self.ok, self.detail, self.gate = name, bool(ok), detail, gate
        self.load_sensitive = load_sensitive

    def as_dict(self):
        return {"name": self.name, "ok": self.ok, "detail": self.detail,
                "gate": self.gate, "load_sensitive": self.load_sensitive}


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
        # Kill/death BASELINE. The scoreboard objectives use the `playerKillCount` /
        # `deathCount` CRITERIA, which Minecraft re-syncs from the player's PERSISTENT
        # statistics: `scoreboard players set ... 0` is undone the next time the stat
        # moves, so a fresh run inherits every kill and death the account ever had. That
        # made `early_stop: kills() >= 1` fire on the very first sample of a clean run and
        # report someone else's kill as ours (observed 2026-07-27: a run "won" 1-1 before
        # the bots had even closed). Everything below is reported as a DELTA from the
        # values seen at scenario start, which is correct regardless of server history.
        self._k0 = None
        self._d0 = None
        self._vd0 = None

    # -- sampling ----------------------------------------------------------
    def sample(self, floor_y=FLOOR_Y, contact_dist=2.5, track_bridge=False):
        now = time.time() - self.t0
        bp = self.bot.pos()
        vp = self.victim.pos() if self.victim else None
        # SWINGS THAT OUR BOT ACTUALLY LANDED, straight from the mod. HP deltas cannot tell
        # who did the damage: this arena is a platform over void, so a fall reads as "damage
        # dealt", and regeneration interleaves with the drops being summed. The counter
        # increments immediately before the attack call and is never reset, so it attributes.
        ok, hits = self.bot.py.try_call("totalHits")
        ok2, crits = self.bot.py.try_call("critHits")
        rec = {
            "t": round(now, 1),
            "bot_hits": hits if ok else None,
            "bot_crits": crits if ok2 else None,
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
        # latch the baseline on the first sample, then report deltas
        if self._k0 is None:
            self._k0, self._d0, self._vd0 = rec["k"], rec["d"], rec["victim_d"]
        rec["k_raw"], rec["d_raw"], rec["victim_d_raw"] = rec["k"], rec["d"], rec["victim_d"]
        rec["k"] = max(0, rec["k"] - self._k0)
        rec["d"] = max(0, rec["d"] - self._d0)
        rec["victim_d"] = max(0, rec["victim_d"] - self._vd0)
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
        # STANDING STILL ON A GOAL YOU HAVE REACHED IS CORRECT, NOT A FREEZE.
        # `caught` only ever fires on courses that have a VICTIM — `dist` is populated
        # from the victim position, so on navigation courses it is always None and the
        # exemption could never apply. With --no-early-stop a nav course then runs to its
        # full timeout after arriving, and the bot waiting at the goal was booked as one
        # freeze window every 6 s. Measured: nav_flat arrived at x=29.3 after 3.4 s and
        # stood there for the remaining 55 s -> "freezes=7" on a course that had just
        # passed clean. That is how a whole suite reported regressions that never existed.
        arrived = self.geo.get("reached_at") is not None
        if self._last_move_pos is None or \
                sum(abs(a - b) for a, b in zip(bp, self._last_move_pos)) > 0.05:
            self._last_move_pos = bp
            self._last_move_t = now
        elif now - self._last_move_t > 6 and not caught and not arrived:
            self.freeze_windows += 1
            self._last_move_t = now
            # WHAT WAS THE BOT DOING WHILE IT STOOD THERE? A position alone cannot tell a
            # "the search found nothing" stall from a "the executor is mid-manoeuvre" one, and
            # those need opposite fixes. execState reports the engines in one string.
            ok, st = self.bot.py.try_call("execState")
            self.log(f"  WARNING freeze window #{self.freeze_windows} at {bp}"
                     + (f" | {st}" if ok else ""))
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

    def landed_swings(self):
        """Swings our bot landed during the run, from the mod's own counter."""
        vals = [s.get("bot_hits") for s in self.samples if s.get("bot_hits") is not None]
        return 0 if len(vals) < 2 else max(0, vals[-1] - vals[0])

    def first_swing_time(self):
        """When the bot first landed a swing, or None. Attributable, unlike an HP dip."""
        base = None
        for s in self.samples:
            h = s.get("bot_hits")
            if h is None:
                continue
            if base is None:
                base = h
            elif h > base:
                return s["t"]
        return None

    def crit_swings(self):
        vals = [s.get("bot_crits") for s in self.samples if s.get("bot_crits") is not None]
        return 0 if len(vals) < 2 else max(0, vals[-1] - vals[0])

    def victim_damage(self):
        """Damage dealt to the victim, summed across respawns."""
        return sum(a for _, a, _ in self.hp_drop_events())

    def hp_drop_events(self, who="victim", min_dist=None):
        """[(t, amount, dist)] hp-drop events; min_dist filters for ranged
        attribution (a drop while the fighters were far apart = arrow)."""
        # ATTRIBUTE OVER THE INTERVAL, NOT THE INSTANT. One sample iteration costs about 7.5 s
        # here (nine blocking rcon round trips), so the distance recorded WITH a drop is up to
        # 7.5 s stale. Measured in allround: t=1.0 dist=25.6 hp=20.0, then t=8.4 dist=2.2 hp=10.0
        # on a flat field with zero landed swings — that 10 HP can only have been the arrow, and
        # testing the drop against dist=2.2 threw the hit away and reported ranged_hits=0. The
        # fighters were far apart for part of that interval, so the far test uses the WIDEST
        # separation the interval saw.
        key = f"{who}_hp"
        events, prev, prev_d = [], None, None
        for s in self.samples:
            hp = s.get(key)
            if hp is None:
                continue
            d = s.get("dist")
            if prev is not None and hp < prev:
                span = [x for x in (d, prev_d) if x is not None]
                widest = max(span) if span else None
                if min_dist is None or (widest is not None and widest > min_dist):
                    events.append((s["t"], prev - hp, widest if widest is not None else d))
            prev = hp
            prev_d = d
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

    # WHAT THE TWO COMMAND SYSTEMS ACTUALLY PRINT. The old list ("unknown command",
    # "command not found", "no such command") matched NOTHING either of them says: altoclef
    # and tungsten both print `Command X does not exist.` / `Invalid command:X`
    # (CommandExecutor.java), brigadier says `Unknown command at position N`, and
    # Debug.logError tags `[ERROR]`. Combined with the chat ring never holding the mod's own
    # lines at all, the criterion below could not fail, and it is on EVERY scenario.
    BAD_CHAT = ("unknown command", "command not found", "no such command",
                "does not exist.", "invalid command", "[error]",
                "unknown or incomplete command", "incorrect argument")

    def chat_lines(self):
        """This scenario's chat. The ring is cleared at scenario start, so read the WHOLE
        thing — pulling the last 30 lines of a verbose 90 s run saw only its final second."""
        return self.bot.recent_chat(2000)

    def chat_errors(self):
        """Verify-with-logs: command errors in this scenario's chat."""
        return [l for l in self.chat_lines()
                if any(b in l.lower() for b in self.BAD_CHAT)]


class Scenario:
    id = "base"
    tier = "gate"              # gate = red blocks the suite; info = recorded only
    duration = 60
    needs_victim = True
    settings = {}              # ;settings pins for the bot
    # PINS FOR THE OPPONENT — the only way a MUTUAL duel can measure anything.
    # melee_basic, narrow_bridge_duel and allround all put this jar against ITSELF with the
    # same kit, so every criterion is symmetric and cancels: over 66 recorded melee_basic runs
    # the mean margin is +0.03 kills, i.e. a dead heat by construction, and the course's green
    # comes from draws counting as wins. No improvement to the bot can fix that, because the
    # improvement lands on the opponent too. Pinning the opponent to the BASELINE engine makes
    # the duel "current versus baseline", which is what a regression suite should be asking.
    victim_settings = {}
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

    # Recording/diagnostic runs need the FULL duration: an objective reached in the
    # first seconds produces a 4-second clip and a sample set too small to judge
    # movement quality from. Set by run_suite's --no-early-stop.
    no_early_stop = False

    def early_stop(self, ctx):
        return False

    def arrived(self, ctx):
        """Has this scenario's OBJECTIVE been reached? Latched by run() into
        ctx.geo['reached_at'], which is what tells the freeze detector "standing on a goal it
        already reached" apart from "stuck". Only NavCourse ever set that key, so a
        goal-navigation scenario living in the pvp suite (slab_hole) booked false freezes.
        Defaults to False, so nothing that does not opt in changes behaviour."""
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
            if ctx.geo.get("reached_at") is None and self.arrived(ctx):
                ctx.geo["reached_at"] = round(time.time() - ctx.t0, 1)
                ctx.log(f"  objective reached at {ctx.geo['reached_at']}s")
            if not shot_taken and time.time() - ctx.t0 > self.duration / 2:
                ctx.bot.py.screenshot(ctx.art.path("mid_run.png"))
                shot_taken = True
            if not Scenario.no_early_stop and self.early_stop(ctx):
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
