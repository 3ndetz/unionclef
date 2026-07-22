#!/usr/bin/env python3
"""Bedwars-like combat baseline/observation on a void island.

A 7x7 island (y=-61) surrounded by void; the bot punks tester2 on it. Observes
the combat the user reported broken: does the bot FALL INTO THE VOID, does it get
a KILL, how does it turn/approach? Ground truth for the combat rework (#28).
Bring up the victim first:
  docker compose -f deploy/compose.test.yml --profile pvp up -d
Usage: bedwars_combat_test.py [seconds]  (default 45)
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; C2="uctest-mc-tester2"; BOT="tester1"; VICTIM="tester2"
SECS=int(sys.argv[1]) if len(sys.argv)>1 else 45
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="punk": mc.ExecuteCommand("@stop"); mc.punkStop(); mc.punk(req["t"]); out={"ok":True}
elif op=="status": out=dict(mc.punkStatus())
elif op=="gs":
    gs=mc.getGameState(); out={"self":dict(gs.get("self") or {}), "players":[dict(p) for p in (gs.get("players") or [])]}
elif op=="stop": mc.ExecuteCommand("@stop"); mc.punkStop(); out={"ok":True}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=30): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(c,op,to=25,**kw):
    r=sh(["docker","exec",c,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
    if r.returncode!=0: raise RuntimeError(f"{op}@{c}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c,to=20): return sh(["docker","exec",SERVER,"rcon-cli",c],to).stdout.strip()
def efloat(name,path):
    o=rcon(f"data get entity {name} {path}")
    try: return float(o.split(":")[-1].strip().rstrip("dbfs"))
    except Exception: return None
def wait_for(desc,fn,ts,iv=4):
    t0=time.time(); last=None
    while time.time()-t0<ts:
        try:
            last=fn()
            if last: print(f"  [ok] {desc}"); return last
        except Exception as e: last=e
        time.sleep(iv)
    raise TimeoutError(f"{desc}: {ts}s ({last})")
def ensure(c,label):
    if not py4j(c,"state")["inGame"]:
        py4j(c,"connect",ip="test-server"); wait_for(f"{label} in game",lambda:py4j(c,"state")["inGame"],180,5); time.sleep(4)

def build_arena():
    rcon("forceload add -16 -16 16 16")
    rcon("fill -16 -64 -16 16 -50 16 air")          # void everywhere
    rcon("fill -4 -61 -4 4 -61 4 stone")            # 9x9 island (top -60)
    rcon("gamerule pvp true"); rcon("gamerule immediate_respawn true")
    rcon("gamerule doDaylightCycle false"); rcon("time set day"); rcon("weather clear")

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    build_arena()
    wait_for("bot py4j", lambda: py4j(C1,"state") is not None,600,10); ensure(C1,"bot")
    wait_for("victim py4j", lambda: py4j(C2,"state") is not None,600,10); ensure(C2,"victim")
    py4j(C1,"stop")
    rcon(f"clear {BOT}"); rcon(f"item replace entity {BOT} weapon.mainhand with iron_sword")
    rcon(f"kill {BOT}"); rcon(f"kill {VICTIM}")
    wait_for("bot respawn", lambda: (efloat(BOT,"Health") or 0)>=19.9,60,3)
    wait_for("victim respawn", lambda: (efloat(VICTIM,"Health") or 0)>=19.9,60,3)
    rcon(f"tp {VICTIM} 2 -60 2 -90 0"); rcon(f"tp {BOT} -2 -60 -2 90 0"); time.sleep(2)

    print(f"[fight] punk {VICTIM}, watching {SECS}s...")
    py4j(C1,"punk", t=VICTIM)
    t0=time.time(); botFell=0; victimKills=0; lastVicHp=20.0; minBotY=-60.0; hits=0; lastVicHp2=20.0
    while time.time()-t0<SECS:
        time.sleep(2)
        bh=efloat(BOT,"Health"); bp=rcon(f"data get entity {BOT} Pos")
        vh=efloat(VICTIM,"Health")
        # bot y
        try:
            by=float(bp.split(",")[1].strip().rstrip("d"))
            minBotY=min(minBotY, by)
            if by < -62: botFell+=1     # below island = falling into void
        except Exception: by=None
        # victim damage/kill
        if vh is not None:
            if vh < lastVicHp2 - 0.1: hits+=1
            if vh > lastVicHp2 + 5: victimKills+=1    # respawned to full = was killed
            lastVicHp2=vh
        print(f"  t={int(time.time()-t0)}s botY={by} botHP={bh} victimHP={vh} fell~{botFell} hits~{hits}")
    py4j(C1,"stop")

    print("\n=== RESULTS (baseline) ===")
    print(f"  bot fell toward void (y<-62) ticks: {botFell}, min botY: {minBotY}")
    print(f"  hits on victim (hp drops): ~{hits}, victim kills (respawns): {victimKills}")
    print("  NOTE: baseline observation — success = 0 falls + >=1 kill")

if __name__=="__main__": main()
