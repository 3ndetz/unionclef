#!/usr/bin/env python3
"""Diagnostic twin of dig_reach_test.py: same geometry, then DUMP what the miner and the executor
are doing while the dig fails (task chain, recent chat, executor place/break counters, guide).
    python3 deploy/runner/dig_reach_diag.py [seconds=35]
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 620, 300; STONE_TOP = -58; DIRT_TOP = -53; FEET = DIRT_TOP + 1
WINDOW_S = int(sys.argv[1]) if len(sys.argv) > 1 else 35

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
elif op=="task": out={"chain": str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-700:], "runner": str(mc.getRunnerStatus() or "")[:300]}
elif op=="stats": out={"s": str(mc.placeStats() or "")}
elif op=="guide": out={"r": str(mc.guideDump() or "")}
elif op=="look": out={"r": str(mc.getGameState().get("self") or {})}
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
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    py4j("chatcmd", c=";settings verboseDebugLogging true"); time.sleep(0.5)
    rcon(f"gamemode survival {BOT}")
    rcon(f"fill {X-5} {FEET} {Z-5} {X+5} {FEET+8} {Z+5} minecraft:air")
    rcon(f"fill {X-5} {STONE_TOP-2} {Z-5} {X+5} {STONE_TOP} {Z+5} minecraft:stone")
    rcon(f"fill {X-5} {STONE_TOP+1} {Z-5} {X+5} {DIRT_TOP} {Z+5} minecraft:dirt")
    time.sleep(1)
    rcon(f"tp {BOT} {X+0.5} {FEET} {Z+0.5}"); rcon(f"clear {BOT}"); rcon(f"give {BOT} minecraft:iron_pickaxe")
    time.sleep(2)
    py4j("cmd", c="@get cobblestone 1")
    t0 = time.time(); seen = []
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        gs = py4j("gs")
        ch = [c for c in py4j("chat", n=25)["chat"] if c not in seen]; seen.extend(ch)
        print(f"--- t={time.time()-t0:.0f}s pos={gs.get('pos')} yaw={gs.get('yaw')} pitch={gs.get('pitch')} look={gs.get('look')} held={gs.get('held') or gs.get('mainHand')}")
        for c in ch:
            print("   ", c[-160:])
        try:
            print("   TASK:", py4j("task")["chain"][-500:])
        except Exception as e:
            print("   task err", e)
    print("=== placeStats ===")
    try:
        print(py4j("stats")["s"][:3000])
    except Exception as e:
        print("stats err", e)
    print("=== guide ===")
    try:
        print(py4j("guide")["r"][:1500])
    except Exception as e:
        print("guide err", e)
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); py4j("chatcmd", c=";settings verboseDebugLogging false")
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
