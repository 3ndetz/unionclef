#!/usr/bin/env python3
"""Diagnose the godbridge 'no block in hand' failure: build a CLEAN void arena, equip
cobblestone, confirm getHeldItem() after selectHotbar, then bridgeForward and poll
bridgeActive/bridgePlaced + the bot's position to see if it actually paves across the void.
"""
import functools, json, subprocess, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; BOT="tester1"
SNIP=r"""
import json,sys,time
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1]); op=req["op"]
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; out={}
try:
    if op=="state": out={"inGame":mc.inGame()}
    elif op=="stop": mc.ExecuteCommand("@stop"); mc.ExecuteCommand("@stop"); out={"ok":True}
    elif op=="equip": out={"held":mc.selectHotbar(req["slot"]) and mc.getHeldItem()}
    elif op=="held": out={"held":mc.getHeldItem()}
    elif op=="pos":
        gs=dict(mc.getGameState()); self=dict(gs.get("self",{})); xyz=str(self.get("pos","0,0,0")).split(",")
        out={"x":float(xyz[0]),"y":float(xyz[1]),"z":float(xyz[2])}
    elif op=="bridge": out={"ok":mc.bridgeForward(req["dir"],req["n"])}
    elif op=="bpoll": out={"active":mc.bridgeActive(),"placed":mc.bridgePlaced()}
except Exception as e:
    sys.stderr.write("ERR:"+repr(e)+"\n"); sys.exit(3)
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=30): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(op,t=25,**kw):
    last=""
    for _ in range(3):
        try:
            r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],t)
            if r.returncode==0 and r.stdout.strip(): return json.loads(r.stdout.strip().splitlines()[-1])
            last=(r.stderr or r.stdout or "").strip()[-200:]
        except Exception as e: last=repr(e)[-200:]
        time.sleep(1)
    return {"err":last}
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()

def main():
    for _ in range(40):
        if py4j("state").get("inGame"): break
        time.sleep(5)
    py4j("stop"); time.sleep(1)
    # CLEAN void arena: wipe a big box to air, drop a small 3x3 pad, bot at its edge.
    rcon("forceload add -8 -8 40 8")
    rcon("fill -8 -64 -8 40 20 8 air")              # wipe everything in view
    rcon("fill -2 -61 -2 1 -61 2 stone")            # 4x5 start pad, east edge at x=1
    rcon(f"clear {BOT}")
    rcon(f"item replace entity {BOT} hotbar.0 with cobblestone 64")
    rcon(f"tp {BOT} 1 -60 0 -90 0"); time.sleep(2)  # at pad east edge, facing east(+x)
    print("held after equip:", py4j("equip",slot=0))
    p0=py4j("pos"); print("pos before:", p0)
    print("bridge start:", py4j("bridge",dir="east",n=8))
    for i in range(16):
        time.sleep(1)
        bp=py4j("bpoll")
        if i%3==0 or not bp.get("active"): print(f"  t={i+1}s poll={bp}")
        if not bp.get("active") and i>2: break
    p1=py4j("pos"); print("pos after:", p1)
    try:
        dx=float(p1["x"])-float(p0["x"])
        placed=py4j("bpoll").get("placed",0)
        print(f"  advanced dx={dx:.1f}, placed={placed}")
        print("BRIDGE:", "PASS" if dx>=4 else "FAIL")
    except Exception as e: print("BRIDGE: FAIL (pos err)", e)
    py4j("stop")

if __name__=="__main__": main()
