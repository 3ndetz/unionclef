"""Bench for G18's cobweb half (2026-09-17): a corridor plugged with cobweb is a WALL today, not
an obstacle to break.

TODOS.md traces the mechanism precisely: `FastPlanner.hazardAt` groups cobweb with genuinely
dangerous blocks (lava, magma, fire, cactus, powder snow) purely because they share an empty or
near-empty collision shape, and `hazardousDestination()` -- the ONE gate every move generator
passes through in `relax()` -- refuses any cell containing cobweb outright, before a break plan is
ever built. Even past that gate, `breakThrough()`'s own obstruction test decides what to mine by
`getCollisionShape().isEmpty()`, which is ALSO empty for cobweb, so it would still read as
"nothing in the way". A mineshaft corridor filled with cobweb -- ordinary terrain, not a contrived
case -- is routed around entirely rather than walked through or cleared.

Layout (flat world, corridor floor at FY, bot feet at FY+1):

    a one-wide, two-tall stone corridor, ten blocks long, walled and roofed on every side.
    at the midpoint the corridor's own feet+head cells are COBWEB instead of air -- the only
    way across is straight through it, there is no way around.

The bot gets shears (cobweb's fastest break, once it is ever offered as one) and is sent
`@goto` to the far end. PASS if it arrives; FAIL if it stands at the near side of the cobweb
for the whole window -- which is the CURRENT, documented behaviour, so a FAIL here is expected
until G18's cobweb half is implemented, and a PASS is the bench this fix should be checked
against.

    python deploy/runner/cobweb_wall_test.py             # build the scene and measure
    python deploy/runner/cobweb_wall_test.py --no-build  # reuse the scene
"""
import functools, json, math, subprocess, sys, time
print = functools.partial(print, flush=True)

SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 3000, 900                    # fresh spot, clear of every other bench's coordinates
FY = -61                            # superflat top solid block; feet at FY+1
LEN = 10                            # corridor length along X
MID = X + LEN // 2                  # the cobweb plug's X
GOAL = (X + LEN - 1, FY + 1, Z)
WINDOW_S = 60

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
elif op=="chatcmd": mc.ChatMessage(req["c"]); out={"ok":True}
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


def build():
    # clear a generous box, then carve the corridor: stone floor, stone walls both sides, stone
    # roof, air inside -- except the midpoint's own two cells, which are cobweb.
    rcon(f"fill {X - 2} {FY - 1} {Z - 3} {X + LEN + 2} {FY + 4} {Z + 3} air")
    rcon(f"fill {X - 2} {FY} {Z - 2} {X + LEN + 2} {FY} {Z + 2} stone")               # floor
    rcon(f"fill {X - 1} {FY + 1} {Z - 1} {X + LEN} {FY + 3} {Z - 1} stone")           # wall
    rcon(f"fill {X - 1} {FY + 1} {Z + 1} {X + LEN} {FY + 3} {Z + 1} stone")           # wall
    rcon(f"fill {X - 1} {FY + 3} {Z - 1} {X + LEN} {FY + 3} {Z + 1} stone")           # roof
    rcon(f"fill {X - 1} {FY + 1} {Z} {X + LEN} {FY + 2} {Z} air")                     # the passage
    rcon(f"setblock {MID} {FY + 1} {Z} cobweb")
    rcon(f"setblock {MID} {FY + 2} {Z} cobweb")


def main():
    build_scene = "--no-build" not in sys.argv
    py4j("connect", ip="test-server")
    for _ in range(40):
        time.sleep(2)
        if py4j("state").get("inGame") and BOT in rcon("list"):
            break
    else:
        print("FAIL: bot not on test-server"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    rcon("difficulty peaceful")
    rcon(f"forceload add {X - 5} {Z - 5} {X + LEN + 5} {Z + 5}"); time.sleep(1)
    if build_scene:
        build()
        time.sleep(1)
    web = rcon(f"execute if block {MID} {FY + 1} {Z} cobweb")
    if "passed" not in web.lower():
        print(f"FAIL: the scene was not built (cobweb={web!r})"); return 2
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} shears")
    rcon(f"tp {BOT} {X + 0.5} {FY + 1} {Z + 0.5}")
    time.sleep(2)
    start = py4j("state")
    print(f"at {start['pos']} in a stone corridor plugged with cobweb at x={MID}; @goto {GOAL[0]} {GOAL[1]} {GOAL[2]}")
    py4j("cmd", c=f"@goto {GOAL[0]} {GOAL[1]} {GOAL[2]}")
    t0 = time.time(); out = False; best_x = start['pos'].split(",")[0]
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        s = py4j("state")
        x, y, z = (float(v) for v in s["pos"].split(","))
        best_x = max(float(best_x), x)
        print(f"  t={time.time()-t0:.0f}s pos={s['pos']} busy={s['busy']}")
        if math.hypot(x - (GOAL[0] + 0.5), z - (GOAL[2] + 0.5)) <= 1.5:
            out = True
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    rcon(f"forceload remove {X - 5} {Z - 5} {X + LEN + 5} {Z + 5}")
    crossed = float(best_x) > MID + 0.5
    print(f"result: out={out} bestX={best_x} crossed_plug={crossed}")
    if out:
        print(f"PASS: reached the far side, cobweb crossed or cleared, in {time.time()-t0:.0f}s"); return 0
    if not crossed:
        print(f"FAIL: never crossed the cobweb plug at x={MID} in {WINDOW_S}s "
              "(the documented G18 behaviour -- the planner walls it off rather than clear it)"); return 1
    print(f"FAIL: crossed the plug but did not reach the goal in {WINDOW_S}s"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
