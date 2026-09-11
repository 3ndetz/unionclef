#!/usr/bin/env python3
"""G25 bench, the TALL case: a log up a trunk is taken from the ground, never by trying to
pillar with an empty pocket.

The first @gamer run on the reach goal (2026-09-11) stood under a spruce for 150 s: the target
log was five blocks up, the planner could not become "adjacent" to it without blocks, and the
navigator looped "walking dead-ends -> physics owns the rest -> Pillaring up -> out of blocks".
The reach goal now also completes on any cell within 4.0 of the block with line of sight, so the
trunk base is the goal, exactly where a player would stand.

    python3 deploy/runner/tree_reach_test.py            # exit 0 = PASS

Builds a 6-high spruce trunk with a leaf crown on the flat server, drops the bot 4 blocks away
with an EMPTY inventory, `@get spruce_log 1`. PASS = a spruce log in the inventory within the
window and no pillar attempt logged.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 700, 300
GROUND = -61                 # flat world surface block; feet at GROUND+1
TRUNK_H = 6
WINDOW_S = 90

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
elif op=="task": out={"chain": str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-300:]}
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
    top = GROUND + TRUNK_H
    rcon(f"fill {X-6} {GROUND+1} {Z-6} {X+6} {top+6} {Z+6} minecraft:air")
    rcon(f"fill {X-6} {GROUND} {Z-6} {X+6} {GROUND} {Z+6} minecraft:grass_block")
    rcon(f"fill {X} {GROUND+1} {Z} {X} {top} {Z} minecraft:spruce_log")
    # a crown around the top three logs so the upper logs are occluded from the side
    rcon(f"fill {X-2} {top-2} {Z-2} {X+2} {top+1} {Z+2} minecraft:spruce_leaves replace minecraft:air")
    time.sleep(1)
    rcon(f"tp {BOT} {X+4.5} {GROUND+1} {Z+0.5}")
    rcon(f"clear {BOT}")
    time.sleep(2)
    gs = py4j("gs")
    print(f"empty pocket at {gs['pos']}; a {TRUNK_H}-high spruce trunk 4 blocks away. @get spruce_log 1")
    py4j("cmd", c="@get spruce_log 1")
    t0 = time.time(); seen = set(); got = False; pillars = 0; unreach = 0
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        gs = py4j("gs"); pos = gs["pos"]
        ch = [c for c in py4j("chat", n=12)["chat"] if c not in seen]; seen.update(ch)
        pillars += sum(1 for c in ch if "Pillaring up" in c)
        unreach += sum(1 for c in ch if "may be unreachable" in c)
        got = any("spruce_log" in i for i in py4j("inv")["ids"])
        note = [c for c in ch if any(w in c for w in ("Pillar", "unreachable", "dead-ends", "arrived", "reach"))]
        try:
            chain = py4j("task")["chain"][-80:]
        except Exception:
            chain = "?"
        print(f"  t={time.time()-t0:.0f}s pos={pos} log={got} | {chain}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    print(f"result: spruce_log={got} pillar-attempts={pillars} unreachable-blacklists={unreach}")
    if got and pillars == 0:
        print("PASS: took the log from the ground, no pillar attempt"); return 0
    if got:
        print("FAIL (soft): got the log but tried to pillar with an empty pocket"); return 1
    print("FAIL: never got a log"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
