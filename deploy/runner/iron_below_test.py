#!/usr/bin/env python3
"""G73/G74 bench: an iron ore seven blocks straight under the feet, in solid stone.

The 23:13 recording: the bot standing on top of an iron ore seven blocks down, "FastNavigator:
the search toward a goal 7 below spent its budget (6784 nodes, 252 ms) -- one more try with 4x" a
hundred times in seven minutes, the unstuck shimmy forty-eight times, nothing mined, the ore never
priced as anything but the nearest. Two things were wrong: the planner's estimate priced the
descent as a walk and opened a disc of surface cells forty blocks wide before the dug column could
be popped (G73: below a free fall the vertical term is a dig's worth of walks per block), and the
drive re-armed the same route for ever (G74: three give-ups in a row make the block unreachable
for the chooser).

    python3 deploy/runner/iron_below_test.py            # exit 0 = PASS

Flat server: a stone slab, an iron ore seven blocks under the bot, a stone pickaxe in hand, `@get
raw_iron 1`. PASS = raw iron in the pack within the window (the bot dug down to it); the counters
say how the planner and the drive behaved (navBudgetBoost, pdRouteRefused).
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 2600, 300
GROUND = -50          # a raised stone slab standing on the flat floor
DEEP = 7
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
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",8))]}
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


def has_iron():
    try:
        return any("raw_iron" in i for i in py4j("inv")["ids"])
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
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {X-12} {Z-12} {X+12} {Z+12}"); time.sleep(1)
    rcon(f"fill {X-8} {GROUND-DEEP-2} {Z-8} {X+8} {GROUND} {Z+8} minecraft:stone")
    rcon(f"fill {X-8} {GROUND+1} {Z-8} {X+8} {GROUND+5} {Z+8} minecraft:air")
    ox, oy, oz = X, GROUND - DEEP, Z
    rcon(f"setblock {ox} {oy} {oz} minecraft:iron_ore")
    time.sleep(0.5)
    probe = rcon(f"execute if block {ox} {oy} {oz} minecraft:iron_ore")
    if "passed" not in probe.lower():
        print(f"FAIL: the ore was not placed (probe={probe!r})"); return 2
    rcon(f"spawnpoint {BOT} {X} {GROUND+1} {Z}")
    rcon(f"tp {BOT} {X+0.5} {GROUND+1} {Z+0.5}")
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} minecraft:stone_pickaxe 1")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10")
    time.sleep(2)
    try:
        py4j("stats")   # reads and resets, so the counters below are this run's alone
    except Exception:
        pass
    gs = py4j("gs")
    print(f"bot at {gs['pos']} on stone with a stone pickaxe; iron ore {DEEP} blocks straight down at ({ox},{oy},{oz}); @get raw_iron 1")
    py4j("cmd", c="@get raw_iron 1")
    t0 = time.time(); got = False; seen = set()
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        gs = py4j("gs")
        chat = [c for c in py4j("chat", n=8)["chat"] if c not in seen]; seen.update(chat)
        note = [c for c in chat if any(w in c for w in ("spent its budget", "giving the route", "unreachable", "at the dig", "NO ROUTE"))]
        got = has_iron()
        print(f"  t={time.time()-t0:.0f}s pos={gs['pos']} raw_iron={got}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    tok = "?"
    try:
        st = py4j("stats")["s"]
        tok = " ".join(t for t in st.split() if t.startswith(("navBudgetBoost=", "pdRouteRefused=", "navStall=", "pdReach=", "pdDig=")))
    except Exception:
        pass
    rcon(f"forceload remove {X-12} {Z-12} {X+12} {Z+12}")
    print(f"result: raw_iron={got} took={time.time()-t0:.0f}s counters: {tok}")
    if got:
        print("PASS: dug down to the ore under the feet"); return 0
    print("FAIL: the ore seven blocks down was never reached"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
