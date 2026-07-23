#!/usr/bin/env python3
"""Quantify course-A (staircase) FLAKINESS: run the same ;goto up the staircase N times
on ONE fresh bot and record each outcome (reached? finalPos, maxY, and a compact
low-rate position trace). A single trace showed a clean climb to the target, but
terrain_test reports A as FAIL on other runs -> non-deterministic. This pins the pass
rate and captures the FAILURE positions so we can see the failure mode (overshoot vs
stall vs fall) before fixing.

Staircase: floor y=-61 (top -60); step i(1..12): setblock (1+i)(-61+i) 0 -> tops x=2..13, y=-59..-48.
Target = top of last step (13,-48,0).
"""
import functools, json, subprocess, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; BOT="tester1"
N_RUNS=8
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="swap": out=dict(mc.setTungstenPathing(bool(req["on"])))
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
        return [round(float(v.strip().rstrip("d")),2) for v in p]
    except Exception: return None

def build():
    rcon("forceload add -8 -8 40 8")
    rcon(f"clear {BOT}")
    rcon("fill 0 -60 -4 40 10 4 air")
    rcon("fill 0 -61 -4 40 -61 4 stone")
    for i in range(1,13):
        rcon(f"setblock {1+i} {-61+i} 0 stone")
    rcon("gamerule doDaylightCycle false"); rcon("time set day")

def run_once(k):
    py4j("stop"); time.sleep(1.2)
    rcon(f"tp {BOT} 0 -60 0 90 0"); time.sleep(1.5)
    py4j("goto", x=13, y=-48, z=0)
    maxY=-999.0; reached=False; last=None; treach=None
    xs=[]
    for s in range(30):  # 15s
        time.sleep(0.5)
        p=pos()
        if p:
            last=p; xs.append(round(p[0]))
            if p[1]>maxY: maxY=p[1]
            if abs(p[0]-13)<=1.5 and p[1]>=-48.5 and not reached:
                reached=True; treach=round(s*0.5,1)
    py4j("stop")
    # compact x-progress signature
    sig="".join(chr(65+min(25,max(0,int(x/2)))) if x is not None else "?" for x in xs[::2])
    tag="PASS" if reached else "FAIL"
    print(f"  run {k}: {tag}  final={last} maxY={maxY:.1f} reachedAt={treach}  xsig={sig}")
    return reached

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(6)
    build()
    print("swap:", py4j("swap",on=True))
    print(f"=== course A x{N_RUNS} (flakiness) ===")
    p=0
    for k in range(1,N_RUNS+1):
        if run_once(k): p+=1
    print(f"=== PASS {p}/{N_RUNS} ===")

if __name__=="__main__": main()
