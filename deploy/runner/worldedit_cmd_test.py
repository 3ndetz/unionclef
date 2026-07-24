#!/usr/bin/env python3
"""`;;` WorldEdit command handler test (via the we() entry that the chat path also uses):
;;pos1 / ;;pos2 (corners at player block) -> ;;set stone -> ;;replace stone cobblestone.
Verifies the command handler wraps the primitives correctly.
"""
import functools, json, subprocess, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; BOT="tester1"
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
try:
    if op=="state": out={"inGame":mc.inGame()}
    elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
    elif op=="stop": mc.ExecuteCommand("@stop"); out={"ok":True}
    elif op=="we": out=dict(mc.we(req["cmd"]))
except Exception as e:
    sys.stderr.write("ERR:"+repr(e)+"\n"); sys.exit(3)
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=40): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(op,t=30,**kw):
    last=""
    for _ in range(3):
        try:
            r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],t)
            if r.returncode==0 and r.stdout.strip(): return json.loads(r.stdout.strip().splitlines()[-1])
            last=(r.stderr or r.stdout or "").strip()[-300:]
        except Exception as e: last=repr(e)[-300:]
        time.sleep(2)
    raise RuntimeError(f"{op}: {last}")
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def is_block(x,y,z,name): return "passed" in rcon(f"execute if block {x} {y} {z} minecraft:{name}").lower()
def tp(x,y,z): rcon(f"tp {BOT} {x} {y} {z} 90 0"); time.sleep(1.2)

def main():
    for _ in range(40):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(5)
    py4j("stop"); time.sleep(1)
    rcon("forceload add -8 -8 16 16")
    rcon("fill 0 -61 -4 12 5 4 air")
    rcon("fill 0 -61 -4 12 -61 4 stone")           # floor
    cells=[(5,-60,0),(6,-60,0),(7,-60,0)]
    print("=== ;; WorldEdit command handler ===")
    tp(5,-60,0); print("  pos1:", py4j("we",cmd="pos1"))
    tp(7,-60,0); print("  pos2:", py4j("we",cmd="pos2"))
    tp(6,-60,2)                                     # stand adjacent, in reach
    rcon(f"item replace entity {BOT} hotbar.1 with stone 64")
    rcon(f"item replace entity {BOT} hotbar.2 with cobblestone 64")
    print("  set stone:", py4j("we",cmd="set stone"))
    time.sleep(2)
    set_ok=all(is_block(*c,"stone") for c in cells)
    print(f"  after ;;set: stone={[is_block(*c,'stone') for c in cells]} -> {'OK' if set_ok else 'FAIL'}")
    print("  replace stone cobblestone:", py4j("we",cmd="replace stone cobblestone"))
    for _ in range(20):
        time.sleep(1.5)
        if all(is_block(*c,"cobblestone") for c in cells): break
    rep_ok=all(is_block(*c,"cobblestone") for c in cells)
    print(f"  after ;;replace: cobble={[is_block(*c,'cobblestone') for c in cells]} -> {'OK' if rep_ok else 'FAIL'}")
    ok=set_ok and rep_ok
    print("  WE_CMD:", "PASS" if ok else "FAIL")
    py4j("stop")

if __name__=="__main__": main()
