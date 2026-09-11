#!/usr/bin/env python3
"""G44 bench: a goal far BELOW and a little to the side is reached by digging a staircase or a
shaft, leg by leg -- never handed to the physics engine ("walking dead-ends (94.2 -> 94.0) ->
physics owns the rest", "Ran out of nodes", the 14:00 recorded run at the diamond phase).

    python3 deploy/runner/deep_goal_test.py            # exit 0 = PASS

Flat server: a 30-deep solid stone block, the bot on top with an iron pickaxe, `@goto` a pocket
25 below and 6 blocks to the side. PASS = feet within 2 blocks of the goal within the window and
zero "physics owns the rest" / "Ran out of nodes" lines.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 460, 430
# The flat world ends at y=-64, so the "mountain" is built UP from the surface: solid stone from
# the ground (-60) to TOP, the bot on top, the goal deep inside it.
TOP = -25; FLOORY = -61          # solid stone from FLOORY+1 to TOP
DROP = 25; SIDE = 6
GOAL = (X + SIDE, TOP - DROP, Z)  # a cell inside the stone; the bot must dig to it
WINDOW_S = 180

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
    rcon(f"forceload add {X-12} {Z-12} {X+12} {Z+12}"); time.sleep(1)
    rcon(f"fill {X-12} {TOP+1} {Z-12} {X+12} {TOP+6} {Z+12} minecraft:air")
    # 25 x 36 x 25 = 22500 blocks: under the 32768 fill limit, but build it in two halves anyway
    mid = (FLOORY + 1 + TOP) // 2
    r1 = rcon(f"fill {X-12} {FLOORY+1} {Z-12} {X+12} {mid} {Z+12} minecraft:stone")
    r2 = rcon(f"fill {X-12} {mid+1} {Z-12} {X+12} {TOP} {Z+12} minecraft:stone")
    r = r1 + " | " + r2
    time.sleep(1)
    ok = rcon(f"execute if block {X} {TOP-10} {Z} minecraft:stone")
    print(f"scene: stone fill -> {r[:40]!r}; solid -> {ok[:30]!r}")
    if "passed" not in ok:
        print("FAIL: the stone block was not built (chunk not loaded?)"); return 2
    rcon(f"tp {BOT} {X+0.5} {TOP+1} {Z+0.5}")
    rcon(f"clear {BOT}"); rcon(f"give {BOT} minecraft:iron_pickaxe")
    time.sleep(2)
    gx, gy, gz = GOAL
    gs = py4j("gs")
    print(f"on top of a solid block at {gs['pos']}; goal {DROP} below and {SIDE} aside: @goto {gx} {gy} {gz}")
    py4j("cmd", c=f"@goto {gx} {gy} {gz}")
    t0 = time.time(); seen = set(); reached = False; best_low = TOP + 1
    bad = {"physics owns the rest": 0, "Ran out of nodes": 0, "giving the route up": 0,
           "Failed exploring": 0}
    while time.time() - t0 < WINDOW_S:
        time.sleep(6)
        gs = py4j("gs"); pos = gs["pos"]
        px, py_, pz = [float(v) for v in pos.split(",")]
        best_low = min(best_low, py_)
        ch = [c for c in py4j("chat", n=12)["chat"] if c not in seen]; seen.update(ch)
        for k in bad:
            bad[k] += sum(1 for c in ch if k in c)
        note = [c for c in ch if any(w in c for w in ("dead-ends", "physics", "nodes", "dig", "NO ROUTE",
                                                       "giving", "arrived", "FINISHED"))]
        d = ((px - (gx + 0.5)) ** 2 + (py_ - gy) ** 2 + (pz - (gz + 0.5)) ** 2) ** 0.5
        print(f"  t={time.time()-t0:.0f}s pos={pos} lowestY={best_low:.0f} dist={d:.1f}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if d <= 2.0:
            reached = True; break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    try:
        st = py4j("stats")["s"]
        tok = [t for t in st.split() if t.startswith(("pdFnBuild=", "pdNearBuild=", "navStall=", "navBreak=",
                                                          "navRes=", "plan=", "snapSelfRefused="))]
        print(f"  counters: {' '.join(tok)}")
    except Exception:
        pass
    rcon(f"forceload remove {X-12} {Z-12} {X+12} {Z+12}")
    flaws = {k: v for k, v in bad.items() if v}
    print(f"result: reached={reached} lowestY={best_low:.0f} (goal y={gy}) flaws={flaws or 'none'}")
    if reached and not flaws:
        print("PASS: dug its way down to the deep goal"); return 0
    print("FAIL"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
