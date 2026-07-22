#!/usr/bin/env python3
"""Bridge-as-a-move test (#46 second half): @goto across a WIDE gap the bot can't
jump (>4 blocks, beyond parkour). With blocks in the inventory the nav should, after
it can't otherwise progress, pave a bridge toward the goal and cross.

Course (flat world, floor y-61 => standing y-60):
  near platform x=-4..1 | GAP x=2..8 (floor removed, void below) | far x=9..16
  bot starts at (0,-60,0), goal (13,-60,0) across the 7-wide gap.
PASS if the bot reaches the far platform (x>=9) at ground level. Exit 0.
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
elif op=="goto": mc.ExecuteCommand("@goto "+str(req["x"])+" "+str(req["y"])+" "+str(req["z"])); out={"ok":True}
elif op=="stop": mc.ExecuteCommand("@stop"); mc.punkStop(); out={"ok":True}
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
    # build near/far platforms with a 7-wide void gap between
    rcon("fill -6 -60 -4 18 6 4 air")
    rcon("fill -6 -61 -4 1 -61 4 stone")     # near platform
    rcon("fill 2 -66 -4 8 -60 4 air")        # the gap (floor removed, void below)
    rcon("fill 9 -61 -4 16 -61 4 stone")     # far platform
    py4j("stop"); time.sleep(1)
    rcon(f"tp {BOT} 0 -60 0 90 0"); time.sleep(1)
    rcon(f"clear {BOT}"); rcon(f"give {BOT} cobblestone 64"); time.sleep(1)
    print("@goto (13,-60,0) across a 7-wide void gap...")
    py4j("goto", x=13, y=-60, z=0)
    reached=False; last=None; maxx=-99
    for _ in range(40):   # bridge fires on the give-up (~14s) then paves across
        time.sleep(1)
        p=pos()
        if p:
            last=p
            if p[0]>maxx: maxx=p[0]
            if p[0]>=9 and p[1]>=-61: reached=True; break
    py4j("stop")
    print(f"  finalPos={last} maxX={maxx:.1f} (far platform starts x=9)")
    print(f"  crossed the gap to the far platform: {reached}")
    print("  BRIDGE-GOTO:", "PASS" if reached else "FAIL")
    import sys; sys.exit(0 if reached else 1)

if __name__=="__main__": main()
