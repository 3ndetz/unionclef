#!/usr/bin/env python3
"""G25 bench: a MINING target under the feet must be reached by DIGGING, not by standing on top
of it and asking for a route into your own cell forever.

Reproduces the 2026-09-11 recorded playthrough exactly (docs/BARITONE-GAPS.md G25): the nearest
stone is six blocks under the bot, under five layers of dirt. Before the fix: the bot walks to the
surface above the stone, "Time taken to execute" every 0.6 s, "Failed to mine block. Suggesting it
may be unreachable" + blacklist, six random shimmies. After: `@get cobblestone` digs down and mines.

    python3 deploy/runner/dig_reach_test.py            # exit 0 = PASS

PASS = cobblestone in the inventory within the window, AND no shimmy fired (a shimmy is the
random-dig recovery the fix is meant to make unnecessary). Needs uctest-server + tester1 up.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 620, 300                       # fresh ground, away from the other benches' leftovers
STONE_TOP = -58                       # stone y=-60..-58 (3 layers)
DIRT_TOP = -53                        # dirt  y=-57..-53 (5 layers) on top of the stone
FEET = DIRT_TOP + 1                   # the bot stands on the dirt: stone is 6 below the feet
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
elif op=="task": out={"chain": str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-400:]}
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
        # older entry points: fall back to the game-state inventory summary
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
    # The stand's saved tungsten.json can carry the old default of this flag (false = the attack
    # key is released every tick by MobDefenseChain, so nothing ever gets mined). Pin it.
    py4j("chatcmd", c=";settings fireReleaseNeedsFire true"); time.sleep(0.5)
    rcon(f"gamemode survival {BOT}")
    # sky above, a dirt cap over a stone slab, nothing else breakable-into-cobblestone nearby
    rcon(f"fill {X-5} {FEET} {Z-5} {X+5} {FEET+8} {Z+5} minecraft:air")
    rcon(f"fill {X-5} {STONE_TOP-2} {Z-5} {X+5} {STONE_TOP} {Z+5} minecraft:stone")
    rcon(f"fill {X-5} {STONE_TOP+1} {Z-5} {X+5} {DIRT_TOP} {Z+5} minecraft:dirt")
    time.sleep(1)
    rcon(f"tp {BOT} {X+0.5} {FEET} {Z+0.5}")
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} minecraft:iron_pickaxe")
    time.sleep(2)
    gs = py4j("gs")
    y0 = float(gs["pos"].split(",")[1])
    print(f"standing on the dirt cap at y={y0:.0f}; nearest stone is at y={STONE_TOP} (6 below). @get cobblestone 1")
    py4j("cmd", c="@get cobblestone 1")
    t0 = time.time(); seen = set(); got = False; shimmies = 0; unreach = 0; timetaken = 0
    lowest = y0
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        gs = py4j("gs"); pos = gs["pos"]; y = float(pos.split(",")[1]); lowest = min(lowest, y)
        ch = [c for c in py4j("chat", n=12)["chat"] if c not in seen]; seen.update(ch)
        shimmies += sum(1 for c in ch if "triggering shimmy" in c)
        unreach += sum(1 for c in ch if "may be unreachable" in c)
        timetaken += sum(1 for c in ch if "Time taken to execute" in c)
        ids = inv_ids()
        got = any("cobblestone" in i for i in ids)
        note = [c for c in ch if any(w in c for w in ("reach", "dig", "ining", "FastNavigator", "unreachable", "shimmy"))]
        print(f"  t={time.time()-t0:.0f}s pos={pos} lowestY={lowest:.0f} busy={py4j('state')['busy']} cobble={got}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    print(f"result: cobblestone={got} lowestY={lowest:.0f} (start {y0:.0f}) shimmies={shimmies} "
          f"unreachable-blacklists={unreach} 'Time taken' lines={timetaken}")
    if got and shimmies == 0:
        print("PASS: dug down to the stone and mined it, no shimmy"); return 0
    if got:
        print("FAIL (soft): mined it, but only after a shimmy fired — the recovery is still luck"); return 1
    print("FAIL: never mined the stone under its feet"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
