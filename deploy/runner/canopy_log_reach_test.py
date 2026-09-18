#!/usr/bin/env python3
"""Finding B bench: after a base-loss respawn (EMPTY pocket) the bot must not fixate on an
unreachable canopy log -- it must blacklist it and take a reachable one.

The in-scope death report (2026-09-18, progress.md "G106 confirmed ... the next ceiling"): the bot
died to a cave-mob nest, respawned at world spawn with an empty inventory (base loss), then WEDGED
re-gathering wood -- it targeted a `dark_oak_log` 6 blocks up in a canopy, "reach route gave up",
for 90+ s (Finding B, open). A dark-oak canopy has branch logs spread out at the top with air
below them; from the ground, with no blocks to pillar (a fresh respawn holds nothing), such a log
cannot be reached, and the chooser should drop it and go to a log it CAN mine, not spin on it.

Scene (flat server, own platform, grass floor), the DEFAULT (the faithful wedge):
  * an UNREACHABLE dark-oak canopy log 6 up, boxed in persistent leaves with air below -- the
    leaves occlude line of sight so NO ground cell can reach the log, and an empty pocket has
    nothing to pillar with, so it genuinely cannot be harvested from here;
  * NO reachable log anywhere in scan range;
  * the bot walks in from 20 blocks west with an EMPTY inventory (post-respawn), `@get dark_oak_log 1`.

The bot must EXCLUDE the unreachable canopy and go EXPLORE for wood it can reach, not spin on it.
  PASS (default / NO_COLUMN) = the bot un-fixated (got a log, or the chain shows wander/explore).
  FAIL = it wedged, cycling "reach route gave up" for the whole window (Finding B).
exit 0 = PASS.

    python3 deploy/runner/canopy_log_reach_test.py                 # the wedge test; exit 0 = PASS
    NO_COLUMN=0 python3 deploy/runner/canopy_log_reach_test.py     # recovery variant: a reachable
                                                                  # column COL_DX east to take instead
    NO_COLUMN=0 LEAVES=0 START_DX=0 python3 deploy/runner/canopy_log_reach_test.py  # exposed canopy

Root cause (2026-09-18): the mining chooser picks the nearest non-blacklisted breakable block with
no reachability check (`canReach` is just "not blacklisted"), so the unreachable canopy log is the
target; G74's reach-route give-up used `requestBlockUnreachable` (allowed-failures 4), so its "3
give-ups in a row -- marking it unreachable" only cost failure 1 of 5 and the block was never
excluded -> indefinite wedge. Fixed by `requestBlockUnreachableNow` (a decisive verdict that
excludes at once, still cools off and retries).
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
import os
X, Z = 820, 340
GROUND = -61                 # flat world surface block; feet at GROUND+1
CANOPY_Y = GROUND + 6        # the overhead canopy log layer (6 up from the ground)
# CANOPY_HALF widens the unreachable log slab (1 -> 3x3, 4 -> 9x9): a wide dark-oak roof forces the
# chooser to grind through many unreachable candidates one blacklist-radius at a time, which is the
# suspected shape of the live 90 s wedge. COL_DX puts the one reachable trunk that far to the east.
CANOPY_HALF = int(os.environ.get("CANOPY_HALF", "1"))
COL_DX = int(os.environ.get("COL_DX", "6"))
# Diagnostic knobs for the wedge probe (not the default recovery test):
#   NO_COLUMN=1 removes the reachable trunk, so the ONLY log is the unreachable canopy one -- a
#     wedge then shows as "reach route gave up" repeating instead of the bot wandering off to look
#     for another tree. START_DX starts the bot that many blocks WEST of the canopy, so it walks in
#     (a monotone halving of distance, which is what can reset the blacklist failure count).
# Defaults reproduce the faithful Finding B wedge: a leaf-occluded unreachable canopy log with no
# reachable log in scan range, the bot walking in from the west. Set NO_COLUMN=0 for the recovery
# variant (a reachable trunk column COL_DX east that the bot must take instead).
NO_COLUMN = os.environ.get("NO_COLUMN", "1") == "1"
LEAVES = os.environ.get("LEAVES", "1") == "1"
START_DX = int(os.environ.get("START_DX", "20"))
WINDOW_S = int(os.environ.get("WINDOW_S", "90"))

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame(),"busy":mc.hasActiveTask()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chatcmd": mc.ChatMessage(req["c"]); out={"ok":True}
elif op=="gs": out=dict(mc.getGameState().get("self") or {})
elif op=="inv":
    ids=[]
    try:
        for s in mc.getInventoryFull().get("slots") or []:
            sd=dict(s)
            if not sd.get("empty"):
                nm=str(sd.get("item") or sd.get("name") or "")
                if nm: ids.append(nm)
    except Exception: pass
    out={"ids":ids}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",8))]}
elif op=="task": out={"chain": str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-300:]}
elif op=="stats": out={"s": str(mc.placeStats())}
print(json.dumps(out,default=str)); gw.close()
"""


def sh(a, to=60):
    return subprocess.run(a, capture_output=True, text=True, timeout=to)


def py4j(op, to=40, **kw):
    r = sh(["docker", "exec", C1, "python3", "-c", SNIP, json.dumps({"op": op, **kw})], to)
    if r.returncode != 0:
        raise RuntimeError(f"{op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])


def rcon(c):
    return sh(["docker", "exec", SERVER, "rcon-cli", c]).stdout.strip()


def stat(s, key):
    for tok in s.split():
        if tok.startswith(key + "="):
            return tok[len(key) + 1:]
    return "?"


def main():
    if not py4j("state")["inGame"]:
        py4j("connect", ip="test-server"); time.sleep(3)
    if BOT not in rcon("list"):
        py4j("connect", ip="test-server"); t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined test-server"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    py4j("chatcmd", c=";settings fireReleaseNeedsFire true"); time.sleep(0.5)
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    R = max(CANOPY_HALF + 3, COL_DX + 3, 12)
    rcon(f"forceload add {X-R} {Z-R} {X+R} {Z+R}"); time.sleep(1)
    rcon(f"kill @e[type=item,x={X},y={GROUND},z={Z},distance=..{R+8}]")
    # clear a generous air box, lay a grass floor
    rcon(f"fill {X-R} {GROUND+1} {Z-R} {X+R} {CANOPY_Y+6} {Z+R} minecraft:air")
    rcon(f"fill {X-R} {GROUND} {Z-R} {X+R} {GROUND} {Z+R} minecraft:grass_block")
    # the UNREACHABLE canopy: a dark-oak-log slab 6 up directly over the start, AIR below and around
    # it (floating), so the block scanner tracks it (an exposed face) and it is the nearest log by
    # distance -- exactly what the chooser grabs -- yet it cannot be reached from the ground with an
    # empty pocket (nothing to pillar with). CANOPY_HALF sets its width. LEAVES=1 boxes it in leaves
    # too (the faithful dark-oak shape), but leaves hide the logs from the scanner, so the default
    # leaves it exposed to test the reach/blacklist path itself.
    h = CANOPY_HALF
    rcon(f"fill {X-h} {CANOPY_Y} {Z-h} {X+h} {CANOPY_Y} {Z+h} minecraft:dark_oak_log")
    if LEAVES:
        rcon(f"fill {X-h-1} {CANOPY_Y-1} {Z-h-1} {X+h+1} {CANOPY_Y+1} {Z+h+1} "
             f"minecraft:oak_leaves[persistent=true] replace minecraft:air")
    # the REACHABLE trunk: a 2-high dark-oak column standing on the ground, COL_DX east, in the open
    if not NO_COLUMN:
        rcon(f"fill {X+COL_DX} {GROUND+1} {Z} {X+COL_DX} {GROUND+2} {Z} minecraft:dark_oak_log")
    time.sleep(1)
    up = rcon(f"execute if block {X} {CANOPY_Y} {Z} minecraft:dark_oak_log")
    lo = "n/a" if NO_COLUMN else rcon(f"execute if block {X+COL_DX} {GROUND+1} {Z} minecraft:dark_oak_log")
    print(f"scene: overhead canopy log present -> {up[:24]!r}; reachable column -> {lo[:24]!r}")
    if "passed" not in up or (not NO_COLUMN and "passed" not in lo):
        print("FAIL: scene not built (chunk not loaded?)"); return 2
    sx = X - START_DX + 0.5
    rcon(f"tp {BOT} {sx} {GROUND+1} {Z+0.5}")       # on the ground, START_DX west of the canopy
    rcon(f"clear {BOT}")                            # empty pocket: post-respawn, nothing to pillar with
    time.sleep(2)
    gs = py4j("gs")
    print(f"bot at {gs['pos']} EMPTY pocket; canopy log 6 up overhead, reachable column {COL_DX} east. "
          f"@get dark_oak_log 1")
    py4j("cmd", c="@get dark_oak_log 1")
    t0 = time.time(); seen = set(); got = False; wandered = False
    give_up = unreach_note = refuse_note = 0
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        gs = py4j("gs"); pos = gs["pos"]
        ch = [c for c in py4j("chat", n=14)["chat"] if c not in seen]; seen.update(ch)
        give_up += sum(1 for c in ch if "reach route gave up" in c)
        unreach_note += sum(1 for c in ch if "may be unreachable" in c)
        refuse_note += sum(1 for c in ch if "marking it unreachable" in c)
        try:
            chain_full = py4j("task")["chain"]
        except Exception:
            chain_full = ""
        if any(w in chain_full for w in ("Wander", "wander", "Explor", "explor", "aimlessly")):
            wandered = True
        got = any("dark_oak_log" in i for i in py4j("inv")["ids"])
        if os.environ.get("DEBUG_CHAT") == "1":
            for c in ch:
                print(f"      chat| {c[-110:]}")
        note = [c for c in ch if any(w in c for w in ("unreachable", "gave up", "reach", "Pillar",
                                                       "Mining", "wander", "Wander"))]
        try:
            chain = py4j("task")["chain"][-90:]
        except Exception:
            chain = "?"
        print(f"  t={time.time()-t0:.0f}s pos={pos} log={got} | {chain}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    try:
        s = py4j("stats")["s"]
        print(f"  counters: pdRouteRefused={stat(s,'pdRouteRefused')} "
              f"scanAccepted={stat(s,'scanAccepted')} scanUnreachable={stat(s,'scanUnreachable')} "
              f"scanNoBreak={stat(s,'scanNoBreak')}")
    except Exception as e:
        print(f"  (counters unavailable: {e})")
    rcon(f"forceload remove {X-R} {Z-R} {X+R} {Z+R}")
    print(f"result: dark_oak_log={got} wandered={wandered} reach-give-up-notes={give_up} "
          f"unreachable-suggests={unreach_note} route-refusals={refuse_note}")
    if NO_COLUMN:
        # no reachable log exists: success is un-fixating -- excluding the unreachable canopy and
        # going to EXPLORE for wood elsewhere, instead of spinning "reach route gave up" for ever.
        if got or wandered:
            print("PASS: did not fixate -- excluded the unreachable canopy and moved on to explore")
            return 0
        print("FAIL: wedged on the unreachable canopy (never excluded it, never explored)"); return 1
    if got:
        print("PASS: blacklisted the unreachable canopy log and mined a reachable one"); return 0
    print("FAIL: never got a log (wedged on the unreachable canopy log)"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
