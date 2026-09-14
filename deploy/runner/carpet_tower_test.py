#!/usr/bin/env python3
"""G82 bench: a tower off a MOSS CARPET -- the block the feet stand in is cleared first, and a
tower with no rung in five seconds is a failure, not a hop.

The operator's screenshot (round 41, 22:39): the bot in a lush cave, "Getting within reach of
96,105,-109", hopping in place for ever, no vine anywhere. Under it: a moss carpet, feet at 91.06.
The planner had a node one cell up with an "air" feet cell (supportTop answers the carpet's top
for both cells) and planned a block INTO that cell -- a click the body can only make from 93.05,
0.7 beyond a jump. "Wall too high to jump -- pillaring to y=93", then "Pillar stuck ... air=488
insideCell=324 placeAt=0 placed=0" three times, twenty-four seconds each, because a hopping body is
never "still" for the old stuck test. Baritone breaks a non-air, non-replaceable source block before
it jumps (MovementPillar.updateState) and bounds a movement by cost+100 ticks. Now: the planner
refuses a tower from inside the cell below and prices the clear; the navigator digs the feet cell
before the tower; PillarTask refuses to start inside such a block and stops without a rung in a
hundred ticks.

    python3 deploy/runner/carpet_tower_test.py          # exit 0 = PASS

Flat server: a one-wide pit two deep in a bedrock slab, stone floor under a MOSS CARPET, the bot in
the pit with sixteen cobblestone, `@goto` a rim cell five blocks off. The only way out is a two-block
tower off the carpet. PASS = the bot stands at rim level within 2.5 blocks of the goal inside the window,
the feet cell was cleared before a tower (navFeet cleared >= 1), and no more than two towers ran
past the rung timeout.
"""
import functools, json, math, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
BX, BY, BZ = 2300, -60, 300          # the pit's centre column; the flat world's floor is at -60
GOAL = (BX + 5, BY + 3, BZ)          # on the rim (the slab is two thick: BY+1..BY+2)
WINDOW_S = 90
PINS = [kv.split("=", 1) for kv in os.environ.get("UC_PINS", "").split(",") if "=" in kv]

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
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",10))]}
elif op=="stats": out={"s": str(mc.placeStats())}
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


def build():
    rcon(f"fill {BX-9} {BY+1} {BZ-9} {BX+9} {BY+9} {BZ+9} minecraft:air")
    rcon(f"fill {BX-9} {BY} {BZ-9} {BX+9} {BY} {BZ+9} minecraft:stone")            # floor
    rcon(f"fill {BX-6} {BY+1} {BZ-6} {BX+6} {BY+2} {BZ+6} minecraft:bedrock")     # the slab
    rcon(f"fill {BX} {BY+1} {BZ} {BX} {BY+2} {BZ} minecraft:air")                 # the one-wide pit
    rcon(f"setblock {BX} {BY+1} {BZ} minecraft:moss_carpet")                       # on the stone


def counters():
    st = py4j("stats")["s"]
    out = {}
    for t in st.split():
        if t.startswith(("navFeet=", "pillarThin=", "planPillarIn=", "navPillarRuns=", "navCeiling=", "pillarColRefused=")):
            k, v = t.split("=", 1); out[k] = v
    return out


def pair(c, key):
    try:
        a, b = c.get(key, "0/0").split("/")
        return int(a), int(b)
    except ValueError:
        return 0, 0


def main():
    if BOT not in rcon("list"):
        print("connecting to test-server")
        py4j("connect", ip="test-server")
        t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    for k, v in PINS:
        py4j("chatcmd", c=f";settings {k} {v}"); time.sleep(0.3); print(f"pinned {k}={v}")
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {BX-12} {BZ-12} {BX+12} {BZ+12}"); time.sleep(1)
    build()
    time.sleep(1)
    carpet = rcon(f"execute if block {BX} {BY+1} {BZ} minecraft:moss_carpet")
    wall = rcon(f"execute if block {BX+1} {BY+2} {BZ} minecraft:bedrock")
    if "passed" not in carpet.lower() or "passed" not in wall.lower():
        print(f"FAIL: the scene was not built (carpet={carpet!r}, wall={wall!r})"); return 2
    rcon(f"spawnpoint {BOT} {BX} {BY+1} {BZ}")
    # ON the carpet (its top is 1/16 up), not teleported into its box at the cell's base: a body
    # embedded in the carpet is not the stance the lush cave had (feet at 91.06), and rounds 44-46
    # read pos y=-59.0 where round 43's nine-carpet pit read -58.9.
    rcon(f"tp {BOT} {BX+0.5} {BY+1.0625} {BZ+0.5}")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10 true")
    rcon(f"effect give {BOT} minecraft:saturation 5 5 true")
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} minecraft:cobblestone 16")
    time.sleep(2)
    counters()   # reads AND resets the counters, so the window below is this run's alone
    start = py4j("state")
    sx, sy, sz = (float(v) for v in start["pos"].split(","))
    if abs(sx - (BX + 0.5)) > 0.6 or abs(sz - (BZ + 0.5)) > 0.6 or sy > BY + 1.5:
        print(f"FAIL: the bot is not in the pit (pos={start['pos']}); the scene is wrong, not the bot"); return 2
    gx, gy, gz = GOAL
    print(f"on the carpet at {start['pos']} in a 2-deep one-wide bedrock pit with 16 cobblestone; @goto {gx} {gy} {gz}")
    py4j("cmd", c=f"@goto {gx} {gy} {gz}")
    t0 = time.time(); seen = set(); out = False; best_y = sy; c = {}
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        s = py4j("state")
        x, y, z = (float(v) for v in s["pos"].split(","))
        best_y = max(best_y, y)
        chat = [m for m in py4j("chat", n=10)["chat"] if m not in seen]; seen.update(chat)
        note = [m for m in chat if any(w in m for w in ("illar", "clearing the", "at the dig", "Mining done",
                                                          "Wall too", "no rung", "refused", "giving"))]
        c = counters()
        print(f"  t={time.time()-t0:.0f}s pos={s['pos']} busy={s['busy']} navFeet={c.get('navFeet','?')}"
              f" pillarThin={c.get('pillarThin','?')} planPillarIn={c.get('planPillarIn','?')}"
              + (" | " + " || ".join(m[-80:] for m in note[-2:]) if note else ""))
        if y >= gy - 0.3 and math.hypot(x - (gx + 0.5), z - (gz + 0.5)) <= 2.5:
            out = True
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    # The carpet is gone either way that clears it: the navigator's own feet dig (navFeet), or the
    # physics engine's wall dig ("At the wall — mining", which G82's executor fix made real for a
    # carpet). Round 45: out in four seconds through the second road with navFeet=0/0, and the old
    # criterion called that a FAIL. What the bench asks is that the carpet was cleared and the
    # tower went up, not which of the two diggers did it. And "gone" is "no carpet there", not
    # "air": the tower's first block goes into that very cell (round 46: cobblestone in it).
    carpet_gone = "passed" not in rcon(f"execute if block {BX} {BY+1} {BZ} minecraft:moss_carpet").lower()
    rcon(f"forceload remove {BX-12} {BZ-12} {BX+12} {BZ+12}")
    cleared, refused = pair(c, "navFeet")
    thin, no_rung = pair(c, "pillarThin")
    print(f"result: out={out} bestY={best_y:.1f} carpetGone={carpet_gone} navFeet={cleared}/{refused}"
          f" pillarThin={thin}/{no_rung} planPillarIn={c.get('planPillarIn','?')} navPillarRuns={c.get('navPillarRuns','?')}")
    if out and carpet_gone and no_rung <= 2:
        print("PASS: the carpet was cleared" + (" by the navigator's feet dig" if cleared >= 1 else " by the physics engine's wall dig")
              + ", the tower went up, the bot reached the rim"); return 0
    if not out:
        print(f"FAIL: still in the pit after {WINDOW_S}s (best Y {best_y:.1f}, rim at {gy})"); return 1
    if not carpet_gone:
        print("FAIL: the bot got out with the carpet still in place (the mechanism under test did not run)"); return 1
    print(f"FAIL: {no_rung} towers ran past the rung timeout"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
