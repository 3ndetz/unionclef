#!/usr/bin/env python3
"""uctest suite runner — ONE entrypoint for the stand autotests (RW-5).

  python3 deploy/runner/run_suite.py pvp                  # whole suite
  python3 deploy/runner/run_suite.py pvp --only bow_flee  # one scenario
  python3 deploy/runner/run_suite.py pvp --repeat 3       # flake analysis
  python3 deploy/runner/run_suite.py --list

Requires the stand with BOTH clients:
  docker compose -f deploy/compose.test.yml --profile pvp up -d

Exit code 0 = every selected GATE scenario passed. Artifacts (timeline.jsonl,
chat, screenshots, verdict.json) in deploy/runner/artifacts/<run>/<scenario>/.


ORDER BIAS IS REAL, AND IT IS NOT THE COURSE'S FAULT. The client degrades as a suite runs:
a full pvp sweep on one container set has been seen at 29 fps early and 9.9 fps by the middle,
which is below the 14.0 validity floor. So a course near the END of the list collects INVALIDs
for its POSITION, not its content -- chase_terrain and bow_flee_hard were once written off as
"structurally unmeasurable" on exactly this artefact, and fresh containers then took the same
course from 9.9 to 29.4.

Practical rule: an INVALID late in a sweep says nothing. Re-run that course alone on fresh
containers (deploy_jar.sh recreates them) before drawing any conclusion about it, and never
compare a late course's numbers against an early one's.
"""
import argparse
import datetime
import functools
import os
import re
import subprocess
import tempfile
import shutil

# The fps below which a failure says nothing about the bot. Module level ON PURPOSE: it was a
# local inside the judging function, and the suite loop referenced it -- which would have raised
# NameError on the FIRST invalid run and never on a healthy one, i.e. exactly where it was needed
# and nowhere it would be noticed.
HEALTHY_FPS_MIN = 14.0
# How many times ONE suite invocation will rebuild the stand chasing a starved frame rate before
# concluding the load is somebody else's. Two minutes a rebuild; a repeat-8 series could spend
# half an hour on it and still measure nothing.
MAX_STARVE_REFRESHES = 2
# COMPARABILITY IS RELATIVE; THE FLOOR ABOVE IS ABSOLUTE, AND THE GAP BETWEEN THEM IS A HOLE.
# A course that ran at 29 fps and then runs at 17 is above the floor twice over and is still not
# the same measurement -- half the frames means half the aim corrections, and aim is delivered per
# FRAME (measured on allround). A before/after series is exactly where that lands: the wear arrives
# mid-series, so the two arms get different stands and the difference is read as the build.
#
# So a repeat of the SAME course in the SAME invocation is also compared against the best frame
# rate that course has already shown here. Same course only, because arenas differ -- a 5x5
# platform and a 90 s chase over terrain do not owe each other a frame rate, and comparing across
# them would mark half a healthy sweep.
#
# The ratio has room: run-to-run jitter on a healthy stand measures about 6% (27.9-29.6 over six
# runs), so a quarter of the frame rate is well outside it and still catches 29 -> 20.
#
# EXERCISED, not merely written: the branch was forced by setting this above 1.0 for one
# --repeat 2 run, and all four of its jobs happened -- the run marked, both frame rates printed,
# a rebuild spent from the capped budget, and "[fps drift ... - not comparable]" in the SUMMARY.
# Worth the five minutes: the starvation guard beside it crashed the eight-run series it existed
# to protect the first time it fired, and its successor inspected the first attempt while the
# flake retry replaced the result. Two guards, two holes, both only visible on the path that fires.
FPS_DRIFT_RATIO = 0.75
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
print = functools.partial(print, flush=True)  # noqa: A001 - stand logs stream

# ⛔ NEVER LET A PRINT KILL A RUN.
# The console on the machine this runs on is cp1251. A warning line carrying U+26A0 raised
# UnicodeEncodeError the FIRST time it fired -- which was inside the starved-stand guard, so the
# guard written to protect an eight-run measurement destroyed one instead, at run 5 of 8, and took
# the queued sweep behind it (the chain waited on a SUMMARY that a dead process never printed).
# Hunting individual characters is a patch; a print that cannot raise is the fix. Nearby lines
# survive an em dash only by the accident that cp1251 has one.
try:
    sys.stdout.reconfigure(errors="replace")
    sys.stderr.reconfigure(errors="replace")
except Exception:                                  # very old interpreters: leave it alone
    pass

def _jar_fingerprint():
    """(filename, "sizeB@mtime") of the jar deploy_jar.sh would copy right now, or (None, None).

    Mirrors that script's selection deliberately -- newest unionclef-1.21.11-*.jar in
    versions/1.21.11/build/libs, minus the -all/-sources variants -- because the point is to
    identify the artefact a refresh WOULD deploy, not the one someone meant to deploy. Size and
    mtime rather than a hash: a rebuild moves both even when the bytecode is identical, and "a
    build happened mid-series" is exactly the event worth flagging."""
    import glob
    d = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                     "versions", "1.21.11", "build", "libs")
    cands = [p for p in glob.glob(os.path.join(d, "unionclef-1.21.11-*.jar"))
             if "-all" not in os.path.basename(p) and "-sources" not in os.path.basename(p)]
    if not cands:
        return (None, None)
    p = max(cands, key=os.path.getmtime)
    st = os.stat(p)
    return (os.path.basename(p), f"{st.st_size}B@{int(st.st_mtime)}")


from uctest.actors import Bot                       # noqa: E402
from uctest.arena import ArenaBuilder, STAND_Y      # noqa: E402
from uctest.harness import Artifacts, Rcon, wait_for  # noqa: E402
from uctest.scenario import Ctx, Scenario, is_flake           # noqa: E402
from uctest.scenarios_nav import SCENARIOS as NAV   # noqa: E402
from uctest.scenarios_pvp import SCENARIOS as PVP   # noqa: E402
from uctest.scenarios_mob import SCENARIOS as MOB   # noqa: E402
from uctest.scenarios_craft import SCENARIOS as CRAFT  # noqa: E402
from uctest.scenarios_end import SCENARIOS as END  # noqa: E402

SUITES = {"pvp": PVP, "nav": NAV, "mob": MOB, "craft": CRAFT, "end": END}
BOT_CONTAINER = "uctest-mc-tester1"
VICTIM_CONTAINER = "uctest-mc-tester2"
BOT, VICTIM = "tester1", "tester2"

# Stand worlds. "flat" = the deterministic superflat arena server; "gamer" = the
# REAL world generator (normal terrain, seed 12345) for benches that must run on
# genuine terrain instead of a hand-built strip (user 2026-07-24: "РЕЛЬЕФ — это
# РЕАЛЬНЫЙ ГЕНЕРАТОР МИРА"). Start it with:
#   docker compose -f deploy/compose.test.yml --profile gamer --profile pvp up -d
WORLDS = {
    "flat":  {"container": "uctest-server",       "host": "test-server"},
    "gamer": {"container": "uctest-gamer-server", "host": "gamer-server"},
}

# Visualisation must be ON for every recorded run: the whole point of the clips is
# to SHOW what tungsten plans (paths, goal, break/place plans, combat + arrow
# trajectories). These are shipped defaults, but a persisted tungsten.json had
# them all false on the stand, so the first clip batch showed nothing.
# Tungsten flags pinned for every scenario of this invocation, from --pin k=v.
# A/B EXPERIMENTS GO THROUGH HERE AND NOWHERE ELSE. Setting a flag over py4j before the
# run does nothing: prepare() calls reset_config(), which resets tungsten.json to shipped
# defaults on purpose, so any pre-run write is wiped before the scenario starts. Four A/B
# batches in one session were run that way and silently compared the build against itself.
EXTRA_PINS = {}

VIZ_SETTINGS = {
    "renderVisualization": "true",
    "renderPathMoves": "true",
    "renderCombat": "true",
    "renderBreakPlan": "true",
    "renderPlacePlan": "true",
}

# The same flags, off: what every UNRECORDED run uses. See the note at the pin site.
VIZ_OFF = {k: "false" for k in VIZ_SETTINGS}


def _rec_start(scn_id, dur, persp=0, bot=None):
    """Record tester1's own screen for the scenario window (x11grab on the
    container's :0). First-person + the tungsten combat overlay (Walker/Punk
    state, freeze behaviour) — reliable and diagnostic; the combat aim forces
    first-person anyway, so third-person doesn't hold during a fight. Returns
    the in-container mp4 path."""
    mp4 = f"/mc-data/rec_{scn_id}.mp4"
    # USE THE BOT'S OWN CLIENT. This called an un-imported `Py4jClient`, so every --record run
    # raised NameError straight into the bare `except` below and filmed in whatever perspective
    # the previous scenario happened to leave behind.
    if bot is not None:
        ok, res = bot.py.try_call("setPerspective", persp)
        if not ok:
            print(f"  WARN setPerspective({persp}) failed: {res}", flush=True)
    subprocess.run(["docker", "exec", BOT_CONTAINER, "sh", "-c",
                    "pkill -INT ffmpeg 2>/dev/null; sleep 0.3; true"],
                   capture_output=True)
    # fragmented mp4: stays valid even when ffmpeg is stopped mid-write (a plain
    # mp4 writes its moov index only on clean exit -> a killed capture is
    # unplayable "moov atom not found").
    subprocess.Popen(["docker", "exec", "-d", BOT_CONTAINER, "ffmpeg", "-y",
                      "-f", "x11grab", "-framerate", "15", "-i", ":0",
                      "-t", str(dur + 8),
                      # x264 + bitrate cap: a raw yuv420p grab of a 90-120s
                      # scenario hit 60 MB (> Telegram's 50 MB sendVideo limit,
                      # 413). ultrafast keeps CPU off the software renderer.
                      "-c:v", "libx264", "-preset", "ultrafast",
                      # ONE KEYFRAME PER SECOND. The container writes a FRAGMENTED mp4 and a
                      # fragment is only flushed on a keyframe; x264's default interval is
                      # 250 frames, i.e. ~17 s at 15 fps. Courses now finish in 6 s, so the
                      # capture was cut before the first fragment ever landed and every clip
                      # came out as a 28-byte empty-moov header. The bot got faster than the
                      # recorder — a good problem, but it silently destroyed the evidence.
                      "-g", "15",
                      "-b:v", "1100k", "-maxrate", "1400k", "-bufsize", "2M",
                      "-pix_fmt", "yuv420p",
                      "-movflags", "+frag_keyframe+empty_moov+default_base_moof",
                      mp4])
    time.sleep(1.0)
    return mp4


def _rec_stop(scn_id, art):
    """Stop ffmpeg, copy the mp4 into the artifact dir. Returns host path."""
    subprocess.run(["docker", "exec", BOT_CONTAINER, "pkill", "-INT", "ffmpeg"],
                   capture_output=True)
    time.sleep(3.5)
    dst = art.path(f"{scn_id}.mp4")
    subprocess.run(["docker", "cp", f"{BOT_CONTAINER}:/mc-data/rec_{scn_id}.mp4",
                    dst], capture_output=True)
    return dst if os.path.exists(dst) and os.path.getsize(dst) > 1000 else None


def run_scenario(cls, rcons, bot, victim, art_root, record=False):
    scn = cls()
    world = WORLDS[scn.world]
    rcon = rcons[scn.world]
    bot.rcon = rcon
    victim.rcon = rcon
    art = Artifacts(art_root, scn.id)
    ctx = Ctx(bot, victim if scn.needs_victim else None, rcon, art)
    print(f"\n--- {scn.id} ({scn.tier}, {scn.duration}s, world={scn.world}) ---")
    try:
        wait_for(f"{world['container']} rcon", lambda: "players" in rcon.cmd("list"),
                 300, 5)
        bot.ensure_in_game(world["host"], rcon=rcon)
        if scn.needs_victim:
            victim.ensure_in_game(world["host"], rcon=rcon)
        # clean config every run (persist poisoning), then force visualisation ON
        # so the recording SHOWS the planned path / trajectories, then the
        # scenario's own pins.
        bot.reset_config()
        victim.reset_config()
        # DRAWING FOR NOBODY IS COST WITHOUT BENEFIT.
        # Visualisation belongs ON for a recording -- that is what the clip is for -- and it is a
        # pure cost for every other run, so unrecorded runs turn it off.
        # WHAT IS AND IS NOT MEASURED: one A/B on this stand read 12 fps with the overlays on and
        # 16 with them off, which looked like the lever the perf notes promise. Two later runs WITH
        # the pins in place read 10. So the effect is NOT established -- that first pair was the
        # machine moving under me, and the number must not be quoted as fact. The pins stay on the
        # principle that drawing for nobody is cost without benefit, not on a measurement.
        bot.pin_settings(VIZ_SETTINGS if record else VIZ_OFF)
        if EXTRA_PINS:
            # SET THROUGH py4j, NOT CHAT. pin_settings() sends ";settings k v" as a chat
            # message, which the client runs on a later tick — at this stand's ~10 fps the
            # 0.3 s it waits is not enough, and a read-back straight after showed the OLD
            # value while the command landed somewhere inside the scenario. tungstenSetting()
            # applies and RETURNS the resulting value in the same call.
            for k, v in EXTRA_PINS.items():
                ok, got = bot.py.try_call("tungstenSetting", k, v)
                print(f"  PIN {got if ok else 'UNSET ' + k + ' (' + str(got) + ')'}",
                      flush=True)
                # Compare loosely on numbers: a double field reads back "12.0" for a pin of
                # "12", and an exact string test would abort every numeric A/B.
                def _same(a, b):
                    if a == b:
                        return True
                    try:
                        return float(a) == float(b)
                    except (TypeError, ValueError):
                        return False
                # Check the NAME as well as the value. findSettingField falls back to a substring
                # match, so "combatWindMouseWindDist" resolves to "combatWindMouseWind" — and a
                # guard that compares only the value would confirm a pin that hit the wrong field.
                # tungstenSetting answers with the field it actually resolved, so compare both.
                name, _, read = str(got).partition("=")
                if not ok or name != k or not _same(read, v):
                    raise SystemExit(
                        f"--pin {k}={v} did not apply (got {got!r}). Refusing to run: an "
                        f"A/B against an unapplied flag measures the build against itself.")
        arena = ArenaBuilder(rcon)
        if scn.builds_arena:
            arena.prepare(half=scn.arena_half, regen=scn.regen)
        scn.build(arena, ctx)
        # respawns must land in the arena, not at the world default (y=101):
        # a bot knocked/killed mid-fight otherwise wanders off at world spawn.
        if scn.builds_arena:
            sp = ctx.geo["bot_spawn"].split()
            arena.set_spawn(float(sp[0]), float(sp[1]), float(sp[2]))
        hard = scn.builds_arena          # never kill-reset on the real world
        bot.fresh_reset(ctx.geo["bot_spawn"], scn.bot_kit, hard)
        if scn.needs_victim:
            victim.fresh_reset(ctx.geo["victim_spawn"], scn.victim_kit, hard)
        rcon.reset_kd([BOT, VICTIM])
        if scn.settings:
            bot.pin_settings(scn.settings)
        if scn.victim_settings and scn.needs_victim:
            # Applied over py4j, verified, for the same reason --pin is: a chat ";settings"
            # lands a tick or more later and a duel that started against the wrong engine is
            # not a measurement.
            for k, v in scn.victim_settings.items():
                ok, got = victim.py.try_call("tungstenSetting", k, str(v))
                print(f"  VICTIM PIN {got if ok else 'UNSET ' + k}", flush=True)
                if not ok or str(got) != f"{k}={v}":
                    raise SystemExit(
                        f"victim pin {k}={v} did not apply (got {got!r}) — a mutual duel with "
                        f"the opponent on the wrong engine measures nothing.")
        # ZERO THE CHAT RING HERE, not earlier: everything above (config reset, pins) logs into
        # it and belongs to the setup, not to the measurement.
        # A COUNTER IS ONLY A MEASUREMENT IF YOU KNOW ITS ZERO. Without this every number in
        # placeStats/execState is a container-lifetime sum printed as if it described this run.
        for b in ((bot, victim) if scn.needs_victim else (bot,)):
            b.py.try_call("resetRunCounters")
        bot.clear_chat()
        mp4 = _rec_start(scn.id, scn.duration, bot=bot) if record else None
        crits = scn.run(ctx)
        if record:
            clip = _rec_stop(scn.id, art)
            ctx.geo["clip"] = clip
            print(f"  clip: {clip}")
    except Exception as e:  # noqa: BLE001 - report, classify, maybe retry
        art.write_json("verdict.json", {"error": str(e)})
        art.close()
        return {"id": scn.id, "tier": scn.tier, "passed": False,
                "error": str(e), "flake_suspect": is_flake(e), "criteria": []}
    finally:
        for b in (bot, victim):
            try:
                b.stop_all()
            except Exception:  # noqa: BLE001 - teardown must not mask results
                pass

    passed = all(c.ok for c in crits if c.gate)

    # A STARVED HOST IS NOT A BOT FAILURE — AND NOT A PASS EITHER.
    # This stand runs the server, the client and (sometimes) a build on one machine.
    # HONEST NOTE ON WHY THIS EXISTS: it was added after a suite reported seven freeze
    # failures on courses that had just passed clean, while the host WAS loaded (a gradle
    # daemon beside the stand, ~10 fps against a healthy ~15). That diagnosis was WRONG.
    # The real cause was the freeze detector booking a bot standing on a REACHED goal as
    # frozen — see scenario.py, where it is fixed. This guard is kept because starving a
    # one-machine stand is a genuine hazard, but it is a safety net, NOT the explanation
    # for that incident, and it must not be cited as one.
    # WHICH CRITERIA A STARVED MACHINE CAN INVALIDATE.
    # "Not reaching the goal stays a failure" was written to stop this excuse being used to hide
    # real breakage, and that intent is kept: a starved run is INVALID, never PASS, so it has to be
    # run again rather than counted.
    # But arrival is measured against a wall-clock course timeout, so at 40% of the normal frame
    # budget it is exactly as load-sensitive as the freeze count. Measured today: nav_wall2 and
    # nav_bridge failed "reached goal" at avg_fps 9.9 and 10.0 with sixteen and seventeen freezes,
    # in ISOLATION, on a build whose previous audit was 12/12 -- and `docker stats` named the
    # reason: two containers belonging to another project were taking 225% and 171% of the CPU.
    # Calling that a code regression would have sent the next pass bisecting the machine.
    # THE KEYWORDS WERE ALL NAV'S, SO THE GUARD STILL COULD NOT FIRE ON THE CRAFT LADDER.
    # Giving the craft courses an fps (earlier today) was only half the repair: this whitelist
    # decides WHICH failed gates a low frame rate is allowed to excuse, and every entry named a nav
    # criterion. Craft gates read "the bot holds an iron pickaxe" or "four logs in the pack", match
    # nothing here, and so a starved craft run was still recorded as a bot failure -- measured:
    # craft_iron_pickaxe FAIL at avg_fps 9.88 with the smelt half already done (ingots=3), while
    # another project held ~470% of this box.
    # Every craft gate is "did the bot finish this within the window", which is exactly the kind of
    # claim a slow client invalidates, so the rung phrasings belong here beside nav's.
    # AND THE MOB PHRASINGS, FOR THE THIRD TIME THIS LIST HAS BEEN HALF-BUILT.
    # It was nav-only, which meant a starved CRAFT run read as a bot failure; craft rungs were added.
    # The mob suite has exactly the same hole: "the skeleton is dead", "at most one arrow landed" and
    # "the bot took ZERO damage" are all "did the bot manage it in time / react fast enough", which a
    # slow client genuinely invalidates -- and none of them matched anything here.
    # DELIBERATELY NOT ADDED: "ran on tungsten". mdTung=0 means the combat path never engaged, and no
    # frame rate excuses an engine that did not run. That one must stay a real failure.
    LOAD_SENSITIVE = ("freeze", "stand-still", "standstill", "reached goal",
                      "holds", "in the pack", "logs", "ground while", "went looking",
                      "is dead", "arrow landed", "ZERO damage",
                      # "reached striking distance" WAS EXCLUDED ON A MISREADING OF MY OWN.
                      # It used to be called "the fight ran on tungsten", and I kept it out on the
                      # grounds that mdTung=0 meant the engine never ran, which no frame rate
                      # excuses. mdTung counts the tungsten DUELLING CONTROLLER, and MobDefenseChain
                      # hands it the legs only AT striking distance -- the approach belongs to the
                      # task. So mdTung=0 means "never closed", and closing on a skeleton that backs
                      # away is exactly what a slow client prevents.
                      # The cost of the mistake, measured: mob_skeleton runs at 9.9, 12.4 and 8.7 fps
                      # were all recorded as genuine bot failures, because this gate always fails and
                      # one non-excusable failure blocks INVALID for the whole run.
                      "striking distance")
    # WHY 14 AND NOT 12. The old floor admitted a band in which the bot provably cannot perform.
    # Measured tonight on a machine with 24 cores and only ~460% of them in use elsewhere: the
    # client tops out at 12 fps whatever I do -- one client instead of two, cores pinned with
    # cpuset, render distance halved -- and at 12 fps three separate runs reached NO rung in four
    # to five minutes. At 15-18 fps, the same build reaches wood in 21 to 43 seconds. nav's own
    # notes say the same from the other side: nav_slime lands on its pad above ~13 fps and misses
    # below ~10. A floor of 12 therefore called a degraded run a bot failure, which is the false
    # red this guard exists to prevent.
    avg_fps = ctx.geo.get("avg_fps")
    invalid = False
    if not passed and avg_fps is not None and avg_fps < HEALTHY_FPS_MIN:
        failed_gates = [c for c in crits if c.gate and not c.ok]
        # THE FLAG FIRST, THE KEYWORDS ONLY AS A FALLBACK.
        # Criterion.load_sensitive lets a gate declare this where it is written; the substring list
        # below stays for every gate that has not been migrated, so no existing verdict moves. See
        # the note on Criterion for why matching names was the wrong shape.
        def _load_sensitive(c):
            if c.load_sensitive is not None:
                return c.load_sensitive
            return any(k in c.name for k in LOAD_SENSITIVE)

        if failed_gates and all(_load_sensitive(c) for c in failed_gates):
            invalid = True

    # ⛔⛔ FIRST, AND MOST IMPORTANT: WAS THE BOT EVEN IN THE ARENA? (checklist rule 4k)
    #
    # This is the check whose absence produced the worst failure in this project's log. The bot
    # was falling into the void and dying, and EVERY counter kept reporting: PASS/FAIL, dmgTaken,
    # min_hp and dw all described a bot 170 blocks below the world it was supposed to fight in.
    # Two clips went to the operator as evidence of combat behaviour and showed a bot dropping
    # into nothing. The operator caught it from the VIDEO; the numbers never said a word.
    #
    #     healthy runs   minY = -60.0  (STAND_Y, the floor)
    #     broken runs    minY = -234.2, -229.2, -246.5, -180.5
    #
    # A fall also CASCADES: later runs opened with the bot ALREADY at -234 (min == max, never
    # near the floor), because ensure_grounded did not recover it -- so the whole rest of the
    # series measured a corpse. Marking this INVALID is what stops one fall poisoning an arm.
    #
    # The threshold is deliberately far below any legitimate course: nav descends and mines, but
    # nothing in the suite belongs 20+ blocks under the stand floor.
    if not invalid:
        ys = [sm["bot"][1] for sm in ctx.samples
              if sm.get("bot") and len(sm["bot"]) > 1]
        if ys and min(ys) < STAND_Y - 20:
            invalid = True
            print(f"  => {scn.id}: INVALID — the bot LEFT THE ARENA: min Y {min(ys):.1f} against a "
                  f"floor at {STAND_Y}. This run measured a fall, not the course, and every other "
                  f"number it produced is void. A fall cascades -- ensure_grounded does not always "
                  f"recover it -- so recreate the clients (deploy/deploy_jar.sh) before trusting "
                  f"anything that follows. Checklist rule 4k.")

    # ⛔ SECOND SOURCE OF INVALIDITY: THE STAND ITSELF ROTTED MID-SERIES.
    #
    # The fps floor catches a starved machine. It does not catch an EMPTIED arena, and that
    # happens: measured 2026-08-12 on a freshly recreated stand, a four-run mob_skeleton series
    # read healthy for two runs (dw gap 2.65, then 1.83-5.15) and then broke (gap 50.9-198.5 with
    # 4 deaths, gap 117-199 with 2). A separate occasion left tester2 GONE from the world entirely
    # -- `data get entity tester2 playerGameType` returned "No entity was found" -- and four runs
    # afterwards read dodgeDrive=0 and looked exactly like a flat code regression.
    #
    # DamageWatch's gap is the distance to the NEAREST LIVING ENTITY at the moment a hit lands. On
    # a 14-block arena that cannot legitimately exceed ~30. A reading near 200 means there is
    # nothing near the bot: the arena has emptied, or the bot has left it. Either way the run
    # measured the STAND, not the build -- which is precisely what INVALID exists to say.
    #
    # This is deliberately generous (50) so it fires only on the unmistakable case. A silent rot
    # is what makes a long A/B arm unreadable; a loud one costs a re-run.
    if not invalid:
        for c in crits:
            m = re.search(r"dw=\d+/[\d.]+/[\d.]+/([\d.]+)/", str(c.detail or ""))
            if m and float(m.group(1)) > 50.0:
                invalid = True
                print(f"  => {scn.id}: INVALID — stand sanity: DamageWatch reports a hit at "
                      f"{float(m.group(1)):.1f} blocks from the nearest living entity. The arena "
                      f"is ~14 blocks, so it has emptied or the bot has left it. This run measured "
                      f"the STAND, not the build. Recreate the clients (deploy/deploy_jar.sh) and "
                      f"re-run. See task #83: the stand degrades after about two runs, which makes "
                      f"long A/B arms part-measured on a rotting bench.")
                break

    if not passed and not invalid:
        bot.py.screenshot(art.path("fail.png"))
    art.write_text("chat.txt", "\n".join(bot.recent_chat(40)))
    verdict = {"id": scn.id, "tier": scn.tier, "passed": passed,
               "invalid": invalid, "avg_fps": avg_fps,
               "clip": ctx.geo.get("clip"),
               "criteria": [c.as_dict() for c in crits]}
    art.write_json("verdict.json", verdict)
    art.close()
    for c in crits:
        mark = "PASS" if c.ok else ("FAIL" if c.gate else "flag")
        print(f"  [{mark}] {c.name}  {c.detail}")
    if invalid:
        # THIS USED TO SAY "host starved ... Close whatever else is running", WHICH IT NEVER
        # MEASURED. The guard looks at fps and nothing else. Measured on 2026-08-08 while that line
        # was printing: 24 cores, ~53% in use and ~47% IDLE, with the client alone taking 383% to
        # produce 5 fps -- a single-threaded render ceiling, not contention. Naming foreign load as
        # the cause sent a whole session through `docker stats` hunting other people's containers
        # instead of reading the justification twelve lines above this message.
        # The FLOOR is not the problem and does not move: below ~12 fps three runs reached no rung
        # in five minutes, while 15-18 fps reached wood in 21-43 seconds. Only the attribution was
        # invented -- and the nav half is worth saying too, because the whole nav suite passes at
        # ~10 fps and a reader who takes "the bot cannot perform" literally will misread a good run.
        print(f"  => {scn.id}: INVALID — client at {avg_fps:.1f} fps, below the "
              f"{HEALTHY_FPS_MIN} floor. Only load-sensitive checks failed, so this run cannot "
              f"say whether the bot is broken. The floor is measured: below ~12 fps the craft "
              f"rungs are unreachable, while nav courses still pass at ~10. The cause may be "
              f"foreign load OR this course's own weight under software rendering — check both "
              f"before blaming either. RE-RUN.")
    else:
        print(f"  => {scn.id}: {'PASS' if passed else 'FAIL'}")
    # A GATE FAILURE IS A CLAIM, AND ONE RUN CANNOT SUPPORT IT.
    # Measured across a day: nav_gaps failed twice in wide runs (final_dist=26.5, a self-fall,
    # eleven freezes, at avg_fps=16.3 -- the host was NOT starved) and passed 3 out of 3 in
    # isolation on the same build, 8.4 to 12.1 seconds each. nav_slime does the same. So a wide-run
    # verdict depends on WHERE in the suite the course ran, which cost this session two false
    # regression alarms and hid one real regression inside the noise.
    # Re-running the course immediately does not clear whatever the earlier courses left behind,
    # but it does separate the two cases that matter: a failure that reproduces at once is REAL,
    # and one that does not is the suite talking about itself. The retry hook was already here and
    # hardcoded to False, so nothing ever used it.
    verdict["flake_suspect"] = (not passed) and (not invalid) and scn.tier == "gate"
    return verdict


# ONE SUITE AT A TIME, ENFORCED BY THE RUNNER ITSELF.
#
# Two suites shared the containers tonight because a shell guard --
# `while pgrep -f "run_suite.py pvp"; do sleep` -- never matched under Git Bash on Windows and fell
# through instantly. The runs that followed competed for the same two clients and their numbers had
# to be thrown away. A guard outside the program cannot know the program is running; a lock the
# program takes itself can.
#
# Stale locks are handled by age rather than by trusting a pid: a suite that has not touched its
# lock in twenty minutes is gone, whatever the process table says.
_LOCK = os.path.join(tempfile.gettempdir(), "uctest_suite.lock")


def _take_lock():
    try:
        if os.path.exists(_LOCK) and (time.time() - os.path.getmtime(_LOCK)) < 1200:
            with open(_LOCK, encoding="utf-8") as fh:
                who = fh.read().strip()
            print(f"REFUSING TO START: another suite holds the bench ({who}).")
            print("Two suites on one pair of containers produce numbers that cannot be trusted.")
            return False
        with open(_LOCK, "w", encoding="utf-8") as fh:
            fh.write(" ".join(sys.argv[1:]) or "suite")
        return True
    except OSError as exc:                     # a lock we cannot take is not a reason to corrupt data
        print(f"REFUSING TO START: cannot manage the bench lock ({exc}).")
        return False


def _release_lock():
    try:
        os.remove(_LOCK)
    except OSError:
        pass


def main():
    if not _take_lock():
        return 2
    try:
        return _main()
    finally:
        _release_lock()


def _main():
    ap = argparse.ArgumentParser()
    ap.add_argument("suite", nargs="?", help="suite name (pvp)")
    ap.add_argument("--only", help="scenario id, or a comma-separated list of them")
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--record", action="store_true",
                    help="record tester1's screen per scenario (x11grab)")
    ap.add_argument("--no-early-stop", action="store_true",
                    help="always run the full duration (needed for usable clips "
                         "and for movement stats with enough samples)")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--pin", action="append", default=[], metavar="NAME=VALUE",
                    help="pin a tungsten setting for every scenario (applied AFTER the "
                         "config reset, then read back). Use this for A/B runs: "
                         "--pin chaseUsesQueue=true")
    args = ap.parse_args()
    global EXTRA_PINS
    for spec in args.pin:
        if "=" not in spec:
            ap.error(f"--pin expects NAME=VALUE, got {spec!r}")
        k, v = spec.split("=", 1)
        EXTRA_PINS[k.strip()] = v.strip()
    Scenario.no_early_stop = args.no_early_stop

    if args.list or not args.suite:
        for name, scns in SUITES.items():
            print(f"suite {name}:")
            for c in scns:
                doc = ((c.__doc__ or "").strip().splitlines() or [""])[0]
                print(f"  {c.id:28s} [{c.tier}] {doc}")
        return 0
    scenarios = SUITES[args.suite]
    if args.only:
        # A LIST, not one id: a targeted pass is almost always "the course I fixed plus the
        # baselines it could have broken", and running those as N separate invocations restarts
        # the whole stand N times. Name the misses individually — reporting only "no scenario
        # '<the entire list>'" hides which element was the typo.
        wanted = [s.strip() for s in args.only.split(",") if s.strip()]
        known = {c.id for c in scenarios}
        missing = [w for w in wanted if w not in known]
        if missing:
            print(f"no scenario {', '.join(repr(m) for m in missing)} in suite {args.suite}")
            print(f"  available: {', '.join(sorted(known))}")
            return 2
        order = {w: i for i, w in enumerate(wanted)}
        scenarios = sorted((c for c in scenarios if c.id in order), key=lambda c: order[c.id])

    stamp = datetime.datetime.utcnow().strftime("%Y%m%d-%H%M%S")
    art_root = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "artifacts", stamp)
    os.makedirs(art_root, exist_ok=True)
    print(f"artifacts: {art_root}")

    # WHICH JAR IS THIS SERIES MEASURING? Recorded at the start, checked before every run.
    # A client refresh deploys whatever is in build/libs at the moment it fires, and the
    # starvation and drift guards make refreshes common and mid-series. Until now the only
    # protection was an operator rule in a comment ("do not build while a series is running"),
    # which is not a measurement. This is: if the build output moves under the suite, every run
    # from that point carries `jar_changed` and says so in the SUMMARY.
    jar0 = _jar_fingerprint()
    print(f"jar: {jar0[0]} {jar0[1]}" if jar0[0] else "jar: NOT FOUND in build/libs")

    rcons = {name: Rcon(w["container"]) for name, w in WORLDS.items()}
    flat = rcons["flat"]
    wait_for("server rcon", lambda: "players" in flat.cmd("list"), 300, 5)
    bot = Bot(BOT_CONTAINER, BOT, flat)
    victim = Bot(VICTIM_CONTAINER, VICTIM, flat)

    # A DEGRADED CLIENT IS A REPAIRABLE CONDITION, NOT A VERDICT. The client slows as a suite
    # runs -- 29 fps early, under 10 by the middle of a pvp sweep -- so a course late in the list
    # collects INVALIDs for its POSITION. Recording that and moving on is what made two courses
    # look "structurally unmeasurable" until fresh containers took one of them from 9.9 to 29.4.
    # Recreating the containers costs a couple of minutes and buys a real measurement, which is
    # the whole point of running the suite at all.
    #
    # VALIDATED ON LIVE DATA, AND IN BOTH DIRECTIONS (full pvp sweep, 2026-08-10). Two courses
    # tripped the floor and were re-measured on fresh clients: chase_terrain 9.0 fps -> PASS, and
    # bow_flee_hard 10.0 fps -> FAIL. That second one is the important half. A repair path that
    # only ever turns INVALID into green is indistinguishable from laundering, and would be worth
    # less than no repair at all; this one cleared a course and condemned a course in the same
    # sweep, which is what says it is measuring rather than flattering.
    state = {"rcons": rcons, "bot": bot, "victim": victim}

    def refresh_clients(why):
        # ⛔ THIS REDEPLOYS WHATEVER IS CURRENTLY BUILT, NOT WHAT THE SERIES STARTED WITH.
        # deploy_jar.sh copies the current build output. Build anything while a series is running
        # and the next automatic refresh swaps the jar under the measurement, mid-series, with
        # nothing in the log to say the runs before and after came from different code.
        #
        # It was nearly harmless while refreshes only fired on INVALID -- rare, and wherever they
        # landed. The starvation guard added 2026-08-10 makes them COMMON and mid-series, which is
        # the third time that guard has taken a rarely-exercised path and put it on the hot one.
        #
        # HALF-FIXED 2026-08-10: the suite now fingerprints the jar deploy_jar.sh would copy, at
        # start and before every run, and marks every run after a change with `jar_changed` in the
        # log and in the SUMMARY. So a swap can no longer happen silently -- but it is still
        # DETECTION, not prevention: this function will happily deploy the new artefact. Keeping
        # the old one and redeploying THAT would mean the suite holding its own copy, which is a
        # bigger change than the problem currently justifies.
        #
        # So the operator rule stands and is now enforced by a witness rather than by memory:
        # DO NOT BUILD while a series is running. Prepare edits in the tree; build after the last run.
        print(f"  refreshing clients: {why}")
        script = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                              "deploy_jar.sh")
        # RESOLVE bash, do not assume it. The first version called ["bash", script] and got
        # exit 127 -- command not found -- because this runs under Windows Python where bash is
        # not on PATH. The guard reported that honestly and carried on, which is exactly why the
        # INVALID it existed to repair still stands in that run's log: a recovery path that is
        # never exercised until it is needed is a recovery path nobody has tested.
        sh = shutil.which("bash") or shutil.which("bash.exe")
        if not sh:
            for cand in (os.path.join("C:", os.sep, "Program Files", "Git", "bin", "bash.exe"),
                         os.path.join("C:", os.sep, "Program Files", "Git", "usr", "bin", "bash.exe")):
                if os.path.exists(cand):
                    sh = cand
                    break
        if not sh:
            print("  refresh SKIPPED: no bash found to run deploy_jar.sh")
            return False
        rc = subprocess.call([sh, script],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if rc != 0:
            print(f"  refresh FAILED (deploy_jar.sh exit {rc}) -- the next result stays suspect")
            return False
        fresh = {name: Rcon(w["container"]) for name, w in WORLDS.items()}
        wait_for("server rcon", lambda: "players" in fresh["flat"].cmd("list"), 300, 5)
        state["rcons"] = fresh
        state["bot"] = Bot(BOT_CONTAINER, BOT, fresh["flat"])
        state["victim"] = Bot(VICTIM_CONTAINER, VICTIM, fresh["flat"])
        return True

    # Mutable cell: refresh_clients is a closure defined above, and the loop below needs a
    # counter both can see without threading state through every call.
    starve_refreshes = [0]
    # Best frame rate each course has reached in THIS invocation — the reference for the drift
    # check below. Per invocation rather than per session: a stand rebuild resets the wear, and a
    # number from an hour ago is not a baseline for this series.
    course_best_fps = {}
    results = []
    for cls in scenarios:
        for rep in range(args.repeat):
            # Checked BEFORE the run, not after a refresh, because a build can land at any point
            # and the next refresh is only the moment it reaches the clients.
            jar_now = _jar_fingerprint()
            if jar_now != jar0:
                print(f"  [!] the build output changed under this series: {jar0} -> {jar_now}. "
                      f"Runs from here are NOT the same code as the ones before it")
            res = run_scenario(cls, state["rcons"], state["bot"], state["victim"],
                               art_root, args.record)
            if jar_now != jar0:
                res["jar_changed"] = f"{jar0[0]} -> {jar_now[0]}"
            # Retry an fps-invalidated run ONCE on fresh clients. If it comes back invalid again,
            # the load is not ours to fix and the INVALID stands honestly.
            if res.get("invalid") and not res.get("refreshed"):
                if refresh_clients(f"{cls.id} ran at {res.get('avg_fps')} fps, "
                                   f"below the {HEALTHY_FPS_MIN} floor"):
                    res = run_scenario(cls, state["rcons"], state["bot"], state["victim"],
                                       art_root, args.record)
                    res["refreshed"] = True
                    if not res.get("invalid"):
                        print(f"  => {cls.id}: measured on fresh clients — the INVALID was the "
                              f"suite's wear, not the course")
            # ⛔ A RUN THAT PASSES AT 10 FPS IS STILL A RUINED MEASUREMENT.
            # The retry above only fires on INVALID, and INVALID needs a load-sensitive criterion
            # to FAIL. So a course that happens to pass while starved sails through, the stand is
            # never refreshed, and every later run in the series inherits the degradation.
            #
            # That is not hypothetical. A --repeat 8 series of narrow_bridge_duel ran all five of
            # its completed runs at exactly 10.0 fps against a baseline taken at 29.3 and 18.1,
            # and nothing flagged it: the course kept passing, so no INVALID, so no refresh. Five
            # runs of a before/after comparison were spent comparing two different stands.
            #
            # The verdict stands -- it passed, and that is real -- but it is MARKED, and the
            # clients are replaced before the next run so the rest of the series is not lost too.
            fps_now = res.get("avg_fps")
            if fps_now is not None and fps_now < HEALTHY_FPS_MIN:
                res["starved"] = True
                # ASCII ONLY IN PRINTED STRINGS. The first version of this line carried a U+26A0
                # warning sign, which cp1251 -- the console encoding on the machine this runs on --
                # cannot encode. The guard therefore raised UnicodeEncodeError the first time it
                # ever fired and killed the eight-run series it existed to protect. Nearby lines
                # survive an em dash only because cp1251 happens to have one.
                print(f"  [!] {cls.id} passed at {fps_now} fps, below the {HEALTHY_FPS_MIN} floor - "
                      f"this run is NOT comparable against a healthy baseline")
                # CAP THE REPAIRS. Rebuilding the containers costs about two minutes, and
                # `refreshed` is only ever set on the INVALID path -- so a --repeat 8 series that
                # starves on every run would rebuild the stand before every one of them. The
                # reasoning is the one the INVALID retry already uses: if replacing the clients
                # twice did not bring the frame rate back, the load is not this suite's, and
                # thrashing the containers buys nothing but wall-clock. Past the cap the runs are
                # still MARKED, so the measurement stays honest; it just stops paying for a repair
                # that has already been shown not to work.
                if starve_refreshes[0] < MAX_STARVE_REFRESHES and not res.get("refreshed"):
                    starve_refreshes[0] += 1
                    refresh_clients(f"{cls.id} starved at {fps_now} fps "
                                    f"(rebuild {starve_refreshes[0]}/{MAX_STARVE_REFRESHES})")
                elif starve_refreshes[0] >= MAX_STARVE_REFRESHES:
                    print(f"  [!] not rebuilding again - {MAX_STARVE_REFRESHES} rebuilds already "
                          f"failed to restore the frame rate; runs stay marked instead")
            if not res["passed"] and res.get("flake_suspect") and args.repeat == 1:
                first = [c["name"] for c in res["criteria"] if not c["ok"] and c["gate"]]
                print(f"  gate failure ({', '.join(first)}) — running it once more before believing it")
                time.sleep(10)
                again = run_scenario(cls, rcons, bot, victim, art_root, args.record)
                again["retried"] = True
                again["first_attempt_failed"] = first
                if again["passed"]:
                    print(f"  => {cls.id}: PASSED on the retry — the first verdict was the suite, "
                          f"not the course")
                res = again
            # ⛔ CHECK THE RUN THAT IS ACTUALLY RECORDED, NOT ONLY THE FIRST ATTEMPT.
            # The starvation check above inspects the first run and then the flake retry REPLACES
            # res, so the retry's own frame rate was never examined -- and the retry is precisely
            # where a "passed on the second go" verdict comes from. Caught live: narrow_bridge_duel
            # failed 11:15 at 28.8 fps, retried at 9.9 fps, came back 17:11, and the sweep recorded
            # an unmarked PASS. Worse on this course than it sounds, because starved runs there
            # flatter the bot systematically -- a whole discarded series at 10.0 fps read as the
            # best result of the session.
            fps_final = res.get("avg_fps")
            if fps_final is not None and fps_final < HEALTHY_FPS_MIN and not res.get("starved"):
                res["starved"] = True
                print(f"  [!] {cls.id} recorded a verdict at {fps_final} fps, below the "
                      f"{HEALTHY_FPS_MIN} floor - NOT comparable against a healthy baseline")
            # ⛔ AND THE FLOOR IS NOT THE WHOLE OF COMPARABILITY (see FPS_DRIFT_RATIO).
            # Everything above only fires below 14 fps. A series that opens at 29 and finishes at
            # 17 never trips it, and 17 against 29 is not the same stand. Compare each repeat with
            # the best this course has shown in this invocation, and treat a big drop the way a
            # starved run is treated: mark it, and spend a rebuild from the same capped budget.
            ref = course_best_fps.get(cls.id)
            if (fps_final is not None and ref is not None and not res.get("starved")
                    and fps_final < FPS_DRIFT_RATIO * ref):
                res["drift_from"] = ref
                print(f"  [!] {cls.id} ran at {fps_final} fps against {ref} earlier in this "
                      f"series - the stand moved under the measurement, NOT comparable")
                if starve_refreshes[0] < MAX_STARVE_REFRESHES:
                    starve_refreshes[0] += 1
                    refresh_clients(f"{cls.id} drifted {ref} -> {fps_final} fps "
                                    f"(rebuild {starve_refreshes[0]}/{MAX_STARVE_REFRESHES})")
            if fps_final is not None:
                course_best_fps[cls.id] = max(ref or 0.0, fps_final)
            results.append(res)

    print("\n================ SUMMARY ================")
    gate_fail = 0
    invalid_n = sum(1 for r in results if r.get("invalid"))
    for r in results:
        status = ("PASS" if r["passed"] else
                  "INVALID" if r.get("invalid") else
                  ("info-fail" if r["tier"] == "info" else "FAIL"))
        if not r["passed"] and not r.get("invalid") and r["tier"] == "gate":
            gate_fail += 1
        extra = f"  ({r['error'][:60]})" if r.get("error") else ""
        # Starved runs are marked in the SUMMARY too, not only in the scroll above it. The summary
        # is the part that gets read and quoted; a run taken at 10 fps that reads a bare "PASS"
        # here is how a degraded series ends up in a before/after table.
        starved = "  [starved - not comparable]" if r.get("starved") else ""
        if not starved and r.get("drift_from"):
            starved = (f"  [fps drift {r['drift_from']} -> {r.get('avg_fps')} - "
                       f"not comparable]")
        # PRINT THE FRAME RATE ON EVERY LINE, not only on the ones a guard objected to. The
        # per-course drift check above cannot fire in a plain --repeat 1 sweep (one run per
        # course, so no earlier reading to compare with), and courses differ enough in arena size
        # that marking one course against another would be a guess. The frame rate itself is not a
        # guess, and the SUMMARY is the part that gets quoted into comparisons, so it belongs here
        # where the reader can see a 29-and-17 pair for themselves.
        fps_col = f"  {r['avg_fps']:.1f}fps" if r.get("avg_fps") is not None else ""
        jarcol = f"  [jar changed mid-series: {r['jar_changed']}]" if r.get("jar_changed") else ""
        print(f"  {r['id']:28s} {status}{fps_col}{extra}{starved}{jarcol}")
    import json
    with open(os.path.join(art_root, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, indent=1, default=str)
    print(f"\n{len(results) - gate_fail - invalid_n}/{len(results)} ok, "
          f"gate failures: {gate_fail}, invalid (host starved): {invalid_n}")
    if invalid_n:
        print("  INVALID runs measure the MACHINE, not the bot. Do not read them "
              "as regressions — stop other heavy processes (`gradlew --stop`) "
              "and re-run.")
    # 2 = inconclusive: nothing regressed, but the run is not evidence.
    return 1 if gate_fail else (2 if invalid_n else 0)


if __name__ == "__main__":
    sys.exit(main())
