#!/usr/bin/env python3
"""White-box the core_bridge (place-as-a-move) FLAKINESS using the EXISTING Debug messages
the bridge pipeline already emits (no new build needed against the deployed jar):
  - "Path needs bridging: N block(s)"      -> the SEARCH planned a bridge (pendingPlaces set)
  - "At the gap - bridging without a physics leg" -> the handoff to executor.placeQueue fired
  - "Bridge place aborted (...)"           -> tickPlacing gave up (no block / timeout / reach / rules)
  - "Path stopped: drift ..."              -> the physics executor replayed and drifted
If a FAIL shows NONE of the bridge messages -> the SEARCH failed to plan (search non-determinism).
If a FAIL shows "needs bridging" but then abort/drift -> the EXECUTOR failed. This splits the fix.

Sky islands y=100: near x=-4..1 | 7-wide gap x=2..8 | far x=9..16 (z=-4..4). gotoXYZ(13,101,0)
with planPlaceMoves ON + cobblestone in hand. Runs N times, dumps the bridge chat per run.
"""
import functools, json, subprocess, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; BOT="tester1"
N_RUNS=6
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="gotoxyz": out=dict(mc.gotoXYZ(req["x"],req["y"],req["z"]))
elif op=="place": out=dict(mc.setTungstenPlanPlaceMoves(req["on"]))
elif op=="stop": mc.ExecuteCommand("@stop"); out={"ok":True}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(int(req.get("n",60)))]}
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
        return [round(float(v.strip().rstrip("d")),1) for v in p]
    except Exception: return None

def build():
    rcon("forceload add -8 -8 24 8")
    rcon("fill -6 96 -6 20 104 6 air")
    rcon("fill -4 100 -4 1 100 4 stone")
    rcon("fill 9 100 -4 16 100 4 stone")

def run_once(k):
    build()
    py4j("stop"); time.sleep(1.2)
    rcon(f"tp {BOT} 0 101 0 90 0"); time.sleep(1.5)
    rcon(f"clear {BOT}")
    rcon(f"item replace entity {BOT} weapon.mainhand with cobblestone 64")
    py4j("place", on=True); time.sleep(0.5)
    py4j("gotoxyz", x=13, y=101, z=0)
    last=None; maxx=-99; fell=False
    for _ in range(60):
        time.sleep(1)
        p=pos()
        if p:
            last=p
            if p[0]>maxx: maxx=p[0]
            if p[1]<60: fell=True; break
            if p[0]>=9 and p[1]>=99: break
    chat=[]
    for c in py4j("chat",n=60)["chat"]:
        cc=c.replace('§2§l§o','').replace('§r','').replace('§c','').replace('§a','').replace('§e','')
        if any(m in cc for m in ('bridging','bridge','Bridge','needs','drift','physics leg','place','Place','nodes','stub')):
            chat.append(cc.strip())
    py4j("stop"); py4j("place", on=False)
    placed=[]
    for gx in range(2,9):
        for gz in range(-4,5):
            if "passed" in rcon(f"execute if block {gx} 100 {gz} cobblestone").lower():
                placed.append(f"{gx}"); break
    crossed = last is not None and maxx>=9 and not fell
    ok = crossed and len(placed)>=1
    print(f"\n=== run {k}: {'PASS' if ok else 'FAIL'}  final={last} maxX={maxx} fell={fell} placed={placed} ===")
    ded=[]
    for c in chat:
        if not ded or ded[-1]!=c: ded.append(c)
    for c in ded[-12:]: print("   |", c)
    return ok

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(6)
    p=0
    for k in range(1,N_RUNS+1):
        if run_once(k): p+=1
    print(f"\n=== PASS {p}/{N_RUNS} ===")

if __name__=="__main__": main()
