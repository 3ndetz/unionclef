#!/usr/bin/env python3
"""Aim-jitter (shake) QUANTIFIER: fighter ;punkPlayer a moving victim; sample the fighter's
per-tick yaw (getAimSamples) and compute the Δyaw sign-reversal rate. A smooth aim tracking
a target turns near-monotonically (few reversals/s); the reported "прицел трясёт" shake is
excessive high-frequency reversals. Validates the v0.47/0.48 aim smoothing.

PASS: reversal rate below MAX_REV_PER_S (smooth). Requires the pvp profile. Exit 0 = pass.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; FC="uctest-mc-tester1"; F="tester1"; VC="uctest-mc-tester2"; V="tester2"
PORT=25333
FIGHT_S=14; STEP_S=2.0; MAX_REV_PER_S=10.0; MIN_DYAW=0.5   # deg, below = noise
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=req.get("port",25333),auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="chat": mc.ChatMessage(req["msg"]); out={"ok":True}
elif op=="samples": out={"yaws":[float(x) for x in mc.getAimSamples(int(req["n"]))]}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=30): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(c,op,to=25,**kw):
    r=sh(["docker","exec",c,"python3","-c",SNIP,json.dumps({"op":op,"port":PORT,**kw})],to)
    if r.returncode!=0: raise RuntimeError(f"py4j {c} {op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def ensure(c,lbl):
    for _ in range(30):
        try:
            if py4j(c,"state")["inGame"]: print(f"  {lbl} in-game"); return
        except Exception: pass
        try: py4j(c,"connect",ip="test-server")
        except Exception: pass
        time.sleep(6)
def wrap(d):
    d%=360.0
    if d>180: d-=360
    if d<=-180: d+=360
    return d

def main():
    for _ in range(60):
        if "players" in rcon("list"): break
        time.sleep(5)
    for c in ["forceload add -32 -16 32 16","fill -26 -60 -12 26 -45 12 air",
              "fill -26 -61 -12 26 -61 12 stone","gamerule pvp true","weather clear","time set day"]:
        rcon(c)
    ensure(FC,"fighter"); ensure(VC,"victim")
    py4j(FC,"chat",msg=";stop")
    rcon(f"tp {F} 0.5 -60 0.5 0 0"); rcon(f"tp {V} 0.5 -60 6.5 180 0"); time.sleep(2)
    print("=== aim jitter: ;punkPlayer a gently strafing victim ===")
    py4j(FC,"chat",msg=f";punkPlayer {V}")
    t0=time.time(); z=6; dz=2
    while time.time()-t0 < FIGHT_S:
        z+=dz
        if z>=10 or z<=3: dz=-dz
        try: rcon(f"tp {V} {2.0} -60 {z}.5 180 0")
        except Exception: pass
        time.sleep(STEP_S)
    n=int((time.time()-t0)*20)
    yaws=py4j(FC,"samples",n=min(n,300))["yaws"]
    py4j(FC,"chat",msg=";stop")
    # reversal rate over meaningful Δyaw
    dys=[wrap(yaws[i+1]-yaws[i]) for i in range(len(yaws)-1)]
    sig=[(1 if d>MIN_DYAW else (-1 if d<-MIN_DYAW else 0)) for d in dys]
    prev=0; rev=0; moved=0
    for s in sig:
        if s==0: continue
        moved+=1
        if prev!=0 and s!=prev: rev+=1
        prev=s
    secs=max(1.0, len(yaws)/20.0)
    rate=rev/secs
    path=sum(abs(d) for d in dys); net=abs(wrap(yaws[-1]-yaws[0])) if yaws else 0
    print(f"\n=== RESULTS ===")
    print(f"  samples: {len(yaws)} (~{secs:.0f}s), meaningful moves: {moved}")
    print(f"  reversals: {rev} -> {rate:.1f}/s (limit {MAX_REV_PER_S})")
    print(f"  angular path: {path:.0f} deg, net: {net:.0f} deg (path/net = churn)")
    ok = len(yaws)>=40 and rate <= MAX_REV_PER_S
    print("  AIM_JITTER:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
