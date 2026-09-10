#!/usr/bin/env python3
"""Deterministic bench for G21: entity-approach goal snaps onto the bot's own cell.

Reproduces, on the flat server, the exact stall that ate the first ~6 minutes of two live
@gamer runs: a pig sits ~1 block away in a cell the player CANNOT stand in (only 1 block of
headroom -- a roof at feet+1), so tryPathToEntity's snap collapses the goal onto the BOT'S OWN
cell, the guide goes zero-length (hop[0,0,0]), and the bot idles while a pig sits within reach.

Layout (flat world, surface block at FY, bot feet FY+1):
    roof:            [R][R]           <- solid at FY+2 over the pocket (1 block headroom)
    feet level: [B ][p ][p ]          <- bot at BX, pig in the pocket at BX+1..BX+2
    floor:      [#][#][#][#]           <- solid at FY

The pocket is walled on the far side so the ONLY standable cell adjacent to the pig is the bot's.

    python deploy/runner/pig_ledge_test.py            # measure current behaviour (baseline)
    python deploy/runner/pig_ledge_test.py --no-build  # reuse existing structure

PASS if the pig dies within KILL_TIMEOUT (the bot reaches/hits it); FAIL if it stalls. Prints the
guide dump + lockAnat either way, so a FAIL says WHICH branch (idle/search/exec) held the tick.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 640, 640                      # fresh spot, clear of older benches
FY = -61                             # superflat top solid block (grass) sits here; feet at FY+1
BOTX, BOTZ = X, Z                    # bot stands on (X, FY, Z)
PIGX, PIGZ = X + 1, Z                # pig one block east, in the roofed pocket
KILL_TIMEOUT = 30
SNIP = r"""
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
elif op=="guide": out={"r": str(mc.guideDump() or "")}
elif op=="stats": out={"r": str(mc.placeStats() or "")}
elif op=="reset": mc.resetValues(); mc.resetRunCounters(); out={"ok":True}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",6))]}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a, to=60): return subprocess.run(a, capture_output=True, text=True, timeout=to)
def py4j(op, to=40, **kw):
    r = sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})], to)
    if r.returncode != 0: raise RuntimeError(f"{op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()

if not py4j("state")["inGame"]:
    py4j("connect", ip="test-server"); time.sleep(3)
if "tester1" not in rcon("list"):
    py4j("connect", ip="test-server"); t0=time.time()
    while time.time()-t0<120 and "tester1" not in rcon("list"): time.sleep(5)
py4j("cmd", c="@stop"); py4j("cmd", c=";stop"); time.sleep(1)
rcon(f"gamemode survival {BOT}")

if "--no-build" not in sys.argv:
    # Flat world already gives a grass floor at FY. Clear the air, roof the pocket at feet+1
    # headroom, and wall the far/side exits so the only standable cell next to the pig is the bot's.
    rcon("kill @e[type=pig]"); rcon("kill @e[type=item]")   # NoAI pigs aren't valid targets; drops confound @meat
    rcon(f"fill {X-4} {FY+1} {Z-9} {X+6} {FY+6} {Z+4} minecraft:air")     # clear the volume (incl. the good-pig strip to the north)
    # A low-headroom nook the pig cannot flee: ceiling at feet+2 (a 1.8-tall PLAYER can't stand
    # under it) + walls on the far and side faces, OPEN only toward the bot. So the pig sits ~1
    # block away in a cell whose only standable neighbour is the bot's own cell -> the approach
    # goal snaps onto the bot, and the canopy edge blocks the swing's line of sight: the live
    # forest stall in miniature (pigs under the tree canopy, 376 log blocks at spawn).
    rcon(f"fill {PIGX} {FY+2} {Z-1} {X+2} {FY+2} {Z+1} minecraft:stone")   # ceiling
    rcon(f"fill {X+3} {FY+1} {Z-1} {X+3} {FY+2} {Z+1} minecraft:stone")    # far wall (east)
    rcon(f"fill {PIGX} {FY+1} {Z-1} {X+2} {FY+1} {Z-1} minecraft:stone")   # side wall (north)
    rcon(f"fill {PIGX} {FY+1} {Z+1} {X+2} {FY+1} {Z+1} minecraft:stone")   # side wall (south)
    time.sleep(1)

def tag_alive(tag):
    return "Test passed" in rcon(f"execute if entity @e[tag={tag},type=pig]")

rcon(f"tp {BOT} {BOTX+0.5} {FY+1} {BOTZ+0.5}")
rcon(f"clear {BOT}"); rcon(f"give {BOT} minecraft:stone_sword")   # NO food: @meat 1 must kill a pig
rcon(f"effect give {BOT} minecraft:saturation 30 20")            # keep hunger full without a food ITEM
time.sleep(1)
# BAD pig: penned in the low-headroom nook 1 block east (the CLOSEST -> the food task picks it first).
rcon(f"summon minecraft:pig {PIGX+0.5} {FY+1} {PIGZ+0.5} {{Tags:[\"bench\",\"bad\"]}}")
# GOOD pig: clean open ground 6 blocks north, trivially killable once the bot stops fixating on BAD.
rcon(f"summon minecraft:pig {X+0.5} {FY+1} {Z-6+0.5} {{Tags:[\"bench\",\"good\"]}}")
time.sleep(1)
py4j("reset")
print(f"bad pig: {'yes' if tag_alive('bad') else 'MISSING'} | good pig: {'yes' if tag_alive('good') else 'MISSING'}")
s = py4j("gs"); print(f"bot at {s.get('pos')} looking {s.get('lookingAt')}")

py4j("cmd", c="@meat 1")
print(f"@meat 1 issued; watching {KILL_TIMEOUT}s (fix should ABANDON the bad pig and kill the good one)...")
good_killed = False; any_killed_at = None; t0 = time.time(); seen=set()
while time.time()-t0 < KILL_TIMEOUT:
    time.sleep(3)
    bad = tag_alive("bad"); good = tag_alive("good")
    s = py4j("gs"); pos = s.get("pos")
    ch=[c for c in py4j("chat",n=6)["chat"] if c not in seen]; seen.update(ch)
    note=[c for c in ch if any(w in c for w in ("bandon","blacklist","Blacklist","No block","reposition","wander"))]
    print(f"  t={time.time()-t0:.0f}s pos={pos} bad={'X' if not bad else '.'} good={'X' if not good else '.'}"
          + (" | "+" || ".join(x[-55:] for x in note[-2:]) if note else ""))
    if (not bad or not good) and any_killed_at is None: any_killed_at = time.time()-t0
    if not good: good_killed = True; break

print("=== GUIDE DUMP ==="); print(py4j("guide")["r"][:800])
py4j("cmd", c="@stop"); py4j("cmd", c=";stop")
print(f"first kill at: {any_killed_at:.0f}s" if any_killed_at is not None else "no kill in window")
# PASS = the GOOD (clean) pig was killed: the bot stopped fixating on the awkward one and moved on.
print("PASS: good pig killed — the bot abandoned the awkward one and moved on"
      if good_killed else "FAIL: good pig survived — still fixating / stalled")
sys.exit(0 if good_killed else 1)
