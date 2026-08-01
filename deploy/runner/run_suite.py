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
"""
import argparse
import datetime
import functools
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
print = functools.partial(print, flush=True)  # noqa: A001 - stand logs stream

from uctest.actors import Bot                       # noqa: E402
from uctest.arena import ArenaBuilder               # noqa: E402
from uctest.harness import Artifacts, Rcon, wait_for  # noqa: E402
from uctest.scenario import Ctx, Scenario, is_flake           # noqa: E402
from uctest.scenarios_nav import SCENARIOS as NAV   # noqa: E402
from uctest.scenarios_pvp import SCENARIOS as PVP   # noqa: E402

SUITES = {"pvp": PVP, "nav": NAV}
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


def _rec_start(scn_id, dur, persp=0):
    """Record tester1's own screen for the scenario window (x11grab on the
    container's :0). First-person + the tungsten combat overlay (Walker/Punk
    state, freeze behaviour) — reliable and diagnostic; the combat aim forces
    first-person anyway, so third-person doesn't hold during a fight. Returns
    the in-container mp4 path."""
    mp4 = f"/mc-data/rec_{scn_id}.mp4"
    try:
        Py4jClient(BOT_CONTAINER).call("setPerspective", persp)
    except Exception:
        pass
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
        bot.pin_settings(VIZ_SETTINGS)
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
        mp4 = _rec_start(scn.id, scn.duration) if record else None
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
    # Only the load-sensitive criteria are excused. Not reaching the goal stays a failure.
    LOAD_SENSITIVE = ("freeze", "stand-still", "standstill")
    HEALTHY_FPS_MIN = 12.0
    avg_fps = ctx.geo.get("avg_fps")
    invalid = False
    if not passed and avg_fps is not None and avg_fps < HEALTHY_FPS_MIN:
        failed_gates = [c for c in crits if c.gate and not c.ok]
        if failed_gates and all(any(k in c.name for k in LOAD_SENSITIVE) for c in failed_gates):
            invalid = True

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
        print(f"  => {scn.id}: INVALID — host starved (avg_fps={avg_fps:.1f} < "
              f"{HEALTHY_FPS_MIN}). Only load-sensitive checks failed; this measures the "
              f"machine, not the bot. Close whatever else is running and RE-RUN.")
    else:
        print(f"  => {scn.id}: {'PASS' if passed else 'FAIL'}")
    verdict["flake_suspect"] = False
    return verdict


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("suite", nargs="?", help="suite name (pvp)")
    ap.add_argument("--only", help="run one scenario id")
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
        scenarios = [c for c in scenarios if c.id == args.only]
        if not scenarios:
            print(f"no scenario '{args.only}' in suite {args.suite}")
            return 2

    stamp = datetime.datetime.utcnow().strftime("%Y%m%d-%H%M%S")
    art_root = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "artifacts", stamp)
    os.makedirs(art_root, exist_ok=True)
    print(f"artifacts: {art_root}")

    rcons = {name: Rcon(w["container"]) for name, w in WORLDS.items()}
    flat = rcons["flat"]
    wait_for("server rcon", lambda: "players" in flat.cmd("list"), 300, 5)
    bot = Bot(BOT_CONTAINER, BOT, flat)
    victim = Bot(VICTIM_CONTAINER, VICTIM, flat)

    results = []
    for cls in scenarios:
        for rep in range(args.repeat):
            res = run_scenario(cls, rcons, bot, victim, art_root, args.record)
            if not res["passed"] and res.get("flake_suspect") and args.repeat == 1:
                print(f"  flake suspected ({res.get('error', '')[:80]}) — one retry")
                time.sleep(10)
                res = run_scenario(cls, rcons, bot, victim, art_root, args.record)
                res["retried"] = True
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
        print(f"  {r['id']:28s} {status}{extra}")
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
