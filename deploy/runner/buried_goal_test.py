#!/usr/bin/env python3
"""G55 bench: a block goal whose cell is SOLID is dug into (baritone's GoalBlock), not "reached"
by standing on top of it.

The 17:56 recorded run (2026-09-11) spent nine of its ten minutes on one spot: BeatMinecraft's
loot action asked to stand in chest.up() -- sand, the chest was buried -- while the bot stood ON
that sand. The snap wanted the bot's own cell (refused, G40), the planner's height tolerance said
"already there" (atGoal ytol52), the navigator "arrived (1.0)" every twelve seconds, and the
task's exact arrival test never agreed.

    python3 deploy/runner/buried_goal_test.py            # exit 0 = PASS

Flat server: a chest one below the surface, sand on top of it, the bot standing on the sand
(phase 1) and then five blocks away (phase 2); `@goto X Y Z` at the sand cell. PASS = the feet
end up IN the sand cell (on the chest) in both phases, no "arrived" without arrival.
"""
import functools, json, math, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 840, 300
GROUND = -61                 # flat world surface block; feet at GROUND+1
WINDOW_S = 45

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


def feet(gs):
    # getGameState()["self"]["pos"] is the string "x,y,z"; +0.1251 on y is baritone's feet rule
    # (a chest top is 7/8 high: floor(y) alone reads the cell below the one the body stands in)
    p = [float(v) for v in str(gs["pos"]).split(",")]
    return (int(math.floor(p[0])), int(math.floor(p[1] + 0.1251)), int(math.floor(p[2])))


def build_scene(x):
    rcon(f"fill {x-8} {GROUND-1} {Z-8} {x+8} {GROUND+6} {Z+8} minecraft:air")
    rcon(f"fill {x-8} {GROUND-2} {Z-8} {x+8} {GROUND-2} {Z+8} minecraft:stone")
    rcon(f"fill {x-8} {GROUND-1} {Z-8} {x+8} {GROUND} {Z+8} minecraft:dirt")
    rcon(f"setblock {x} {GROUND-1} {Z} minecraft:chest")
    rcon(f"setblock {x} {GROUND} {Z} minecraft:sand")
    time.sleep(1)
    ok = rcon(f"execute if block {x} {GROUND} {Z} minecraft:sand")
    ok2 = rcon(f"execute if block {x} {GROUND-1} {Z} minecraft:chest")
    return "passed" in ok and "passed" in ok2


def phase(name, x, tp):
    # each phase gets its own column: re-placing the sand where the bot just broke it reads to
    # the break-failure detector as "failed to break! Maybe private area" and protects the cell
    if not build_scene(x):
        print(f"FAIL: the scene was not built (chunk not loaded?)"); return False
    rcon(f"tp {BOT} {tp}")
    time.sleep(1.5)
    gs = py4j("gs")
    print(f"[{name}] bot at {gs['pos']} feet={feet(gs)}; goal = the sand cell ({x},{GROUND},{Z}) on a chest. "
          f"@goto {x} {GROUND} {Z}")
    py4j("cmd", c=f"@goto {x} {GROUND} {Z}")
    t0 = time.time(); seen = set(); ok = False
    bad = {"Failed! No block path": 0, "giving the route up": 0, "not getting closer": 0}
    arrived_lines = 0
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        gs = py4j("gs"); f = feet(gs)
        ch = [c for c in py4j("chat", n=14)["chat"] if c not in seen]; seen.update(ch)
        for k in bad:
            bad[k] += sum(1 for c in ch if k in c)
        arrived_lines += sum(1 for c in ch if "FastNavigator: arrived" in c)
        note = [c for c in ch if any(w in c for w in ("digging into", "at the dig", "Mining done", "arrived",
                                                       "FINISHED", "Failed", "giving", "no progress"))]
        try:
            chain = py4j("task")["chain"][-80:]
        except Exception:
            chain = "?"
        print(f"  t={time.time()-t0:.0f}s feet={f} | {chain}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if f == (x, GROUND, Z):
            ok = True
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    flaws = {k: v for k, v in bad.items() if v}
    print(f"[{name}] result: inCell={ok} feet={f} flaws={flaws or 'none'} arrivedLines={arrived_lines}")
    return ok and not flaws


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
    rcon("difficulty peaceful")
    X2 = X + 20
    rcon(f"forceload add {X-10} {Z-10} {X2+10} {Z+10}"); time.sleep(1)
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} minecraft:stone_shovel 1")
    a = phase("on top", X, f"{X+0.5} {GROUND+1} {Z+0.5} 0 60")
    b = phase("five away", X2, f"{X2+5.5} {GROUND+1} {Z+0.5} 90 30")
    try:
        st = py4j("stats")["s"]
        tok = [t for t in st.split() if t.startswith(("pdDig=", "snapSelfRefused=", "pdNearBuild=",
                                                          "navStall=", "navBreak=", "atGoal", "plan="))]
        print(f"  counters: {' '.join(tok)}")
    except Exception:
        pass
    rcon(f"forceload remove {X-10} {Z-10} {X2+10} {Z+10}")
    if a and b:
        print("PASS: dug into the solid goal cell from on top and from five blocks away"); return 0
    print("FAIL"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
