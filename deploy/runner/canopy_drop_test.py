#!/usr/bin/env python3
"""G42 bench: a drop lying on top of a leaf canopy four blocks up is reached by a TOWER built by
PillarTask, never by a chain of MovementPillar steps that time out one after another.

The 14:00 recorded run (2026-09-11) lost four minutes under a spruce: a log lay on the canopy,
the navigator's leg went to the MovementQueue as "9 movement(s) ... CLIMB+5", and the ported
MovementPillar reported "step 2 has taken too long (126 ticks, expected 25)" eleven times under
open sky, two blocks placed in all. A planned pillar run is now cut out of the queue leg and handed
to PillarTask, the primitive that already clears pit_escape, nav_wall2 and drop_ledge.

    python3 deploy/runner/canopy_drop_test.py            # exit 0 = PASS

Flat server: a 7x7 oak-leaf canopy one block thick, four above the ground, a raw_iron on top of
it, the bot three blocks outside the canopy's edge with 8 cobblestone, `@get raw_iron 1`.
PASS = raw_iron in the inventory within the window and zero "has taken too long" lines.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 760, 300
GROUND = -61                 # flat world surface block; feet at GROUND+1
CANOPY_Y = GROUND + 4        # the leaf layer; feet on it at CANOPY_Y+1
HALF = 3                     # canopy spans X-HALF..X+HALF
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
    # A creeper that wandered in from a bench that set the difficulty to normal sent the bot fleeing
    # thirty blocks and the parent task wandering; this course is about the canopy, not mobs.
    rcon("difficulty peaceful")
    rcon(f"forceload add {X-10} {Z-8} {X+10} {Z+8}"); time.sleep(1)
    rcon(f"kill @e[type=item,x={X},y={GROUND},z={Z},distance=..30]")
    rcon(f"fill {X-10} {GROUND+1} {Z-8} {X+10} {CANOPY_Y+8} {Z+8} minecraft:air")
    rcon(f"fill {X-10} {GROUND} {Z-8} {X+10} {GROUND} {Z+8} minecraft:grass_block")
    r = rcon(f"fill {X-HALF} {CANOPY_Y} {Z-HALF} {X+HALF} {CANOPY_Y} {Z+HALF} minecraft:oak_leaves[persistent=true]")
    time.sleep(1)
    ok = rcon(f"execute if block {X} {CANOPY_Y} {Z} minecraft:oak_leaves")
    print(f"scene: canopy fill -> {r[:40]!r}; present -> {ok[:30]!r}")
    if "passed" not in ok:
        print("FAIL: the canopy was not built (chunk not loaded?)"); return 2
    rcon(f"tp {BOT} {X-HALF-3+0.5} {GROUND+1} {Z+0.5} 270 0")   # 3 blocks west of the canopy edge, facing it
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} minecraft:cobblestone 8")
    time.sleep(1)
    r = rcon(f'summon minecraft:item {X+0.5} {CANOPY_Y+1.2} {Z+0.5} '
             f'{{Item:{{id:"minecraft:raw_iron",count:1}},PickupDelay:0s}}')
    time.sleep(2)
    n = rcon(f"execute if entity @e[type=item,x={X},y={CANOPY_Y+1},z={Z},distance=..3]")
    gs = py4j("gs")
    print(f"bot at {gs['pos']} with 8 cobblestone; raw_iron on a leaf canopy {CANOPY_Y-GROUND} up "
          f"(summon: {r[:40]!r}; present: {n[:40]!r}). @get raw_iron 1")
    py4j("cmd", c="@get raw_iron 1")
    t0 = time.time(); seen = set(); got = False
    bad = {"has taken too long": 0, "Failed exploring": 0, "Failed to pick up drop": 0,
           "cost more than its budget": 0, "not getting closer": 0}
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        gs = py4j("gs"); pos = gs["pos"]
        ch = [c for c in py4j("chat", n=14)["chat"] if c not in seen]; seen.update(ch)
        for k in bad:
            bad[k] += sum(1 for c in ch if k in c)
        got = any("raw_iron" in i for i in py4j("inv")["ids"])
        note = [c for c in ch if any(w in c for w in ("Pillar", "CLIMB", "too long", "Mining", "NO ROUTE",
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
                                                          "pdFnOrphan=", "navStall=", "navBreak=",
                                                          "navPillarRuns="))]
        print(f"  counters: {' '.join(tok)}")
    except Exception:
        pass
    rcon(f"forceload remove {X-10} {Z-8} {X+10} {Z+8}")
    flaws = {k: v for k, v in bad.items() if v}
    print(f"result: raw_iron={got} flaws={flaws or 'none'}")
    if got and not flaws:
        print("PASS: towered up to the canopy and took the drop, no pillar step timed out"); return 0
    print("FAIL"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
