#!/usr/bin/env python3
"""G65 bench: a cobblestone drop at the bottom of a one-wide shaft, the bot standing beside it.

The 19:00 recording: the bot over a one-wide shaft with a cobblestone at its bottom,
"FastNavigator: arrived (1.3)", six and a half minutes; buried_goal phase two: the sand over the
chest dug open and the body shuffling 859 <-> 861 on either rim for forty seconds. A one-wide hole
takes a body only when its whole hitbox is over the air -- a 0.4-block window in both axes -- and
the walker approached it at sprint speed with a 45-degree bearing tolerance, so it landed on the
far rim every time. Baritone's MovementDescend walks to the destination's CENTRE at walking pace
with its aim on it, and falls in because it arrives there.

    python3 deploy/runner/narrow_shaft_test.py          # exit 0 = PASS

Flat server. Phase A: a shaft one block wide and two deep, the bot two blocks from its rim, a
cobblestone item at the bottom, `@get cobblestone 1`. Phase B: the same shaft three deep (a fall
that hurts a little but is planned). PASS = the cobblestone in the inventory in both phases within
the window; intoHole reported, no "not getting closer" line.
"""
import functools, json, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z, GROUND = 1800, 300, -61
WINDOW_S = 60
PINS = [kv.split("=", 1) for kv in os.environ.get("UC_PINS", "").split(",") if "=" in kv]

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
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",12))]}
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


def phase(name, x, depth):
    rcon(f"kill @e[type=item,x={x},y={GROUND},z={Z},distance=..30]")
    rcon(f"fill {x-8} {GROUND-depth-2} {Z-8} {x+8} {GROUND+6} {Z+8} minecraft:air")
    rcon(f"fill {x-8} {GROUND-depth-2} {Z-8} {x+8} {GROUND} {Z+8} minecraft:stone")
    rcon(f"fill {x-8} {GROUND} {Z-8} {x+8} {GROUND} {Z+8} minecraft:grass_block")
    # the shaft: one wide, `depth` deep, its floor at GROUND-depth
    rcon(f"fill {x+2} {GROUND-depth+1} {Z} {x+2} {GROUND} {Z} minecraft:air")
    time.sleep(1)
    rcon(f"spawnpoint {BOT} {x} {GROUND+1} {Z}")
    rcon(f"tp {BOT} {x+0.5} {GROUND+1} {Z+0.5} 270 0")     # two blocks west of the shaft, facing it
    rcon(f"effect give {BOT} minecraft:instant_health 1 10")
    rcon(f"clear {BOT}")
    time.sleep(1)
    rcon(f"summon minecraft:item {x+2.5} {GROUND-depth+1.2} {Z+0.5} "
         f"{{Item:{{id:\"minecraft:cobblestone\",count:1}},PickupDelay:0}}")
    time.sleep(1.5)
    gs = py4j("gs")
    print(f"[{name}] bot at {gs['pos']}; a one-wide shaft {depth} deep two blocks east, cobblestone at its bottom. @get cobblestone 1")
    py4j("cmd", c="@get cobblestone 1")
    t0 = time.time(); got = False; seen = set()
    bad = {"not getting closer": 0, "Costing": 0}
    while time.time() - t0 < WINDOW_S:
        time.sleep(4)
        gs = py4j("gs")
        ch = [c for c in py4j("chat", n=12)["chat"] if c not in seen]; seen.update(ch)
        for k in bad:
            bad[k] += sum(1 for c in ch if k in c)
        got = any("cobblestone" in i for i in py4j("inv")["ids"])
        print(f"  t={time.time()-t0:.0f}s pos={gs['pos']} cobblestone={got}")
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    into = "?"
    try:
        st = py4j("stats")["s"]
        tok = [t for t in st.split() if t.startswith(("intoHole=", "walkerHeldAbove=", "hole", "navStall="))]
        print(f"  counters: {' '.join(tok)}")
        for t in tok:
            if t.startswith("intoHole="):
                into = t.split("=", 1)[1]
    except Exception:
        pass
    flaws = {k: v for k, v in bad.items() if v}
    print(f"[{name}] result: cobblestone={got} intoHole={into} flaws={flaws or 'none'}")
    return got and not flaws


def main():
    if not py4j("state")["inGame"] or BOT not in rcon("list"):
        py4j("connect", ip="test-server")
        t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined test-server"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    for k, v in PINS:
        py4j("chatcmd", c=f";settings {k} {v}"); time.sleep(0.3); print(f"pinned {k}={v}")
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {X-12} {Z-12} {X+40} {Z+12}"); time.sleep(1)
    a = phase("two deep", X, 2)
    b = phase("three deep", X + 20, 3)
    rcon(f"forceload remove {X-12} {Z-12} {X+40} {Z+12}")
    if a and b:
        print("PASS: walked into the one-wide shaft and took the drop, both depths"); return 0
    print("FAIL"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
