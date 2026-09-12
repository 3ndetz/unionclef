#!/usr/bin/env python3
"""G58 repro: a drop eighteen blocks BELOW, over the lip of a cliff.

The 20:29 recording (2026-09-11) spent four minutes at (902,104,-243) on the rim of a drop with a
wooden pickaxe lying eighteen blocks down: the planner searched 7000 nodes in 251 ms of a 250 ms
budget, could not reach the goal, and its best partial lay inside five blocks -- so the partial
rule (baritone's MIN_DIST_PATH = 5) refused it, the route was given up, and the drop was
blacklisted after three tries. Retrying at four times the budget (planBudgetBoostBeforeGiveUp) did
not change the verdict, which is why the real root is still open: the search cannot buy its way
DOWN, because a dig costs ~23 ticks against a walk's 4.6 and A* spends the budget widening a disc
of surface cells instead.

    python3 deploy/runner/cliff_drop_test.py            # exit 0 = PASS

The scene, on the flat server: a plateau, a sheer face, and a floor eighteen blocks below with an
iron ingot on it four blocks out from the foot of the cliff. The bot starts on the rim with
`@get iron_ingot 1`. PASS = the ingot in the inventory within the window, with no "giving the
route up" and no blacklisting of the drop.
"""
import functools, json, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 1200, 300
# The flat world's own surface block is at -61 (feet at -60). The plateau is built UP from it, so
# the ground below the cliff is the world's own floor and the only question the bench asks is the
# DESCENT -- eighteen blocks of sheer face with a drop at its foot.
GROUND = -61
DEPTH = 18
TOP = GROUND + DEPTH          # the plateau's surface block; the bot stands at TOP+1
WINDOW_S = 120
PINS = [kv.split("=", 1) for kv in os.environ.get("UC_PINS", "").split(",") if "=" in kv]

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
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",12))]}
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
    rcon(f"kill @e[type=item,x={X},y={GROUND},z={Z},distance=..60]")
    # air above everything, the world's own floor kept, then a plateau raised west of X
    rcon(f"fill {X-14} {GROUND+1} {Z-12} {X+16} {TOP+6} {Z+12} minecraft:air")
    rcon(f"fill {X-14} {GROUND} {Z-12} {X+16} {GROUND} {Z+12} minecraft:grass_block")  # the ground below
    rcon(f"fill {X-14} {GROUND+1} {Z-12} {X} {TOP-1} {Z+12} minecraft:stone")          # the plateau body
    rcon(f"fill {X-14} {TOP} {Z-12} {X} {TOP} {Z+12} minecraft:grass_block")           # its surface


def main():
    if not py4j("state")["inGame"] or BOT not in rcon("list"):
        py4j("connect", ip="test-server")
        t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined test-server"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    for k, v in PINS:
        py4j("chatcmd", c=f";settings {k} {v}"); time.sleep(0.3); print(f"pinned {k}={v}")
    py4j("chatcmd", c=";settings verboseDebugLogging true"); time.sleep(0.5)
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {X-20} {Z-20} {X+24} {Z+20}"); time.sleep(1)
    build()
    time.sleep(1)
    # ⛔ SPAWN ON THE ARENA. The flat server's own spawn had NO FLOOR (2026-09-12) and a bot that
    # died there fell out of the world, respawned and fell again for the whole window.
    rcon(f"spawnpoint {BOT} {X-2} {TOP+1} {Z}")
    rcon(f"tp {BOT} {X-1.5} {TOP+1} {Z+0.5} 270 0")     # on the rim, facing the drop (+x)
    rcon(f"effect give {BOT} minecraft:instant_health 1 10")
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} minecraft:stone_pickaxe")
    time.sleep(1)
    rcon(f"summon minecraft:item {X+4.5} {GROUND+1.5} {Z+0.5} "
         f"{{Item:{{id:\"minecraft:iron_ingot\",count:1}},PickupDelay:0}}")
    time.sleep(1.5)
    gs = py4j("gs")
    print(f"bot on the rim at {gs['pos']}; iron ingot {DEPTH} blocks below, 4 out from the face")
    py4j("cmd", c="@get iron_ingot 1")
    t0 = time.time(); got = False; seen = set(); last = None; still = 0
    bad = {"giving the route up": 0, "Failed to get to target": 0, "Not closing on this target": 0}
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        gs = py4j("gs"); pos = gs["pos"]
        ch = [c for c in py4j("chat", n=12)["chat"] if c not in seen]; seen.update(ch)
        for k in bad:
            bad[k] += sum(1 for c in ch if k in c)
        got = any("iron_ingot" in i for i in py4j("inv")["ids"])
        note = [c for c in ch if any(w in c for w in ("giving", "dead-end", "partial", "Pillar",
                                                     "no progress", "Ran out", "blacklist", "Costing"))]
        if pos == last:
            still += 1
        last = pos
        print(f"  t={time.time()-t0:.0f}s pos={pos} iron={got}"
              + (" | " + " || ".join(x[-70:] for x in note[:2]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    py4j("chatcmd", c=";settings verboseDebugLogging false")
    try:
        st = py4j("stats")["s"]
        tok = [t for t in st.split() if t.startswith(("plan=", "navPartial=", "navBudgetBoost=",
                                                      "banLifted=", "banExpired=", "planPartialCoef="))]
        print(f"  counters: {' '.join(tok)}")
    except Exception:
        pass
    if not got:
        r = sh(["docker", "exec", C1, "sh", "-c",
                "tail -n 8000 /mc-data/logs/latest.log | grep -E "
                "'FastNavigator|PLAN n=|giving the route|Ran out of nodes|partial|dead-end|Pillar|"
                "primDrive NO ROUTE|no progress' | tail -n 30"])
        print("  navigator log:")
        for l in r.stdout.splitlines():
            print("    " + l[11:200].replace("[Render thread/INFO]: [CHAT] ", "")
                  .replace("[FastNavigator-plan/INFO]: [CHAT] ", ""))
    rcon(f"forceload remove {X-20} {Z-20} {X+24} {Z+20}")
    flaws = {k: v for k, v in bad.items() if v}
    print(f"result: iron={got} stillSamples={still} flaws={flaws or 'none'}")
    if got:
        print("PASS: went down the cliff and took the drop")
        return 0
    print(f"FAIL: the drop {DEPTH} blocks below was never collected")
    return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
