#!/usr/bin/env python3
"""runAwayPlayer test: the bot flees a chasing player, keeping distance, without
falling into the void.

15x15 island (top y=-60) surrounded by void. tester2 punks tester1 (chases +
attacks); tester1 runs away from tester2 keeping ~8 blocks. Success = the bot
never falls off (void-safe flee) and keeps meaningful distance instead of being
instantly cornered.

Bring up the victim container first:
  docker compose -f deploy/compose.test.yml --profile pvp up -d
Usage: runaway_test.py [seconds]  (default 40)
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; C2="uctest-mc-tester2"; BOT="tester1"; THREAT="tester2"
SECS=int(sys.argv[1]) if len(sys.argv)>1 else 40
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="punk": mc.ExecuteCommand("@stop"); mc.punkStop(); mc.punk(req["t"]); out={"ok":True}
elif op=="flee": mc.ExecuteCommand("@stop"); mc.runAwayPlayer(req["t"], float(req.get("d",8))); out={"ok":True}
elif op=="fleestatus": out=dict(mc.runAwayStatus())
elif op=="stop": mc.ExecuteCommand("@stop"); mc.punkStop(); mc.runAwayStop(); out={"ok":True}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=30): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(c,op,to=25,**kw):
    r=sh(["docker","exec",c,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
    if r.returncode!=0: raise RuntimeError(f"{op}@{c}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c,to=20): return sh(["docker","exec",SERVER,"rcon-cli",c],to).stdout.strip()
def efloat(name,path):
    o=rcon(f"data get entity {name} {path}")
    try: return float(o.split(":")[-1].strip().rstrip("dbfs"))
    except Exception: return None
def pos(name):
    o=rcon(f"data get entity {name} Pos")
    try:
        p=o.split("[")[1].split("]")[0].split(",")
        return [float(v.strip().rstrip("d")) for v in p]
    except Exception: return None
def dist(a,b):
    if not a or not b: return None
    return ((a[0]-b[0])**2+(a[2]-b[2])**2)**0.5
def wait_for(desc,fn,ts,iv=4):
    t0=time.time(); last=None
    while time.time()-t0<ts:
        try:
            last=fn()
            if last: print(f"  [ok] {desc}"); return last
        except Exception as e: last=e
        time.sleep(iv)
    raise TimeoutError(f"{desc}: {ts}s ({last})")
def ensure(c,label):
    if not py4j(c,"state")["inGame"]:
        py4j(c,"connect",ip="test-server"); wait_for(f"{label} in game",lambda:py4j(c,"state")["inGame"],180,5); time.sleep(4)

def build_arena():
    rcon("forceload add -20 -20 20 20")
    rcon("fill -18 -64 -18 18 -50 18 air")       # void everywhere
    rcon("fill -7 -61 -7 7 -61 7 stone")         # 15x15 island (top -60)
    rcon("gamerule pvp true"); rcon("gamerule immediate_respawn true")
    rcon("gamerule doDaylightCycle false"); rcon("time set day"); rcon("weather clear")

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    build_arena()
    wait_for("bot py4j", lambda: py4j(C1,"state") is not None,600,10); ensure(C1,"bot")
    wait_for("threat py4j", lambda: py4j(C2,"state") is not None,600,10); ensure(C2,"threat")
    py4j(C1,"stop"); py4j(C2,"stop")
    rcon(f"item replace entity {THREAT} weapon.mainhand with iron_sword")
    rcon(f"tp {THREAT} 3 -60 0 -90 0"); rcon(f"tp {BOT} 0 -60 0 90 0"); time.sleep(2)

    print(f"[flee] threat punks bot; bot runs away, watching {SECS}s...")
    py4j(C2,"punk", t=BOT)          # chaser
    py4j(C1,"flee", t=THREAT, d=8)  # runner
    t0=time.time(); fell=0; minY=-60.0; dsum=0.0; dn=0; dmin=999; dmax=0
    while time.time()-t0<SECS:
        time.sleep(2)
        bp=pos(BOT); tp=pos(THREAT); d=dist(bp,tp)
        by=bp[1] if bp else None
        if by is not None:
            minY=min(minY,by)
            if by<-63: fell+=1
        if d is not None:
            dsum+=d; dn+=1; dmin=min(dmin,d); dmax=max(dmax,d)
        print(f"  t={int(time.time()-t0)}s botY={by} dist={round(d,1) if d else None} fell~{fell}")
    st=py4j(C1,"fleestatus")
    py4j(C1,"stop"); py4j(C2,"stop")

    avg=round(dsum/dn,1) if dn else 0
    print("\n=== RESULTS (runAway) ===")
    print(f"  flee status: {st}")
    print(f"  bot falls (y<-63): {fell}, min botY: {minY:.1f}")
    print(f"  distance to threat  avg={avg} min={round(dmin,1)} max={round(dmax,1)} (keep target ~8)")
    ok = fell==0 and avg>=4.5
    print(f"  SUCCESS (0 falls + avg dist >=4.5): {ok}")

if __name__=="__main__": main()
