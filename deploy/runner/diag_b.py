#!/usr/bin/env python3
"""Diagnostic: can the PURE tungsten async pathfinder (;goto via gotoXYZ, bypassing
driveTungstenPrimary/walker/CombatPathfinder) climb course B (steep parkour-ascend
chain)? If yes -> the default-path B failure is only the degenerate-2-wp-stub routing
(fix: ungate degenerateStub). If no -> the block-space move-gen itself can't do the
+2x+1y parkour-ascend and needs the deeper fix.

B steps: setblock (12+2i, -61+i, 0) for i=0..5 -> tops at x=12..22, y=-61..-56.
Start below the chain at (8,-60,0); goal the top (22,-56,0).
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

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(6)
    # (re)build B steps + a small landing
    rcon("fill 0 -60 -4 40 10 4 air")
    rcon("fill 0 -61 -4 40 -61 4 stone")
    for i in range(6):
        rcon(f"setblock {12+2*i} {-61+i} 0 stone")
    rcon("setblock 24 -56 0 stone")  # small landing past the top
    py4j("stop"); time.sleep(1)
    rcon(f"tp {BOT} 8 -60 0 90 0"); time.sleep(1.5)
    print("gotoXYZ (pure tungsten ;goto) -> top of B (22,-56,0)")
    py4j("gotoxyz", x=22, y=-56, z=0)
    maxY=-999.0; last=None
    for _ in range(28):
        time.sleep(1)
        p=pos()
        if p:
            last=p
            if p[1]>maxY: maxY=p[1]
    py4j("stop")
    reached = last is not None and abs(last[0]-22)<2 and last[1]>=-56.5
    print(f"  finalPos={last} maxY={maxY:.1f} (target y=-56)")
    print(f"  async pathfinder climbed B: {reached}")
    print("  DIAG-B:", "CLIMBS" if reached else ("PARTIAL" if maxY>-59 else "NO-CLIMB"))

if __name__=="__main__": main()
