#!/usr/bin/env python3
"""G62 bench: a tower is built out of whatever the pack holds, not out of eight named blocks.

The 16:30 recording (2026-09-12) stood two minutes at (277,110,-205) and two more at
(276,97,-189) on "Pillar: out of blocks -- nothing placeable in the hotbar" with a stack of
spruce planks in the pack, and a zombie villager finished the second stand. Three separate
places said "placeable": the planner counted every BlockItem anywhere in the pack (so it
promised the route), the hotbar selector took the first BlockItem in the HOTBAR only, and the
brain's restock knew eight vanilla blocks -- cobblestone, dirt, stone, netherrack, cobbled
deepslate, OAK planks, deepslate, andesite. Spruce planks are in none of them.

    python3 deploy/runner/pillar_stock_test.py          # exit 0 = PASS

Three phases in the same shaft on the flat server:
  1. the pack holds SPRUCE planks, deep in the inventory (not the hotbar) -- the old restock
     had no name for them and the old selector could not reach them;
  2. the hotbar holds only rubbish that cannot be a floor (torches, saplings, a bed, a chest)
     and the planks are again deep in the pack -- the old count promised a tower out of the
     rubbish and the tower then refused;
  3. the hotbar holds AZALEA and the planks are deep in the pack. Azalea is a BlockItem, so the
     old selector equipped it and the old count believed in it, but it does not place as a floor:
     the same recording stood seven and a half minutes at (259.5, 68, -539.5) reading
     "Pillar stuck ... tryFalse=238 placed=0 hand=minecraft:azalea" -- 245 jumps in its own cell,
     not one block laid, until the run ended.
PASS = the bot leaves the shaft in all three phases, and "out of blocks" / "Pillar stuck" is
never said.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
BX, BY, BZ = 240, -60, 240
DEPTH = 7
GOAL = (BX + 4, BY + DEPTH, BZ)
WINDOW_S = 90

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


def build_shaft():
    rcon(f"fill {BX-1} {BY} {BZ-1} {BX+1} {BY+DEPTH} {BZ+1} minecraft:stone")
    rcon(f"fill {BX} {BY+1} {BZ} {BX} {BY+DEPTH+1} {BZ} minecraft:air")
    gx, gy, gz = GOAL
    rcon(f"fill {gx-1} {gy} {gz-1} {gx+2} {gy} {gz+1} minecraft:stone")
    rcon(f"fill {gx-1} {gy+1} {gz-1} {gx+2} {gy+3} {gz+1} minecraft:air")


HOTBARS = {
    # nine stacks fill the hotbar, so the planks given afterwards land deep in the pack
    "plain": ("torch 16", "oak_sapling 16", "stick 16", "wheat_seeds 16",
              "bone 16", "string 16", "apple 16", "feather 16", "flint 16"),
    "junk": ("torch 16", "oak_sapling 16", "white_bed 1", "chest 1", "crafting_table 1",
             "wheat_seeds 16", "stick 16", "sand 16", "gravel 16"),
    # the live case: a BlockItem the old code equipped and could never place
    "azalea": ("azalea 16", "flowering_azalea 16", "torch 16", "oak_sapling 16", "short_grass 16",
               "poppy 16", "dandelion 16", "wheat_seeds 16", "stick 16"),
}


def phase(name, hotbar):
    rcon(f"kill @e[type=item,x={BX},y={BY},z={BZ},distance=..30]")
    build_shaft()
    time.sleep(1)
    rcon(f"tp {BOT} {BX + 0.5} {BY + 1} {BZ + 0.5}")
    rcon(f"clear {BOT}")
    time.sleep(0.5)
    # Fill the hotbar first so the planks land DEEP in the pack -- the old hotbar-only selector
    # could not reach them, and only the brain's restock can hand them over.
    for it in HOTBARS[hotbar]:
        rcon(f"give {BOT} minecraft:{it}")
    time.sleep(0.5)
    rcon(f"give {BOT} minecraft:spruce_planks 64")
    time.sleep(1.5)
    start = py4j("state")
    y0 = float(start["pos"].split(",")[1])
    gx, gy, gz = GOAL
    print(f"[{name}] in the shaft at {start['pos']}; spruce planks deep in the pack, "
          f"hotbar = {', '.join(h.split()[0] for h in HOTBARS[hotbar][:4])} ...")
    py4j("cmd", c=f"@goto {gx} {gy} {gz}")
    best = y0
    out = False
    seen = set()
    bad = {"out of blocks": 0, "Bridge place aborted": 0, "Pillar stuck": 0}
    t0 = time.time()
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        s = py4j("state")
        y = float(s["pos"].split(",")[1])
        best = max(best, y)
        chat = [c for c in py4j("chat", n=8)["chat"] if c not in seen]
        seen.update(chat)
        for k in bad:
            bad[k] += sum(1 for c in chat if k in c)
        note = [c for c in chat if any(w in c for w in ("illar", "out of blocks", "at the dig", "Wall too"))]
        print(f"  t={time.time()-t0:.0f}s pos={s['pos']} bestY={best:.0f}"
              + (" | " + " || ".join(x[-60:] for x in note) if note else ""))
        if y >= y0 + DEPTH - 1.5:
            out = True
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    try:
        st = py4j("stats")["s"]
        tok = [t for t in st.split() if t.startswith(("scaffold=", "navPillarRuns=", "pillarNoHeadroom="))]
        print(f"  counters: {' '.join(tok)}")
    except Exception:
        pass
    flaws = {k: v for k, v in bad.items() if v}
    print(f"[{name}] result: escaped={out} rise={best-y0:.1f} flaws={flaws or 'none'}")
    return out and not flaws


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
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {BX-16} {BZ-16} {BX+16} {BZ+16}"); time.sleep(1)
    ok = [phase("planks only", "plain"),
          phase("junk hotbar", "junk"),
          phase("azalea hotbar", "azalea")]
    rcon(f"forceload remove {BX-16} {BZ-16} {BX+16} {BZ+16}")
    if all(ok):
        print("PASS: towered out on spruce planks from deep in the pack, three hotbars"); return 0
    print("FAIL"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
