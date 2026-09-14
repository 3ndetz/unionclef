#!/usr/bin/env python3
"""G88 bench: a settled drop that falls into a shaft after the route to it was armed must be
followed, not stood over.

Round 44 (2026-09-14): the cobblestone the bot had just mined fell three blocks into a one-wide
shaft beside it. GetToDropTask kept the cell it was built with ("settled drops do not move"), the
drive stood in that cell -- "atGoal=146 ... @GetToDropTask@block(-342,77,-548) x61", zero-length
plans -- and the pickup timed out on the same drop eleven times: seven minutes on one spot. Three
blocks is under the drive's four-block "goal moved far" bar, so the armed route was never
re-planned either. Now the cell follows the drop the moment it rests again, and the drive
re-plans a route whose armed cell no longer satisfies the goal (baritone: the path's end must
stay in the goal).

    python3 deploy/runner/drop_fall_test.py          # exit 0 = PASS

Flat server: a one-wide shaft three deep with a stone lid; a cobblestone item resting on the lid
ten blocks from the bot. `@get cobblestone 1`; a second after the task starts (the route is armed
for the lid's cell) the lid is pulled and the item falls to the shaft's floor, three below.
PASS = the cobblestone is in the pack within the window (the bot re-targets, drops into the shaft,
collects it).
"""
import functools, json, math, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
BX, BY, BZ = 2500, -60, 300          # the bot's start; the flat world's floor is at BY
SX, SZ = BX + 24, BZ                 # the shaft column (a sprint covers ten blocks before the poll)
WINDOW_S = 60
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


def counters():
    st = py4j("stats")["s"]
    out = {}
    for t in st.split():
        if t.startswith(("goalLeft=", "dropBlock=", "navStall=")):
            k, v = t.split("=", 1); out[k] = v
    return out


def build():
    rcon(f"fill {BX-4} {BY+1} {BZ-6} {SX+6} {BY+8} {BZ+6} minecraft:air")
    rcon(f"fill {BX-4} {BY-4} {BZ-6} {SX+6} {BY} {BZ+6} minecraft:stone")        # solid ground
    rcon(f"fill {SX} {BY-2} {SZ} {SX} {BY-1} {SZ} minecraft:air")                # the shaft, two cells...
    rcon(f"setblock {SX} {BY} {SZ} minecraft:stone")                             # ...under a stone lid
    rcon(f"kill @e[type=item,x={SX},y={BY},z={SZ},distance=..20]")


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
    for k, v in PINS:
        py4j("chatcmd", c=f";settings {k} {v}"); time.sleep(0.3); print(f"pinned {k}={v}")
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {BX-12} {BZ-12} {SX+12} {BZ+12}"); time.sleep(1)
    build()
    time.sleep(1)
    lid = rcon(f"execute if block {SX} {BY} {SZ} minecraft:stone")
    hole = rcon(f"execute if block {SX} {BY-1} {SZ} minecraft:air")
    if "passed" not in lid.lower() or "passed" not in hole.lower():
        print(f"FAIL: the scene was not built (lid={lid!r}, hole={hole!r})"); return 2
    rcon(f"spawnpoint {BOT} {BX} {BY+1} {BZ}")
    rcon(f"tp {BOT} {BX+0.5} {BY+1} {BZ+0.5}")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10 true")
    rcon(f"effect give {BOT} minecraft:saturation 5 5 true")
    rcon(f"clear {BOT}")
    rcon(f"summon minecraft:item {SX+0.5} {BY+1.2} {SZ+0.5} {{Item:{{id:\"minecraft:cobblestone\",count:1}},PickupDelay:0}}")
    time.sleep(2)
    counters()
    start = py4j("state")
    sx, sy, sz = (float(v) for v in start["pos"].split(","))
    if abs(sx - (BX + 0.5)) > 0.6 or abs(sz - (BZ + 0.5)) > 0.6:
        print(f"FAIL: the bot is not at the start (pos={start['pos']})"); return 2
    print(f"bot at {start['pos']}, a cobblestone on the lid of a shaft at ({SX},{BY+1},{SZ}); @get cobblestone 1")
    py4j("cmd", c="@get cobblestone 1")
    # Pull the lid once the bot has committed to the route -- moved three blocks, or 2.5 s in --
    # and while it is still well short of the shaft. Round 46 slept 1.2 s and found the bot 1.4
    # blocks from the shaft: a poll costs half a second and a sprint covers ten blocks in two.
    t_arm = time.time(); d_before = 99.0
    while time.time() - t_arm < 2.5:
        s = py4j("state")
        x, y, z = (float(v) for v in s["pos"].split(","))
        d_before = math.hypot(x - (SX + 0.5), z - (SZ + 0.5))
        if math.hypot(x - sx, z - sz) >= 3.0:
            break
        time.sleep(0.2)
    rcon(f"setblock {SX} {BY} {SZ} minecraft:air")      # the lid goes: the drop falls three blocks
    print(f"  lid pulled with the bot {d_before:.1f} blocks from the shaft")
    t0 = time.time(); seen = set(); got = False; c = {}
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        s = py4j("state")
        chat = [m for m in py4j("chat", n=8)["chat"] if m not in seen]; seen.update(chat)
        note = [m for m in chat if any(w in m for w in ("Drop not getting", "not getting closer", "unreachable", "no progress", "intoHole", "arrived"))]
        c = counters()
        got = has_cobble()
        print(f"  t={time.time()-t0:.0f}s pos={s['pos']} cobble={got} goalLeft={c.get('goalLeft','?')}"
              + (" | " + " || ".join(m[-70:] for m in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    rcon(f"forceload remove {BX-12} {BZ-12} {SX+12} {BZ+12}")
    print(f"result: cobble={got} took={time.time()-t0:.0f}s goalLeft={c.get('goalLeft','?')} lidPulledAt={d_before:.1f}")
    if d_before < 6.0:
        print("FAIL: the lid was pulled with the bot already near the shaft; the scene did not test a fallen drop (make the start further)"); return 2
    if got:
        print("PASS: the drop fell three blocks after the route was armed and the bot still collected it"); return 0
    print(f"FAIL: the cobblestone was not collected within {WINDOW_S}s"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
