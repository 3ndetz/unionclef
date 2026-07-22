#!/usr/bin/env python3
"""Probe: start ;punk tester2, poll bot pose/yaw + recent chat to see WHY frozen."""
import functools,json,subprocess,sys,time
print=functools.partial(print,flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; BOT="tester1"; VICTIM="tester2"
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="punk": mc.ExecuteCommand("@stop"); mc.ChatMessage(";stop"); mc.ChatMessage(";punk "+req["t"]); out={"ok":True}
elif op=="stop": mc.ExecuteCommand("@stop"); mc.ChatMessage(";stop"); out={"ok":True}
elif op=="chat": out={"chat":mc.getRecentChat(req.get("n",25))}
elif op=="pose":
    p=mc.getGameState(); s=dict(p.get("self") or {}); out={"self":s}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=30): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(op,to=25,**kw):
    r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def epos(n):
    o=rcon(f"data get entity {n} Pos"); 
    try: return o.split("[")[1].split("]")[0]
    except: return o
def erot(n):
    o=rcon(f"data get entity {n} Rotation")
    try: return o.split("[")[1].split("]")[0]
    except: return o

rcon(f"tp {VICTIM} 2 -60 2 -90 0"); rcon(f"tp {BOT} -2 -60 -2 90 0"); time.sleep(1)
print("=== punk tester2 ===")
print(py4j("punk", t=VICTIM))
for i in range(9):
    time.sleep(2)
    print(f"t={i*2+2}s bot={epos(BOT)} rot={erot(BOT)} vic={epos(VICTIM)}")
print("=== recent chat ===")
try:
    for line in py4j("chat", n=30)["chat"]: print("  ", line)
except Exception as e: print("chat err", e)
py4j("stop")
