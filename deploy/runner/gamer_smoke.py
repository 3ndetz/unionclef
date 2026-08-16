import io
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
RUN_INDEX_FILE=pathlib.Path(__file__).with_name("gamer_run_index.txt")
RUN_SEQ=[0]   # which run of this sweep we are in, so a freeze dump names its own file
# WRITE THE DUMPS WHERE BOTH SHELLS AGREE. Python on Windows reads a leading /tmp as the tmp
# directory of the current DRIVE, while the bash side looks in its own; the first two captures
# were written and then reported missing. An explicit directory next to the runner ends that.
FREEZE_DIR=str(pathlib.Path(__file__).with_name("freezes"))
os.makedirs(FREEZE_DIR, exist_ok=True)
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
elif op=="swapstate": out=dict(mc.pathingMode())
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chatcmd": mc.ChatMessage(req["c"]); out={"ok":True}
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
elif op=="perf": out={"p": dict(mc.getPerfStats())}
elif op=="tdump": out={"d": str(mc.threadDump(str(req.get("f",""))))[:4000]}
elif op=="logs": out={"n": int(mc.countLogsNear(int(req.get("r",40))))}
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

def quiet_the_box():
    """Stop the tester clients THIS run does not use, and return them so they can be put back.

    ⛔ THIS IS WHAT MAKES THE PLAYTHROUGH MEASURABLE AT ALL, and it is one docker stop.

    The playthrough uses tester1 only. tester2 sits on the flat arena doing nothing and still
    burns ~110% of a core doing it, because an idle MC client is not an idle process -- it
    renders. On this box that was the difference between a run and no run:

        both clients up    7.0 fps  ->  INVALID, refused before the run even starts
        tester2 stopped   11.0 fps  ->  PASS, ladder to wood tools@185.4s

    The floor is 12 and the survival world is fps-bound, so the fps every other container is
    NOT using is the whole budget. Worth stating because a week of "the playthrough cannot be
    measured on this machine" was really "the bench was competing with itself", and the fix was
    never the GPU -- which on this host cannot render at all (docs/AUTOTESTING.md).

    Derived from what is actually running rather than a hardcoded name, so a third tester or a
    renamed one needs no edit here.
    """
    running = sh(["docker", "ps", "--format", "{{.Names}}"], t=30).stdout.split()
    peers = [n for n in running if n.startswith("uctest-mc-tester") and n != CLIENT]
    for p in peers:
        sh(["docker", "stop", p], t=90)
    if peers:
        print(f"  quieted for the run: {', '.join(peers)} (idle clients still cost ~110% CPU each)")
    return peers

def unquiet_the_box(peers):
    """Put back whatever quiet_the_box stopped. Best-effort: never fail a finished run over it."""
    for p in peers or []:
        try:
            sh(["docker", "start", p], t=90)
        except Exception as e:                       # noqa: BLE001 -- teardown must not mask a result
            print(f"  note: could not restart {p}: {str(e)[:80]}")
    if peers:
        print(f"  restarted after the run: {', '.join(peers)}")
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
    if True:
        # ALWAYS CONNECT, AND SAY SO RATHER THAN HIDING IT BEHIND A DEAD CONDITION.
        # This used to read `if not py4j("state")["inGame"] or True:` -- a condition welded open,
        # which reads like a check and is not one. The `or True` was right and the CONDITION was
        # wrong: inGame says the bot is in A world, not that it is in the GAMER one, so skipping
        # the connect when it happens to be on the uctest server is how a run ends up measuring the
        # wrong world. Connecting unconditionally is the correct behaviour, so state it plainly.
        #
        # ONE CONNECT ATTEMPT IS NOT ENOUGH.
        # Reconstructed from both logs: the bot dies mid-run (a creeper, in the run that exposed
        # this), the next run's connect disconnects the client first -- "tester1 lost connection"
        # on the server at 09:30:21 -- and it never gets back in, sitting on the title screen with
        # no position. Which then read as "the stand is down" and threw the run away. Retry the
        # connect instead, and only give up when several attempts in a row fail.
        # A SECOND OPINION IS NOT A BETTER ONE. This used to re-check inGame right after a
        # SUCCESSFUL wait, and that extra call catches the odd transient false -- which then threw
        # away a perfectly good run as "would not rejoin". Trust the wait that just succeeded;
        # only give up when every attempt has failed.
        joined = False
        for attempt in range(4):
            py4j("connect", ip="gamer-server")
            try:
                wait_for("bot in game (gamer)", lambda: py4j("state")["inGame"], 60, 5)
                joined = True
                break
            except TimeoutError:
                print(f"  connect attempt {attempt + 1} did not land, retrying")
        if not joined:
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
    # A FRESH PATCH OF FOREST EVERY RUN, OR THE BENCH ROTS.
    # The world is never wiped and the bot fells the trees around wherever it starts, so a FIXED
    # start gets harder every run: measured 21.5s to first log on untouched ground against 300 to
    # 585 seconds, or never, at a point the bot had been working all session. That decay was read
    # as code regressions for hours. So each run steps to ground the bot has not cleared: a
    # square spiral out from the base point, 300 blocks a step, with the index kept on disk.
    spawn = os.environ.get("GAMER_SPAWN")
    if not spawn and SPAWN_FILE.exists():
        base = [int(v) for v in SPAWN_FILE.read_text(encoding="utf-8").split()]
        idx = 0
        if RUN_INDEX_FILE.exists():
            try:
                idx = int(RUN_INDEX_FILE.read_text(encoding="utf-8").strip())
            except ValueError:
                idx = 0
        RUN_INDEX_FILE.write_text(str(idx + 1), encoding="utf-8")
        # ⛔ THE SPIRAL MARCHES AWAY FROM THE BASE FOR EVER, AND NOBODY BOUNDED IT.
        #
        # The index is monotonic across every run ever taken and each step is 300 blocks, so it
        # only ever gets further out. Found at 968: the spiral radius is ~31 steps, i.e. NINE
        # THOUSAND BLOCKS from the recorded base, landing in whatever the world has there. Three
        # windows in one day started at 94,-44 then 1492,-5038 then 5392,-3538, and the last of
        # those was treeless -- so the acceptance-criterion instrument has been degrading with
        # every run since it was written, and the degradation is invisible because each run only
        # prints its own coordinates.
        #
        # Variety WAS the point ("a 300-block step lands in whatever biome is there... the metric
        # was reading the biome"), and it still is. Variety inside a bounded neighbourhood is
        # variety; marching to the edge of the world is drift. Capped so runs cycle through a
        # ring around the base instead of leaving it behind: 64 steps is a radius of about four,
        # roughly 1200 blocks, which is plenty of biomes and always comes back.
        SPIRAL_CAP = 64
        idx %= SPIRAL_CAP
        # square spiral: right, up, left, down, growing every two legs
        dx, dz, leg, step = 1, 0, 1, 0
        x, z = 0, 0
        for _ in range(idx):
            x += dx
            z += dz
            step += 1
            if step == leg:
                step = 0
                dx, dz = -dz, dx
                if dz == 0:
                    leg += 1
        spawn = f"{base[0] + x * 300} {base[1]} {base[2] + z * 300}"
        print(f"  fresh start #{idx}: {spawn}")
    # FRESH IS NOT THE SAME AS SUITABLE.
    # A 300-block step lands in whatever biome is there. Measured: first log in 21 seconds on one
    # patch, never on another, same build -- so the metric was reading the biome. Walk the spiral
    # until the ground actually has trees on it, and say how many were skipped.
    if spawn and not os.environ.get("GAMER_SPAWN"):
        base = [int(v) for v in SPAWN_FILE.read_text(encoding="utf-8").split()]
        skipped = 0
        while skipped < 8:
            grcon(f"tp {BOT} {spawn}")
            time.sleep(3)
            try:
                logs = py4j("logs", r=40).get("n", -1)
            except Exception:
                logs = -1
            # A DOZEN LOGS IS NOT A FOREST. The first threshold accepted 12 within forty blocks
            # and that run reached nothing, so the check was still partly measuring the world.
            #
            # AND AN UNREADABLE COUNT IS NOT A FOREST EITHER. This used to break out of the loop on
            # logs < 0 -- the value it uses for "the call threw" -- so a failed count ACCEPTED the
            # spot. That is a check that cannot fail: measured, a run started in an ancient city at
            # y=-45 with no wood within reach, spent five minutes blacklisting wool 750 times, and
            # was recorded as the bot failing to reach the wood rung. Ask again; if it will not
            # answer twice, step on rather than pretend.
            if logs is not None and logs >= 0 and logs >= 40:
                print(f"  start has {logs} log blocks within 40")
                break
            if logs is None or logs < 0:
                time.sleep(2)
                try:
                    logs = py4j("logs", r=40).get("n", -1)
                except Exception:
                    logs = -1
                if logs is not None and logs >= 40:
                    print(f"  start has {logs} log blocks within 40 (second reading)")
                    break
                print(f"  could not count logs at {spawn} ({logs}) — stepping on rather than guessing")
            skipped += 1
            print(f"  no trees at {spawn} ({logs} logs) — stepping on")
            idx = (idx + 1) % 64
            RUN_INDEX_FILE.write_text(str(idx + 1), encoding="utf-8")
            dx2, dz2, leg2, step2, x2, z2 = 1, 0, 1, 0, 0, 0
            for _ in range(idx):
                x2 += dx2; z2 += dz2; step2 += 1
                if step2 == leg2:
                    step2 = 0
                    dx2, dz2 = -dz2, dx2
                    if dz2 == 0: leg2 += 1
            spawn = f"{base[0] + x2 * 300} {base[1]} {base[2] + z2 * 300}"
        if skipped:
            print(f"  skipped {skipped} treeless start(s)")
        # ⛔ RUNNING OUT OF ATTEMPTS IS NOT THE SAME AS FINDING A FOREST.
        #
        # This loop hardened its INNER checks three times -- a count that could not fail, an
        # unreadable count accepted as a forest, twelve logs called a forest -- and never handled
        # exhausting the spiral. It fell out of the while and started the run from the LAST
        # spawn, the one it had just rejected, printing nothing but an informational line.
        #
        # So the run measures the BIOME and reports it as the bot. Measured 2026-08-14: eight
        # skips, pinned at 5392,-3538 with "no trees ... (0 logs)" on that very spot, and the bot
        # spent the window walking 70+ blocks on rung one with items=0 and HP falling 15 -> 5 to
        # hostiles. Read naively that is "the playthrough regressed to nothing"; it is a treeless
        # start. The block above already records this happening once before, in an ancient city
        # at y=-45 -- "recorded as the bot failing to reach the wood rung".
        #
        # A world that cannot host the test is the same verdict as a client that cannot: INVALID,
        # not FAIL. Failing open in the direction that blames the bot is the defect this file
        # keeps paying for.
        if skipped >= 8:
            raise StandDown(
                f"no forest within {skipped} spiral steps of the pinned base -- the WORLD cannot "
                f"host this run, so its ladder would measure the biome. Delete "
                f"deploy/runner/gamer_spawn.txt to re-seed the search from a new base.")
    elif spawn:
        grcon(f"tp {BOT} {spawn}")
        time.sleep(2)
    pos = (py4j("gs").get("self") or {}).get("pos")
    if not spawn and pos:
        got = ",".join(str(round(float(c))) for c in str(pos).split(","))
        SPAWN_FILE.write_text(got.replace(",", " "), encoding="utf-8")
        print("  recorded start point for later runs:", got)
    print("  start pos:", pos, "(pinned)" if spawn else "(first run — recording)")
    print("[3] tungsten-primary (SHIPPED DEFAULT) + @gamer...")
    # MEASURE WHAT SHIPS. This used to call setTungstenPathing(True), which turned on four flags
    # at once -- including smartMoves, which is NOT a shipped default (it costs the search its
    # water moves; see nav_water 3/3). So the bench measured a configuration no user ever ran.
    # tungsten-primary is the default now, so the bench asserts it instead of setting it: if the
    # default ever regresses to baritone, this run says so instead of quietly fixing it.
    # DRAWING FOR NOBODY IS COST WITHOUT BENEFIT. No clip is recorded here, so tungsten's overlays
    # buy nothing.
    # WHAT IS AND IS NOT MEASURED: one A/B read 12 fps with them on and 16 with them off, which
    # looked like the lever the perf notes promise. Two later runs WITH the pins in place read 10.
    # The effect is NOT established -- the first pair was the machine moving under me -- so the
    # pins stay on principle and that number is not to be quoted as fact.
    for flag in ("renderVisualization", "renderPathMoves", "renderCombat",
                 "renderBreakPlan", "renderPlacePlan"):
        # ChatMessage, not ExecuteCommand: `;settings` is TUNGSTEN's chat command, while
        # ExecuteCommand runs altoclef's `@` commands -- sent the wrong way it silently does
        # nothing, which is what the first attempt did (fps unchanged at 10).
        py4j("chatcmd", c=f";settings {flag} false")
    # WHAT THIS CLIENT CAN DO IN THIS WORLD, TODAY, BEFORE THE BOT STARTS.
    # A fixed fps floor cannot work here. The survival world costs about half the frame budget of
    # the flat course arena -- measured tonight: 35-43 fps idle on the flat stand, 17-19 idle in the
    # gamer world, 9-12 under a run -- so the floor that keeps nav honest (14) marks EVERY @gamer
    # failure invalid, which is a check that cannot fail: the very defect this guard was added to
    # remove. The reference is therefore taken per run, from this client in this world moments
    # before @gamer starts, and a run is only disowned when it collapses well below its own
    # reference.
    fps_ref = None
    try:
        fps_ref = float(py4j("perf").get("p", {}).get("fps") or 0) or None
    except Exception:
        pass
    print(f"  client fps before start: {fps_ref}")
    # A REFERENCE TAKEN ON A CRAWLING CLIENT CALIBRATES THE FLOOR DOWN TO THE CRAWL.
    # The per-run reference fixed one hole and opened a smaller one: if the machine is already
    # struggling when the reference is taken, the floor drops with it and a doomed run counts as a
    # real failure. Measured: reference 5.0, median 8.0, floor 6.0 -- a ten-minute window that
    # reached nothing recorded as a bot failure, on a build that had cleared wood in 44s hours
    # earlier. The gamer world manages 17-19 fps idle when the machine is healthy, so a reference
    # in single digits is not a slow world, it is a machine that cannot answer.
    # Standing down BEFORE the window also gives back the ten minutes.
    SANE_REF_FPS = 12.0
    if fps_ref is not None and fps_ref < SANE_REF_FPS:
        raise StandDown(f"client at {fps_ref:.0f} fps before the run even starts"
                        f" (< {SANE_REF_FPS}) — the machine cannot answer today")
    st = py4j("swapstate")
    print("  shipped pathing flags:", st)
    if not st.get("tungstenPrimary"):
        print("FAIL: tungsten is not the default pathfinder - the bot would run on the old engine")
        sys.exit(2)
    # --swap runs the OLD bench behaviour (setTungstenPathing turns on four flags at once,
    # smartMoves among them). Kept as a CONTROL: when a run on shipped defaults fails, this says
    # whether the difference is the flags or something else that changed.
    if "--swap" in sys.argv:
        print("  CONTROL RUN: setTungstenPathing(True) ->", py4j("swap", on=True))
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
    prev_stats = {}
    stall = [None, 0]
    froze = [0, False]   # consecutive frozen polls, and whether this run already dumped

    # MARK WHERE THE RUN STARTS IN THE SERVER LOG.
    # Deaths were being read from a --tail window WIDER than the run, so a run whose counters were
    # all zero still reported "6 deaths, 4 falls" -- deaths from some earlier run entirely. Counters
    # and deaths were measuring different spans of time and being compared as if they were not.
    log_mark = len(sh(["docker", "logs", "--tail", "2000", GSERVER]).stdout.splitlines())
    print(f"[4] watching {MINUTES} min for progress...")
    t0=time.time(); best_items=inv0.get("items",0); moved=set(); last_pos=None; responsive=0; busy_cnt=0
    fps_samples = []
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
            # HOW FAST WAS THE CLIENT WHILE IT TRIED? The nav suite has asked this since the day a
            # starved host was read as a code regression; this bench never has, so its verdicts
            # during a loaded hour looked exactly like the bot failing. Measured on this machine:
            # another project's containers at 250%+, the client at 10 fps, and four sweeps in a row
            # reporting "nothing reached" against 3/3 from the last quiet sample.
            try:
                fps_samples.append(float(py4j("perf").get("p", {}).get("fps") or 0))
            except Exception:
                pass
            pos=gs.get("self",{}).get("pos"); hp=gs.get("self",{}).get("hp")
            if ht.get("busy"): busy_cnt+=1
            if pos: moved.add(pos)
            best_items=max(best_items, inv.get("items",0))
            # DELTAS, NOT TOTALS. Over a whole run the counters smear two and a half idle
            # minutes across eight working ones; per poll they show what goes quiet WITH the bot.
            dl = ""
            try:
                st = py4j("stats").get("s") or ""
                cur = {}
                for tok in st.split():
                    if "=" in tok:
                        k, v = tok.split("=", 1)
                        if v.lstrip("-").isdigit():
                            cur[k] = int(v)
                keys = ["pdEnter", "mqStarted", "mqSteps", "dbTick", "rayMiss", "leafCleared"]
                dl = " d:" + ",".join(f"{k[:7]}+{cur.get(k,0)-prev_stats.get(k,0)}" for k in keys)
                prev_stats.clear()
                prev_stats.update(cur)
            except Exception:
                pass
            # A STALL IS AN UNCHANGED POSITION, NOT A ZEROED COUNTER.
            # The first watcher only knew "every delta zero" and walked straight past the state
            # that replaced it: the movement queue taking 110 steps a poll while the bot does not
            # shift a single block. Position is the honest signal, whatever the counters say.
            here = str(pos)
            if here == stall[0] and here != "None":
                stall[1] += 1
                if stall[1] == 3:
                    print(f"  STALLED: position unchanged for 3 polls at {here}")
                    # A STALL DESERVES THE SAME EVIDENCE A FREEZE GETS.
                    # The dump trigger below only covers "no world", so the run that stalled
                    # IN GAME -- position frozen, still connected -- produced no stack and no
                    # exit distribution, and left nothing to diagnose but six counter deltas.
                    # A stalled bot is still ticking, so the useful evidence here is WHICH exit
                    # the drive keeps taking: the six printed deltas cannot say, and the full
                    # counter string can.
                    try:
                        blob = ["position stalled at %s" % here,
                                "", "FULL COUNTERS:", py4j("stats").get("s") or "",
                                "", "RUNNER:", str(py4j("task").get("runner", "")),
                                "", "CHAIN:", str(py4j("task").get("chain", "")),
                                "", "THREADS:", py4j("tdump", f="PathFinder,Tungsten,Baritone,Render")["d"] or ""]
                        fn = os.path.join(FREEZE_DIR, "stall_run%d.txt" % RUN_SEQ[0])
                        io.open(fn, "w", encoding="utf-8").write(chr(10).join(blob))
                        print(f"  stall evidence written to {fn}")
                    except Exception as e:
                        print(f"  stall evidence failed: {str(e)[:70]}")
            else:
                stall[0] = here
                stall[1] = 0
            # A FROZEN CLIENT MUST BE DUMPED WHILE IT IS STILL FROZEN.
            # Measured signature, run 1 of the sweep after the constructor-NPE fix: inGame=False,
            # hp=None, and EVERY counter delta zero for 222 seconds straight -- while py4j kept
            # answering. That is the main thread blocked with the bridge thread alive, and it is
            # the freeze the BlockOptionalMeta bounded wait was supposed to have removed.
            # Reading the log afterwards is useless: by the end of the sweep the client has
            # restarted (the deltas go NEGATIVE as the counters reset) and the stack is gone.
            # So take the dump HERE, on the poll that sees it, and keep the first one per run --
            # the first is the one that caught the block, later ones only catch the aftermath.
            frozen = (not gs.get("inGame")) and dl and all(
                tok.endswith("+0") for tok in dl.replace(" d:", "").split(","))
            if frozen:
                froze[0] += 1
                if froze[0] == 2 and not froze[1]:
                    froze[1] = True
                    try:
                        # NAME THE THREAD, OR THE DUMP IS ALL POOL WORKERS.
                        # The unfiltered dump is truncated at 4000 chars and the JVM hands the
                        # threads over in no useful order: the first capture spent all of it on
                        # netty and ForkJoin workers parked in the usual places and never reached
                        # the one thread that matters. The client ticks on "Render thread".
                        d = py4j("tdump", f="Render,PathFinder,Tungsten,Baritone")["d"] or ""
                        if len(d) < 40:
                            d = py4j("tdump", f="")["d"] or ""
                        fn = os.path.join(FREEZE_DIR, "freeze_run%d.txt" % RUN_SEQ[0])
                        io.open(fn, "w", encoding="utf-8").write(d)
                        print(f"  FROZEN: no world and every counter still -> thread dump in {fn}")
                    except Exception as e:
                        print(f"  FROZEN: thread dump failed: {str(e)[:60]}")
            else:
                froze[0] = 0
            print(f"  t={int(time.time()-t0)}s inGame={gs.get('inGame')} hp={hp} pos={pos} items={inv.get('items')} busy={ht.get('busy')}{dl}")
        except Exception as e:
            print(f"  poll error (client may be busy): {str(e)[:80]}")
    # WHAT DID IT ACTUALLY END UP HOLDING? "Ten items gathered" and "no materials to craft"
    # are only contradictory if those ten are logs. Print the list rather than assume.
    # DID IT DIE, AND TO WHAT? A run that ends with an empty pack has usually lost it on death,
    # and the server says so in plain words ("tester1 was blown up by Creeper"). Counting that is
    # the difference between "crafting is flaky" and "the bot keeps dying with its progress".
    # THE BENCH'S OWN /kill IS NOT A DEATH. It produces the sourceless "tester1 was killed",
    # and counting it put the figure at twenty-two a run when sixty-one of some eighty-nine log
    # entries were simply the reset. Drop it, and report the rest BY CAUSE -- because the split
    # is the finding: falls outnumber any single mob, and a fall is a movement failure.
    all_lines = sh(["docker", "logs", "--tail", "2000", GSERVER]).stdout.splitlines()
    raw = [ln for ln in all_lines[log_mark:]
           if BOT in ln and (" was " in ln or " died" in ln or " fell " in ln)]
    deaths = [ln for ln in raw if "was killed" not in ln or " by " in ln]
    causes = {}
    for ln in deaths:
        i = ln.find(BOT)
        key = ln[i + len(BOT):].strip()[:44]
        causes[key] = causes.get(key, 0) + 1
    print(f"  deaths this run: {len(deaths)} (bench /kill excluded: {len(raw) - len(deaths)})")
    for k, v in sorted(causes.items(), key=lambda kv: -kv[1])[:5]:
        print(f"    {v}x {k}")
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
    # A LADDER IS CLIMBED PAST, NOT ONLY LANDED ON.
    # Rungs are detected by what is HELD, and holding is temporary: a run that chopped a log and
    # spent it on planks, a table and tools no longer holds a log. Measured on this bench --
    # "first craft 21.8s, crafting 65.8s, wood tools 65.8s, STONE TOOLS 88.1s" reported as
    # "required rung 'wood': NOT reached", FAIL. That is a false red on the best run of the sweep,
    # and it is the same family as the false greens already removed: a bar that cannot answer the
    # question it is asked. Reaching anything DEEPER than the required rung is passing it.
    order = [name for name, _ in LADDER]
    deepest = max((order.index(r) for r in reached), default=-1)
    satisfied = want in reached or (want in order and deepest >= order.index(want))
    ok = responsive>=3 and busy_cnt>=2 and (satisfied if want else bool(reached))
    if want:
        how = ("reached" if want in reached else
               ("passed (got as far as '%s')" % order[deepest]) if satisfied else "NOT reached")
        print(f"  required rung '{want}':", how)
    # A STARVED CLIENT DID NOT MEASURE THE BOT.
    # Same convention as run_suite: the run is INVALID, never PASS -- it has to be run again rather
    # than counted -- and it does not read as broken code. Only a failing run can be invalidated;
    # a run that cleared its rung on a slow machine cleared it.
    # WHY 14 AND NOT 12. The old floor admitted a band in which the bot provably cannot perform.
    # Measured tonight on a machine with 24 cores and only ~460% of them in use elsewhere: the
    # client tops out at 12 fps whatever I do -- one client instead of two, cores pinned with
    # cpuset, render distance halved -- and at 12 fps three separate runs reached NO rung in four
    # to five minutes. At 15-18 fps, the same build reaches wood in 21 to 43 seconds. nav's own
    # notes say the same from the other side: nav_slime lands on its pad above ~13 fps and misses
    # below ~10. A floor of 12 therefore called a degraded run a bot failure, which is the false
    # red this guard exists to prevent.
    # Disown a failure only when the client collapsed to well under what it managed in this same
    # world minutes earlier. 60% of the reference, with a hard floor of 6 so a broken reference
    # cannot silence everything.
    HEALTHY_FPS_MIN = max(6.0, 0.6 * fps_ref) if fps_ref else 6.0
    med_fps = None
    if fps_samples:
        ordered = sorted(fps_samples)
        med_fps = ordered[len(ordered) // 2]
    if not ok and med_fps is not None and med_fps < HEALTHY_FPS_MIN:
        print(f"  client fps (median): {med_fps:.1f} over {len(fps_samples)} samples")
        raise StandDown(f"client starved: median {med_fps:.1f} fps < {HEALTHY_FPS_MIN}"
                        f" — this measured the MACHINE, not the bot")
    if med_fps is not None:
        print(f"  client fps (median): {med_fps:.1f} over {len(fps_samples)} samples")
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
        RUN_SEQ[0] = i + 1
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
    # EXIT 2 MEANS "ASK AGAIN LATER", NOT "THE BOT IS BROKEN".
    # sweep() already treats a stand-down as invalid; a single run used to let the exception out as
    # a traceback, which reads like a crash in the bench itself.
    # Quiet the box for the WHOLE invocation, including a --repeat sweep, and put it back
    # whatever happens -- a stand-down, a failure or a KeyboardInterrupt must not leave the
    # bench with a client missing, because run_suite needs both of them.
    _peers = quiet_the_box()
    try:
        sys.exit(0 if (sweep(rep, need) if rep > 1 else main()) else 1)
    except StandDown as e:
        print(f"  GAMER_SMOKE: INVALID — {e}")
        sys.exit(2)
    finally:
        unquiet_the_box(_peers)
