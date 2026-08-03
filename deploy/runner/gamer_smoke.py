#!/usr/bin/env python3
"""@gamer smoke test on a real survival world, routed through tungsten.

Boots the survival gamer-server, connects the bot, enables tungsten-primary
(setTungstenPathing), starts @gamer, and watches for a few minutes: does the bot
make PROGRESS (gather items) without crashing or freezing? A bounded baptism —
the full playthrough is nightly-scale. Bring up the server first:
  docker compose -f deploy/compose.test.yml --profile gamer up -d
Exit 0 = the bot started @gamer and made early progress (items gained), stayed
responsive and not permanently stuck.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
CLIENT="uctest-mc-tester1"; GSERVER="uctest-gamer-server"; BOT="tester1"; PORT=25333
MINUTES=float(sys.argv[1]) if len(sys.argv)>1 else 5.0
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=req.get("port",25333),auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="swap": out=dict(mc.setTungstenPathing(bool(req["on"])))
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="gs":
    gs=mc.getGameState()
    out={"inGame":gs.get("inGame"),"self":dict(gs.get("self") or {})}
elif op=="inv":
    f=mc.getInventoryFull(); n=0; items=0; ids=[]
    try:
        for s in f.get("slots") or []:
            sd=dict(s)
            if not sd.get("empty"):
                n+=1; items+=int(sd.get("count",0) or 0)
                nm=str(sd.get("item") or sd.get("name") or "")
                if nm: ids.append(nm)
    except Exception: pass
    out={"nonEmpty":n,"items":items,"ids":ids}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(int(req.get("n",8)))]}
elif op=="hasTask": out={"busy":mc.hasActiveTask()}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=40): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(op,t=30,**kw):
    r=sh(["docker","exec",CLIENT,"python3","-c",SNIP,json.dumps({"op":op,"port":PORT,**kw})],t)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def grcon(c,t=20): return sh(["docker","exec",GSERVER,"rcon-cli",c],t).stdout.strip()
def wait_for(desc,fn,ts,iv=4):
    t0=time.time(); last=None
    while time.time()-t0<ts:
        try:
            last=fn()
            if last: print(f"  [ok] {desc}"); return last
        except Exception as e: last=e
        time.sleep(iv)
    raise TimeoutError(f"{desc}: {ts}s ({last})")

def main():
    print("[1] wait gamer-server rcon...")
    wait_for("gamer rcon", lambda: "players" in grcon("list"), 300, 6)
    grcon("difficulty easy"); grcon("gamerule doDaylightCycle true")
    print("[2] connect bot to gamer-server...")
    if not py4j("state")["inGame"] or True:
        py4j("connect", ip="gamer-server")
        wait_for("bot in game (gamer)", lambda: py4j("state")["inGame"], 200, 5)
        # WAIT FOR THE WORLD, NOT JUST THE CONNECTION. inGame flips as soon as the play
        # handler exists; the client world can still be null a moment later, and @gamer
        # issued in that window threw a NullPointerException out of BeatMinecraftTask's
        # constructor. The task then never existed, the bot sat "busy" and motionless, and
        # this script recorded five minutes of that as a PATHFINDING failure. Wait for a
        # position the server agrees with instead of sleeping and hoping.
        wait_for("world loaded (bot has a position)",
                 lambda: bool((py4j("gs").get("self") or {}).get("pos")), 200, 5)
        time.sleep(5)
    print("[3] tungsten-primary ON + @gamer...")
    print("  swap:", py4j("swap", on=True))
    inv0 = py4j("inv"); print("  start inv:", inv0)
    py4j("cmd", c="@gamer")

    # WHAT THE RUN ACHIEVED, NOT JUST HOW MUCH IT CARRIED.
    # "items gained" cannot tell progress from thrashing: a run that mines 17 cobblestone and
    # one that crafts a stone pickaxe read the same. A playthrough is a LADDER, so name the
    # rungs and report which were reached. This is the measurable form of "@gamer beats the
    # game" — without it the acceptance criterion is one boolean at the very end.
    LADDER = [("wood", ("log", "planks")), ("crafting", ("crafting_table",)),
              ("wood tools", ("wooden_pickaxe", "wooden_axe", "wooden_sword")),
              ("stone tools", ("stone_pickaxe", "stone_axe", "stone_sword")),
              ("furnace", ("furnace",)), ("coal", ("coal", "torch")),
              ("iron ore", ("raw_iron", "iron_ore")), ("iron", ("iron_ingot",)),
              ("iron tools", ("iron_pickaxe", "iron_sword")),
              ("food", ("bread", "cooked_", "apple", "carrot", "berries")),
              ("bucket", ("bucket",)), ("nether", ("obsidian", "flint_and_steel")),
              ("blaze", ("blaze_rod", "blaze_powder")), ("ender", ("ender_pearl", "ender_eye"))]
    reached = {}

    print(f"[4] watching {MINUTES} min for progress...")
    t0=time.time(); best_items=inv0.get("items",0); moved=set(); last_pos=None; responsive=0; busy_cnt=0
    while time.time()-t0 < MINUTES*60:
        time.sleep(20)
        try:
            gs=py4j("gs"); inv=py4j("inv"); ht=py4j("hasTask")
            for rung, needles in LADDER:
                if rung in reached: continue
                if any(any(nd in i for nd in needles) for i in inv.get("ids") or []):
                    reached[rung] = round(time.time()-t0, 1)
                    print(f"  RUNG '{rung}' at {reached[rung]}s")
            responsive+=1
            pos=gs.get("self",{}).get("pos"); hp=gs.get("self",{}).get("hp")
            if ht.get("busy"): busy_cnt+=1
            if pos: moved.add(pos)
            best_items=max(best_items, inv.get("items",0))
            print(f"  t={int(time.time()-t0)}s inGame={gs.get('inGame')} hp={hp} pos={pos} items={inv.get('items')} busy={ht.get('busy')}")
        except Exception as e:
            print(f"  poll error (client may be busy): {str(e)[:80]}")
    print("  recent chat:", py4j("chat", n=10).get("chat"))

    gained = best_items - inv0.get("items",0)
    distinct_pos = len(moved)
    print("\n=== RESULTS ===")
    print(f"  responsive polls: {responsive}, busy polls: {busy_cnt}, distinct positions: {distinct_pos}, items gained: {gained}")
    if reached:
        print("  ladder: " + ", ".join(f"{k}@{v}s" for k, v in reached.items()))
    else:
        print("  ladder: nothing reached")
    # PROGRESS IS A RUNG, NOT A WIGGLE.
    # The old bar was "gained an item OR stood in 3 different places", and `distinct_pos >= 3`
    # is met by a bot that shuffles. It passed a run measured at THREE positions, ZERO items and
    # NOT ONE rung of the ladder — a green light that meant nothing, which is the same defect
    # class as the bench checks repaired earlier today. A playthrough is a ladder: clearing no
    # rung in a whole window is not progress, whatever the bot's feet did.
    ok = responsive>=3 and busy_cnt>=2 and (bool(reached) or gained>0)
    print("  GAMER_SMOKE:", "PASS" if ok else "FAIL (or no early progress in window)")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
