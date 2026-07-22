#!/usr/bin/env python3
"""Sneak-bridge autotest (tungsten BridgeTask) — epic parkour block placing.

Bot stands at a platform edge over a void with dirt in hand, bridges east
across the gap. Verifies it crossed (advanced east, did not fall) and that
floor blocks were actually placed in the gap.

Exit code 0 = pass.
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
if op=="state": out={"inGame":mc.inGame(),"name":mc.getUsername()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="selhot": out={"ok":mc.selectHotbar(int(req["s"]))}
elif op=="bridge": out={"ok":mc.bridgeForward(req["dir"],int(req["n"]))}
elif op=="bridgeto": out={"ok":mc.bridgeTo(int(req["x"]),int(req["y"]),int(req["z"]))}
elif op=="bstate": out={"active":mc.bridgeActive(),"placed":mc.bridgePlaced()}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=30): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(op,t=25,**kw):
    r=sh(["docker","exec",CLIENT,"python3","-c",SNIP,json.dumps({"op":op,"port":PORT,**kw})],t)
    if r.returncode!=0: raise RuntimeError(f"py4j {op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c,t=20):
    r=sh(["docker","exec",SERVER,"rcon-cli",c],t)
    if r.returncode!=0: raise RuntimeError(f"rcon {c}: {r.stderr.strip()[-200:]}")
    return r.stdout.strip()
def wait_for(desc,fn,ts,iv=3):
    t0=time.time(); last=None
    while time.time()-t0<ts:
        try:
            last=fn()
            if last: print(f"  [ok] {desc}"); return last
        except Exception as e: last=e
        time.sleep(iv)
    raise TimeoutError(f"{desc}: {ts}s ({last})")
def pos():
    o=rcon(f"data get entity {BOT} Pos"); inner=o[o.index('[')+1:o.index(']')]
    return [float(p.strip().rstrip('d')) for p in inner.split(',')]
def is_block(c): return "passed" in rcon(f"execute if block {c}").lower()

def main():
    print("[1/3] arena (platform + void)...")
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    rcon("forceload add 0 0 15 15")
    # hard-reset the lane to air WITHIN the forceloaded region (0..15)
    print("  clearing:", rcon("fill 0 -72 0 14 -50 8 air"))
    time.sleep(0.5)
    # belt-and-suspenders: explicit air on the exact gap cells (any z the bot uses)
    for x in range(3, 9):
        for y in (-61, -60, -59):
            rcon(f"setblock {x} {y} 4 air")
    rcon("fill 0 -61 2 2 -61 6 stone")      # start platform floor x=0..2 only
    rcon("gamerule advance_time false"); rcon("weather clear"); rcon("time set day")
    void_ok = all(is_block(f"{x} -61 4 air") for x in (3,4,5,6,7))
    print(f"  arena ok, gap is void: {void_ok}")
    if not void_ok:
        print("  FAIL: could not clear the gap"); sys.exit(1)

    print("[2/3] client + gear...")
    wait_for("py4j", lambda: py4j("state") is not None,600,10)
    if not py4j("state")["inGame"]:
        py4j("connect",ip="test-server")
        wait_for("in game", lambda: py4j("state")["inGame"],180,5)
        time.sleep(5)
    rcon(f"clear {BOT}")
    rcon(f"item replace entity {BOT} hotbar.0 with dirt 64")
    rcon(f"tp {BOT} 2.5 -60 4.5 -90 0")     # on the east edge, facing east(+x)
    time.sleep(3)
    py4j("selhot",s=0)
    p0=pos(); print(f"  start pos {p0}")

    print("[3/3] bridging east x5...")
    py4j("bridge",dir="east",n=5)
    t0=time.time()
    while time.time()-t0<30:
        st=py4j("bstate")
        if not st["active"]: break
        time.sleep(2)
    time.sleep(1)
    p1=pos(); placed=py4j("bstate")["placed"]
    advanced=p1[0]-p0[0]
    fell=p1[1]<p0[1]-1.5
    gap_blocks=sum(1 for x in (3,4,5,6,7) if not is_block(f"{x} -61 4 air"))
    print(f"  end pos {p1}  advanced_x={advanced:.1f}  fell={fell}  placed={placed}  gap_floor={gap_blocks}/5")

    # ---- bridgeTo a target across a further void ----
    print("[bridge to target]")
    rcon(f"tp {BOT} 2.5 -60 4.5 -90 0"); time.sleep(2)
    p2=pos()
    py4j("bridgeto",x=10,y=-60,z=4)
    t0=time.time()
    while time.time()-t0<30:
        if not py4j("bstate")["active"]: break
        time.sleep(2)
    time.sleep(1)
    p3=pos()
    reached = p3[0]>=9.0 and p3[1]>=-60.5
    print(f"  bridgeTo(10,-60,4): end {p3}  reached_x>=9={p3[0]>=9.0}  not_fell={p3[1]>=-60.5}")

    print("\n=== RESULTS ===")
    ok = (not fell) and advanced>=3.0 and gap_blocks>=3 and reached
    print(f"  fwd advanced {advanced:.1f} (>=3), not fell {not fell}, gap {gap_blocks}/5 (>=3), bridgeTo reached {reached}")
    print("  BRIDGE:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
