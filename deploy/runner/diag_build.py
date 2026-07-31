#!/usr/bin/env python3
"""Schematic placement core (buildBlocks) autotest: build a mixed-block structure from a
[[x,y,z,name],...] list, verify each cell is the right block. Bot has stone + cobblestone
in the hotbar (buildBlocks equips per block). Validates the schematic executor primitive.
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
    elif op=="build": out=dict(mc.buildBlocks(req["blocks"]))
    elif op=="bq": out=dict(mc.buildQueue())
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
def block_is(x,y,z,name):
    return "passed" in rcon(f"execute if block {x} {y} {z} minecraft:{name}").lower()

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(6)
    # hard-confirm in-game before setup (a restart-fresh bot connects late)
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
    rcon("fill 0 -61 -4 12 -61 4 stone")          # floor
    rcon(f"tp {BOT} 3 -60 0 90 0"); time.sleep(2)
    rcon(f"item replace entity {BOT} hotbar.1 with stone 64")
    rcon(f"item replace entity {BOT} hotbar.2 with cobblestone 64")
    # structure: stone column (5,-60/-59) + cobblestone cap (5,-58) + a stone step (6,-60)
    struct=[[5,-60,0,"stone"],[5,-59,0,"stone"],[5,-58,0,"cobblestone"],[6,-60,0,"stone"]]
    print("=== buildBlocks: mixed-block structure ===")
    # buildBlocks hands the cells to the tick-driven queue (4 ticks a block, BlockPlaceHelper),
    # and the queue WALKS ITSELF to a position each cell is placeable from — the port of
    # baritone's BuilderProcess placement goal. So the test does NOT reposition the bot: two
    # owners of movement fight, and a manual tp yanks the builder off the path it just planned.
    # One hand-off, then watch.
    r=py4j("build", blocks=struct)
    print(f"  build (enqueued): {r}")
    q=None
    t0=time.time()
    while time.time()-t0 < 180:
        q=py4j("bq")
        if q.get("done"): break
        time.sleep(2)
    print(f"  buildQueue: {q}")
    checks={(5,-60,0):"stone",(5,-59,0):"stone",(5,-58,0):"cobblestone",(6,-60,0):"stone"}
    results={p:block_is(p[0],p[1],p[2],n) for p,n in checks.items()}
    print("  verify:", results)
    ok=all(results.values())
    print("  BUILD:", "PASS" if ok else "FAIL")
    py4j("stop")

if __name__=="__main__": main()
