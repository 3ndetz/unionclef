#!/usr/bin/env python3
"""Movement lever autotest (py4j gotoXYZ + pathStatus + stopPathing).

Builds a flat stone platform, sends the bot to a far corner via gotoXYZ, and
polls pathStatus() until arrived. Verifies the fire-and-poll movement lever the
cognitive agent uses to tie perception -> action (and to reposition for far
fillSelection cells). Exit 0 = pass.
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
elif op=="goto": out=dict(mc.gotoXYZ(int(req["x"]),int(req["y"]),int(req["z"])))
elif op=="status": out=dict(mc.pathStatus())
elif op=="stop": out=dict(mc.stopPathing())
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

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    rcon("forceload add 0 0 24 24")
    rcon("fill -2 -61 -2 22 -55 22 air")
    rcon("fill -2 -61 -2 22 -61 22 stone")          # flat 24x24 walkable platform
    rcon("gamerule advance_time false"); rcon("weather clear"); rcon("time set day")
    wait_for("py4j", lambda: py4j("state") is not None,600,10)
    if not py4j("state")["inGame"]:
        py4j("connect",ip="test-server"); wait_for("ingame",lambda:py4j("state")["inGame"],180,5); time.sleep(4)
    rcon(f"tp {BOT} 1.5 -60 1.5 -45 0"); time.sleep(3)

    goal=(18,-60,18)
    g=py4j("goto", x=goal[0], y=goal[1], z=goal[2])
    print(f"  gotoXYZ{goal}: {g}")
    arrived=False; last=None
    t0=time.time()
    while time.time()-t0<70:
        last=py4j("status")
        print(f"  pathStatus: busy={last.get('busy')} pos={last.get('pos')} dist={last.get('distance')} arrived={last.get('arrived')}")
        if last.get("arrived"): arrived=True; break
        time.sleep(3)

    print(f"\n=== RESULTS ===")
    print(f"  goal {goal}, final {last.get('pos')}, distance {last.get('distance')}, arrived {arrived}")
    ok = g.get("ok") and arrived
    print("  GOTO:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
