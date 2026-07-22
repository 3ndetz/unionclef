#!/usr/bin/env python3
"""Pillar-to-reach integration test (#46 + #27 real fix).

Same 10-high pillar as unreachable_test, but the bot has blocks. @goto its top:
the pathfinder should PILLAR up (place blocks under itself, adjacent to the pillar)
and step onto the top — REACH it, instead of giving up. Exit 0 = reached.
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
    for _ in range(6):
        o = rcon(f"data get entity {BOT} Pos")
        try:
            p = o.split("[")[1].split("]")[0].split(",")
            return [round(float(v.strip().rstrip("d")),1) for v in p]
        except Exception: time.sleep(1)
    return None

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect", ip="test-server")
        except Exception: pass
        time.sleep(6)
    rcon("forceload add -8 -8 40 8")
    rcon("fill 0 -60 -4 40 10 4 air"); rcon("fill 0 -61 -4 40 -61 4 stone")
    for y in range(-60, -50):          # 10-high pillar at x=20, top standable at y=-50
        rcon(f"setblock 20 {y} 0 stone")
    rcon("gamerule doDaylightCycle false"); rcon("time set day")
    print("swap:", py4j("swap", on=True))
    py4j("cmd", c="@stop"); time.sleep(1)
    rcon(f"tp {BOT} 0 -60 0 90 0"); time.sleep(1.5)
    rcon(f"item replace entity {BOT} weapon.mainhand with minecraft:stone 64")
    rcon(f"give {BOT} cobblestone 64")   # inventory fallback for equipBuildBlock
    time.sleep(1)
    print("goto pillar top (20,-50,0) — should PILLAR up + reach...")
    py4j("cmd", c="@goto 20 -50 0")

    reached = False; pillared = False; best = -999; t0 = time.time()
    while time.time() - t0 < 55:
        time.sleep(3)
        p = pos()
        if p: best = max(best, p[1])
        chat = py4j("chat", n=40)["chat"]
        if any("pillaring up" in c.lower() for c in chat): pillared = True
        if p and abs(p[0]-20) < 1.6 and p[1] >= -50.5:
            reached = True; print(f"  reached at {time.time()-t0:.0f}s (pos {p})"); break
    py4j("cmd", c="@stop")
    alive = py4j("state").get("inGame", False)
    print("\n=== RESULTS (#46 pillar-to-reach) ===")
    print(f"  pillared (log): {pillared}")
    print(f"  reached top: {reached} (maxY={best})")
    print(f"  client alive: {alive}")
    ok = reached and alive
    print("  PILLAR-REACH:", "PASS" if ok else "FAIL")
    import sys; sys.exit(0 if ok else 1)

if __name__ == "__main__": main()
