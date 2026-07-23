#!/usr/bin/env python3
"""Follow-nav DIAGNOSTIC: enable the walker's per-tick DEBUG, run @follow vs a moving
victim, and summarise WHY the follower's average speed is low (gating? not sprinting?
stuck-bails? LOS loss?). Not pass/fail — prints the per-tick decision distribution.

Arena has a FLOOR (the plain follow arena only clears air, so a wandering bot can fall
into natural-terrain holes — seen as y=101 respawns).
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)

SERVER="uctest-server"; F="tester1"; FC="uctest-mc-tester1"; V="tester2"; VC="uctest-mc-tester2"
PORT=25333
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=req.get("port",25333),auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="exec": mc.ExecuteCommand(req["cmd"]); out={"ok":True}
elif op=="wdbg": out=dict(mc.setWalkerDebug(bool(req["on"])))
elif op=="recent": out={"chat":[str(x) for x in mc.getRecentChat(int(req.get("n",300)))]}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=30): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(c,op,to=25,**kw):
    r=sh(["docker","exec",c,"python3","-c",SNIP,json.dumps({"op":op,"port":PORT,**kw})],to)
    if r.returncode!=0: raise RuntimeError(f"py4j {c} {op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c,to=20):
    r=sh(["docker","exec",SERVER,"rcon-cli",c],to); return r.stdout.strip()
def ensure(c,lbl):
    for _ in range(30):
        try:
            if py4j(c,"state")["inGame"]: print(f"  {lbl} in-game"); return
        except Exception: pass
        try: py4j(c,"connect",ip="test-server")
        except Exception: pass
        time.sleep(6)

def main():
    for _ in range(60):
        if "players" in rcon("list"): break
        time.sleep(5)
    for c in ["forceload add -32 -16 32 16",
              "fill -26 -60 -12 26 -45 12 air",
              "fill -26 -61 -12 26 -61 12 stone",   # FLOOR (was missing)
              "gamerule advance_time false","weather clear","time set day"]:
        rcon(c)
    ensure(FC,"follower"); ensure(VC,"victim")
    py4j(FC,"exec",cmd="@stop"); py4j(FC,"exec",cmd="@stop")
    rcon(f"tp {F} -20.5 -60 0.5 0 0"); rcon(f"tp {V} -20.5 -60 -7.5 0 0"); time.sleep(3)
    py4j(FC,"wdbg",on=True)
    print("walker debug ON; starting @follow chase...")
    py4j(FC,"exec",cmd=f"@follow {V}")
    # rectangle loop, ~3 b/s
    pts=[]
    for x in range(-20,21,6): pts.append((x,-8))
    for z in range(-8,9,6): pts.append((20,z))
    for x in range(20,-21,-6): pts.append((x,8))
    i=0; t0=time.time()
    while time.time()-t0 < 24:
        x,z=pts[i%len(pts)]; i+=1
        rcon(f"tp {V} {x+0.5} -60 {z+0.5}"); time.sleep(2)
    py4j(FC,"exec",cmd="@stop")
    chat=py4j(FC,"recent",n=300).get("chat",[])
    py4j(FC,"wdbg",on=False)
    # parse "dir live%d d%.1f yawErr%.0f move%d grnd%d stuck%d spd%.2f los%d"
    def field(toks, pre):
        for t in toks:
            if t.startswith(pre):
                rest = t[len(pre):]
                if rest.replace(".","",1).replace("-","",1).isdigit():
                    return float(rest)
        return None
    rows=[]
    for line in chat:
        k=line.find("dir live")
        if k<0: continue
        toks=line[k:].split()   # dir live1 d5.0 yawErr30 move1 grnd1 stuck0 spd0.28 los1
        d=field(toks,"d"); ye=field(toks,"yawErr"); mv=field(toks,"move")
        st=field(toks,"stuck"); sp=field(toks,"spd"); lo=field(toks,"los")
        if None in (d,ye,mv,st,sp,lo): continue
        rows.append((d,ye,int(mv),int(st),sp,int(lo)))
    print(f"\n=== WALKER tickDirect samples: {len(rows)} ===")
    if rows:
        n=len(rows)
        mv=sum(r[2] for r in rows)
        moving=sum(1 for r in rows if r[4]>0.05)
        los=sum(r[5] for r in rows)
        print(f"  move-gate OPEN (move=1): {mv}/{n} = {100*mv/n:.0f}%")
        print(f"  physically moving (spd>0.05): {moving}/{n} = {100*moving/n:.0f}%")
        print(f"  LOS to target: {los}/{n} = {100*los/n:.0f}%")
        print(f"  avg spd: {sum(r[4] for r in rows)/n:.2f} blk/tick (sprint~0.28)")
        print(f"  avg yawErr: {sum(r[1] for r in rows)/n:.0f} deg")
        print(f"  avg dist: {sum(r[0] for r in rows)/n:.1f}")
        print(f"  stuck>0 ticks: {sum(1 for r in rows if r[3]>0)}/{n}")
        # sample tail
        print("  last 6 rows (d,yawErr,move,stuck,spd,los):")
        for r in rows[-6:]: print(f"    {r}")
    else:
        print("  NO tickDirect debug rows — walker never ran DIRECT (BFS/physics owned it?)")
        for l in chat[-15:]: print("  chat|",l)

if __name__=="__main__": main()
