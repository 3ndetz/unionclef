#!/usr/bin/env python3
"""G79 bench: a furnace to place while standing at the bottom of a one-wide shaft.

The 00:20 run: the bot at the bottom of the shaft it had dug (1454,57,-802), a smoker to place for
the porkchops, "Failed placing, wandering and trying again" three times at 45 s each, "Failed
exploring" (a wander has nowhere to go in a shaft), the unstuck shimmy -- six minutes. In a 1x1
shaft the only air cells are the two the body occupies, so there is nowhere to place; the niche
carve (G27) exists for that and something between it and the placement still failed.

    python3 deploy/runner/shaft_place_test.py            # exit 0 = PASS

Flat server: a stone slab, the bot at the bottom of a one-wide shaft three deep with a furnace
item, raw beef, coal and a pickaxe in the pack, `@get cooked_beef 1`. PASS = cooked beef in the
pack within the window (the furnace was placed somewhere reachable and used).
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 2800, 300
GROUND = -50
DEPTH = 3
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


def has_cooked():
    try:
        return any("cooked_beef" in i for i in py4j("inv")["ids"])
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
    rcon(f"fill {X-8} {GROUND-DEPTH-2} {Z-8} {X+8} {GROUND} {Z+8} minecraft:stone")
    rcon(f"fill {X-8} {GROUND+1} {Z-8} {X+8} {GROUND+5} {Z+8} minecraft:air")
    # the shaft: one wide, DEPTH deep, the bot at its bottom
    rcon(f"fill {X} {GROUND-DEPTH+1} {Z} {X} {GROUND} {Z} minecraft:air")
    time.sleep(0.5)
    probe = rcon(f"execute if block {X} {GROUND-DEPTH+1} {Z} minecraft:air")
    wall = rcon(f"execute if block {X+1} {GROUND-DEPTH+1} {Z} minecraft:stone")
    if "passed" not in probe.lower() or "passed" not in wall.lower():
        print(f"FAIL: the shaft was not built (probe={probe!r}, wall={wall!r})"); return 2
    rcon(f"spawnpoint {BOT} {X} {GROUND-DEPTH+1} {Z}")
    rcon(f"tp {BOT} {X+0.5} {GROUND-DEPTH+1} {Z+0.5}")
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} minecraft:stone_pickaxe 1")
    rcon(f"give {BOT} minecraft:furnace 1")
    rcon(f"give {BOT} minecraft:beef 1")
    rcon(f"give {BOT} minecraft:coal 2")
    rcon(f"give {BOT} minecraft:cobblestone 8")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10")
    time.sleep(2)
    gs = py4j("gs")
    sx, sy = (float(v) for v in gs["pos"].split(",")[:2])
    if abs(sx - (X + 0.5)) > 0.6 or sy > GROUND - DEPTH + 1.5:
        print(f"FAIL: the bot is not at the shaft's bottom (pos={gs['pos']}); the scene is wrong, not the bot"); return 2
    print(f"bot at {gs['pos']}, the bottom of a one-wide shaft {DEPTH} deep, a furnace, beef and coal in the pack; @get cooked_beef 1")
    py4j("cmd", c="@get cooked_beef 1")
    t0 = time.time(); got = False; seen = set()
    while time.time() - t0 < WINDOW_S:
        time.sleep(4)
        gs = py4j("gs")
        chat = [c for c in py4j("chat", n=8)["chat"] if c not in seen]; seen.update(chat)
        note = [c for c in chat if any(w in c for w in ("Failed placing", "niche", "Failed exploring", "Pillar", "placing", "Placing"))]
        try:
            chain = py4j("task")["chain"][-70:]
        except Exception:
            chain = "?"
        got = has_cooked()
        print(f"  t={time.time()-t0:.0f}s pos={gs['pos']} cooked_beef={got} | {chain}"
              + (" | " + " || ".join(x[-60:] for x in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    rcon(f"forceload remove {X-12} {Z-12} {X+12} {Z+12}")
    print(f"result: cooked_beef={got} took={time.time()-t0:.0f}s")
    if got:
        print("PASS: the furnace was placed from the shaft's bottom and used"); return 0
    print("FAIL: no cooked beef within the window -- the furnace was never placed"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
