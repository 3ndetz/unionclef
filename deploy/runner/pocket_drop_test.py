#!/usr/bin/env python3
"""G89 bench: a drop in a buried pocket two blocks down and one aside, under two blocks of dirt.

Round 46 (2026-09-14): the bot on a grass block at (-299,69,-1102), its cobblestone at
(-299.13,67,-1101.13) in a pocket one column west and two down, dirt in the two cells above the
pocket; the navigator reported "arrived (2.3)" with the body two blocks above the drop, then
"no progress" x30, "goal unreachable -- no progress in 14s (dist 2.2), yielding" x12, "Drop not
getting closer" x10: seven minutes over a drop it could have dug to in ten seconds. This scene is
that geometry: a pocket whose own cell cannot be stood in (dirt overhead), open to one side
through a two-tall cave cell, the bot on the surface beside it with a pickaxe.

    python3 deploy/runner/pocket_drop_test.py          # exit 0 = PASS (a stone pickaxe in hand)
    python3 deploy/runner/pocket_drop_test.py --nopick # no pickaxe: planks, sticks and a table only

PASS = the cobblestone is in the pack within the window (the bot digs down or sideways into the
pocket and collects it). With --nopick the round-46 trap is the subject: the pickup's "pickaxe
first" diversion used to ask for a STONE pickaxe -- three cobblestone, the very drop -- and the
chain closed on itself for seven minutes; now it asks for a WOODEN one, which the pack can make.
"""
import functools, json, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
BX, BY, BZ = 2900, -60, 300          # the bot stands on (BX,BY,BZ); the flat world's floor is at BY
PX, PZ = BX - 1, BZ                  # the pocket column (the drop's cell is (PX, BY-2, PZ))
NOPICK = "--nopick" in sys.argv
WINDOW_S = 100 if NOPICK else 75
PINS = [kv.split("=", 1) for kv in os.environ.get("UC_PINS", "").split(",") if "=" in kv]

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
def me():
    s=dict(mc.getGameState().get("self") or {}); return s.get("pos")
if op=="state": out={"inGame":mc.inGame(),"pos":me(),"busy":mc.hasActiveTask()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chatcmd": mc.ChatMessage(req["c"]); out={"ok":True}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",10))]}
elif op=="stats": out={"s": str(mc.placeStats())}
elif op=="task": out={"chain": str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-260:]}
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


def has_cobble():
    try:
        return any("cobblestone" in i for i in py4j("inv")["ids"])
    except Exception:
        return False


def build():
    rcon(f"fill {BX-7} {BY+1} {BZ-7} {BX+7} {BY+8} {BZ+7} minecraft:air")
    rcon(f"fill {BX-7} {BY-5} {BZ-7} {BX+7} {BY} {BZ+7} minecraft:stone")        # solid ground
    rcon(f"fill {BX-7} {BY} {BZ-7} {BX+7} {BY} {BZ+7} minecraft:grass_block")    # the surface
    rcon(f"setblock {PX} {BY-2} {PZ} minecraft:air")                             # the pocket (the drop's cell)
    rcon(f"setblock {PX} {BY-1} {PZ} minecraft:dirt")                            # dirt over it...
    rcon(f"setblock {PX} {BY} {PZ} minecraft:dirt")                              # ...two deep
    rcon(f"fill {PX} {BY-2} {PZ-1} {PX} {BY-1} {PZ-1} minecraft:air")            # a two-tall cave cell beside the pocket
    rcon(f"kill @e[type=item,x={PX},y={BY},z={PZ},distance=..20]")


def main():
    if BOT not in rcon("list"):
        print("connecting to test-server")
        py4j("connect", ip="test-server")
        t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    py4j("chatcmd", c=";settings verboseDebugLogging true"); time.sleep(0.4)
    for k, v in PINS:
        py4j("chatcmd", c=f";settings {k} {v}"); time.sleep(0.3); print(f"pinned {k}={v}")
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {BX-12} {BZ-12} {BX+12} {BZ+12}"); time.sleep(1)
    build()
    time.sleep(1)
    pocket = rcon(f"execute if block {PX} {BY-2} {PZ} minecraft:air")
    lid = rcon(f"execute if block {PX} {BY} {PZ} minecraft:dirt")
    if "passed" not in pocket.lower() or "passed" not in lid.lower():
        print(f"FAIL: the scene was not built (pocket={pocket!r}, lid={lid!r})"); return 2
    rcon(f"spawnpoint {BOT} {BX} {BY+1} {BZ}")
    rcon(f"tp {BOT} {BX+0.5} {BY+1} {BZ+0.5}")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10 true")
    rcon(f"effect give {BOT} minecraft:saturation 5 5 true")
    rcon(f"clear {BOT}")
    if NOPICK:
        rcon(f"give {BOT} minecraft:oak_planks 6")
        rcon(f"give {BOT} minecraft:stick 4")
        rcon(f"give {BOT} minecraft:crafting_table 1")
    else:
        rcon(f"give {BOT} minecraft:stone_pickaxe 1")
    rcon(f"give {BOT} minecraft:dirt 8")
    rcon(f"summon minecraft:item {PX+0.5} {BY-1.8} {PZ+0.5} {{Item:{{id:\"minecraft:cobblestone\",count:1}},PickupDelay:0}}")
    time.sleep(2)
    py4j("stats")
    start = py4j("state")
    sx, sy, sz = (float(v) for v in start["pos"].split(","))
    if abs(sx - (BX + 0.5)) > 0.6 or abs(sz - (BZ + 0.5)) > 0.6:
        print(f"FAIL: the bot is not at the start (pos={start['pos']})"); return 2
    item = rcon(f"execute if entity @e[type=item,x={PX+0.5},y={BY-2},z={PZ+0.5},distance=..1.5]")
    if "passed" not in item.lower():
        print(f"FAIL: the drop is not in the pocket ({item!r})"); return 2
    print(f"bot at {start['pos']} on grass" + (" with planks, sticks and a table, NO pickaxe" if NOPICK else " with a stone pickaxe")
          + f", a cobblestone in a buried pocket at ({PX},{BY-2},{PZ}); @get cobblestone 1")
    py4j("cmd", c="@get cobblestone 1")
    t0 = time.time(); seen = set(); got = False
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        s = py4j("state")
        chat = [m for m in py4j("chat", n=8)["chat"] if m not in seen]; seen.update(chat)
        note = [m for m in chat if any(w in m for w in ("arrived", "Drop not getting", "unreachable", "no progress", "at the dig", "Mining done", "failed to break", "snap", "yield", "pickaxe first"))]
        try:
            chain = py4j("task")["chain"][-100:]
        except Exception:
            chain = "?"
        got = has_cobble()
        print(f"  t={time.time()-t0:.0f}s pos={s['pos']} cobble={got} | {chain}"
              + (" | " + " || ".join(m[-70:] for m in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    py4j("chatcmd", c=";settings verboseDebugLogging false")
    rcon(f"forceload remove {BX-12} {BZ-12} {BX+12} {BZ+12}")
    print(f"result: cobble={got} took={time.time()-t0:.0f}s nopick={NOPICK}")
    if got:
        print("PASS: the bot " + ("made a wooden pickaxe, " if NOPICK else "") + "dug into the pocket and collected the drop"); return 0
    print(f"FAIL: the cobblestone was not collected within {WINDOW_S}s"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
