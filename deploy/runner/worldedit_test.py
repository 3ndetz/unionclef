#!/usr/bin/env python3
"""WorldEdit-like //set autotest (py4j select + fillSelection).

Bot on a stone floor with dirt in hand selects a small reachable region and
fills it. Verifies the cells became dirt. (Larger regions: the agent
repositions and calls fillSelection again for out-of-reach cells.)
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
elif op=="selhot": out={"ok":mc.selectHotbar(int(req["s"]))}
elif op=="select": out=dict(mc.select(*[int(v) for v in req["r"]]))
elif op=="fill": out=dict(mc.fillSelection(req["b"]))
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
def is_block(c): return "passed" in rcon(f"execute if block {c}").lower()

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    rcon("forceload add 0 0 8 8")
    rcon("fill -2 -61 -2 6 -55 6 air")
    rcon("fill -2 -61 -2 6 -61 6 stone")
    rcon("gamerule advance_time false"); rcon("weather clear"); rcon("time set day")
    wait_for("py4j", lambda: py4j("state") is not None,600,10)
    if not py4j("state")["inGame"]:
        py4j("connect",ip="test-server"); wait_for("ingame",lambda:py4j("state")["inGame"],180,5); time.sleep(4)
    rcon(f"clear {BOT}"); rcon(f"item replace entity {BOT} hotbar.0 with dirt 64")
    rcon(f"tp {BOT} 0.5 -60 0.5 -45 0"); time.sleep(3)
    py4j("selhot",s=0)

    # select a small 2x1x2 region on the floor within reach, fill with dirt
    sel = py4j("select", r=[1,-60,1, 2,-60,2])
    print(f"  select: {sel}")
    res = py4j("fill", b="dirt")
    print(f"  fillSelection: {res}")
    time.sleep(1)
    cells = [(1,-60,1),(2,-60,1),(1,-60,2),(2,-60,2)]
    got = sum(1 for (x,y,z) in cells if is_block(f"{x} {y} {z} dirt"))
    print(f"\n=== RESULTS ===")
    print(f"  region volume {sel.get('volume')}, filled {res.get('filled')}, cells dirt {got}/4")
    ok = sel.get("ok") and res.get("ok") and got>=4
    print("  WORLDEDIT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
