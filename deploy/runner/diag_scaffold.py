#!/usr/bin/env python3
"""Scaffolding-cleanup autotest: bot pillars up (PillarTask places scaffold blocks, recorded
in ScaffoldRegistry), then cleanupScaffold mines them back out — verify the pillar is GONE,
scaffoldCount back to 0, and the cleanup does NOT loop (bounded time). Bot has cobblestone.
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
    elif op=="pillar": out={"ok":bool(mc.pillarTo(int(req["y"])))}
    elif op=="pillaract": out={"active":bool(mc.pillarActive())}
    elif op=="scount": out={"n":int(mc.scaffoldCount())}
    elif op=="cleanup": out=dict(mc.cleanupScaffold())
    elif op=="minestat": out=dict(mc.mineStatus())
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
def is_air(x,y,z): return "passed" in rcon(f"execute if block {x} {y} {z} air").lower()

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
    rcon("fill 0 -61 -4 12 10 4 air")
    rcon("fill 0 -61 -4 12 -61 4 stone")           # floor
    rcon(f"tp {BOT} 5 -60 0 90 0"); time.sleep(1.5)
    rcon(f"item replace entity {BOT} hotbar.1 with cobblestone 64")
    rcon(f"item replace entity {BOT} weapon.mainhand with diamond_pickaxe")
    print("=== scaffolding cleanup ===")
    print("  pillarTo -55:", py4j("pillar", y=-55))
    for _ in range(20):
        time.sleep(1.5)
        if not py4j("pillaract")["active"]: break
    time.sleep(1)
    scount = py4j("scount")["n"]
    col=[(5,y,0) for y in range(-60,-54)]
    solid_before = sum(0 if is_air(*c) else 1 for c in col)
    print(f"  after pillar: scaffoldCount={scount}, solid pillar cells={solid_before}")
    if scount == 0 or solid_before == 0:
        print("  (no scaffold created — pillar didn't place; can't test cleanup)")
        print("  SCAFFOLD: FAIL"); py4j("stop"); return
    print("  cleanupScaffold:", py4j("cleanup"))
    t0=time.time(); looped=False
    while time.time()-t0 < 45:                       # bounded — a loop would blow this
        time.sleep(2)
        st=py4j("minestat")
        if not st.get("mining"): break
    else:
        looped=True
    time.sleep(1)
    solid_after = sum(0 if is_air(*c) else 1 for c in col)
    scount_after = py4j("scount")["n"]
    print(f"  after cleanup: solid pillar cells={solid_after}, scaffoldCount={scount_after}, timed_out={looped}")
    ok = (solid_after == 0) and (scount_after == 0) and (not looped)
    print("  SCAFFOLD:", "PASS" if ok else "FAIL")
    py4j("stop")

if __name__=="__main__": main()
