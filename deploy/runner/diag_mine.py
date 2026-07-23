#!/usr/bin/env python3
"""Break primitive (mineBlocks) autotest: a 3-block dirt wall in reach, mineBlocks it, poll
mineStatus, verify the blocks are gone. Bot with a shovel (fast dirt) next to the wall.
"""
import functools, json, subprocess, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; BOT="tester1"
SNIP=r"""
import json,sys,traceback
from py4j.java_gateway import JavaGateway,GatewayParameters
from py4j.protocol import Py4JJavaError
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
try:
    if op=="state": out={"inGame":mc.inGame()}
    elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
    elif op=="mine": out=dict(mc.mineBlocks(req["pos"]))
    elif op=="minestat": out=dict(mc.mineStatus())
    elif op=="stop": mc.ExecuteCommand("@stop"); out={"ok":True}
except Py4JJavaError as e:
    sys.stderr.write("PY4J_JAVA_ERR: "+str(e.java_exception)+"\n"); sys.exit(3)
except Exception as e:
    sys.stderr.write("PY_ERR: "+repr(e)+"\n"); traceback.print_exc(); sys.exit(4)
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=40): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(op,to=30,**kw):
    last=""
    for _ in range(3):
        try:
            r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
            if r.returncode==0 and r.stdout.strip(): return json.loads(r.stdout.strip().splitlines()[-1])
            last=(r.stderr or r.stdout or "").strip()[-500:]
        except Exception as e: last=repr(e)[-500:]
        time.sleep(2)
    raise RuntimeError(f"{op}: {last}")
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def is_air(x,y,z):
    return "passed" in rcon(f"execute if block {x} {y} {z} air").lower()

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(6)
    py4j("stop"); time.sleep(1)
    rcon("forceload add -8 -8 16 16")
    rcon("fill 0 -61 -4 12 5 4 air")
    rcon("fill 0 -61 -4 12 -61 4 stone")        # floor
    wall=[[5,-60,0],[5,-59,0],[5,-58,0]]        # 3-block dirt column at x=5
    for x,y,z in wall: rcon(f"setblock {x} {y} {z} dirt")
    rcon(f"tp {BOT} 3 -60 0 90 0"); time.sleep(1.5)
    rcon(f"item replace entity {BOT} weapon.mainhand with diamond_shovel")
    print("=== mineBlocks: 3-block dirt wall in reach ===")
    print("  before:", [is_air(*p) for p in wall], "(all False = solid)")
    print("  mine:", py4j("mine", pos=wall))
    for _ in range(20):
        time.sleep(1)
        st=py4j("minestat")
        if not st.get("mining"): break
    time.sleep(1)
    gone=[is_air(*p) for p in wall]
    print(f"  after: {gone} (all True = mined)")
    ok = all(gone)
    print(f"  status: {py4j('minestat')}")
    print("  MINE:", "PASS" if ok else "FAIL")
    py4j("stop")

if __name__=="__main__": main()
