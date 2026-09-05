#!/usr/bin/env python3
"""Adversarial bedwars combat test on a void island.

A 13x13 island (top y=-60) surrounded by void. Both bots punk EACH OTHER
(mutual combat) so knockback flies both ways — this reproduces the real
complaint: does OUR bot get knocked / sprint into the void, and does it get
kills against an opponent that fights back?

Kills/deaths are read from vanilla scoreboard objectives (robust — survives
immediate_respawn). Falls are read from live Y.

Bring up the victim container first:
  docker compose -f deploy/compose.test.yml --profile pvp up -d
Usage: bedwars_combat_test.py [seconds] [mutual|solo]   (default 60 mutual)
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; C2="uctest-mc-tester2"; BOT="tester1"; VICTIM="tester2"
SECS=int(sys.argv[1]) if len(sys.argv)>1 else 60
MUTUAL=(sys.argv[2] if len(sys.argv)>2 else "mutual")!="solo"
ARENA=sys.argv[3] if len(sys.argv)>3 else "flat"   # flat | bridge
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
elif op=="stop": mc.ExecuteCommand("@stop"); mc.punkStop(); out={"ok":True}
elif op=="chat": out={"chat":mc.getRecentChat(req.get("n",25))}
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
def boty(name):
    o=rcon(f"data get entity {name} Pos")
    try: return float(o.split(",")[1].strip().rstrip("d"))
    except Exception: return None
def score(name,obj):
    o=rcon(f"scoreboard players get {name} {obj}")
    try: return int(o.split("has ")[1].split(" ")[0])
    except Exception: return 0
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

# spawn points per arena: (bot, victim) as "x y z yaw pitch"
SPAWNS={
    "flat":   ("-4 -60 -4 45 0",  "4 -60 4 -135 0"),
    "bridge": ("-7 -60 0 -90 0",  "7 -60 0 90 0"),
}

def build_arena():
    rcon("forceload add -20 -20 20 20")
    rcon("fill -18 -64 -18 18 -50 18 air")          # void everywhere
    if ARENA=="bridge":
        # two 5x5 islands joined by a 1-wide bridge (top y=-60). The bot must
        # cross the bridge to reach the victim and fight at the edges — this is
        # where a real bedwars bot sprints off into the void.
        rcon("fill -9 -61 -2 -5 -61 2 stone")       # island A (bot)
        rcon("fill 5 -61 -2 9 -61 2 stone")         # island B (victim)
        rcon("fill -4 -61 0 4 -61 0 stone")         # 1-wide bridge (z=0)
    else:
        rcon("fill -6 -61 -6 6 -61 6 stone")        # 13x13 flat island
    rcon("gamerule pvp true"); rcon("gamerule immediate_respawn true")
    rcon("gamerule doDaylightCycle false"); rcon("time set day"); rcon("weather clear")
    rcon("gamerule keepInventory true")
    for o in ("k","d"): rcon(f"scoreboard objectives remove {o}")
    rcon("scoreboard objectives add k playerKillCount")
    rcon("scoreboard objectives add d deathCount")

def kit(name):
    rcon(f"clear {name}")
    rcon(f"item replace entity {name} weapon.mainhand with iron_sword")

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    build_arena()
    wait_for("bot py4j", lambda: py4j(C1,"state") is not None,600,10); ensure(C1,"bot")
    wait_for("victim py4j", lambda: py4j(C2,"state") is not None,600,10); ensure(C2,"victim")
    py4j(C1,"stop"); py4j(C2,"stop")
    kit(BOT); kit(VICTIM)
    rcon(f"kill {BOT}"); rcon(f"kill {VICTIM}")
    wait_for("bot respawn", lambda: (efloat(BOT,"Health") or 0)>=19.9,60,3)
    wait_for("victim respawn", lambda: (efloat(VICTIM,"Health") or 0)>=19.9,60,3)
    kit(BOT); kit(VICTIM)
    # reset scores after the setup kills
    for n in (BOT,VICTIM):
        for o in ("k","d"): rcon(f"scoreboard players set {n} {o} 0")
    botSpawn,vicSpawn=SPAWNS.get(ARENA,SPAWNS["flat"])
    rcon(f"tp {VICTIM} {vicSpawn}"); rcon(f"tp {BOT} {botSpawn}"); time.sleep(2)

    print(f"[fight] arena={ARENA} {'MUTUAL' if MUTUAL else 'SOLO'} punk, watching {SECS}s...")
    py4j(C1,"punk", t=VICTIM)
    if MUTUAL: py4j(C2,"punk", t=BOT)
    t0=time.time(); botFell=0; vicFell=0; minBotY=-60.0
    while time.time()-t0<SECS:
        time.sleep(2)
        by=boty(BOT); vy=boty(VICTIM); bh=efloat(BOT,"Health")
        bk=score(BOT,"k"); bd=score(BOT,"d"); vk=score(VICTIM,"k"); vd=score(VICTIM,"d")
        if by is not None:
            minBotY=min(minBotY,by)
            if by<-63: botFell+=1
        if vy is not None and vy<-63: vicFell+=1
        print(f"  t={int(time.time()-t0)}s botY={by} vicY={vy} botHP={bh} | botK={bk} botD={bd} vicK={vk} vicD={vd} botFell~{botFell}")
    py4j(C1,"stop")
    if MUTUAL: py4j(C2,"stop")
    bk=score(BOT,"k"); bd=score(BOT,"d"); vk=score(VICTIM,"k"); vd=score(VICTIM,"d")

    print("\n=== RESULTS (adversarial baseline) ===")
    print(f"  BOT   kills={bk} deaths={bd}   (deaths include void falls)")
    print(f"  VICTIM kills={vk} deaths={vd}")
    print(f"  bot below-island samples (y<-63): {botFell}, min botY: {minBotY:.1f}")
    ok = bk>=1 and botFell==0
    print(f"  SUCCESS (>=1 bot kill + 0 falls): {ok}")
    import sys; sys.exit(0 if ok else 1)

if __name__=="__main__": main()
