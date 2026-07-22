#!/usr/bin/env python3
"""Measure @goto reaction time — command → first bot movement (#32).

Answers "is tungsten start-up latency actually a problem, or already fast?".
With the drift-immune walker the first segment is a cheap instant grid BFS, so
the bot should start moving almost immediately. Run on the flat test-server.
Usage: reaction_test.py [runs]  (default 5)
"""
import functools, json, re, subprocess, sys, time
print = functools.partial(print, flush=True)
C="uctest-mc-tester1"; G="uctest-server"
RUNS=int(sys.argv[1]) if len(sys.argv)>1 else 5
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="swap": mc.setTungstenPathing(True); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
print(json.dumps(out)); gw.close()
"""
def py4j(op,**kw):
    r=subprocess.run(["docker","exec",C,"python3","-c",SNIP,json.dumps({"op":op,**kw})],
                     capture_output=True,text=True,timeout=30)
    try: return json.loads(r.stdout.strip().splitlines()[-1])
    except Exception: return {}
def rc(c): return subprocess.run(["docker","exec",G,"rcon-cli",c],capture_output=True,text=True).stdout
def pos():
    o=rc("data get entity tester1 Pos"); m=re.search(r'\[([^\]]+)\]',o)
    return [float(v.strip().rstrip("d")) for v in m.group(1).split(",")] if m else None

for _ in range(15):
    if py4j("state").get("inGame"): break
    py4j("connect",ip="test-server"); time.sleep(6)
py4j("swap"); time.sleep(1)

print(f"=== @goto reaction time ({RUNS} runs) ===")
times=[]
for run in range(1,RUNS+1):
    py4j("cmd",c="@stop"); time.sleep(1.2)
    rc("tp tester1 0 -60 0 90 0"); time.sleep(1.5)
    p0=pos()
    py4j("cmd",c="@goto 30 -60 0")
    t0=time.time(); moved=None
    while time.time()-t0<8:
        p=pos()
        if p and p0 and ((p[0]-p0[0])**2+(p[2]-p0[2])**2)**0.5>0.6:
            moved=time.time()-t0; break
        time.sleep(0.03)
    times.append(moved)
    print(f"  run {run}: first-move at {round(moved,2) if moved else '>8'}s")
py4j("cmd",c="@stop")
good=[t for t in times if t]
if good:
    print(f"avg first-move: {round(sum(good)/len(good),2)}s (min {round(min(good),2)}, max {round(max(good),2)})")
    print("VERDICT:", "FAST — pipeline opt NOT needed" if sum(good)/len(good) < 0.6 else "SLOW — pipeline opt worth it")
else:
    print("no movement detected (nav broken?)")
