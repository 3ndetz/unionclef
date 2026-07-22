#!/usr/bin/env python3
"""Disconnect-reset test (#29) — nothing tungsten survives a reconnect.

Start a long pillar (task active), then force a reconnect. After re-joining, the
pillar task must be STOPPED (resetAllState fired on DISCONNECT). PASS if the task
is cleared and the client is back in game. Exit 0.
"""
import functools, json, subprocess, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="pillar": out={"ok":mc.pillarTo(int(req["y"])),"active":mc.pillarActive()}
elif op=="pactive": out={"active":mc.pillarActive()}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a, to=40): return subprocess.run(a, capture_output=True, text=True, timeout=to)
def py4j(op, to=30, **kw):
    r = sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})], to)
    if r.returncode != 0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect", ip="test-server")
        except Exception: pass
        time.sleep(6)
    rcon("forceload add -8 -8 8 8")
    rcon("fill -4 -60 -4 4 30 4 air"); rcon("fill -4 -61 -4 4 -61 4 stone")
    rcon(f"tp {BOT} 0 -60 0 0 0"); time.sleep(1)
    rcon(f"item replace entity {BOT} weapon.mainhand with minecraft:stone 64")
    time.sleep(1)
    print("start a long pillar (to y=-40, ~20 up)...")
    r = py4j("pillar", y=-40)
    time.sleep(3)
    active_before = py4j("pactive")["active"]
    print(f"  pillar active before reconnect: {active_before}")
    print("force reconnect (triggers DISCONNECT -> resetAllState)...")
    py4j("connect", ip="test-server")
    # wait for re-join
    inGame = False
    for _ in range(20):
        time.sleep(3)
        try:
            if py4j("state")["inGame"]: inGame = True; break
        except Exception: pass
    time.sleep(2)
    active_after = py4j("pactive")["active"] if inGame else True
    print(f"  in game after reconnect: {inGame}")
    print(f"  pillar active after reconnect (should be False): {active_after}")
    print("\n=== RESULTS (#29 disconnect reset) ===")
    ok = active_before and inGame and (not active_after)
    print(f"  task ran, reconnected, state cleared: {ok}")
    print("  DISCONNECT-RESET:", "PASS" if ok else "FAIL")
    import sys; sys.exit(0 if ok else 1)

if __name__ == "__main__": main()
