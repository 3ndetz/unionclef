#!/usr/bin/env python3
"""Unreachable-goal give-up test (#27).

Build a 10-high 1x1 pillar and @goto its top. The bot can't climb it (no pillar/
place-as-move yet), so the pathfinder must GIVE UP — log '[nav] goal unreachable'
within ~20s and stop — instead of searching forever. Also asserts no crash (py4j
stays responsive).

PASS: give-up fired, bot did NOT reach the top, client still alive. Exit 0.
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
elif op=="swap": out=dict(mc.setTungstenPathing(bool(req["on"])))
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(int(req.get("n",30)))]}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a, to=40): return subprocess.run(a, capture_output=True, text=True, timeout=to)
def py4j(op, to=30, **kw):
    r = sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})], to)
    if r.returncode != 0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def pos():
    o = rcon(f"data get entity {BOT} Pos")
    try:
        p = o.split("[")[1].split("]")[0].split(",")
        return [round(float(v.strip().rstrip("d")),1) for v in p]
    except Exception: return None

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect", ip="test-server")
        except Exception: pass
        time.sleep(6)
    # arena: flat floor + a 10-high 1x1 pillar at x=20; goal is its top (unreachable)
    rcon("forceload add -8 -8 40 8")
    rcon("fill 0 -60 -4 40 10 4 air"); rcon("fill 0 -61 -4 40 -61 4 stone")
    for y in range(-60, -50):   # pillar (20,-60..-51) -> top standable at y=-50
        rcon(f"setblock 20 {y} 0 stone")
    rcon("gamerule doDaylightCycle false"); rcon("time set day")
    print("swap:", py4j("swap", on=True))
    py4j("cmd", c="@stop"); time.sleep(1)
    rcon(f"tp {BOT} 0 -60 0 90 0"); time.sleep(1.5)
    print("goto pillar top (20,-50,0) — unreachable, must give up...")
    py4j("cmd", c="@goto 20 -50 0")

    gaveup = False; reached = False; t0 = time.time()
    while time.time() - t0 < 32:
        time.sleep(3)
        p = pos()
        chat = py4j("chat", n=40)["chat"]
        if any("goal unreachable" in c for c in chat):
            gaveup = True
            print(f"  [give-up] fired at {time.time()-t0:.0f}s (pos {p})")
            break
        if p and abs(p[0]-20) < 1.5 and p[1] >= -50.5:
            reached = True; break
    py4j("cmd", c="@stop")
    alive = py4j("state").get("inGame", False)   # crash check: py4j still responds
    print("\n=== RESULTS (#27 unreachable give-up) ===")
    print(f"  gave up gracefully: {gaveup}")
    print(f"  reached top (should be False): {reached}")
    print(f"  client alive (no crash): {alive}")
    ok = gaveup and not reached and alive
    print("  UNREACHABLE:", "PASS" if ok else "FAIL")
    import sys; sys.exit(0 if ok else 1)

if __name__ == "__main__": main()
