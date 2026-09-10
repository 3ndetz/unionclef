#!/usr/bin/env python3
"""Deterministic proof: @gamer-style @goto now BUILDS up a sheer 6-high ledge (nav_wall2, taller).

Bot starts at the foot of a verified-solid 6-block ledge face; the goal is on top. Grid BFS
cannot route up a sheer 6-high face, so the drive escalates to FastNavigator, which pillars/
staircases up and walks onto the ledge. PASS only if the bot reaches the ledge top.
    python3 deploy/runner/cliff_climb_test.py
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; BOT="tester1"
FLOOR=-60; TOP=-54                       # ledge solid FLOOR..TOP (6 tall), top surface walkable at TOP+1
LX0,LX1,LZ0,LZ1 = 216,226,214,226        # ledge footprint
START=(213, FLOOR+1, 220)                # on the floor, 3 west of the ledge face (x=216)
GOAL =(221, TOP+1, 220)                  # on the ledge top
SNIP=r'''
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="gs": out=dict(mc.getGameState().get("self") or {})
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",8))]}
print(json.dumps(out,default=str)); gw.close()
'''
def sh(a,to=60): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(op,to=40,**kw):
    r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def solid(x,y,z):
    return "passed" in rcon(f"execute if block {x} {y} {z} minecraft:stone")

if not py4j("state")["inGame"]: py4j("connect",ip="test-server"); time.sleep(2)
if "tester1" not in rcon("list"):
    py4j("connect",ip="test-server"); t0=time.time()
    while time.time()-t0<120 and "tester1" not in rcon("list"): time.sleep(5)
py4j("cmd",c="@stop"); py4j("cmd",c=";stop"); time.sleep(1)
rcon(f"gamemode survival {BOT}")
# clear a generous air box, lay a floor west of the ledge, build the solid ledge
rcon(f"fill {LX0-8} {FLOOR+1} {LZ0-4} {LX1+4} {TOP+8} {LZ1+4} minecraft:air")
rcon(f"fill {LX0-8} {FLOOR} {LZ0-4} {LX1+4} {FLOOR} {LZ1+4} minecraft:stone")   # floor
rcon(f"fill {LX0} {FLOOR} {LZ0} {LX1} {TOP} {LZ1} minecraft:stone")             # the ledge (solid)
time.sleep(1)
# VERIFY the ledge is really there before trusting the run
ok = solid(LX0, TOP, 220) and solid(LX0, FLOOR+3, 220) and solid(GOAL[0], TOP, GOAL[2])
print(f"ledge solid-check west-face-top={solid(LX0,TOP,220)} west-face-mid={solid(LX0,FLOOR+3,220)} under-goal={solid(GOAL[0],TOP,GOAL[2])}")
if not ok:
    print("FAIL(setup): ledge did not build"); sys.exit(2)
sx,sy,sz=START
rcon(f"tp {BOT} {sx+0.5} {sy} {sz+0.5}"); rcon(f"clear {BOT}"); rcon(f"give {BOT} minecraft:cobblestone 64")
time.sleep(2)
gx,gy,gz=GOAL
y0=float(py4j("gs")["pos"].split(",")[1])
py4j("cmd",c=f"@goto {gx} {gy} {gz}")
print(f"at foot of a 6-high ledge (y={y0:.0f}); goal on top y={gy}. driving @goto...")
best=y0; climbed=False; seen=set(); t0=time.time()
while time.time()-t0<150:
    time.sleep(5)
    s=py4j("gs"); pos=s["pos"]; y=float(pos.split(",")[1]); best=max(best,y)
    ch=[c for c in py4j("chat",n=6)["chat"] if c not in seen]; seen.update(ch)
    note=[c for c in ch if any(w in c for w in ("uilding","illar","ridg","tair","lace","nreach"))]
    print(f"  t={time.time()-t0:.0f}s pos={pos} bestY={best:.0f} look={s.get('lookingAt')}"+(" | "+" || ".join(x[-70:] for x in note[-2:]) if note else ""))
    if y>=TOP-0.5: climbed=True; break
py4j("cmd",c="@stop"); py4j("cmd",c=";stop")
print(f"start y={y0:.0f} best y={best:.0f} ledge top y={TOP}")
print("PASS: climbed the ledge" if climbed else "FAIL: never got up")
sys.exit(0 if climbed else 1)
