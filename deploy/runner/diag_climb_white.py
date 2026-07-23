#!/usr/bin/env python3
"""White-box course-A trace: enable BlockPathWalker.DEBUG and dump the walker's per-tick
internal decisions (waypoint idx, dist, onGround, jump, playerYaw>targetYaw, velocity, pos)
for each climb. Runs N times to catch both PASS and FAIL and see the exact mechanism
(overshoot? yaw lag? lateral drift? stall?) instead of guessing from external position.

Staircase: floor y=-61 (top -60); step i(1..12): setblock (1+i)(-61+i) 0 -> tops x=2..13,y=-59..-48.
"""
import functools, json, subprocess, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; BOT="tester1"
N_RUNS=4
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="swap": out=dict(mc.setTungstenPathing(bool(req["on"])))
elif op=="wdbg": out=dict(mc.setWalkerDebug(bool(req["on"])))
elif op=="goto": mc.ExecuteCommand("@goto "+str(req["x"])+" "+str(req["y"])+" "+str(req["z"])); out={"ok":True}
elif op=="stop": mc.ExecuteCommand("@stop"); mc.punkStop(); out={"ok":True}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(int(req.get("n",120)))]}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=40): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(op,to=30,**kw):
    last=""
    for _ in range(4):
        try:
            r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
            if r.returncode==0 and r.stdout.strip(): return json.loads(r.stdout.strip().splitlines()[-1])
            last=(r.stderr or r.stdout or "").strip()[-160:]
        except Exception as e: last=repr(e)[-160:]
        time.sleep(2)
    raise RuntimeError(f"{op}: {last}")
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def pos():
    o=rcon(f"data get entity {BOT} Pos")
    try:
        p=o.split("[")[1].split("]")[0].split(",")
        return [round(float(v.strip().rstrip("d")),2) for v in p]
    except Exception: return None

def build():
    rcon("forceload add -8 -8 40 8"); rcon(f"clear {BOT}")
    rcon("fill 0 -60 -4 40 10 4 air"); rcon("fill 0 -61 -4 40 -61 4 stone")
    for i in range(1,13): rcon(f"setblock {1+i} {-61+i} 0 stone")
    rcon("gamerule doDaylightCycle false"); rcon("time set day")

def run_once(k):
    py4j("stop"); time.sleep(1.2)
    for _ in range(5):
        rcon(f"tp {BOT} 0 -60 0 90 0"); time.sleep(1.2)
        p=pos()
        if p and p[0] < 2.0 and abs(p[1]+60) < 2: break
    py4j("goto", x=13, y=-48, z=0)
    maxY=-999.0; reached=False; last=None
    for s in range(30):
        time.sleep(0.5); p=pos()
        if p:
            last=p
            if p[1]>maxY: maxY=p[1]
            if abs(p[0]-13)<=1.5 and p[1]>=-48.5: reached=True
    wlk=[c for c in py4j("chat",n=120)["chat"] if "wlk " in c]
    py4j("stop")
    tag="PASS" if reached else "FAIL"
    print(f"\n=== run {k}: {tag}  final={last} maxY={maxY:.1f} ===")
    for c in wlk[-34:]:
        print("  "+c.split("wlk ",1)[1] if "wlk " in c else c)
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
    print("wdbg:", py4j("wdbg",on=True))
    p=0
    for k in range(1,N_RUNS+1):
        if run_once(k): p+=1
    print(f"\n=== PASS {p}/{N_RUNS} ===")

if __name__=="__main__": main()
