#!/usr/bin/env python3
"""Can the bot climb out of a pit toward an up-and-offset goal? A CORE-fix regression test.

Live on the survival stand 2026-09-10 the @gamer bot dug/fell into a shaft and stood at the
bottom for 3 minutes: its goal (a log) was UP and to the SIDE, and the task-driven navigator's
pillar recovery only fired when the goal was nearly straight overhead (horizToGoal < 1.5), so it
yielded forever. The fix (CustomBaritoneGoalTask.pillarEscapeY) pillars out of a shaft whose wall
in the goal's direction is >= 2 tall. This test reproduces the shaft deterministically and asserts
the bot leaves it.

    python3 deploy/runner/pit_escape_test.py            # exit 0 = PASS

Builds a 1-wide, ~7-deep stone shaft on the FLAT nav server, drops the bot in with a stack of
dirt, sets an @goto to a point on the surface a few blocks to the side, and checks the bot's Y
rises out of the shaft within the window. Needs uctest-server + uctest-mc-tester1 up.
"""
import functools
import json
import subprocess
import sys
import time

print = functools.partial(print, flush=True)
SERVER = "uctest-server"
C1 = "uctest-mc-tester1"
BOT = "tester1"
# A clear, empty patch of the flat world well away from spawn structures.
BX, BY, BZ = 200, -60, 200          # shaft floor (flat world floor sits at y=-60)
DEPTH = 7                            # wall height above the floor
GOAL = (BX + 4, BY + DEPTH, BZ)      # on the surface, 4 blocks to the +X side

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
def me():
    s=dict(mc.getGameState().get("self") or {}); return s.get("pos")
if op=="state": out={"inGame":mc.inGame(),"pos":me(),"busy":mc.hasActiveTask()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",10))]}
print(json.dumps(out,default=str)); gw.close()
"""


def sh(a, to=60):
    return subprocess.run(a, capture_output=True, text=True, timeout=to)


def py4j(op, to=40, **kw):
    r = sh(["docker", "exec", C1, "python3", "-c", SNIP, json.dumps({"op": op, **kw})], to)
    if r.returncode != 0:
        raise RuntimeError(f"{op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])


def rcon(c):
    return sh(["docker", "exec", SERVER, "rcon-cli", c]).stdout.strip()


def build_shaft():
    # A solid stone block of wall around a 1-wide column, floor to surface, then hollow the column.
    rcon(f"fill {BX-1} {BY} {BZ-1} {BX+1} {BY+DEPTH} {BZ+1} minecraft:stone")
    rcon(f"fill {BX} {BY+1} {BZ} {BX} {BY+DEPTH+1} {BZ} minecraft:air")   # the shaft the bot stands in
    # The surface the bot must reach: clear a small pad around the goal, one block up from the wall top.
    gx, gy, gz = GOAL
    rcon(f"fill {gx-1} {gy} {gz-1} {gx+2} {gy} {gz+1} minecraft:stone")   # ground at surface level
    rcon(f"fill {gx-1} {gy+1} {gz-1} {gx+2} {gy+3} {gz+1} minecraft:air")


def main():
    # "IN GAME" IS NOT "ON THIS SERVER". After a @gamer run the client is in game on the survival
    # server, so the old test skipped connecting, the rcon teleport addressed a player the flat
    # server did not have, and the bench failed in setup (2026-09-11 regression: rise 0.0).
    if BOT not in rcon("list"):
        print("connecting to test-server")
        py4j("connect", ip="test-server")
        t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined"); return 2
    py4j("cmd", c="@stop"); py4j("cmd", c=";stop"); time.sleep(2)
    # the planner's own lines (PLAN n=.., HANDOFF, ceiling) print only in verbose; the client log
    # keeps them even when the chat overflows, and nav_lines() reads the log on failure
    py4j("cmd", c=";settings verboseDebugLogging true"); time.sleep(0.5)
    rcon(f"gamemode survival {BOT}")
    build_shaft()
    time.sleep(1)
    rcon(f"tp {BOT} {BX + 0.5} {BY + 1} {BZ + 0.5}")
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} minecraft:dirt 64")
    time.sleep(2)
    start = py4j("state")
    print("dropped in shaft:", start["pos"])
    y0 = float(start["pos"].split(",")[1])
    gx, gy, gz = GOAL
    py4j("cmd", c=f"@goto {gx} {gy} {gz}")
    print(f"@goto {gx} {gy} {gz} (surface, +4 X of the shaft)")
    best_y = y0
    escaped = False
    t0 = time.time()
    seen = set()
    while time.time() - t0 < 90:
        time.sleep(5)
        s = py4j("state")
        pos = s["pos"]
        y = float(pos.split(",")[1])
        best_y = max(best_y, y)
        chat = [c for c in py4j("chat", n=6)["chat"] if c not in seen]
        seen.update(chat)
        pill = [c for c in chat if any(w in c for w in ("illar", "ceiling", "at the dig", "Mining done",
                                                          "no progress", "giving", "HANDOFF", "Wall too"))]
        print(f"  t={time.time()-t0:.0f}s pos={pos} bestY={best_y:.0f} busy={s['busy']}"
              + (" | " + " || ".join(x[-70:] for x in pill) if pill else ""))
        if y >= y0 + DEPTH - 1.5:      # climbed essentially out of the shaft
            escaped = True
            break
    py4j("cmd", c="@stop"); py4j("cmd", c=";stop")
    py4j("cmd", c=";settings verboseDebugLogging false")
    if not escaped:
        print("  navigator log:")
        r = sh(["docker", "exec", C1, "sh", "-c",
                "tail -n 12000 /mc-data/logs/latest.log | grep -E "
                "'FastNavigator|Path needs|Pillar|MovementQueue: [0-9]+ movement|no progress|giving the route|"
                "Ran out of nodes|HANDOFF|Walker: BFS|primDrive NO|PLAN n=|FastPlanner: [0-9]+ nodes|at the dig|"
                "Mining done|Mining aborted|ceiling|WALKSTOP|BFS stuck|childless' "
                "| grep -v 'repeat muted' | tail -n 45"])
        for l in r.stdout.splitlines():
            print("    " + l[11:210].replace("[Render thread/INFO]: [CHAT] ", "")
                  .replace("[PathFinder/INFO]: [CHAT] ", "").replace("[FastNavigator-plan/INFO]: [CHAT] ", ""))
    print(f"start Y={y0:.0f}, best Y={best_y:.0f}, shaft depth={DEPTH}")
    if escaped:
        print("PASS: bot pillared out of the shaft")
        return 0
    print("FAIL: bot never climbed out (bestY rise "
          f"{best_y - y0:.1f} < {DEPTH - 1.5})")
    return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
