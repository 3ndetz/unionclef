#!/usr/bin/env python3
"""#30 probe: when @goto would drive the bot INTO a wall (a route it can't execute),
does the bot recover (re-path / give up cleanly) or ram the wall forever?

Course: bot in a small pen with a tall wall between it and the goal, no way around and
NO blocks given (so it can't bridge/pillar). The bot should either mine through (if
allowed) or, failing that, stop cleanly — it must NOT stay pinned to the wall with the
search spinning indefinitely.

Measures isTungstenActive over time + whether the bot ends pinned to the wall face.
Diagnostic (informs whether #48 needs a walker stuck-detector or the existing anti-stuck
net already handles it).
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
elif op=="tactive": out={"active":mc.isTungstenActive()}
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

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(6)
    # pen with a tall UNBREAKABLE bedrock wall between bot and goal, no way around
    rcon("fill -6 -60 -6 16 8 6 air")
    rcon("fill -6 -61 -6 16 -61 6 stone")            # floor
    rcon("fill 4 -60 -6 4 -50 6 bedrock")            # tall wall at x=4 spanning the pen
    py4j("stop"); time.sleep(1)
    rcon(f"tp {BOT} 0 -60 0 90 0"); time.sleep(1)
    rcon(f"clear {BOT}"); time.sleep(1)              # no blocks -> can't bridge/pillar
    print("@goto (10,-60,0) — bedrock wall at x=4 blocks the way, no blocks to build...")
    py4j("goto", x=10, y=-60, z=0)
    trace=[]; inactive_at=None
    for k in range(20):   # ~40s
        time.sleep(2)
        p=pos()
        if p and k % 2 == 0: trace.append(p)
        try:
            if not py4j("tactive")["active"] and inactive_at is None: inactive_at=2*k+2
        except Exception: pass
    p=pos(); py4j("stop")
    pinned = p is not None and 3.0 < p[0] < 4.6   # jammed against the x=4 wall face
    print(f"  trace: {trace}")
    print(f"  finalPos={p}  pinned-to-wall={pinned}  tungsten inactive at: {inactive_at}s")
    print("  (diagnostic: pinned + never inactive = the #30 ram-forever symptom)")

if __name__=="__main__": main()
