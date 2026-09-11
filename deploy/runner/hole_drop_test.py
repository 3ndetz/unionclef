#!/usr/bin/env python3
"""G45 bench: a drop at the bottom of a 1x1 hole three deep, with the bot standing on the rim
half over the edge, is collected by stepping off the edge -- not by planning from inside the hole.

The round-4 playthrough (2026-09-11 14:50) stood on such a rim for the whole run: FastPlanner's
start snap moved the start three cells DOWN the hole onto the drop, the planner answered "start is
goal" 434 times, nobody moved. A body on the ground plans from its feet cell.

    python3 deploy/runner/hole_drop_test.py            # exit 0 = PASS

Flat server: a 1x1 hole three deep, a raw_iron at its bottom, the bot placed on the rim with its
hitbox straddling the edge, `@get raw_iron 1`. PASS = raw_iron in the inventory within the window.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 800, 300
GROUND = -61                 # flat world surface block; feet at GROUND+1
DEPTH = 3
WINDOW_S = 60

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
    rcon(f"forceload add {X-8} {Z-8} {X+8} {Z+8}"); time.sleep(1)
    rcon(f"kill @e[type=item,x={X},y={GROUND},z={Z},distance=..30]")
    rcon(f"fill {X-8} {GROUND+1} {Z-8} {X+8} {GROUND+6} {Z+8} minecraft:air")
    rcon(f"fill {X-8} {GROUND-DEPTH} {Z-8} {X+8} {GROUND} {Z+8} minecraft:stone")   # solid ground
    r = rcon(f"fill {X} {GROUND-DEPTH+1} {Z} {X} {GROUND} {Z} minecraft:air")          # the hole
    time.sleep(1)
    ok = rcon(f"execute if block {X} {GROUND} {Z} minecraft:air")
    print(f"scene: hole fill -> {r[:40]!r}; hole present -> {ok[:30]!r}")
    if "passed" not in ok:
        print("FAIL: the hole was not built (chunk not loaded?)"); return 2
    # the rim: hitbox 0.6 wide, centre 0.25 west of the hole's edge -> straddles it
    rcon(f"tp {BOT} {X-0.25} {GROUND+1} {Z+0.5} 270 30")
    rcon(f"clear {BOT}")
    time.sleep(1)
    r = rcon(f'summon minecraft:item {X+0.5} {GROUND-DEPTH+1.2} {Z+0.5} '
             f'{{Item:{{id:"minecraft:raw_iron",count:1}},PickupDelay:0s}}')
    time.sleep(2)
    n = rcon(f"execute if entity @e[type=item,x={X},y={GROUND-DEPTH+1},z={Z},distance=..2]")
    gs = py4j("gs")
    print(f"bot on the rim at {gs['pos']}; raw_iron {DEPTH} down the hole (summon: {r[:40]!r}; present: {n[:40]!r}). @get raw_iron 1")
    py4j("cmd", c="@get raw_iron 1")
    t0 = time.time(); seen = set(); got = False
    bad = {"Failed exploring": 0, "Failed to pick up drop": 0, "not getting closer": 0,
           "cost more than its budget": 0}
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        gs = py4j("gs"); pos = gs["pos"]
        ch = [c for c in py4j("chat", n=14)["chat"] if c not in seen]; seen.update(ch)
        for k in bad:
            bad[k] += sum(1 for c in ch if k in c)
        got = any("raw_iron" in i for i in py4j("inv")["ids"])
        note = [c for c in ch if any(w in c for w in ("NO ROUTE", "Failed", "budget", "closer", "gave up", "arrived"))]
        try:
            chain = py4j("task")["chain"][-90:]
        except Exception:
            chain = "?"
        print(f"  t={time.time()-t0:.0f}s pos={pos} iron={got} | {chain}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    try:
        st = py4j("stats")["s"]
        tok = [t for t in st.split() if t.startswith(("dropBlock=", "pdFnBuild=", "pdNearBuild=",
                                                          "startSnapRefused=", "navStall=", "plan="))]
        print(f"  counters: {' '.join(tok)}")
    except Exception:
        pass
    rcon(f"forceload remove {X-8} {Z-8} {X+8} {Z+8}")
    flaws = {k: v for k, v in bad.items() if v}
    print(f"result: raw_iron={got} flaws={flaws or 'none'}")
    if got and not flaws:
        print("PASS: stepped off the rim and took the drop from the hole"); return 0
    print("FAIL"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
