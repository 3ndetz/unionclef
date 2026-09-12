#!/usr/bin/env python3
"""G69 bench: a drop in a one-deep hole right beside the standing bot -- the pickup must step in.

The 22:10 run: the bot mined a cobblestone at its feet's neighbour, the drop settled in the hole
one block down and a block and a half away, the goal was nearLive(r=1) on the drop's cell, and
the navigator declared "arrived (1.7)" on its own two-block sphere without moving; the drive's
own test said not reached, restarted the route, "arrived (1.7)" again every fifteen seconds; the
pursuit gave the drop up at twenty-five, the blacklist restored it, four attempts, a hundred
seconds, never touched. Baritone has no navigator radius: the GOAL decides arrival. Ours now
hands the goal's own test to the navigator.

    python3 deploy/runner/rim_drop_test.py          # exit 0 = PASS

Flat server: the bot standing STILL on the ground, a one-deep hole in the next cell with a
cobblestone item lying in it (the drop 1.3 blocks from the body's centre, one block down),
`@get cobblestone 1`. PASS = the cobblestone is picked up within the window.
"""
import functools, json, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
BX, BY, BZ = 2200, -60, 300          # the bot's column; the flat world's floor is at -60
HOLE = (BX, BY, BZ + 1)              # the one-deep hole: this floor block removed
WINDOW_S = 45
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
elif op=="inv":
    ids=[]
    try:
        for s in mc.getInventoryFull().get("slots") or []:
            sd=dict(s)
            if not sd.get("empty"):
                nm=str(sd.get("item") or sd.get("name") or "")
                if nm: ids.append(nm)
    except Exception: pass
    out={"ids": ids}
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


def has_cobble():
    try:
        return any("cobblestone" in i for i in py4j("inv")["ids"])
    except Exception:
        return False


def main():
    if not py4j("state")["inGame"] or BOT not in rcon("list"):
        py4j("connect", ip="test-server")
        t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    py4j("chatcmd", c=";settings fireReleaseNeedsFire true"); time.sleep(0.3)
    for k, v in PINS:
        py4j("chatcmd", c=f";settings {k} {v}"); time.sleep(0.3); print(f"pinned {k}={v}")
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {BX-12} {BZ-12} {BX+12} {BZ+12}"); time.sleep(1)
    rcon(f"fill {BX-4} {BY} {BZ-4} {BX+4} {BY} {BZ+4} minecraft:stone")
    rcon(f"fill {BX-4} {BY+1} {BZ-4} {BX+4} {BY+4} {BZ+4} minecraft:air")
    hx, hy, hz = HOLE
    rcon(f"setblock {hx} {hy} {hz} minecraft:air")
    rcon(f"kill @e[type=item,distance=..30]")
    time.sleep(0.5)
    probe = rcon(f"execute if block {hx} {hy} {hz} minecraft:air")
    if "passed" not in probe.lower():
        print(f"FAIL: the hole was not dug (probe={probe!r})"); return 2
    rcon(f"spawnpoint {BOT} {BX} {BY+1} {BZ}")
    rcon(f"tp {BOT} {BX+0.5} {BY+1} {BZ+0.1}")     # on the rim, the hole's edge 0.9 away
    rcon(f"effect give {BOT} minecraft:instant_health 1 10")
    rcon(f"clear {BOT}")
    # the drop where the run's lay: a third into the hole cell, one block down
    rcon(f"summon minecraft:item {hx+0.38} {hy+0.05} {hz+0.36} {{Item:{{id:\"minecraft:cobblestone\",count:1}},Motion:[0.0,0.0,0.0],PickupDelay:0}}")
    time.sleep(2)
    present = rcon(f"execute if entity @e[type=item,x={hx},y={hy},z={hz},distance=..1.5]")
    start = py4j("state")
    print(f"on the rim at {start['pos']}, the cobblestone in the hole at {HOLE} (present: {present!r}); @get cobblestone 1")
    py4j("cmd", c="@get cobblestone 1")
    t0 = time.time(); seen = set(); got = False
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        s = py4j("state")
        chat = [c for c in py4j("chat", n=8)["chat"] if c not in seen]; seen.update(chat)
        note = [c for c in chat if any(w in c for w in ("arrived", "Drop", "drop", "FINISHED", "intoHole", "no progress"))]
        got = has_cobble()
        print(f"  t={time.time()-t0:.0f}s pos={s['pos']} cobblestone={got}"
              + (" | " + " || ".join(x[-70:] for x in note) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    tok = "?"
    try:
        st = py4j("stats")["s"]
        tok = " ".join(t for t in st.split() if t.startswith(("navArrivalRefused=", "intoHole=", "navStall=", "dropBlock=")))
    except Exception:
        pass
    rcon(f"forceload remove {BX-12} {BZ-12} {BX+12} {BZ+12}")
    print(f"result: cobblestone={got} counters: {tok}")
    if got:
        print("PASS: stepped into the hole and took the drop"); return 0
    print("FAIL: stood on the rim, the drop one block down never touched"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
