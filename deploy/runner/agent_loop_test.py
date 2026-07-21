#!/usr/bin/env python3
"""Cognitive-agent loop integration test — see -> move -> build.

The exact bedwars micro-scenario, driven purely through agent levers:
  1. getGameState() -> find the enemy bed in beds[]
  2. gotoXYZ() to a cell next to the bed, poll pathStatus() until arrived
  3. buildDefenseAround(bed) -> box the bed
Verifies the composed workplace (perception -> movement -> building) works as a
whole, not just each lever in isolation. Exit 0 = pass.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
CLIENT="uctest-mc-tester1"; SERVER="uctest-server"; BOT="tester1"; PORT=25333
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=req.get("port",25333),auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="gamestate":
    gs=mc.getGameState()
    out={"inGame":gs.get("inGame"),
         "self":dict(gs.get("self") or {}),
         "beds":[dict(b) for b in (gs.get("beds") or [])],
         "players":[dict(p) for p in (gs.get("players") or [])]}
elif op=="goto": out=dict(mc.gotoXYZ(int(req["x"]),int(req["y"]),int(req["z"])))
elif op=="status": out=dict(mc.pathStatus())
elif op=="selhot": out={"ok":mc.selectHotbar(int(req["s"]))}
elif op=="defend": out=dict(mc.buildDefenseAround(int(req["x"]),int(req["y"]),int(req["z"])))
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=30): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(op,t=30,**kw):
    r=sh(["docker","exec",CLIENT,"python3","-c",SNIP,json.dumps({"op":op,"port":PORT,**kw})],t)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c,t=20): return sh(["docker","exec",SERVER,"rcon-cli",c],t).stdout.strip()
def is_solid(x,y,z): return "passed" not in rcon(f"execute if block {x} {y} {z} air").lower()
def wait_for(desc,fn,ts,iv=3):
    t0=time.time(); last=None
    while time.time()-t0<ts:
        try:
            last=fn()
            if last: print(f"  [ok] {desc}"); return last
        except Exception as e: last=e
        time.sleep(iv)
    raise TimeoutError(f"{desc}: {ts}s ({last})")

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    rcon("forceload add 0 0 24 24")
    rcon("fill -2 -61 -2 22 -55 22 air")
    rcon("fill -2 -61 -2 22 -61 22 stone")
    rcon("weather clear"); rcon("time set day")
    # place the "enemy bed" (foot+head) at a known spot on the platform
    bx,by,bz = 16,-60,16
    rcon(f"setblock {bx} {by} {bz} red_bed[facing=east,part=foot]")
    rcon(f"setblock {bx+1} {by} {bz} red_bed[facing=east,part=head]")
    wait_for("py4j", lambda: py4j("state") is not None,600,10)
    if not py4j("state")["inGame"]:
        py4j("connect",ip="test-server"); wait_for("ingame",lambda:py4j("state")["inGame"],180,5); time.sleep(4)
    rcon(f"clear {BOT}"); rcon(f"item replace entity {BOT} hotbar.0 with dirt 64")
    rcon(f"tp {BOT} 2.5 -60 2.5 -45 0"); time.sleep(3)
    py4j("selhot",s=0)

    # 1. SEE — perception finds the bed
    gs = py4j("gamestate")
    beds = gs.get("beds") or []
    print(f"  getGameState: self={gs.get('self',{}).get('pos')} beds={beds}")
    if not beds:
        print("  FAIL: perception saw no bed"); sys.exit(1)
    bedpos = beds[0].get("pos") if isinstance(beds[0],dict) else beds[0][0]
    # bed pos is "x,y,z" of the foot — navigate next to it
    fx,fy,fz = [int(round(float(v))) for v in str(bedpos).split(",")]
    print(f"  target bed foot: {fx},{fy},{fz}")

    # 2. MOVE — go to a cell adjacent to the bed
    gx,gy,gz = fx-2, fy, fz    # stand 2 west of the bed
    py4j("goto", x=gx, y=gy, z=gz)
    arrived=False; last=None; t0=time.time()
    while time.time()-t0<70:
        last=py4j("status")
        print(f"  pathStatus: pos={last.get('pos')} dist={last.get('distance')} arrived={last.get('arrived')}")
        if last.get("arrived"): arrived=True; break
        time.sleep(3)
    if not arrived:
        print(f"  FAIL: didn't reach bed vicinity (dist {last.get('distance')})"); sys.exit(1)

    # 3. BUILD — box the bed
    py4j("selhot",s=0)
    d = py4j("defend", x=fx, y=fy, z=fz)
    print(f"  buildDefenseAround: {d}")
    time.sleep(1)
    # verify the 4 horizontal neighbours of the bed foot got covered (solid)
    shell = [(fx+1,fy,fz),(fx-1,fy,fz),(fx,fy,fz+1),(fx,fy,fz-1)]
    covered = sum(1 for (x,y,z) in shell if is_solid(x,y,z))
    print(f"\n=== RESULTS ===")
    print(f"  saw bed: yes | reached: {arrived} | shell covered: {covered}/4")
    ok = bool(beds) and arrived and covered>=3   # >=3 (one side may be the bed head/out of reach)
    print("  AGENT_LOOP:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
