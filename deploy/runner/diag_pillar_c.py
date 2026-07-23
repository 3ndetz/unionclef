#!/usr/bin/env python3
"""Course C (2-block vertical wall) — does the bot climb it WITH a block in hand?
A sprint-jump apex is ~1.25 blocks, so a 2-block wall is physically unjumpable — it needs
placing (pillar-up beside the wall + step onto the ledge, or a step block). This checks the
CURRENT state (reactive PillarTask / place-as-a-move) before building the pillar feature.

Build: floor y=-61 (top -60). Wall at x=20: blocks y=-60,-59 (top -58). Landing platform
x=21..23 at top -58. Bot starts at (14,-60,0) with cobblestone; goto the platform (22,-58,0).
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
elif op=="place": out=dict(mc.setTungstenPlanPlaceMoves(req["on"]))
elif op=="goto": mc.ExecuteCommand("@goto "+str(req["x"])+" "+str(req["y"])+" "+str(req["z"])); out={"ok":True}
elif op=="stop": mc.ExecuteCommand("@stop"); mc.punkStop(); out={"ok":True}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(int(req.get("n",30)))]}
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
    rcon("fill 20 -60 -2 20 -59 2 stone")     # 2-block wall at x=20
    rcon("fill 21 -59 -2 24 -59 2 stone")     # landing platform top -58
    rcon(f"item replace entity {BOT} weapon.mainhand with cobblestone 64")

def run(k):
    py4j("stop"); time.sleep(1)
    for _ in range(5):
        rcon(f"tp {BOT} 14 -60 0 90 0"); time.sleep(1.2)
        p=pos()
        if p and abs(p[0]-14)<3 and abs(p[1]+60)<2: break
    rcon(f"item replace entity {BOT} weapon.mainhand with cobblestone 64")
    py4j("place", on=True); time.sleep(0.5)
    py4j("goto", x=22, y=-58, z=0)
    maxY=-999; last=None
    for _ in range(35):
        time.sleep(1); p=pos()
        if p:
            last=p
            if p[1]>maxY: maxY=p[1]
            if abs(p[0]-22)<=2 and p[1]>=-58.5: break
    chat=[c for c in py4j("chat",n=30)["chat"] if any(m in c for m in ('illar','ridg','lace','laced','give','stuck','No block','nodes'))]
    py4j("stop"); py4j("place", on=False)
    reached = last is not None and abs(last[0]-22)<=2 and last[1]>=-58.5
    print(f"  run {k}: {'PASS' if reached else 'FAIL'}  final={last} maxY={maxY:.1f} (target y=-58)")
    for c in chat[-6:]: print("    |", c.replace('§2§l§o','').replace('§r','').replace('§c','').replace('§a',''))
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
    print("=== course C (2-block wall) with block in hand + planPlaceMoves ===")
    p=sum(run(k) for k in range(1,4))
    print(f"=== PASS {p}/3 ===")

if __name__=="__main__": main()
