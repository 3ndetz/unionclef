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
import functools, json, os, pathlib, re, subprocess, sys, time
print = functools.partial(print, flush=True)
SPAWN_FILE=pathlib.Path(__file__).with_name("gamer_spawn.txt")
CLIENT="uctest-mc-tester1"; GSERVER="uctest-gamer-server"; BOT="tester1"; PORT=25333
MINUTES=float(sys.argv[1]) if len(sys.argv)>1 and not sys.argv[1].startswith("--") else 5.0
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
elif op=="stats": out={"s": str(mc.placeStats() or "")}
elif op=="blk": out={"b": {str(k): str(v) for k, v in dict(mc.getBlockAt(int(req["x"]),int(req["y"]),int(req["z"]))).items()}}
elif op=="respawn": out={"r": str(mc.respawnPlayer())}
elif op=="zero": out={"r": str(mc.resetRunCounters())}
elif op=="wdbg": out={"r": str(mc.setWalkerDebug(bool(req.get("on"))))}
elif op=="task": out={"chain": str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-1400:], "runner": str(mc.getRunnerStatus() or "")[:300]}
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
    try:
        wait_for("gamer rcon", lambda: "players" in grcon("list"), 120, 6)
    except TimeoutError as e:
        raise StandDown(f"gamer-server rcon never answered: {e}")
    grcon("difficulty easy"); grcon("gamerule doDaylightCycle true")
    print("[2] connect bot to gamer-server...")
    if not py4j("state")["inGame"] or True:
        # ONE CONNECT ATTEMPT IS NOT ENOUGH.
        # Reconstructed from both logs: the bot dies mid-run (a creeper, in the run that exposed
        # this), the next run's connect disconnects the client first -- "tester1 lost connection"
        # on the server at 09:30:21 -- and it never gets back in, sitting on the title screen with
        # no position. Which then read as "the stand is down" and threw the run away. Retry the
        # connect instead, and only give up when several attempts in a row fail.
        for attempt in range(4):
            py4j("connect", ip="gamer-server")
            try:
                wait_for("bot in game (gamer)", lambda: py4j("state")["inGame"], 60, 5)
                break
            except TimeoutError:
                print(f"  connect attempt {attempt + 1} did not land, retrying")
        if not py4j("state")["inGame"]:
            raise StandDown("client would not rejoin after four attempts")
        # WAIT FOR THE WORLD, NOT JUST THE CONNECTION. inGame flips as soon as the play
        # handler exists; the client world can still be null a moment later, and @gamer
        # issued in that window threw a NullPointerException out of BeatMinecraftTask's
        # constructor. The task then never existed, the bot sat "busy" and motionless, and
        # this script recorded five minutes of that as a PATHFINDING failure. Wait for a
        # position the server agrees with instead of sleeping and hoping.
        # THREE CASES, NOT TWO. A bot with no position is usually not a dead stand at all -- it
        # is a DEAD BOT sitting on the death screen, which has no position to report. Measured:
        # the server was running the whole time (exit 0, never OOM, zero restarts) with
        # "tester1 was blown up by Creeper" in its log, while this branch was filing the run as
        # infrastructure failure and throwing it away -- two runs in three. Respawn first, and
        # only call it INVALID if that does not help either.
        try:
            wait_for("world loaded (bot has a position)",
                     lambda: bool((py4j("gs").get("self") or {}).get("pos")), 60, 5)
        except TimeoutError:
            print("  no position — bot may be dead, respawning")
            for _ in range(8):
                try:
                    py4j("respawn")
                except Exception:
                    pass
                time.sleep(4)
                if bool((py4j("gs").get("self") or {}).get("pos")):
                    break
            if not bool((py4j("gs").get("self") or {}).get("pos")):
                raise StandDown("no position even after respawn attempts")
            print("  respawned")
        time.sleep(5)
    # A RUN THAT STARTS WHEREVER THE LAST ONE STOPPED MEASURES LUCK, NOT CODE.
    # This world is never wiped, so each @gamer run began from whatever the previous one left:
    # one run started at a pond holding an iron sword, the next somewhere else entirely. That
    # made single-run conclusions worthless -- measured twice in a row, the same build gave
    # pdEnter=192 and then pdEnter=0, because the bot was simply doing different things. Death
    # and respawn is vanilla's own "put me back at world spawn", and it is what the nav suite's
    # hard reset uses for exactly this reason (uctest/actors.py fresh_reset).
    print("[2b] reset to a known start (kill -> respawn -> empty -> heal -> day)...")
    grcon(f"gamerule keepInventory false")
    grcon(f"kill {BOT}")
    time.sleep(3)
    py4j("cmd", c="@stop")          # a task surviving the death would fight the next run
    wait_for("bot respawned", lambda: (py4j("gs").get("self") or {}).get("hp", 0) > 0, 120, 4)
    grcon(f"clear {BOT}")
    grcon(f"effect clear {BOT}")
    grcon("time set day")
    grcon(f"kill @e[type=item,distance=..40]")   # our own dropped kit must not be re-collected
    time.sleep(2)
    # RESPAWN IS NOT A FIXED POINT. Vanilla scatters a respawn around the world spawn, and two
    # consecutive resets landed the bot at 99.5,143 and 91.7,137 -- far enough apart that one is
    # beside a forest and the other is not, which is the difference between this bench passing
    # and failing. Pin it: the first run records where it landed, every later run teleports
    # there, and GAMER_SPAWN overrides. Recorded rather than written into the source, because a
    # coordinate baked into a runner is exactly the per-world hardcode this project forbids.
    spawn = os.environ.get("GAMER_SPAWN")
    if not spawn and SPAWN_FILE.exists():
        spawn = SPAWN_FILE.read_text(encoding="utf-8").strip()
    if spawn:
        grcon(f"tp {BOT} {spawn}")
        time.sleep(2)
    pos = (py4j("gs").get("self") or {}).get("pos")
    if not spawn and pos:
        got = ",".join(str(round(float(c))) for c in str(pos).split(","))
        SPAWN_FILE.write_text(got.replace(",", " "), encoding="utf-8")
        print("  recorded start point for later runs:", got)
    print("  start pos:", pos, "(pinned)" if spawn else "(first run — recording)")
    print("[3] tungsten-primary ON + @gamer...")
    print("  swap:", py4j("swap", on=True))
    print("  walker debug:", py4j("wdbg", on=True))
    # A COUNTER IS ONLY A MEASUREMENT IF YOU KNOW ITS ZERO. Without this every pd*/mq* number
    # below is a container-lifetime sum printed as if it described this run -- two consecutive
    # runs both reported pdNoVec=238, which is what gave it away. run_suite.py has always done
    # this; the smoke did not.
    print("  zero counters:", py4j("zero"))
    inv0 = py4j("inv"); print("  start inv:", inv0)
    py4j("cmd", c="@gamer")

    # WHAT THE RUN ACHIEVED, NOT JUST HOW MUCH IT CARRIED.
    # "items gained" cannot tell progress from thrashing: a run that mines 17 cobblestone and
    # one that crafts a stone pickaxe read the same. A playthrough is a LADDER, so name the
    # rungs and report which were reached. This is the measurable form of "@gamer beats the
    # game" — without it the acceptance criterion is one boolean at the very end.
    # THE FIRST CRAFT IS ITS OWN RUNG, AND "wood" MUST NOT SWALLOW IT.
    # "wood" used to accept planks, so a bot that had CRAFTED planks scored the same as one that
    # had merely chopped a log -- and the next rung asked for a crafting_table, an item not needed
    # yet. Between "can chop" and "built a table" lay the whole of "can craft", invisible: measured
    # a run ending with sticks and planks in the pack while the bench reported no crafting at all.
    LADDER = [("wood", ("log",)), ("first craft", ("planks", "stick")),
              ("crafting", ("crafting_table",)),
              ("wood tools", ("wooden_pickaxe", "wooden_axe", "wooden_sword")),
              ("stone tools", ("stone_pickaxe", "stone_axe", "stone_sword")),
              ("furnace", ("furnace",)), ("coal", ("coal", "torch")),
              ("iron ore", ("raw_iron", "iron_ore")), ("iron", ("iron_ingot",)),
              ("iron tools", ("iron_pickaxe", "iron_sword")),
              ("food", ("bread", "cooked_", "apple", "carrot", "berries")),
              ("bucket", ("bucket",)), ("nether", ("obsidian", "flint_and_steel")),
              ("blaze", ("blaze_rod", "blaze_powder")), ("ender", ("ender_pearl", "ender_eye"))]
    reached = {}
    # A RUNG YOU SPAWNED WITH IS NOT A RUNG YOU CLIMBED.
    # The gamer world is not wiped between runs, so the bot can start already holding gear it
    # earned in an earlier run. Measured: a run reported "iron tools at 21.2s" with items gained
    # 0 and reported PASS — it had simply been handed the previous run's pickaxe. Rungs already
    # satisfied by the STARTING inventory are recorded and then excluded, so the bar is what this
    # run actually achieved.
    preexisting = {rung for rung, needles in LADDER
                   if any(any(nd in i for nd in needles) for i in (inv0.get("ids") or []))}
    if preexisting:
        print("  already held at start (cannot count):", ", ".join(sorted(preexisting)))
    ctx_last_chain = [None]

    print(f"[4] watching {MINUTES} min for progress...")
    t0=time.time(); best_items=inv0.get("items",0); moved=set(); last_pos=None; responsive=0; busy_cnt=0
    while time.time()-t0 < MINUTES*60:
        time.sleep(20)
        try:
            gs=py4j("gs"); inv=py4j("inv"); ht=py4j("hasTask")
            # WHAT IS IT DOING WHEN IT FAILS? Asking after the run is useless — the task
            # chain reads "No tasks" the moment @gamer ends, which is what my first attempt
            # measured. Sample it WHILE the run is alive, and only when it changes, so the
            # log shows the sequence of intents rather than one line repeated forty times.
            okc, tc, tcj = True, None, {}
            try:
                tcj = py4j("task"); tc = tcj.get("chain")
            except Exception:
                okc = False
            if okc and tc and tc != ctx_last_chain[0]:
                ctx_last_chain[0] = tc
                print(f"  TASK {tc}")
                # IS THE TARGET REALLY THERE? The bot was seen walking hundreds of blocks toward
                # a "wool" cell at y=-50. Ask the CLIENT what is actually at the cell it picked,
                # because the scanner that picked it is the client's.
                m = re.search(r"Destroy block at (-?\d+),\s*(-?\d+),\s*(-?\d+)", tc)
                if m:
                    bx, by, bz = (int(g) for g in m.groups())
                    try:
                        print(f"  TARGET ({bx},{by},{bz}) is",
                              py4j("blk", x=bx, y=by, z=bz).get("b"))
                    except Exception as e:
                        print(f"  target probe failed: {str(e)[:80]}")
            if okc and tcj.get("runner"):
                print(f"  RUNNER {tcj.get('runner')}")
            for rung, needles in LADDER:
                if rung in reached or rung in preexisting: continue
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
    # WHAT DID IT ACTUALLY END UP HOLDING? "Ten items gathered" and "no materials to craft"
    # are only contradictory if those ten are logs. Print the list rather than assume.
    # DID IT DIE, AND TO WHAT? A run that ends with an empty pack has usually lost it on death,
    # and the server says so in plain words ("tester1 was blown up by Creeper"). Counting that is
    # the difference between "crafting is flaky" and "the bot keeps dying with its progress".
    deaths = [ln for ln in sh(["docker", "logs", "--tail", "400", GSERVER]).stdout.splitlines()
              if BOT in ln and (" was " in ln or " died" in ln or " fell " in ln)]
    print(f"  deaths this run: {len(deaths)}")
    for d in deaths[-4:]:
        print("   ", d.strip()[-110:])
    print("  end inv:", py4j("inv"))
    print("  queue stats:", py4j("stats").get("s"))
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
    # AND NOT "ANY ITEM" EITHER. That escape hatch was still in the bar one commit ago, and a
    # run passed on a single picked-up AZALEA — measured, item id minecraft:azalea, ten minutes,
    # no rung. A flower is not progress toward beating the game. The bar is a RUNG.
    # A BAR THAT ANY RUNG SATISFIES CANNOT SHOW THE NEXT ONE.
    # With wood solved, every run passes on wood alone and progress on crafting is invisible.
    # --rung NAME demands that specific rung, so work on rung two can be measured at all.
    want = None
    if "--rung" in sys.argv:
        want = sys.argv[sys.argv.index("--rung") + 1]
    ok = responsive>=3 and busy_cnt>=2 and (want in reached if want else bool(reached))
    if want:
        print(f"  required rung '{want}':", "reached" if want in reached else "NOT reached")
    print("  GAMER_SMOKE:", "PASS" if ok else "FAIL (or no early progress in window)")
    return ok

# ONE RUN OF THIS IS A COIN, NOT A CRITERION.
# With the start point pinned to a tenth of a block, two consecutive runs still went FAIL then
# PASS: the variance is the bot's own behaviour -- task order, which way it explores, what
# spawns -- not the setup. Over a session's runs from an empty inventory the tally was four in
# seven. So a fix "confirmed" or "refuted" by a single run is being judged at random, which
# cost real passes. The verdict is a repeated one, the way run_suite --repeat already works.
class StandDown(Exception):
    """The stand, not the bot, is what failed."""

def sweep(runs, need):
    # AN INFRASTRUCTURE FAILURE IS NOT A BOT FAILURE.
    # The gamer server's container died mid-session and every run afterwards reported FAIL with
    # zeroed counters, which read exactly like a code regression -- and was believed as one for
    # a pass. That is the mirror image of the false-green bars repaired earlier: a false RED.
    # A run that cannot reach a live server is INVALID and does not count either way, the same
    # convention run_suite already uses for a starved host.
    results = []
    for i in range(runs):
        print("")
        print(f"=========== RUN {i + 1}/{runs} ===========")
        try:
            results.append(bool(main()))
        except StandDown as e:
            # THE CLIENT WEDGES EVERY FEW RUNS, AND DISCARDING THE RUN IS NOT GOOD ENOUGH.
            # It has eaten about half the measurements today and twice produced a false
            # conclusion -- "the server is dying", then "the bot is dead" -- before the logs said
            # otherwise. Restart it and take the run again; only call it INVALID if that fails too.
            print(f"  stand down ({str(e)[:120]}) — restarting the client and retrying")
            sh(["docker", "restart", CLIENT], t=120)
            time.sleep(90)
            try:
                results.append(bool(main()))
            except StandDown as e2:
                print(f"  INVALID (stand down after restart): {str(e2)[:140]}")
                results.append(None)
        except Exception as e:                       # a broken run is a failed run, not a crash
            print(f"  run error: {str(e)[:160]}")
            results.append(False)
    invalid = sum(1 for r in results if r is None)
    if invalid:
        print(f"  {invalid} run(s) INVALID — the stand was down, not the bot. Restart with:")
        print("    docker compose -f deploy/compose.test.yml --profile gamer up -d")
    results = [r for r in results if r is not None]
    runs = len(results) or 1
    passed = sum(results)
    print("")
    print(f"=========== GAMER_SUITE: {passed}/{runs} passed, need {need} ===========")
    print("  runs:", " ".join("PASS" if r else "FAIL" for r in results))
    return passed >= need

if __name__ == "__main__":
    rep = 1
    if "--repeat" in sys.argv:
        rep = int(sys.argv[sys.argv.index("--repeat") + 1])
    # Default threshold: all of one run, otherwise all but one. A playthrough bench that tolerates
    # half its runs failing is not a criterion either.
    need = rep if rep < 3 else rep - 1
    if "--need" in sys.argv:
        need = int(sys.argv[sys.argv.index("--need") + 1])
    sys.exit(0 if (sweep(rep, need) if rep > 1 else main()) else 1)
