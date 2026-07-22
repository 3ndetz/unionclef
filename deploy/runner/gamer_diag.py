#!/usr/bin/env python3
"""Clean @gamer diagnostic: hard-stop ALL tungsten tasks (punk/flee) + altoclef,
then start @gamer and watch position + chat. Isolates whether the earlier "stuck
in PUNK combat" was leftover combat state bleeding across the server switch.
  docker compose -f deploy/compose.test.yml --profile gamer up -d
Usage: gamer_diag.py [minutes]  (default 2.5)
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
CLIENT="uctest-mc-tester1"; GSERVER="uctest-gamer-server"; BOT="tester1"
MIN=float(sys.argv[1]) if len(sys.argv)>1 else 2.5
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="hardstop":
    mc.ExecuteCommand("@stop")
    try: mc.punkStop()
    except Exception: pass
    try: mc.runAwayStop()
    except Exception: pass
    out={"ok":True}
elif op=="swap": out=dict(mc.setTungstenPathing(bool(req["on"])))
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="gs": gs=mc.getGameState(); out={"self":dict(gs.get("self") or {})}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(int(req.get("n",10)))]}
elif op=="punkstatus": out=dict(mc.punkStatus())
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=40): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(op,t=30,**kw):
    r=sh(["docker","exec",CLIENT,"python3","-c",SNIP,json.dumps({"op":op,**kw})],t)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def grcon(c,t=20): return sh(["docker","exec",GSERVER,"rcon-cli",c],t).stdout.strip()
def wait_for(desc,fn,ts,iv=4):
    t0=time.time()
    while time.time()-t0<ts:
        try:
            if fn(): print(f"  [ok] {desc}"); return
        except Exception: pass
        time.sleep(iv)
    raise TimeoutError(desc)

def main():
    wait_for("gamer rcon", lambda:"players" in grcon("list"),300,6)
    grcon("difficulty easy"); grcon("gamerule doDaylightCycle true")
    if not py4j("state")["inGame"]:
        py4j("connect", ip="gamer-server"); wait_for("in game", lambda:py4j("state")["inGame"],200,5); time.sleep(5)
    print("hardstop:", py4j("hardstop")); time.sleep(2)
    print("punkStatus after stop:", py4j("punkstatus"))
    print("swap:", py4j("swap", on=True))
    py4j("cmd", c="@gamer")
    t0=time.time(); positions=set()
    while time.time()-t0<MIN*60:
        time.sleep(15)
        try:
            s=py4j("gs")["self"]; pos=s.get("pos")
            positions.add(str(pos))
            print(f"  t={int(time.time()-t0)}s pos={pos} hp={s.get('hp')}")
        except Exception as e: print("  poll err", str(e)[:80])
    print("distinct positions:", len(positions))
    print("recent chat:")
    for c in py4j("chat", n=14)["chat"]: print("   ", c)

if __name__=="__main__": main()
