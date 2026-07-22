#!/usr/bin/env python3
"""Deterministic tungsten terrain-traversal probe (#20 gap analysis).

On the FLAT test-server (reliable reconnect) build a set of ascending features
and, with tungsten-primary, goto the top of each — pinpoints which terrain move
the block-space pathfinder can't generate (the @gamer mountain-stuck cause).

Features (built next to spawn):
  A staircase +x: six 1-block steps (walk-up ascend chain)
  B steep    +x: 1-block steps every OTHER x (needs a jump-up each step)
  C wall     +x: a single 2-block vertical step (jump onto a ledge)

  docker compose -f deploy/compose.test.yml up -d
Usage: terrain_test.py
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
elif op=="swap": out=dict(mc.setTungstenPathing(bool(req["on"])))
elif op=="goto": mc.ExecuteCommand("@goto "+str(req["x"])+" "+str(req["y"])+" "+str(req["z"])); out={"ok":True}
elif op=="stop": mc.ExecuteCommand("@stop"); mc.punkStop(); out={"ok":True}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(int(req.get("n",14)))]}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=40): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(c,op,to=30,**kw):
    r=sh(["docker","exec",c,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def pos():
    o=rcon(f"data get entity {BOT} Pos")
    try:
        p=o.split("[")[1].split("]")[0].split(",")
        return [round(float(v.strip().rstrip("d")),1) for v in p]
    except Exception: return None
def wait_for(desc,fn,ts,iv=4):
    t0=time.time()
    while time.time()-t0<ts:
        try:
            if fn(): print(f"  [ok] {desc}"); return
        except Exception: pass
        time.sleep(iv)
    raise TimeoutError(desc)

def build():
    rcon("forceload add -8 -8 40 8")
    # clear a working area and lay a flat floor at y=-61 (top -60)
    rcon("fill 0 -60 -4 40 10 4 air")
    rcon("fill 0 -61 -4 40 -61 4 stone")
    # A: LONG staircase, 12 one-block steps ascending +x from x=2 (accumulate drift)
    for i in range(1,13):
        rcon(f"setblock {1+i} {-61+i} 0 stone")
    # B: steep — 1-block step every other x from x=14 (jump-up each)
    for i in range(1,6):
        rcon(f"setblock {12+2*i} {-61+i} 0 stone")
    # C: single 2-block wall ledge at x=28 (top at -58, needs jump onto ledge)
    rcon("setblock 28 -60 0 stone"); rcon("setblock 28 -59 0 stone")
    rcon("setblock 29 -59 0 stone"); rcon("setblock 30 -59 0 stone")  # landing platform
    rcon("gamerule doDaylightCycle false"); rcon("time set day")

def ensure():
    if not py4j(C1,"state")["inGame"]:
        py4j(C1,"connect",ip="test-server"); wait_for("in game",lambda:py4j(C1,"state")["inGame"],180,5); time.sleep(4)

def trial(name,target,secs=35):
    print(f"--- {name}: goto {target} ---")
    py4j(C1,"stop"); time.sleep(1)
    rcon(f"tp {BOT} 0 -60 0 90 0"); time.sleep(1.5)
    py4j(C1,"goto",x=target[0],y=target[1],z=target[2])
    reached=False; best=-999
    t0=time.time()
    while time.time()-t0<secs:
        time.sleep(3)
        p=pos()
        if p:
            best=max(best,p[1])
            if abs(p[0]-target[0])<=1.5 and p[1]>=target[1]-0.5: reached=True; break
    print(f"  reached={reached} finalPos={pos()} maxY={best} (target y={target[1]})")
    keys=('swap-walk','Walker','drift','nodes','No block','executePath','Rejecting')
    print("  chat:", [c.replace('§2§l§o','').replace('§r','').replace('§c','') for c in py4j(C1,"chat",n=30)["chat"] if any(k in c for k in keys)][-10:])
    return reached

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    ensure(); build()
    print("swap:", py4j(C1,"swap",on=True))
    a=trial("A long staircase (12 1-blk steps)", [13,-48,0], 45)
    b=trial("B steep (jump-up each)",    [22,-56,0])
    c=trial("C 2-block wall ledge",      [29,-58,0])
    # D: invalid goal — a FLOATING air cell (5 blocks above the floor), the user's
    # "click on grass → goal is the air above the surface" case. #25 snaps it to the
    # standable ground below; success = the bot reaches ~(5,-60), not stalls in air.
    print("--- D floating-air goal (5,-55,0) → should snap to ground ---")
    py4j(C1,"stop"); time.sleep(1); rcon(f"tp {BOT} 0 -60 0 90 0"); time.sleep(1.5)
    py4j(C1,"goto",x=5,y=-55,z=0)
    d=False
    for _ in range(12):
        time.sleep(3); p=pos()
        if p and abs(p[0]-5)<=1.6 and p[1]<=-59: d=True; break
    print(f"  D finalPos={pos()} reached-ground={d}")
    py4j(C1,"stop")
    print("\n=== RESULTS (tungsten terrain gap) ===")
    print(f"  A staircase: {'PASS' if a else 'FAIL'}")
    print(f"  B steep    : {'PASS' if b else 'FAIL'}")
    print(f"  C wall     : {'PASS' if c else 'FAIL'}")
    print(f"  D invalid-goal snap: {'PASS' if d else 'FAIL'}")

if __name__=="__main__": main()
