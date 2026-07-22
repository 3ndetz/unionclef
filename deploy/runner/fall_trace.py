#!/usr/bin/env python3
"""Fine-grained fall telemetry: punk the victim on the bridge arena and poll the
bot's Pos/Motion/OnGround at ~4 Hz to see HOW it leaves the island (walk off /
jump / knockback). Prints a burst around any y-drop.

Assumes the bridge arena is already built and both bots in-game (run a
bedwars_combat_test bridge first, or it builds nothing — pure observation).
Usage: fall_trace.py [seconds]  (default 40)
"""
import functools, json, re, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; BOT="tester1"; VICTIM="tester2"
SECS=int(sys.argv[1]) if len(sys.argv)>1 else 40
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="punk": mc.ExecuteCommand("@stop"); mc.punkStop(); mc.punk(req["t"]); out={"ok":True}
elif op=="stop": mc.ExecuteCommand("@stop"); mc.punkStop(); out={"ok":True}
elif op=="chat": out={"chat":mc.getRecentChat(req.get("n",300))}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=30): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(op,**kw):
    r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})])
    try: return json.loads(r.stdout.strip().splitlines()[-1])
    except Exception: return r.stdout.strip()
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def nums(s):
    return [float(x) for x in re.findall(r'-?\d+\.?\d*(?:[eE]-?\d+)?',s)]

def snap(name):
    o=rcon(f"data get entity {name}")
    # extract Pos, Motion, OnGround
    pos=re.search(r'Pos:\s*\[([^\]]+)\]',o); mot=re.search(r'Motion:\s*\[([^\]]+)\]',o)
    og=re.search(r'OnGround:\s*(\d)b',o)
    p=nums(pos.group(1)) if pos else [None,None,None]
    m=nums(mot.group(1)) if mot else [0,0,0]
    return {"x":p[0],"y":p[1],"z":p[2],"vx":m[0],"vy":m[1],"vz":m[2],"og":og.group(1) if og else "?"}

def build_bridge():
    rcon("forceload add -20 -20 20 20")
    rcon("fill -18 -64 -18 18 -50 18 air")
    rcon("fill -9 -61 -2 -5 -61 2 stone")   # island A (bot)
    rcon("fill 5 -61 -2 9 -61 2 stone")     # island B (victim)
    rcon("fill -4 -61 0 4 -61 0 stone")     # 1-wide bridge
    rcon("gamerule pvp true"); rcon("gamerule immediate_respawn true")
    rcon("time set day"); rcon("weather clear"); rcon("gamerule keepInventory true")
    for n in (BOT,VICTIM):
        rcon(f"item replace entity {n} weapon.mainhand with iron_sword")

def main():
    build_bridge()
    rcon(f"tp {VICTIM} 7 -60 0 90 0"); rcon(f"tp {BOT} -7 -60 0 -90 0"); time.sleep(1)
    print("punk:", py4j("punk", t=VICTIM))
    t0=time.time(); prevY=-60
    while time.time()-t0<SECS:
        s=snap(BOT)
        y=s["y"]
        if y is None: time.sleep(0.25); continue
        drop = y < -60.6
        tag = " <== OFF-ISLAND" if drop else ""
        # only print when moving vertically/falling or near an edge event
        if drop or abs(s["vy"])>0.15 or (prevY>-60.6 and y<=prevY-0.05):
            print(f"t={time.time()-t0:4.1f} x={s['x']:.2f} z={s['z']:.2f} y={y:.2f} "
                  f"v=({s['vx']:.2f},{s['vy']:.2f},{s['vz']:.2f}) og={s['og']}{tag}")
        prevY=y
        time.sleep(0.22)
    print("\n=== EDGE telemetry (from bot chat log) ===")
    ch=py4j("chat", n=300)
    lines=ch.get("chat") if isinstance(ch,dict) else None
    if isinstance(lines,str):
        import ast
        try: lines=ast.literal_eval(lines)
        except Exception: lines=[lines]
    for ln in (lines or []):
        if "EDGE" in str(ln): print("  ", str(ln).replace("§e","").replace("§r",""))
    py4j("stop")

if __name__=="__main__": main()
