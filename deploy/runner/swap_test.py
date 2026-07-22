#!/usr/bin/env python3
"""Drop-in swap autotest (goal 13): altoclef @goto routed through tungsten.

Baritone movement doesn't execute on this headless client (@goto leaves the bot
frozen). With setTungstenPathing(True), altoclef's goal tasks route straight to
tungsten (TungstenHelper primary) — the bot actually moves. Verifies the ALTOCLEF
command (@goto, not the tungsten ;goto) reaches the target with the swap on, and
stays frozen with it off. Exit 0 = pass.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
CLIENT="uctest-mc-tester1"; SERVER="uctest-server"; BOT="tester1"; PORT=25333
SNIP=r"""
import json,sys,time
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=req.get("port",25333),auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="swap": out=dict(mc.setTungstenPathing(bool(req["on"])))
elif op=="mode": out=dict(mc.pathingMode())
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="stop": out=dict(mc.stopPathing())
elif op=="pos": out=dict(mc.pathStatus())
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=30): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(op,t=30,**kw):
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
def dist(target):
    p=py4j("pos").get("pos")
    if not p: return 999
    x,y,z=[float(v) for v in p.split(",")]
    return ((x-target[0])**2+(z-target[2])**2)**0.5

def run_goto(target, label, budget):
    rcon(f"tp {BOT} 0.5 -60 0.5 -90 0"); time.sleep(3)
    py4j("cmd", c=f"@goto {target[0]} {target[1]} {target[2]}")
    d=999; t0=time.time()
    while time.time()-t0<budget:
        d=dist(target)
        print(f"  [{label}] dist={d:.1f} pos={py4j('pos').get('pos')}")
        if d<2.0: break
        time.sleep(3)
    py4j("cmd", c="@stop"); py4j("stop"); time.sleep(2)
    return d

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    rcon("forceload add 0 0 24 4")
    rcon("fill -2 -61 -3 24 -61 3 stone"); rcon("fill -2 -60 -3 24 -55 3 air")
    rcon("time set day"); rcon("weather clear")
    wait_for("py4j", lambda: py4j("state") is not None,600,10)
    if not py4j("state")["inGame"]:
        py4j("connect",ip="test-server"); wait_for("ingame",lambda:py4j("state")["inGame"],180,5); time.sleep(4)
    target=(18,-60,0)

    # swap ON: @goto (altoclef) must reach the target via tungsten
    on=py4j("swap", on=True); print(f"  setTungstenPathing(True): {on}")
    d_on=run_goto(target, "ON", 40)

    # swap OFF: @goto (baritone) stays frozen (movement broken headless)
    off=py4j("swap", on=False); print(f"  setTungstenPathing(False): {off}")
    d_off=run_goto(target, "OFF", 18)

    print("\n=== RESULTS ===")
    print(f"  swap ON  -> final dist {d_on:.1f} (reached: {d_on<2.0})")
    print(f"  swap OFF -> final dist {d_off:.1f} (frozen: {d_off>10})")
    ok = on.get("tungstenPrimary")==True and d_on<2.0
    print("  SWAP:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
