#!/usr/bin/env python3
"""Protected-area (claim/private) autotest — place-side baritone compatibility.

Verifies tungsten honours protected zones for PLACING (the gap symmetric to the
break side): markProtectedArea -> placeBlockAt refuses inside + canPlaceBlock
false; placing outside works; clearProtectedAreas re-enables. Exit 0 = pass.
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
elif op=="markprot": out=dict(mc.markProtectedArea(int(req["x"]),int(req["y"]),int(req["z"]),int(req["r"])))
elif op=="clearprot": out=dict(mc.clearProtectedAreas())
elif op=="canplace": out=dict(mc.canPlaceBlock(int(req["x"]),int(req["y"]),int(req["z"])))
elif op=="canbreak": out={"canBreak":mc.canBreakBlock(int(req["x"]),int(req["y"]),int(req["z"]))}
elif op=="place": out=dict(mc.placeBlockAt(int(req["x"]),int(req["y"]),int(req["z"])))
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=30): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(op,t=25,**kw):
    r=sh(["docker","exec",CLIENT,"python3","-c",SNIP,json.dumps({"op":op,"port":PORT,**kw})],t)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c,t=20): return sh(["docker","exec",SERVER,"rcon-cli",c],t).stdout.strip()
def is_air(x,y,z): return "passed" in rcon(f"execute if block {x} {y} {z} air").lower()
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
    rcon("forceload add 0 0 8 8")
    rcon("fill -2 -61 -2 10 -55 10 air")
    rcon("fill -2 -61 -2 10 -61 10 stone")
    wait_for("py4j", lambda: py4j("state") is not None,600,10)
    if not py4j("state")["inGame"]:
        py4j("connect",ip="test-server"); wait_for("ingame",lambda:py4j("state")["inGame"],180,5); time.sleep(4)
    rcon(f"clear {BOT}"); rcon(f"item replace entity {BOT} hotbar.0 with dirt 64")
    rcon(f"tp {BOT} 4.5 -60 4.5 -45 0"); time.sleep(3)
    py4j("selhot",s=0)
    py4j("clearprot")  # reset

    inside=(6,-60,6); outside=(4,-60,5)
    mk=py4j("markprot", x=inside[0], y=inside[1], z=inside[2], r=1)
    print(f"  markProtectedArea: {mk}")

    cp_in=py4j("canplace", x=inside[0], y=inside[1], z=inside[2])
    cb_in=py4j("canbreak", x=inside[0], y=inside[1], z=inside[2])   # same zone must block MINING too
    pl_in=py4j("place", x=inside[0], y=inside[1], z=inside[2])
    inside_air=is_air(*inside)
    print(f"  inside {inside}: canPlace={cp_in.get('canPlace')} canBreak={cb_in.get('canBreak')} policyAllows={cp_in.get('policyAllows')} place.ok={pl_in.get('ok')} reason={pl_in.get('reason')} still_air={inside_air}")

    cp_out=py4j("canplace", x=outside[0], y=outside[1], z=outside[2])
    pl_out=py4j("place", x=outside[0], y=outside[1], z=outside[2])
    outside_solid=not is_air(*outside)
    print(f"  outside {outside}: canPlace={cp_out.get('canPlace')} place.placed={pl_out.get('placed')} solid={outside_solid}")

    py4j("clearprot")
    pl_after=py4j("place", x=inside[0], y=inside[1], z=inside[2])
    inside_solid_after=not is_air(*inside)
    print(f"  after clear, inside place.placed={pl_after.get('placed')} solid={inside_solid_after}")

    print("\n=== RESULTS ===")
    denied = (cp_in.get("policyAllows")==False and pl_in.get("ok")==False
              and "protected" in str(pl_in.get("reason","")) and inside_air)
    break_denied = (cb_in.get("canBreak")==False)   # 14.1: zone blocks mining too
    allowed_out = (cp_out.get("canPlace")==True and outside_solid)
    reenabled = inside_solid_after
    print(f"  protected inside denied (place): {denied}")
    print(f"  protected inside denied (break): {break_denied}")
    print(f"  outside allowed: {allowed_out}")
    print(f"  re-enabled after clear: {reenabled}")
    ok = denied and break_denied and allowed_out and reenabled
    print("  PROTECT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
