#!/usr/bin/env python3
"""Reproduce: a goal on a NON-STANDABLE cell (air / the upper block of 2-tall grass)
makes tungsten ';goto' compute forever instead of cancelling (user bug 2026-07-22).

Two cases via the pure tungsten ;goto (gotoXYZ, no altoclef snap):
  1. air cell 4 blocks above the ground
  2. the upper block of 2-tall grass on the ground
For each: issue the goto, then poll pathStatus busy + recent chat for ~20s. If the
pathfinder stays busy / keeps logging search attempts and never settles, that's the
bug. A correct pathfinder snaps to standable or gives up quickly.
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
elif op=="pstatus": out=dict(mc.pathStatus())
elif op=="stop": mc.ExecuteCommand("@stop"); out={"ok":True}
elif op=="chat": out={"chat":[str(x) for x in mc.getRecentChat(req["n"])]}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=40): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(op,to=30,**kw):
    r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()

def busy(v): return v.get("busy") in (True,"true","True") or v.get("active") in (True,"true","True")
def arrived(v): return v.get("arrived") in (True,"true","True")

def probe(name, tx, ty, tz, secs=26):
    print(f"\n--- {name}: ;goto ({tx},{ty},{tz}) ---")
    py4j("stop"); time.sleep(1)
    rcon(f"tp {BOT} 5 -60 0 -90 0"); time.sleep(1)
    py4j("gotoxyz", x=tx, y=ty, z=tz)
    n=secs//2; busystreak=0; arr=False; laststatus=None
    for k in range(n):
        time.sleep(2)
        try:
            st=py4j("pstatus"); laststatus=st
            if busy(st): busystreak+=1
            if arrived(st): arr=True
            if k in (2, n-1): print(f"  t={2*k+2}s pathStatus={st}")
        except Exception as e:
            print("  pstatus err", e)
    endbusy = busy(laststatus) if laststatus else True
    py4j("stop")
    return {"busy_ratio": f"{busystreak}/{n}", "arrived": arr, "end_busy": endbusy}

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(6)
    rcon("fill -4 -60 -4 10 6 4 air")
    rcon("fill -4 -61 -4 10 -61 4 stone")            # ground
    rcon("setblock 0 -60 0 grass_block")
    rcon("setblock 0 -59 0 tall_grass[half=lower]")
    rcon("setblock 0 -58 0 tall_grass[half=upper]")
    # a genuine over-void goal snap can't help: an island far across a cleared void
    rcon("fill 14 -70 -4 40 6 4 air")                # void beyond x=14
    rcon("fill 30 -61 -1 31 -61 1 stone")            # tiny unreachable island (no bridge blocks)
    r_air  = probe("air-4-up", 5, -56, 0)            # snaps to ground under the bot -> arrive
    r_grass= probe("tallgrass-upper", 0, -58, 0)     # snaps to ground below the grass -> arrive
    r_void = probe("over-void-island", 30, -60, 0, secs=30)  # unreachable -> must GIVE UP (end not busy)

    print("\n=== RESULTS (#user-bug: no infinite compute on unreachable goals) ===")
    print(f"  air-4-up      : {r_air}")
    print(f"  tallgrass     : {r_grass}")
    print(f"  over-void     : {r_void}")
    # snapped goals must ARRIVE (reachable ground), not spin; the truly-unreachable
    # goal must GIVE UP (end_busy False) within the cap instead of computing forever.
    ok = r_air["arrived"] and r_grass["arrived"] and (not r_void["end_busy"])
    print("  GOAL-SNAP/GIVEUP:", "PASS" if ok else "FAIL")
    import sys; sys.exit(0 if ok else 1)

if __name__=="__main__": main()
