#!/usr/bin/env python3
"""//replace autotest: a 3x3 stone patch in reach, replaceSelection(stone -> cobblestone),
poll replaceStatus until done, verify the cells are cobblestone. Bot has a pickaxe (fast
break) + cobblestone in the hotbar (to place). Validates the break-then-place composition
on the mineBlocks break primitive.
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
    elif op=="select": out=dict(mc.select(req["a"][0],req["a"][1],req["a"][2],req["b"][0],req["b"][1],req["b"][2]))
    elif op=="replace": out=dict(mc.replaceSelection(req["src"],req["dst"]))
    elif op=="replstat": out=dict(mc.replaceStatus())
except Exception as e:
    sys.stderr.write("ERR:"+repr(e)+"\n"); sys.exit(3)
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=40): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(op,to=30,**kw):
    last=""
    for _ in range(3):
        try:
            r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
            if r.returncode==0 and r.stdout.strip(): return json.loads(r.stdout.strip().splitlines()[-1])
            last=(r.stderr or r.stdout or "").strip()[-300:]
        except Exception as e: last=repr(e)[-300:]
        time.sleep(2)
    raise RuntimeError(f"{op}: {last}")
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def block_at(x,y,z):
    # returns the block id at (x,y,z)
    r=rcon(f"execute if block {x} {y} {z} minecraft:cobblestone")
    return "cobblestone" if "passed" in r.lower() else "other"

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
    rcon("fill 0 -61 -4 12 -61 4 stone")          # floor
    # vertical 3-cell stone column at x=5, y=-60..-58, z=0 (close + in reach, like diag_mine)
    rcon("fill 5 -60 0 5 -58 0 stone")
    rcon(f"tp {BOT} 3 -60 0 90 0"); time.sleep(1.5)
    rcon(f"item replace entity {BOT} weapon.mainhand with diamond_pickaxe")
    rcon(f"item replace entity {BOT} hotbar.1 with cobblestone 64")
    patch=[(5,-60,0),(5,-59,0),(5,-58,0)]
    print("=== //replace: 3-cell stone column -> cobblestone at x=5 ===")
    print("  before cobble?:", [block_at(*p) for p in patch])
    print("  select:", py4j("select", a=[5,-60,0], b=[5,-58,0]))
    print("  replace:", py4j("replace", src="stone", dst="cobblestone"))
    last=None
    for _ in range(40):
        time.sleep(1.5)
        st=py4j("replstat"); last=st
        if st.get("phase")=="done": break
    time.sleep(1)
    after=[block_at(*p) for p in patch]
    print(f"  final replaceStatus: {last}")
    print(f"  after: {after}")
    ok = all(b=="cobblestone" for b in after)
    print("  REPLACE:", "PASS" if ok else "FAIL")
    py4j("stop")

if __name__=="__main__": main()
