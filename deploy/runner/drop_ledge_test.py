#!/usr/bin/env python3
"""G39 bench: a drop that lies two blocks up a ledge is collected by BUILDING or DIGGING up to it,
never chased into the ledge face and abandoned.

The recorded 2026-09-11 @gamer run lost 200 s here: raw iron on a ledge two blocks above the
bot, GetToEntityTask asking the physics engine (which cannot place or break), then "Walking
straight at it (navigation would not)", "Failed exploring" x12, "Drop has cost more than its
budget". A settled drop is now a block goal on its cell through the drive, whose escalation
hands the goal to FastNavigator (pillarUp / breakStair).

    python3 deploy/runner/drop_ledge_test.py            # exit 0 = PASS

Two phases on the flat server, each with a raw_iron summoned on top of a 2-high stone ledge
five blocks from the bot and `@get raw_iron 1`:
  A: a stone pickaxe and NO blocks   -> the planner must carve a stair (breakStair)
  B: 8 cobblestone and NO pickaxe    -> the planner must pillar (pillarUp)
PASS = raw_iron in the inventory in both phases within the window, zero "Failed exploring",
zero "Failed to pick up drop", zero "Walking straight at it".
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 740, 300
GROUND = -61                 # flat world surface block; feet at GROUND+1
LEDGE_H = 2                  # ledge top block at GROUND+LEDGE_H, feet on it at GROUND+LEDGE_H+1
WINDOW_S = 75

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


def build_scene():
    top = GROUND + LEDGE_H
    # The bot may be hundreds of blocks away (the previous bench's arena): an unloaded chunk makes
    # every fill fail silently and the course then tests flat ground. Load the area first.
    rcon(f"forceload add {X-8} {Z-6} {X+12} {Z+6}")
    time.sleep(1)
    rcon(f"kill @e[type=item,x={X},y={GROUND},z={Z},distance=..30]")
    rcon(f"fill {X-8} {GROUND+1} {Z-6} {X+12} {top+8} {Z+6} minecraft:air")
    rcon(f"fill {X-8} {GROUND} {Z-6} {X+12} {GROUND} {Z+6} minecraft:grass_block")
    # the ledge: a solid stone step LEDGE_H high, from x=X+3 to the east edge, full width
    r = rcon(f"fill {X+3} {GROUND+1} {Z-6} {X+12} {top} {Z+6} minecraft:stone")
    time.sleep(1)
    ok = rcon(f"execute if block {X+5} {top} {Z} minecraft:stone")
    print(f"  scene: ledge fill -> {r[:40]!r}; ledge present -> {ok[:30]!r}")
    if "passed" not in ok:
        raise RuntimeError("the ledge was not built (chunk not loaded?)")


def phase(name, gear):
    build_scene()
    top = GROUND + LEDGE_H
    rcon(f"tp {BOT} {X-1.5} {GROUND+1} {Z+0.5} 270 0")   # facing east, toward the ledge
    rcon(f"clear {BOT}")
    for it in gear:
        rcon(f"give {BOT} {it}")
    time.sleep(1)
    r = rcon(f'summon minecraft:item {X+5.5} {top+1.2} {Z+0.5} '
             f'{{Item:{{id:"minecraft:raw_iron",count:1}},PickupDelay:0s}}')
    time.sleep(2)
    n = rcon(f"execute if entity @e[type=item,x={X+5},y={top+1},z={Z},distance=..3]")
    gs = py4j("gs")
    print(f"[{name}] bot at {gs['pos']} with {gear}; raw_iron on the ledge {LEDGE_H} up, 5 blocks east "
          f"(summon: {r[:40]!r}; present: {n[:40]!r}). @get raw_iron 1")
    py4j("cmd", c="@get raw_iron 1")
    t0 = time.time(); seen = set(); got = False
    bad = {"Failed exploring": 0, "Failed to pick up drop": 0, "Walking straight at it": 0,
           "cost more than its budget": 0, "not getting closer": 0}
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        gs = py4j("gs"); pos = gs["pos"]
        ch = [c for c in py4j("chat", n=14)["chat"] if c not in seen]; seen.update(ch)
        for k in bad:
            bad[k] += sum(1 for c in ch if k in c)
        got = any("raw_iron" in i for i in py4j("inv")["ids"])
        note = [c for c in ch if any(w in c for w in ("Pillar", "Mining", "NO ROUTE", "arrived",
                                                       "Failed", "budget", "closer", "gave up"))]
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
                                                          "snapSelfRefused=", "pdFnOrphan=",
                                                          "navStall=", "navBreak="))]
        print(f"  counters: {' '.join(tok)}")
    except Exception:
        pass
    time.sleep(1)
    return got, bad


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
    results = {}
    results["A stair"] = phase("A stair", ["minecraft:stone_pickaxe 1"])
    results["B pillar"] = phase("B pillar", ["minecraft:cobblestone 8"])
    ok = True
    for name, (got, bad) in results.items():
        flaws = {k: v for k, v in bad.items() if v}
        print(f"result [{name}]: raw_iron={got} flaws={flaws or 'none'}")
        ok = ok and got and not flaws
    rcon(f"forceload remove {X-8} {Z-6} {X+12} {Z+6}")
    if ok:
        print("PASS: both drops taken by building/digging up the ledge, no chase, no wander"); return 0
    print("FAIL"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
