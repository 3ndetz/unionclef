#!/usr/bin/env python3
"""Why is @gamer stuck? Print the current task (@status), the bot's surroundings,
and probe whether basic nav works here (@get log 1) on the survival terrain.
Usage: gamer_diag2.py"""
import functools, json, subprocess, time
print = functools.partial(print, flush=True)
CLIENT="uctest-mc-tester1"; GSERVER="uctest-gamer-server"
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="hardstop":
    mc.ExecuteCommand("@stop")
    try: mc.punkStop()
    except Exception: pass
    try: mc.runAwayStop()
    except Exception: pass
    out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(int(req.get("n",12)))]}
elif op=="gs":
    gs=mc.getGameState(); s=dict(gs.get("self") or {})
    out={"pos":s.get("pos"),"hp":s.get("hp"),"blocks":s.get("blocks"),"held":s.get("held")}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=40): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(op,**kw):
    r=sh(["docker","exec",CLIENT,"python3","-c",SNIP,json.dumps({"op":op,**kw})])
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def grcon(c): return sh(["docker","exec",GSERVER,"rcon-cli",c]).stdout.strip()

print("surroundings:", py4j("gs"))
print("--- @gamer 25s then @status ---")
py4j("hardstop"); time.sleep(1); py4j("cmd", c="@gamer"); time.sleep(25)
py4j("cmd", c="@status"); time.sleep(2)
print("status chat:")
for c in py4j("chat", n=10)["chat"]: print("   ", c)

print("--- probe basic nav: @get log 1 (40s) ---")
py4j("hardstop"); time.sleep(1); py4j("cmd", c="@get log 1")
p0=py4j("gs")["pos"]; print("  start pos:", p0)
for i in range(8):
    time.sleep(5)
    p=py4j("gs")["pos"]; print(f"  t={ (i+1)*5}s pos={p}")
print("get-log chat:")
for c in py4j("chat", n=12)["chat"]: print("   ", c)
py4j("hardstop")
