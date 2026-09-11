#!/usr/bin/env python3
"""G34 bench: a dig target that needs WALKING first, then DIGGING — the break run must be
executed by the navigator, not handed to the physics engine.

The recorded @gamer run (2026-09-11 12:03) stood two minutes over iron ore nine blocks down a
slope: the plan had the digs, but every break run went to the physics engine with the ore as its
target ("Mining aborted: ticks=1 dist=5.19", "walking dead-ends -> physics owns the rest"). The
dig bench never caught it because there the first break is right under the feet.

    python3 deploy/runner/dig_terrace_test.py            # exit 0 = PASS

Builds a dirt terrace stepping down 6 blocks over 12, with a stone slab 3 under the last step
(9 below the start). Bot on top with an iron pickaxe. `@get cobblestone 1`: walk the terrace,
dig the last 3. PASS = cobblestone within the window, no "walking dead-ends", no shimmy.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 760, 300
TOP = -52                       # feet at the start
STEPS = 6                       # terrace steps, 2 blocks long, 1 block down each
WINDOW_S = 120

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


def ensure_flat_server():
    if BOT not in rcon("list"):
        py4j("connect", ip="test-server"); t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
    return BOT in rcon("list")


def main():
    if not ensure_flat_server():
        print("FAIL: never joined test-server"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    for f in ("fireReleaseNeedsFire", "mineTheBlockInTheWay", "executorYieldsAimToMiner"):
        py4j("chatcmd", c=f";settings {f} true"); time.sleep(0.3)
    rcon(f"gamemode survival {BOT}")
    x0 = X - 2; x1 = X + 2 * STEPS + 4
    rcon(f"fill {x0-3} {TOP-12} {Z-4} {x1+3} {TOP+8} {Z+4} minecraft:air")
    # terrace: step i occupies x in [X+2i, X+2i+1], top block at y = TOP-1-i (feet at TOP-i)
    for i in range(STEPS + 1):
        top = TOP - 1 - i
        rcon(f"fill {X+2*i} {TOP-12} {Z-2} {X+2*i+1} {top} {Z+2} minecraft:dirt")
    # the lowest step continues 2 more blocks, then a stone slab 3 under it (the "ore")
    last_top = TOP - 1 - STEPS
    rcon(f"fill {X+2*STEPS+2} {TOP-12} {Z-2} {X+2*STEPS+3} {last_top} {Z+2} minecraft:dirt")
    sx = X + 2 * STEPS + 2
    rcon(f"fill {sx} {last_top-3} {Z} {sx+1} {last_top-3} {Z} minecraft:stone")
    # nothing else stone nearby: the start column is dirt all the way down
    time.sleep(1)
    rcon(f"tp {BOT} {X+0.5} {TOP} {Z+0.5}")
    rcon(f"clear {BOT}"); rcon(f"give {BOT} minecraft:iron_pickaxe")
    time.sleep(2)
    gs = py4j("gs"); y0 = float(gs["pos"].split(",")[1])
    stone_y = last_top - 3
    print(f"on the terrace top at y={y0:.0f}; stone at ({sx},{stone_y},{Z}), {y0-stone_y:.0f} below and 14 along. @get cobblestone 1")
    py4j("cmd", c="@get cobblestone 1")
    t0 = time.time(); seen = set(); got = False; deadends = 0; shimmies = 0; aborts = 0; lowest = y0
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        gs = py4j("gs"); pos = gs["pos"]; y = float(pos.split(",")[1]); lowest = min(lowest, y)
        ch = [c for c in py4j("chat", n=12)["chat"] if c not in seen]; seen.update(ch)
        deadends += sum(1 for c in ch if "dead-ends" in c or "handing over" in c)
        shimmies += sum(1 for c in ch if "triggering shimmy" in c)
        aborts += sum(1 for c in ch if "Mining aborted" in c)
        got = any("cobblestone" in i for i in py4j("inv")["ids"])
        note = [c for c in ch if any(w in c for w in ("at the dig", "dead-ends", "Mining", "re-planning", "shimmy", "hidden"))]
        print(f"  t={time.time()-t0:.0f}s pos={pos} lowestY={lowest:.0f} cobble={got}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    print(f"result: cobblestone={got} lowestY={lowest:.0f} (start {y0:.0f}) physics-handoffs={deadends} "
          f"shimmies={shimmies} mining-aborts={aborts}")
    if got and deadends == 0 and shimmies == 0:
        print("PASS: walked the terrace and dug to the stone, navigator-owned"); return 0
    if got:
        print("FAIL (soft): got there, but via a physics hand-off or a shimmy"); return 1
    print("FAIL: never reached the stone"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
