#!/usr/bin/env python3
"""Verify: a non-standable / unreachable goal no longer makes tungsten compute forever
(user bug 2026-07-22). Measures isTungstenActive() (PATHFINDER.active || EXECUTOR
running) — the real 'вечно считает' signal — NOT hasActiveTask (which is true whenever
the altoclef task isn't idle).

Cases (pure tungsten ;goto = gotoXYZ, the path with no altoclef snap):
  1. tallgrass-upper: goal on the upper block of 2-tall grass -> GoalSnap drops it to the
     grass block, bot arrives, search stops.
  2. air-4-up: goal 4 blocks up in the air -> snaps to the ground, bot already there,
     search stops.
  3. sky-unreachable: goal on a far sky island across a 28-wide sky void with no blocks ->
     genuinely unreachable -> the search stall-cap gives up.
PASS if tungsten goes INACTIVE (search stopped) within the window for all three. Exit 0.
"""
import functools, json, subprocess, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; BOT="tester1"
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="gotoxyz": out=dict(mc.gotoXYZ(req["x"],req["y"],req["z"]))
elif op=="tactive": out={"active":mc.isTungstenActive()}
elif op=="stop": mc.ExecuteCommand("@stop"); out={"ok":True}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=40): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(op,to=30,**kw):
    r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def pos():
    o=rcon(f"data get entity {BOT} Pos")
    try:
        p=o.split("[")[1].split("]")[0].split(",")
        return [round(float(v.strip().rstrip("d")),1) for v in p]
    except Exception: return None
def active(): return py4j("tactive").get("active") in (True,"true","True")

def probe(name, sx, sy, sz, tx, ty, tz, secs=30):
    print(f"\n--- {name}: from ({sx},{sy},{sz}) ;goto ({tx},{ty},{tz}) ---")
    py4j("stop"); time.sleep(1.5)
    rcon(f"tp {BOT} {sx} {sy} {sz} 90 0"); time.sleep(1.5)
    py4j("gotoxyz", x=tx, y=ty, z=tz)
    went_inactive_at=None
    for k in range(secs//2):
        time.sleep(2)
        try:
            a=active()
            if not a and went_inactive_at is None: went_inactive_at=2*k+2
        except Exception as e: print("  tactive err", e)
    p=pos(); py4j("stop")
    print(f"  finalPos={p}  tungsten went INACTIVE at: {went_inactive_at}s (None = still spinning at {secs}s)")
    return went_inactive_at

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(6)
    # ground + 2-tall grass
    rcon("fill -4 -60 -4 10 6 4 air")
    rcon("fill -4 -61 -4 10 -61 4 stone")
    rcon("setblock 0 -60 0 grass_block")
    rcon("setblock 0 -59 0 tall_grass[half=lower]")
    rcon("setblock 0 -58 0 tall_grass[half=upper]")
    # sky islands: near (bot) + far (unreachable), 28-wide void between, void all around
    rcon("fill -6 96 -6 40 104 6 air")
    rcon("fill -4 100 -4 1 100 4 stone")
    rcon("fill 30 100 -1 31 100 1 stone")
    r1 = probe("tallgrass-upper", 5, -60, 0, 0, -58, 0)
    r2 = probe("air-4-up",        5, -60, 0, 5, -56, 0)
    r3 = probe("sky-unreachable", 0, 101, 0, 30, 101, 0, secs=32)

    print("\n=== RESULTS (#user-bug: no infinite compute on unreachable goals) ===")
    print(f"  tallgrass inactive at : {r1}s")
    print(f"  air-4-up  inactive at : {r2}s")
    print(f"  sky-unreach inactive  : {r3}s")
    # every case must eventually go inactive (search stopped) — none may spin forever
    ok = r1 is not None and r2 is not None and r3 is not None
    print("  NO-INFINITE-COMPUTE:", "PASS" if ok else "FAIL")
    import sys; sys.exit(0 if ok else 1)

if __name__=="__main__": main()
