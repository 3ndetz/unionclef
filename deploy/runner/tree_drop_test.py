#!/usr/bin/env python3
"""G53 bench: a drop lying inside the tree the bot stands on -- five blocks below its feet, two
aside, on the canopy's lower skirt -- is reached by stepping down the leaf layers or by digging
through them, never "Drop not getting closer for 25s" three times and a blacklist.

The 17:22 recorded run (2026-09-11) lost four and a half minutes on top of a spruce it had just
felled: a stick and a plank lay five blocks below, the drive's grid BFS found no walking route,
the navigator planned, made no progress at its own cell, re-planned, gave the route up, and the
pickup blacklisted both drops after three tries each -- "MovementQueue: 1 movement(s)
1234,65,-1406 -> 1235,65,-1406" over and over, the body never leaving the crown.

    python3 deploy/runner/tree_drop_test.py            # exit 0 = PASS

Flat server: a spruce-shaped tree (trunk of 7 logs; a 7x7 skirt, a 5x5 body four thick, a 3x3
crown), the bot standing on the crown, a stick lying on the skirt five below and two aside,
`@get stick 1`. PASS = stick in the inventory within the window and no "not getting closer" or
"giving the route up" line.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 800, 300
GROUND = -61                 # flat world surface block; feet at GROUND+1
TRUNK_TOP = GROUND + 7       # logs GROUND+1 .. TRUNK_TOP
SKIRT_Y = GROUND + 3         # 7x7 single layer
BODY_Y0, BODY_Y1 = GROUND + 4, GROUND + 7   # 5x5, four thick
CROWN_Y = GROUND + 8         # 3x3; the bot stands on it, feet at CROWN_Y+1
WINDOW_S = 90

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame(),"busy":mc.hasActiveTask()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chatcmd": mc.ChatMessage(req["c"]); out={"ok":True}
elif op=="gs": out=dict(mc.getGameState().get("self") or {})
elif op=="inv":
    ids=[]
    try:
        for s in mc.getInventoryFull().get("slots") or []:
            sd=dict(s)
            if not sd.get("empty"):
                nm=str(sd.get("item") or sd.get("name") or "")
                if nm: ids.append(nm)
    except Exception: pass
    out={"ids":ids}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",8))]}
elif op=="task": out={"chain": str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-300:]}
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


def nav_lines(n=40):
    """The navigator's own account of the course, from the client log."""
    r = sh(["docker", "exec", C1, "sh", "-c",
            "tail -n 6000 /mc-data/logs/latest.log | grep -E "
            "'FastNavigator|Path needs|Pillaring|MovementQueue: [0-9]+ movement|no progress|giving the route|"
            "Ran out of nodes|partial|Partial|dead end|Drop not|HANDOFF|Walker: BFS|planned|primDrive' "
            f"| tail -n {n}"])
    return [l[11:200].replace("[Render thread/INFO]: [CHAT] ", "").replace("[PathFinder/INFO]: [CHAT] ", "")
            for l in r.stdout.splitlines()]


def main():
    if not py4j("state")["inGame"]:
        py4j("connect", ip="test-server"); time.sleep(3)
    if BOT not in rcon("list"):
        py4j("connect", ip="test-server"); t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined test-server"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    py4j("chatcmd", c=";settings fireReleaseNeedsFire true"); time.sleep(0.5)
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {X-10} {Z-10} {X+10} {Z+10}"); time.sleep(1)
    rcon(f"kill @e[type=item,x={X},y={GROUND},z={Z},distance=..30]")
    rcon(f"fill {X-10} {GROUND+1} {Z-10} {X+10} {CROWN_Y+8} {Z+10} minecraft:air")
    rcon(f"fill {X-10} {GROUND} {Z-10} {X+10} {GROUND} {Z+10} minecraft:grass_block")
    L = "minecraft:spruce_leaves[persistent=true]"
    rcon(f"fill {X-3} {SKIRT_Y} {Z-3} {X+3} {SKIRT_Y} {Z+3} {L}")
    rcon(f"fill {X-2} {BODY_Y0} {Z-2} {X+2} {BODY_Y1} {Z+2} {L}")
    rcon(f"fill {X-1} {CROWN_Y} {Z-1} {X+1} {CROWN_Y} {Z+1} {L}")
    rcon(f"fill {X} {GROUND+1} {Z} {X} {TRUNK_TOP} {Z} minecraft:spruce_log")
    time.sleep(1)
    ok = rcon(f"execute if block {X+1} {CROWN_Y} {Z} minecraft:spruce_leaves")
    ok2 = rcon(f"execute if block {X+3} {SKIRT_Y} {Z} minecraft:spruce_leaves")
    print(f"scene: crown -> {ok[:30]!r}; skirt -> {ok2[:30]!r}")
    if "passed" not in ok or "passed" not in ok2:
        print("FAIL: the tree was not built (chunk not loaded?)"); return 2
    rcon(f"tp {BOT} {X+1.5} {CROWN_Y+1} {Z+0.5} 270 30")   # on the crown's east cell, facing the trunk
    rcon(f"clear {BOT}")
    time.sleep(1)
    r = rcon(f'summon minecraft:item {X+3.5} {SKIRT_Y+1.2} {Z+0.5} '
             f'{{Item:{{id:"minecraft:stick",count:1}},PickupDelay:0s}}')
    time.sleep(2)
    n = rcon(f"execute if entity @e[type=item,x={X+3},y={SKIRT_Y+1},z={Z},distance=..3]")
    gs = py4j("gs")
    print(f"bot at {gs['pos']} on the crown (feet y={CROWN_Y+1}); stick on the skirt at "
          f"({X+3.5},{SKIRT_Y+1},{Z+0.5}) = {CROWN_Y+1-(SKIRT_Y+1)} below, 2 aside "
          f"(summon: {r[:40]!r}; present: {n[:40]!r}). @get stick 1")
    py4j("cmd", c="@get stick 1")
    t0 = time.time(); seen = set(); got = False
    bad = {"not getting closer": 0, "giving the route up": 0, "Failed exploring": 0,
           "Failed to pick up drop": 0, "cost more than its budget": 0}
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        gs = py4j("gs"); pos = gs["pos"]
        ch = [c for c in py4j("chat", n=14)["chat"] if c not in seen]; seen.update(ch)
        for k in bad:
            bad[k] += sum(1 for c in ch if k in c)
        got = any("stick" in i for i in py4j("inv")["ids"])
        note = [c for c in ch if any(w in c for w in ("Pillar", "Mining", "Path needs", "no progress",
                                                       "giving", "Failed", "closer", "gave up", "nodes"))]
        try:
            chain = py4j("task")["chain"][-90:]
        except Exception:
            chain = "?"
        print(f"  t={time.time()-t0:.0f}s pos={pos} stick={got} | {chain}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    try:
        st = py4j("stats")["s"]
        tok = [t for t in st.split() if t.startswith(("dropBlock=", "pdFnBuild=", "pdNearBuild=",
                                                          "pdFnOrphan=", "pdRouteStopped=", "navStall=",
                                                          "navBreak=", "navPartial=", "navPillarRuns="))]
        print(f"  counters: {' '.join(tok)}")
    except Exception:
        pass
    if not got:
        print("  navigator log:")
        for l in nav_lines():
            print("    " + l)
    rcon(f"forceload remove {X-10} {Z-10} {X+10} {Z+10}")
    flaws = {k: v for k, v in bad.items() if v}
    print(f"result: stick={got} flaws={flaws or 'none'}")
    if got and not flaws:
        print("PASS: came down through the tree and took the drop"); return 0
    print("FAIL"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
