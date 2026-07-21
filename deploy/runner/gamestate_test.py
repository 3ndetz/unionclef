#!/usr/bin/env python3
"""Cognitive game-state autotest (py4j getGameState) — the agent's battle view.

Two clients in an arena. Verify getGameState returns self (hp/pos/blocks/held)
and the other player in players[] with a sane distance. Requires pvp profile.
Exit 0 = pass.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
FC="uctest-mc-tester1"; VC="uctest-mc-tester2"; SERVER="uctest-server"; PORT=25333
F="tester1"; V="tester2"
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=req.get("port",25333),auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="gs":
    gs=mc.getGameState()
    out={"self":dict(gs.get("self") or {}),
         "players":[dict(p) for p in (gs.get("players") or [])],
         "beds":[dict(b) for b in (gs.get("beds") or [])],
         "inGame":gs.get("inGame"),"playerCount":gs.get("playerCount")}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=30): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(c,op,t=25,**kw):
    r=sh(["docker","exec",c,"python3","-c",SNIP,json.dumps({"op":op,"port":PORT,**kw})],t)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c,t=20):
    r=sh(["docker","exec",SERVER,"rcon-cli",c],t); return r.stdout.strip()
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
    for c in ["forceload add 0 0 8 8","fill -8 -61 -8 8 -55 8 air","fill -8 -61 -8 8 -61 8 stone",
              "gamerule advance_time false","weather clear","time set day"]:
        rcon(c)
    wait_for("fighter py4j", lambda: py4j(FC,"state") is not None,600,10)
    if not py4j(FC,"state")["inGame"]:
        py4j(FC,"connect",ip="test-server"); wait_for("fighter ingame",lambda:py4j(FC,"state")["inGame"],180,5); time.sleep(4)
    wait_for("victim py4j", lambda: py4j(VC,"state") is not None,600,10)
    if not py4j(VC,"state")["inGame"]:
        py4j(VC,"connect",ip="test-server"); wait_for("victim ingame",lambda:py4j(VC,"state")["inGame"],180,5); time.sleep(4)
    rcon(f"clear {F}"); rcon(f"item replace entity {F} hotbar.0 with dirt 32")
    rcon(f"tp {F} 0.5 -60 0.5 0 0"); rcon(f"tp {V} 4.5 -60 0.5 0 0")
    rcon("setblock 3 -60 3 red_bed")  # a bed to detect nearby
    time.sleep(3)

    gs = py4j(FC,"gs")
    print("  gameState:", json.dumps(gs)[:500])
    self = gs.get("self",{})
    players = gs.get("players",[])
    beds = gs.get("beds",[])
    ok = (gs.get("inGame")
          and float(self.get("hp",0))>0
          and int(self.get("blocks",0))>=30
          and self.get("held","").endswith("dirt")
          and any(p.get("name")==V and float(p.get("distance",99))<6 for p in players)
          and len(beds)>=1 and float(beds[0].get("distance",99))<10)
    print(f"\n=== RESULTS ===")
    print(f"  self hp={self.get('hp')} blocks={self.get('blocks')} held={self.get('held')}")
    print(f"  players={[(p.get('name'),p.get('distance')) for p in players]}")
    print(f"  beds={[(b.get('pos'),b.get('distance')) for b in beds]}")
    print("  GAMESTATE:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
