#!/usr/bin/env python3
"""Bench: a shield crafted from logs and an iron ingot, planks made on the way.

The 22:10 run spent its last four minutes at a crafting table on a shield that would not craft:
"GRIDCLEAR by=CraftInTableTask for=[[shield]] slotItem=spruce_log" nine times, "MOVEMISMATCH
holding=iron_ingot want=[stick]", "holding=spruce_planks want=[iron_ingot]", "holding=iron_ingot
want=[planks]", the pack going 41 -> 28 as the grid took and gave back. The shape: the shield needs
six planks and an ingot, the planks come from a log crafted IN THE SAME TABLE, and the two crafts
take turns clearing each other's grid.

    python3 deploy/runner/shield_craft_test.py            # exit 0 = PASS

Flat server: the bot on open ground with a crafting table, two spruce logs and one iron ingot in
the pack, `@get shield 1`. PASS = a shield in the pack within the window.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 2400, 300
GROUND = -61
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
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",8))]}
elif op=="task": out={"chain": str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-300:]}
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


def has_shield():
    try:
        return any("shield" in i for i in py4j("inv")["ids"])
    except Exception:
        return False


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
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {X-12} {Z-12} {X+12} {Z+12}"); time.sleep(1)
    rcon(f"fill {X-8} {GROUND+1} {Z-8} {X+8} {GROUND+5} {Z+8} minecraft:air")
    rcon(f"fill {X-8} {GROUND} {Z-8} {X+8} {GROUND} {Z+8} minecraft:grass_block")
    rcon(f"spawnpoint {BOT} {X} {GROUND+1} {Z}")
    rcon(f"tp {BOT} {X+0.5} {GROUND+1} {Z+0.5}")
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} minecraft:crafting_table 1")
    rcon(f"give {BOT} minecraft:spruce_log 2")
    rcon(f"give {BOT} minecraft:iron_ingot 1")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10 true")
    time.sleep(2)
    gs = py4j("gs")
    print(f"bot at {gs['pos']} with a crafting table, two spruce logs and an iron ingot; @get shield 1")
    py4j("cmd", c="@get shield 1")
    t0 = time.time(); seen = set(); got = False
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        ch = [c for c in py4j("chat", n=10)["chat"] if c not in seen]; seen.update(ch)
        note = [c for c in ch if any(w in c for w in ("MOVEMISMATCH", "GRIDCLEAR", "CURSORBACK", "stuck"))]
        try:
            chain = py4j("task")["chain"][-90:]
        except Exception:
            chain = "?"
        got = has_shield()
        print(f"  t={time.time()-t0:.0f}s shield={got} | {chain}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    rcon(f"forceload remove {X-12} {Z-12} {X+12} {Z+12}")
    print(f"result: shield={got} took={time.time()-t0:.0f}s")
    if got:
        print("PASS: the shield was crafted, planks made on the way"); return 0
    print("FAIL: no shield within the window"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
