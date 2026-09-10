#!/usr/bin/env python3
"""Deterministic proof of G1 (dig straight down): the bot must MINE DOWN through solid stone to
reach a goal buried below it. Without the breakDown generator this is unreachable (walk=INF).

Builds a solid stone block, hollows a 1x2 air pocket at the bottom, puts the bot on top with an
iron pickaxe, and drives @goto to the pocket. PASS if the bot descends to it by mining.
    python3 deploy/runner/dig_down_test.py
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; BOT="tester1"
TOP=-52; FLOORY=-63; X,Z=430,430             # solid stone TOP..FLOORY; bot digs its own shaft
POCKET_Y=FLOORY+1                            # goal feet: stand on the solid floor at the bottom
START=(X, TOP+1, Z); GOAL=(X, POCKET_Y, Z)
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chatcmd": mc.ChatMessage(req["c"]); out={"ok":True}
elif op=="gs": out=dict(mc.getGameState().get("self") or {})
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",6))]}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=60): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(op,to=40,**kw):
    r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()

if not py4j("state")["inGame"]: py4j("connect",ip="test-server"); time.sleep(3)
if "tester1" not in rcon("list"):
    py4j("connect",ip="test-server"); t0=time.time()
    while time.time()-t0<120 and "tester1" not in rcon("list"): time.sleep(5)
py4j("cmd",c="@stop"); py4j("cmd",c=";stop"); time.sleep(1)
rcon(f"gamemode survival {BOT}")
# solid stone block, a clear pocket at the bottom, air above so the bot stands on top
rcon(f"fill {X-3} {TOP+1} {Z-3} {X+3} {TOP+6} {Z+3} minecraft:air")   # sky above
rcon(f"fill {X-3} {FLOORY+1} {Z-3} {X+3} {TOP} {Z+3} minecraft:stone")  # solid block to dig through
rcon(f"fill {X-3} {FLOORY} {Z-3} {X+3} {FLOORY} {Z+3} minecraft:stone")  # solid floor at the bottom
time.sleep(1)
sx,sy,sz=START
rcon(f"tp {BOT} {sx+0.5} {sy} {sz+0.5}"); rcon(f"clear {BOT}"); rcon(f"give {BOT} minecraft:iron_pickaxe")
time.sleep(2)
gx,gy,gz=GOAL
y0=float(py4j("gs")["pos"].split(",")[1])
py4j("cmd",c=f"@goto {gx} {gy} {gz}")
print(f"on top of a 12-deep stone block (y={y0:.0f}); goal is a pocket at the BOTTOM y={gy}. Must dig down.")
best_low=y0; reached=False; seen=set(); t0=time.time()
while time.time()-t0<150:
    time.sleep(5)
    s=py4j("gs"); pos=s["pos"]; y=float(pos.split(",")[1]); best_low=min(best_low,y)
    ch=[c for c in py4j("chat",n=6)["chat"] if c not in seen]; seen.update(ch)
    note=[c for c in ch if any(w in c for w in ("building","reak","NO ROUTE","nreach","ining"))]
    print(f"  t={time.time()-t0:.0f}s pos={pos} lowestY={best_low:.0f} look={s.get('lookingAt')}"+(" | "+" || ".join(x[-60:] for x in note[-2:]) if note else ""))
    if y <= POCKET_Y + 0.5: reached=True; break
py4j("cmd",c="@stop"); py4j("cmd",c=";stop")
print(f"start y={y0:.0f}, lowest y={best_low:.0f}, pocket y={POCKET_Y}")
print("PASS: dug down to the buried goal" if reached else "FAIL: never dug down")
sys.exit(0 if reached else 1)
