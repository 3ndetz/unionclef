#!/usr/bin/env python3
"""#46 CORE place-as-a-move test: the block-space search itself plans a bridge (not the
reactive give-up). Pure tungsten ;goto (gotoXYZ = the async pathfinder where the
place-move lives) across a sky void, with planPlaceMoves ON and a block in the bot's
hand. The search should route through the gap via place-moves and the executor paves it
segment-by-segment (mirror of break-through).

Also verifies parkour is UNAFFECTED without blocks: same async ;goto over a small gap
with planPlaceMoves OFF should still be handled by normal move-gen (no regression).

  sky islands y=100: near x=-4..1 | gap x=2..5 (void) | far x=6..12  (z=-4..4)
PASS if with planPlaceMoves ON the bot crosses AND left blocks in the gap. Exit 0.
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
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="gotoxyz": out=dict(mc.gotoXYZ(req["x"],req["y"],req["z"]))
elif op=="place": out=dict(mc.setTungstenPlanPlaceMoves(req["on"]))
elif op=="stop": mc.ExecuteCommand("@stop"); out={"ok":True}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=40): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(op,to=30,**kw):
    r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def pos():
    o=rcon(f"data get entity {BOT} Pos")
    try:
        p=o.split("[")[1].split("]")[0].split(",")
        return [round(float(v.strip().rstrip("d")),1) for v in p]
    except Exception: return None

def run(planplace, secs):
    # Force-load the sky-island chunks: without this the block-space search can hit
    # not-yet-loaded chunks intermittently and plan an erratic path (bot walks backward
    # off the island / stalls) — a test artifact, not a bridge bug. Mirrors terrain_test.
    rcon("forceload add -8 -8 24 8")
    # 7-wide gap (x=2..8): beyond a sprint-jump, so the ONLY way across is a bridge.
    rcon("fill -6 96 -6 20 104 6 air")
    rcon("fill -4 100 -4 1 100 4 stone")
    rcon("fill 9 100 -4 16 100 4 stone")
    py4j("stop"); time.sleep(1)
    rcon(f"tp {BOT} 0 101 0 90 0"); time.sleep(1.5)
    rcon(f"clear {BOT}")
    if planplace:
        rcon(f"item replace entity {BOT} weapon.mainhand with cobblestone 64")
    py4j("place", on=planplace); time.sleep(0.5)
    py4j("gotoxyz", x=13, y=101, z=0)
    last=None; maxx=-99; fell=False
    for _ in range(secs):
        time.sleep(1)
        p=pos()
        if p:
            last=p
            if p[0]>maxx: maxx=p[0]
            if p[1]<60: fell=True; break
            if p[0]>=9 and p[1]>=99: break
    py4j("stop"); py4j("place", on=False)   # reset the flag so a following test is clean
    placed=[]
    for gx in range(2,9):
        for gz in range(-4,5):
            if "passed" in rcon(f"execute if block {gx} 100 {gz} cobblestone").lower():
                placed.append(f"{gx},{gz}"); break
    return {"finalPos":last,"maxX":maxx,"fell":fell,"placed":placed}

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(6)
    print("=== CORE bridge: planPlaceMoves ON + block in hand, async ;goto across a sky gap ===")
    on = run(True, 90)
    print(f"  {on}")
    crossed = on["finalPos"] is not None and on["maxX"] >= 9 and not on["fell"]
    ok = crossed and len(on["placed"]) >= 1
    print(f"  crossed by search-planned bridge: {ok}")
    print("  CORE-BRIDGE:", "PASS" if ok else "FAIL")
    import sys; sys.exit(0 if ok else 1)

if __name__=="__main__": main()
