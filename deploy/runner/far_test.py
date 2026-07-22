#!/usr/bin/env python3
"""Far-route receding-horizon autotest (py4j gotoFar + pathStatus).

Sends the bot ~60 blocks with a 20-block horizon: gotoFar advances one segment,
the agent polls pathStatus to arrival, then calls gotoFar again until
finalSegment. Verifies the "roughly get there, refine as you approach" lever
reaches a far target without ever asking the pathfinder for the whole route.
Exit 0 = pass.
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
elif op=="gotofar": out=dict(mc.gotoFar(int(req["x"]),int(req["y"]),int(req["z"]),int(req["h"])))
elif op=="status": out=dict(mc.pathStatus())
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=30): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(op,t=25,**kw):
    r=sh(["docker","exec",CLIENT,"python3","-c",SNIP,json.dumps({"op":op,"port":PORT,**kw})],t)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c,t=20): return sh(["docker","exec",SERVER,"rcon-cli",c],t).stdout.strip()
def wait_for(desc,fn,ts,iv=3):
    t0=time.time(); last=None
    while time.time()-t0<ts:
        try:
            last=fn()
            if last: print(f"  [ok] {desc}"); return last
        except Exception as e: last=e
        time.sleep(iv)
    raise TimeoutError(f"{desc}: {ts}s ({last})")
def dist_to(target):
    st=py4j("status"); pos=st.get("pos")
    if not pos: return 999
    x,y,z=[float(v) for v in pos.split(",")]
    return ((x-target[0])**2+(z-target[2])**2)**0.5, st

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    rcon("forceload add 0 0 68 4")
    rcon("fill -2 -61 -3 66 -61 3 stone")     # long 68-block walkway
    rcon("fill -2 -60 -3 66 -55 3 air")
    rcon("time set day"); rcon("weather clear")
    wait_for("py4j", lambda: py4j("state") is not None,600,10)
    if not py4j("state")["inGame"]:
        py4j("connect",ip="test-server"); wait_for("ingame",lambda:py4j("state")["inGame"],180,5); time.sleep(4)
    rcon(f"tp {BOT} 0.5 -60 0.5 -90 0"); time.sleep(3)

    target=(60,-60,0); horizon=20
    segments=0; final=False; t0=time.time()
    while time.time()-t0<180 and segments<8:
        r=py4j("gotofar", x=target[0], y=target[1], z=target[2], h=horizon)
        segments+=1
        final=r.get("finalSegment")
        print(f"  seg {segments}: waypoint={r.get('waypoint')} final={final} remaining={r.get('remainingDist')}")
        # wait to arrive at this segment's waypoint
        seg_t0=time.time(); arrived=False
        while time.time()-seg_t0<50:
            st=py4j("status")
            if st.get("arrived"): arrived=True; break
            time.sleep(3)
        if final and arrived: break
        if not arrived:
            print(f"  segment stalled (not arrived)"); break

    fin_d, fin_st = dist_to(target)
    print(f"\n=== RESULTS ===")
    print(f"  segments={segments} finalSegment={final} final_pos={fin_st.get('pos')} dist_to_target={fin_d:.1f}")
    ok = final and fin_d < 3.0
    print("  FAR:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
