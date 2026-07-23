#!/usr/bin/env python3
"""Ground-truth trace of course A (the 1-block staircase) — WHY the walker fails to climb.

Samples the bot every ~0.4s during a ;goto up the staircase: position, onGround (via rcon
Motion≈0 heuristic is unreliable, so we infer from Y stability), Y, and the walker chat
(mode transitions, BFS wp counts). Prints a compact per-sample trace so we can see if the
bot climbs then overshoots, stalls on a step, or gets a degenerate BFS stub.

Staircase (same as terrain_test build):
  floor stone y=-61 (top -60); step i (1..12): setblock (1+i) (-61+i) 0 stone
  -> tops ascend x=2..13, y=-59..-48. Target = top of last step (13,-48,0).
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
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(int(req.get("n",30)))]}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=40): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(op,to=30,**kw):
    r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def getf(path):
    o=rcon(f"data get entity {BOT} {path}")
    try:
        p=o.split("[")[1].split("]")[0].split(",")
        return [round(float(v.strip().rstrip("d")),2) for v in p]
    except Exception: return None
def onground():
    o=rcon(f"data get entity {BOT} OnGround")
    return "1b" in o or "true" in o.lower()

def build():
    rcon("forceload add -8 -8 40 8")
    rcon(f"clear {BOT}")
    rcon("fill 0 -60 -4 40 10 4 air")
    rcon("fill 0 -61 -4 40 -61 4 stone")
    for i in range(1,13):
        rcon(f"setblock {1+i} {-61+i} 0 stone")
    rcon("gamerule doDaylightCycle false"); rcon("time set day")

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
    py4j("stop"); time.sleep(1)
    rcon(f"tp {BOT} 0 -60 0 90 0"); time.sleep(1.5)
    print("goto (13,-48,0) — top of the staircase")
    py4j("goto", x=13, y=-48, z=0)
    trace=[]
    for k in range(45):   # ~18s at 0.4s
        p=getf("Pos"); v=getf("Motion"); g=onground()
        if p:
            trace.append((round(k*0.4,1), p[0], p[1], p[2], (v[1] if v else 0.0), 1 if g else 0))
        time.sleep(0.4)
    py4j("stop")
    print("  t     x      y      z     vy    grnd")
    for t,x,y,z,vy,g in trace:
        bar = "#"*int(max(0,(y+61))*2)
        print(f"  {t:4.1f} {x:6.2f} {y:6.2f} {z:5.2f} {vy:6.3f}  {g}  {bar}")
    ys=[y for _,_,y,_,_,_ in trace]
    xs=[x for _,x,_,_,_,_ in trace]
    print(f"  maxY={max(ys):.2f} finalX={xs[-1]:.2f} finalY={ys[-1]:.2f} (target x=13 y=-48)")
    print("  walker chat:")
    for c in py4j("chat",n=40)["chat"]:
        cc=c.replace('§2§l§o','').replace('§r','').replace('§c','').replace('§a','')
        if any(k in cc for k in ('Walker','drift','No block','executePath','Rejecting','nodes','stub','giving up','stuck')):
            print("   |", cc)

if __name__=="__main__": main()
