"""Run the uctest elytra courses (uctest/scenarios_elytra.py) on clients and a server on this machine.

    python elytra_local.py [elytra_rings,elytra_500,elytra_follow] [--repeat N] [--record]

run_suite.py drives the containers of deploy/compose.test.yml; this is the same course code (build,
fresh_reset, run, judge, cleanup) for a local flat test server and local clients, through
uctest/local.py. The world must be a default superflat (grass at -61): the arena is carved round the
origin as in the suite, and put back to the flat world afterwards.

    UCTEST_BOT=name       the bot under test (default arcmlchikkk)
    UCTEST_VICTIM=name    the leader for elytra_follow (default pyhtun_lead)
    and the rcon / py4j python settings of uctest/local.py
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uctest.actors import Bot  # noqa: E402
from uctest.arena import ArenaBuilder  # noqa: E402
from uctest.harness import Artifacts  # noqa: E402
from uctest.local import LocalPy4jClient, LocalRcon  # noqa: E402
from uctest.scenario import Ctx  # noqa: E402
from uctest.scenarios_elytra import SCENARIOS  # noqa: E402


GATEWAYS = range(25333, 25341, 2)  # a client on this machine takes the next free gateway port


def actor(name, rcon):
    """The client logged in as `name`, found by asking each local gateway whose it is: the ports
    go in the order the clients came up, which is not always the order they were started in."""
    for port in GATEWAYS:
        py = LocalPy4jClient(port)
        ok, who = py.try_call("getUsername", timeout=15)
        if ok and str(who).lower() == name.lower():
            bot = Bot(f"local:{port}", name, rcon)
            bot.py = py
            return bot
    raise SystemExit(f"no client on this machine is logged in as {name}")


def flat_again(rcon, half):
    """Undo the arena's void: the superflat layers back under the carved square."""
    for y, block in ((-64, "bedrock"), (-63, "dirt"), (-62, "dirt"), (-61, "grass_block")):
        rcon.cmd(f"fill {-half} {y} {-half} {half} {y} {half} minecraft:{block}")


def run(cls, rcon, bot, victim, art_root, record=False):
    scn = cls()
    ctx = Ctx(bot, victim if scn.needs_victim else None, rcon, Artifacts(art_root, scn.id))
    print(f"\n--- {scn.id} ({scn.tier}, {scn.duration}s) ---", flush=True)
    arena = ArenaBuilder(rcon)
    try:
        arena.prepare(half=scn.arena_half, regen=scn.regen)
        scn.build(arena, ctx)
        sp = ctx.geo["bot_spawn"].split()
        arena.set_spawn(float(sp[0]), float(sp[1]), float(sp[2]))
        bot.fresh_reset(ctx.geo["bot_spawn"], scn.bot_kit, True)
        if scn.needs_victim:
            victim.fresh_reset(ctx.geo["victim_spawn"], scn.victim_kit, True)
        if record:  # the bot's own window, from behind it, no HUD: the mod's recorder
            bot.py.call("camera", 1, False)
            clip = bot.py.call("recordStart", f"uctest-{scn.id}-{time.strftime('%H%M%S')}", 30)
            ctx.geo["clip"] = clip.get("file") if isinstance(clip, dict) else None
        try:
            crits = scn.run(ctx)
        finally:
            if record:
                bot.py.try_call("recordStop")
                bot.py.try_call("camera", 0, True)
    finally:
        scn.cleanup(ctx)
        for b in (bot, victim):
            b.stop_all()
        flat_again(rcon, scn.arena_half)
    rows = [c.as_dict() for c in crits]
    passed = all(c["ok"] for c in rows if c["gate"])
    for c in rows:
        print(f"  {'PASS' if c['ok'] else 'FAIL'}{'' if c['gate'] else ' (info)'} {c['name']}: {c['detail']}")
    print(f"  => {'PASS' if passed else 'FAIL'}", flush=True)
    ctx.art.write_json("verdict.json", {"passed": passed, "criteria": rows, "clip": ctx.geo.get("clip")})
    return passed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("only", nargs="?", default=",".join(SCENARIOS))
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--record", action="store_true", help="film each course from the bot's client")
    a = ap.parse_args()
    rcon = LocalRcon()
    bot = actor(os.environ.get("UCTEST_BOT", "arcmlchikkk"), rcon)
    victim = actor(os.environ.get("UCTEST_VICTIM", "pyhtun_lead"), rcon)
    art_root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "artifacts",
                            "elytra-local-" + time.strftime("%Y%m%d-%H%M%S"))
    results = {}
    for sid in a.only.split(","):
        results[sid] = [run(SCENARIOS[sid], rcon, bot, victim, art_root, a.record) for _ in range(a.repeat)]
    print("\n" + json.dumps({k: f"{sum(v)}/{len(v)}" for k, v in results.items()}))


if __name__ == "__main__":
    main()
