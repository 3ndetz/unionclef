#!/usr/bin/env python3
"""Bridge-as-a-move test (#46 second half): @goto across a gap the bot can ONLY cross
by bridging. Two sky islands at y=100 with a 7-wide void between and void all around —
no walls to climb, no way around, and a mis-step falls ~160 blocks to the ground. So
reaching the far island proves the bot paved a bridge across.

  near island x=-4..1 | GAP x=2..8 (void) | far island x=9..16   (all z=-4..4, y=100)
  bot on near island, goal (13,101,0) on the far island, cobblestone in inventory.
PASS if the bot reaches the far island (x>=9, y>=99) AND left blocks in the gap. Exit 0.
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
elif op=="goto": mc.ExecuteCommand("@goto "+str(req["x"])+" "+str(req["y"])+" "+str(req["z"])); out={"ok":True}
elif op=="stop": mc.ExecuteCommand("@stop"); mc.punkStop(); out={"ok":True}
elif op=="chat": out={"chat":[str(x) for x in mc.getRecentChat(req["n"])]}
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

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(6)
    # two sky islands at y=100, 7-wide void between, void all around
    rcon("fill -6 96 -6 18 104 6 air")
    rcon("fill -4 100 -4 1 100 4 stone")     # near island (top y=100 -> stand y=101)
    rcon("fill 9 100 -4 16 100 4 stone")     # far island (goal side)
    py4j("stop"); time.sleep(1)
    rcon(f"tp {BOT} 0 101 0 90 0"); time.sleep(1)
    rcon(f"clear {BOT}"); rcon(f"give {BOT} cobblestone 64"); time.sleep(1)
    print("@goto (13,101,0) across a 7-wide sky void — bridge or fall...")
    py4j("goto", x=13, y=101, z=0)
    reached=False; last=None; maxx=-99; fell=False; trace=[]
    for k in range(45):
        time.sleep(1)
        p=pos()
        if p:
            last=p
            if k % 4 == 0: trace.append(p)
            if p[0]>maxx: maxx=p[0]
            if p[1] < 60: fell=True; break
            if p[0]>=9 and p[1]>=99 and abs(p[2])<=5: reached=True; break
    try:
        for line in py4j("chat", n=25).get("chat", []):
            if any(w in line for w in ("Bridg","Pillar","nav","unreach","gave up")): print("  chat:", line)
    except Exception: pass
    placed=[]
    for gx in range(2,9):
        for gz in range(-4,5):
            if "passed" in rcon(f"execute if block {gx} 100 {gz} cobblestone").lower():
                placed.append(f"{gx},{gz}"); break
    py4j("stop")
    print(f"  trace: {trace}")
    print(f"  finalPos={last} maxX={maxx:.1f} fell={fell}")
    print(f"  cobblestone in the gap (bridge proof): {placed}")
    ok = reached and len(placed) >= 1 and not fell
    print(f"  crossed by bridging: {ok}")
    print("  BRIDGE-GOTO:", "PASS" if ok else "FAIL")
    import sys; sys.exit(0 if ok else 1)

if __name__=="__main__": main()
