"""Deterministic bench for G98: out of a deep shaft toward a goal almost straight up.

The 60-minute playthrough of 2026-09-16 ended with twenty minutes at the bottom of the shaft the
bot had dug for diamonds, (1508,52,-1516), the next goal nine blocks up on the surface: every
plan was partial (two ledges, then flagged tower cells), every partial was under the five blocks
the walk-the-partial rule wants, and the dead-end branch handed the GOAL to the physics engine
sixty-four times -- which has no place move. pit_escape_test covers the up-and-to-the-side goal
that CustomBaritoneGoalTask.pillarEscapeY handles; this is the navigator's own plan with the goal
nearly overhead, where the tower must come from the plan's flagged cells.

Layout (flat world, surface block at FY, feet at FY+1): a 2x1 shaft DEPTH deep at (X..X+1, Z),
the bot at its bottom with a stack of cobblestone in the HOTBAR, the goal on the surface two
blocks to the +Z side.

    python deploy/runner/shaft_exit_test.py            # build and measure
    python deploy/runner/shaft_exit_test.py --no-build

PASS if the feet reach surface level within TIMEOUT. Prints navPartialBuild, navTowerAllowedFar,
navPhysicsGaveUp and the pillar counters either way.
"""
import functools, json, re, subprocess, sys, time
print = functools.partial(print, flush=True)

SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 760, 760
FY = -61
# `--depth N` deepens the shaft: ten completes the plan (the tower hand-off from a complete
# plan); around twenty the planner's budget runs out and the plan comes back PARTIAL with the
# tower cells flagged, which is the G98 branch itself (navPartialBuild counts it).
DEPTH = int(sys.argv[sys.argv.index("--depth") + 1]) if "--depth" in sys.argv else 10
# `--budget-ms N` starves the planner (fastPlanBudgetMs, shipped 250) for the run and restores it
# after: on an open mound even a 22-deep tower completes in one plan, so the G98 branch -- a
# PARTIAL plan whose flagged tower cells must still be handed to PillarTask -- only shows with a
# budget too small to finish the climb. Measured: 22 deep at 250 ms completes (navPartial=0/0).
BUDGET_MS = int(sys.argv[sys.argv.index("--budget-ms") + 1]) if "--budget-ms" in sys.argv else None
# THE SHAFT IS CUT INTO A MOUND, NOT INTO THE FLOOR. The flat world's floor sits three blocks
# above the world's bottom (-64): a shaft dug ten deep from it leaves the world, the fill for
# its floor fails silently, and the bot falls into the void -- the first version of this bench
# read "start Y=-180.8" and then PASSED on the respawn's surface height. So the ground is
# built UP: stone from FY+1 to TOP, the shaft carved inside it, the bot at the bottom on the
# real floor, the goal on the mound's top.
TOP = FY + DEPTH                    # the mound's top block; its surface is TOP+1
GOAL = (X, TOP + 1, Z + 2)
TIMEOUT = 75

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="goto": mc.gotoXYZ(*req["pos"]); out={"ok":True}
elif op=="set": out={"r": str(mc.tungstenSetting(req["k"], req["v"]))}
elif op=="gs": out=dict(mc.getGameState().get("self") or {})
elif op=="stats": out={"r": str(mc.placeStats() or "")}
elif op=="reset": mc.resetValues(); mc.resetRunCounters(); out={"ok":True}
print(json.dumps(out,default=str)); gw.close()
"""


def sh(a, to=60):
    return subprocess.run(a, capture_output=True, text=True, timeout=to)


def py4j(op, to=40, **kw):
    r = sh(["docker", "exec", C1, "python3", "-c", SNIP, json.dumps({"op": op, **kw})], to)
    if r.returncode != 0:
        raise RuntimeError(f"{op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])


def rcon(c, to=30):
    r = sh(["docker", "exec", SERVER, "rcon-cli", c], to)
    return (r.stdout or "").strip()


def bot_y():
    p = str(py4j("gs").get("pos") or "")
    try:
        return float(p.split(",")[1])
    except Exception:
        return None


def counters(stats, keys):
    return " ".join(m.group(0) for k in keys for m in [re.search(k + r"=\S+", stats)] if m)


def main():
    build = "--no-build" not in sys.argv
    py4j("connect", ip="test-server")
    for _ in range(40):
        time.sleep(2)
        if py4j("state").get("inGame") and BOT in rcon("list"):
            break
    else:
        print("FAIL: bot not on test-server"); return 2
    py4j("cmd", c="@stop")
    rcon(f"tp {BOT} {X + 0.5} {FY + 1} {Z + 0.5}")
    time.sleep(5)
    if build:
        # a stone mound on the floor, then the shaft carved out of it (per-layer fills stay
        # under the vanilla block cap)
        for y in range(FY + 1, TOP + 1):
            rcon(f"fill {X - 8} {y} {Z - 8} {X + 8} {y} {Z + 8} stone")
        rcon(f"fill {X - 8} {TOP + 1} {Z - 8} {X + 8} {TOP + 4} {Z + 8} air")
        rcon(f"fill {X} {FY + 1} {Z} {X + 1} {TOP} {Z} air")
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} cobblestone 64")
    rcon(f"tp {BOT} {X + 0.5} {FY + 1} {Z + 0.5}")
    time.sleep(3)
    y0 = bot_y()
    if y0 is None or abs(y0 - (FY + 1)) > 0.6:
        print(f"FAIL: the scene did not hold — start Y={y0}, expected {FY + 1}"); return 2
    py4j("reset")
    if BUDGET_MS is not None:
        print("  " + py4j("set", k="fastPlanBudgetMs", v=str(BUDGET_MS)).get("r", ""))
    py4j("goto", pos=list(GOAL))
    t0 = time.time(); best = y0 if y0 is not None else -999; out = False
    while time.time() - t0 < TIMEOUT:
        time.sleep(3)
        y = bot_y()
        if y is None:
            continue
        best = max(best, y)
        if y >= TOP + 1 - 0.01:
            out = True; break
    dt = time.time() - t0
    py4j("cmd", c="@stop")
    if BUDGET_MS is not None:
        py4j("set", k="fastPlanBudgetMs", v="250")
    stats = py4j("stats").get("r", "")
    print("  " + counters(stats, ["navPartialBuild", "navTowerAllowedFar", "navPhysicsGaveUp",
                                  "navPillarRuns", "pillarNoHeadroom", "navPartial", "fastNowhere"]))
    print(f"  start Y={y0} best Y={best} shaft depth={DEPTH}")
    if out:
        print(f"PASS: out of the shaft in {dt:.1f}s"); return 0
    print(f"FAIL: still in the shaft after {dt:.0f}s (best Y {best})"); return 1


if __name__ == "__main__":
    sys.exit(main())
