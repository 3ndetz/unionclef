#!/usr/bin/env python3
"""G27/G28 bench: craft at a table while standing in a 1x1 pit.

Reproduces the six-minute stall of the 2026-09-11 recorded playthrough (docs/BARITONE-GAPS.md
G27, G28): the bot has a crafting table, cobblestone and sticks, wants a stone sword, and stands
at the bottom of a 1x1 shaft where every neighbour is stone. Before the fix it tried to place the
table INTO ITS OWN FEET CELL ("Failed placing" at the bot's position), wandered nowhere, and the
executor tried to bridge into its own cell every 30 s. After: it carves a niche in the wall (or
climbs) and crafts.

    python3 deploy/runner/pit_table_test.py            # exit 0 = PASS

PASS = stone_sword in the inventory within the window. Needs uctest-server + tester1 up.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 660, 300
FLOOR = -60                 # shaft floor block; the bot's feet stand at FLOOR+1
DEPTH = 4                   # wall height above the floor
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
elif op=="task": out={"chain": str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-300:]}
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


def inv_ids():
    try:
        return py4j("inv")["ids"]
    except Exception:
        gs = py4j("gs")
        return [str(x) for x in (gs.get("inventory") or [])]


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
    # a solid stone block with a 1-wide shaft in the middle; open sky above
    rcon(f"fill {X-3} {FLOOR+DEPTH+1} {Z-3} {X+3} {FLOOR+DEPTH+8} {Z+3} minecraft:air")
    rcon(f"fill {X-3} {FLOOR} {Z-3} {X+3} {FLOOR+DEPTH} {Z+3} minecraft:stone")
    rcon(f"fill {X} {FLOOR+1} {Z} {X} {FLOOR+DEPTH+1} {Z} minecraft:air")
    time.sleep(1)
    rcon(f"tp {BOT} {X+0.5} {FLOOR+1} {Z+0.5}")
    rcon(f"clear {BOT}")
    for it in ("minecraft:crafting_table 1", "minecraft:cobblestone 3", "minecraft:stick 2",
               "minecraft:stone_pickaxe 1"):
        rcon(f"give {BOT} {it}")
    time.sleep(2)
    gs = py4j("gs"); pos0 = gs["pos"]
    print(f"in a 1x1 pit at {pos0} (depth {DEPTH}) with a crafting table, 3 cobblestone, 2 sticks. @get stone_sword 1")
    py4j("cmd", c="@get stone_sword 1")
    t0 = time.time(); seen = set(); got = False; own_cell = 0; carve = 0; timeouts = 0
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        gs = py4j("gs"); pos = gs["pos"]
        ch = [c for c in py4j("chat", n=12)["chat"] if c not in seen]; seen.update(ch)
        own_cell += sum(1 for c in ch if "my own cell" in c or "own feet" in c)
        carve += sum(1 for c in ch if "niche" in c)
        timeouts += sum(1 for c in ch if "Bridge place aborted (TIMEOUT)" in c)
        got = any("stone_sword" in i for i in inv_ids())
        note = [c for c in ch if any(w in c for w in ("niche", "own cell", "own feet", "Failed placing", "TIMEOUT", "illar"))]
        try:
            chain = py4j("task")["chain"][-90:]
        except Exception:
            chain = "?"
        print(f"  t={time.time()-t0:.0f}s pos={pos} sword={got} | {chain}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    print(f"result: stone_sword={got} niche-carves={carve} own-cell-pillar-guards={own_cell} bridge-timeouts={timeouts}")
    if got:
        print("PASS: crafted at a table from inside the pit"); return 0
    print("FAIL: never crafted the sword"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
