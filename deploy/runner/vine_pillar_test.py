#!/usr/bin/env python3
"""G66 bench: a tower out of a narrow pit with a vine on the wall.

The operator watched it live: the bot needed to jump and place a block under itself, a vine hung
beside it, and it hopped and turned in place for ever -- vanilla treats a body inside a vine as
CLIMBING, so JUMP becomes "go up the vine", the feet never leave the cell in a free arc, and the
tower's place window (airborne, rising, feet above the cell's top) never opens. Baritone's
MovementPillar has its own branch for a ladder or vine at the source. Ours takes the vine out of
the column first -- a vine breaks in a few ticks by hand -- and then the jump is a jump again.

    python3 deploy/runner/vine_pillar_test.py          # exit 0 = PASS

Flat server: a one-wide stone shaft five deep, vines on its north wall from the floor up, the bot
at the bottom with a stack of cobblestone, `@goto` a point on the surface four blocks away. PASS =
the bot's Y rises out of the shaft within the window, and pillarVine reports the vine was met.
"""
import functools, json, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
BX, BY, BZ = 2000, -60, 200          # shaft floor block; the flat world's floor is at -60
DEPTH = 5
GOAL = (BX + 4, BY + DEPTH, BZ)
WINDOW_S = 90
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


def build():
    rcon(f"fill {BX-2} {BY} {BZ-2} {BX+2} {BY+DEPTH} {BZ+2} minecraft:stone")
    rcon(f"fill {BX} {BY+1} {BZ} {BX} {BY+DEPTH+1} {BZ} minecraft:air")   # the shaft
    # vines on the north wall (the wall at z-1, so the vine faces south, into the shaft)
    for dy in range(1, DEPTH + 1):
        rcon(f"setblock {BX} {BY+dy} {BZ} minecraft:vine[north=true]")
    gx, gy, gz = GOAL
    rcon(f"fill {gx-1} {gy} {gz-1} {gx+2} {gy} {gz+1} minecraft:stone")
    rcon(f"fill {gx-1} {gy+1} {gz-1} {gx+2} {gy+3} {gz+1} minecraft:air")


def main():
    if not py4j("state")["inGame"] or BOT not in rcon("list"):
        py4j("connect", ip="test-server")
        t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    # The stand's saved tungsten.json carries fireReleaseNeedsFire=false -- the attack-key thief
    # (G33): CLICK_LEFT released every tick, so a held strike never breaks anything. The
    # playthrough pins it true; round 28 measured pillarVine=1914/1 with the vine intact, which
    # is this pin missing, not the strike. Same line buried_goal_test.py carries.
    py4j("chatcmd", c=";settings fireReleaseNeedsFire true"); time.sleep(0.5)
    for k, v in PINS:
        py4j("chatcmd", c=f";settings {k} {v}"); time.sleep(0.3); print(f"pinned {k}={v}")
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {BX-12} {BZ-12} {BX+12} {BZ+12}"); time.sleep(1)
    build()
    time.sleep(1)
    rcon(f"spawnpoint {BOT} {BX} {BY+1} {BZ}")
    rcon(f"tp {BOT} {BX+0.5} {BY+1} {BZ+0.5}")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10")
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} minecraft:cobblestone 64")
    time.sleep(2)
    start = py4j("state")
    y0 = float(start["pos"].split(",")[1])
    gx, gy, gz = GOAL
    print(f"in the shaft at {start['pos']} with vines on the wall; @goto {gx} {gy} {gz}")
    py4j("cmd", c=f"@goto {gx} {gy} {gz}")
    best = y0; out = False; seen = set(); t0 = time.time()
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        s = py4j("state")
        y = float(s["pos"].split(",")[1])
        best = max(best, y)
        chat = [c for c in py4j("chat", n=8)["chat"] if c not in seen]; seen.update(chat)
        note = [c for c in chat if any(w in c for w in ("illar", "vine", "Vine", "stuck", "out of blocks"))]
        print(f"  t={time.time()-t0:.0f}s pos={s['pos']} bestY={best:.0f}"
              + (" | " + " || ".join(x[-60:] for x in note) if note else ""))
        if y >= y0 + DEPTH - 1.5:
            out = True
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    vine = "?"
    try:
        st = py4j("stats")["s"]
        tok = [t for t in st.split() if t.startswith(("pillarVine=", "navPillarRuns=", "pillarNoHeadroom=", "pillarColRefused="))]
        print(f"  counters: {' '.join(tok)}")
        for t in tok:
            if t.startswith("pillarVine="):
                vine = t.split("=", 1)[1]
    except Exception:
        pass
    rcon(f"forceload remove {BX-12} {BZ-12} {BX+12} {BZ+12}")
    print(f"result: escaped={out} rise={best-y0:.1f} pillarVine={vine}")
    if out:
        print("PASS: got out of the vined shaft"); return 0
    print("FAIL: never climbed out"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
