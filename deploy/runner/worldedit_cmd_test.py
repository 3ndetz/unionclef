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

def ingame():
    try: return py4j("state")["inGame"]
    except Exception: return False
def we_at(cmd, x, y, z):
    # tp + run the WE command, RETRYING until the mod read a valid player block (pb) —
    # a restart-fresh bot flickers in/out of game, and a null pb means no selection.
    for _ in range(8):
        tp(x, y, z)
        r = py4j("we", cmd=cmd)
        if r.get("pb") not in (None, "null"): return r
        time.sleep(3)
    return r

def main():
    for _ in range(60):
        if ingame(): break
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(5)
    time.sleep(8)                                   # settle (connect flicker)
    py4j("stop"); time.sleep(1)
    rcon("forceload add -8 -8 16 16")
    rcon("fill 0 -61 -4 12 5 4 air")
    rcon("fill 0 -61 -4 12 -61 4 stone")           # floor
    cells=[(5,-60,0),(6,-60,0),(7,-60,0)]
    print("=== @@ WorldEdit command handler ===")
    print("  pos1:", we_at("pos1",5,-60,0))
    print("  pos2:", we_at("pos2",7,-60,0))
    tp(6,-60,2)                                     # stand adjacent, in reach
    rcon(f"item replace entity {BOT} hotbar.1 with stone 64")
    rcon(f"item replace entity {BOT} hotbar.2 with cobblestone 64")
    rcon(f"item replace entity {BOT} hotbar.3 with diamond_pickaxe")   # break queue equips it (fast stone)
    print("  set stone:", py4j("we",cmd="set stone"))
    time.sleep(2)
    set_ok=all(is_block(*c,"stone") for c in cells)
    print(f"  after ;;set: stone={[is_block(*c,'stone') for c in cells]} -> {'OK' if set_ok else 'FAIL'}")
    print("  replace stone cobblestone:", py4j("we",cmd="replace stone cobblestone"))
    for _ in range(25):
        time.sleep(1.5)
        st=py4j("we",cmd="restat")               # poll replaceStatus: advances break->place
        if st.get("phase")=="done" or all(is_block(*c,"cobblestone") for c in cells): break
    rep_ok=all(is_block(*c,"cobblestone") for c in cells)
    print(f"  after ;;replace: cobble={[is_block(*c,'cobblestone') for c in cells]} -> {'OK' if rep_ok else 'FAIL'}")
    # copy + paste: copy the cobblestone row, paste it re-anchored at 10,-60,0
    print("  copy:", py4j("we",cmd="copy"))
    tp(10,-60,0)
    print("  paste:", py4j("we",cmd="paste"))
    for _ in range(12):
        time.sleep(1.5)
        if is_block(11,-60,0,"cobblestone") and is_block(12,-60,0,"cobblestone"): break
    pasted=[is_block(11,-60,0,"cobblestone"), is_block(12,-60,0,"cobblestone")]
    paste_ok=all(pasted)
    print(f"  after paste@10,-60,0: 11/12={pasted} -> {'OK' if paste_ok else 'FAIL'}")
    print("  size:", py4j("we",cmd="size"))
    ok=set_ok and rep_ok and paste_ok
    print("  WE_CMD:", "PASS" if ok else "FAIL")
    py4j("stop")

if __name__=="__main__": main()
