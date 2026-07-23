#!/usr/bin/env python3
"""Ranged combat vs a MOVING target: fighter with a bow shoots a victim that strafes,
via the shootArrowAt primitive (BowShooter: TrajectorySolver lead-aim, charge, release
on-solution). Validates #6.7/#21 ranged for a MOVING target (the existing check was a
STANDING target). Victim moves GENTLY (3 blocks / 3s) so the ballistic solution can
converge between steps — arrows have travel time and the release only fires on-solution.

PASS: victim takes >= MIN_DAMAGE over the window and at least one arrow lands.
Requires the pvp profile. Exit 0 = pass.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)

SERVER="uctest-server"; F="tester1"; FC="uctest-mc-tester1"; V="tester2"; VC="uctest-mc-tester2"
PORT=25333
WINDOW_S=70; STEP_S=3.0; STEP_BLOCKS=3; MIN_DAMAGE=6.0
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=req.get("port",25333),auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="exec": mc.ExecuteCommand(req["cmd"]); out={"ok":True}
elif op=="shoot": out={"ok":bool(mc.shootArrowAt(req["name"]))}
elif op=="recent": out={"chat":[str(x) for x in mc.getRecentChat(int(req.get("n",40)))]}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=30): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(c,op,to=25,**kw):
    r=sh(["docker","exec",c,"python3","-c",SNIP,json.dumps({"op":op,"port":PORT,**kw})],to)
    if r.returncode!=0: raise RuntimeError(f"py4j {c} {op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c,to=20): return sh(["docker","exec",SERVER,"rcon-cli",c],to).stdout.strip()
def hp(name):
    try: return float(rcon(f"data get entity {name} Health").rsplit(":",1)[-1].strip().rstrip("fd"))
    except Exception: return 0.0
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
              "fill -26 -61 -12 26 -61 12 stone",
              "gamerule natural_health_regeneration false","gamerule pvp true",
              "gamerule immediate_respawn true","weather clear","time set day"]:
        rcon(c)
    ensure(FC,"fighter"); ensure(VC,"victim")
    py4j(FC,"exec",cmd="@stop"); py4j(FC,"exec",cmd=";stop")
    rcon(f"kill {F}"); rcon(f"kill {V}")
    for _ in range(20):
        if hp(F)>=19.9 and hp(V)>=19.9: break
        time.sleep(2)
    rcon(f"clear {F}")
    rcon(f"give {F} bow")
    rcon(f"give {F} arrow 64")
    rcon(f"item replace entity {F} weapon.mainhand with bow")
    # fighter fixed; victim strafes ~12 blocks away in a line
    rcon(f"tp {F} 0.5 -60 0.5 0 0")
    rcon(f"tp {V} 0.5 -60 12.5 180 0")
    time.sleep(3)
    hp0=hp(V); print(f"  victim hp0={hp0}, fighter shooting bow...")
    t0=time.time(); min_hp=hp0; first=None; shots=0; z=12
    dz=STEP_BLOCKS
    while time.time()-t0 < WINDOW_S:
        # gentle strafe along z between 6..16
        z+=dz
        if z>=16 or z<=6: dz=-dz
        try: rcon(f"tp {V} 0.5 -60 {z}.5 180 0")
        except Exception: pass
        try:
            if py4j(FC,"shoot",name=V)["ok"]: shots+=1
        except Exception: pass
        time.sleep(STEP_S)
        h=hp(V)
        if h < min_hp-0.01:
            min_hp=h
            if first is None: first=time.time()-t0; print(f"  first arrow hit at {first:.1f}s (hp {h})")
        if h<=0.5:
            print(f"  victim dead at {time.time()-t0:.1f}s"); break
    py4j(FC,"exec",cmd=";stop")
    dmg=hp0-min_hp
    print(f"\n=== RESULTS ===")
    print(f"  shots requested: {shots}, damage dealt: {dmg:.1f} (min {MIN_DAMAGE})")
    print(f"  first hit: {f'{first:.1f}s' if first else 'never'}")
    ok = dmg>=MIN_DAMAGE and first is not None
    if not ok:
        for l in py4j(FC,"recent",n=15).get("chat",[]): print("  chat|",l)
    print("  BOW_MOVING:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
