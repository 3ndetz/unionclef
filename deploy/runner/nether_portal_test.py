#!/usr/bin/env python3
"""Nether-portal bench: ConstructNetherPortalBucketTask against a known lava lake.

WHY. The full @gamer run reaches "Construct Nether Portal" only ~12 minutes in, so testing
the portal builder from a fresh inventory costs a whole run per attempt. The prepared next
ceiling is this task's lava-lake path: `findLavaLake` accepts only a KNOWN lava lake of >=12
connected SOURCE blocks (FluidState level 8), and `getPortalableRegion` needs a clear
4x6x6 box within 20 blocks of it; on a miss the task falls to TimeoutWanderTask and
re-searches every 5 s indefinitely (it never digs to lava, never gives up). This isolates
that mechanism on the flat stand.

Scene (flat server, own stone platform): a solid stone floor, a lava SOURCE pool
(LAVA_SIZE x LAVA_SIZE, default 5x5 = 25 source blocks) sunk flush into it, and open air
with solid ground beside it for the portal. The bot starts on the floor a few blocks from
the pool with the prerequisites already in the pack (water bucket + empty bucket +
flint & steel), so the task goes straight to the lava search and the cast -- not to
"Getting flint & steel" / "Getting buckets", which on a flat server have no source and would
mask the mechanism under test.

    python3 deploy/runner/nether_portal_test.py              # 5x5 lava lake beside a clear spot
    LAVA_SIZE=3 python3 deploy/runner/nether_portal_test.py  # 3x3 = 9 sources: BELOW the >=12 gate (control: should NOT qualify)
    NO_ROOM=1  python3 deploy/runner/nether_portal_test.py   # lake walled in: no portalable region (control: should wander)

PASS = a NETHER_PORTAL block exists at the built origin inside the window.
FAIL = no portal (stalled in the lava search / cast), which is the ceiling to diagnose.
exit 0 = PASS.

⛔ WHAT THIS FLAT BENCH CAN AND CANNOT TEST (2026-09-18). It cleanly proves the FRAME BUILD:
the bucket cast (`@build portal`) FAILs on the mid-air upper frame; the obsidian method
(`@build portalobs`) PLACEs the same frame and PASSes (~139 s with obsidian given). It CANNOT
faithfully test the obsidian GATHERING (cast at ground + mine), for two harness reasons that are
NOT bot bugs and both bite a real run's opposite way:
  1. a plain `give diamond_pickaxe` is not auto-equipped, so obsidian (unbreakable by hand)
     never mines -- forced into the main hand here (a real run holds its self-crafted pickaxe);
  2. the flat stand has NO iron, so if the builder ever needs a fresh bucket it drops into
     "Mine And Collect raw_iron -> Wander for Infinity" -- a real run has iron and buckets.
So the full obsidian path is validated by a REAL @gamer run, not this bench.
"""
import functools, json, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
BX, BZ = 2360, 360                     # platform centre
FLOOR_Y = -58                          # y of the solid floor the bot stands on
LAVA_SIZE = int(os.environ.get("LAVA_SIZE", "5"))
NO_ROOM = os.environ.get("NO_ROOM", "0") == "1"
# OBS=1 tests the OBSIDIAN method (ConstructNetherPortalObsidianTask via `@build portalobs`):
# give 10 obsidian + a diamond pickaxe so the frame is PLACED (isolating placement from the
# ground-cast gathering), and compare against the default bucket cast (`@build portal`), which
# stalls on the mid-air upper frame (G108).
OBS = os.environ.get("OBS", "0") == "1"
WINDOW_S = int(os.environ.get("WINDOW_S", "180"))

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
def me():
    s=dict(mc.getGameState().get("self") or {}); return s.get("pos")
if op=="state": out={"inGame":mc.inGame(),"pos":me(),"busy":mc.hasActiveTask()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chatcmd": mc.ChatMessage(req["c"]); out={"ok":True}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",12))]}
elif op=="task": out={"t": str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-500:]}
elif op=="blk": out={"b": {str(k): str(v) for k,v in dict(mc.getBlockAt(int(req["x"]),int(req["y"]),int(req["z"]))).items()}}
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


def build():
    half = LAVA_SIZE // 2
    # a generous air box over the whole area, then a solid stone floor
    rcon(f"fill {BX-12} {FLOOR_Y+1} {BZ-12} {BX+12} {FLOOR_Y+8} {BZ+12} minecraft:air")
    rcon(f"fill {BX-12} {FLOOR_Y-3} {BZ-12} {BX+12} {FLOOR_Y} {BZ+12} minecraft:stone")
    # the lava lake: a pool of SOURCE lava sunk flush into the floor, centred at (BX,BZ)
    # (offset a few blocks from the bot's start so there is a clear region between them)
    lx, lz = BX + 6, BZ
    rcon(f"fill {lx-half} {FLOOR_Y} {lz-half} {lx+half} {FLOOR_Y} {lz+half} minecraft:lava")
    if NO_ROOM:
        # wall the lake in with stone up to head height on all sides + a lid: no 4x6x6
        # portalable box can fit within 20 blocks -> the search must fail.
        rcon(f"fill {lx-half-1} {FLOOR_Y+1} {lz-half-1} {lx+half+1} {FLOOR_Y+5} {lz+half+1} minecraft:stone hollow")


def portal_found(cx, cy, cz, rad=10):
    # A NETHER_PORTAL anywhere in the portalable region (within ~20 blocks of the lake). This is
    # SLOW (one rcon per block), so it is called sparingly -- once when the task finishes or at
    # the window's end -- never every poll. Rows are cheap to skip with `execute if block` only
    # where a portal could be, so we scan a modest box around the lake at head height.
    for dy in range(0, 5):
        for dx in range(-rad, rad + 1):
            for dz in range(-rad, rad + 1):
                r = rcon(f"execute if block {cx+dx} {cy+dy} {cz+dz} minecraft:nether_portal")
                if "passed" in r.lower():
                    return (cx + dx, cy + dy, cz + dz)
    return None


def main():
    if BOT not in rcon("list"):
        print("connecting to test-server")
        py4j("connect", ip="test-server")
        t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {BX-16} {BZ-16} {BX+16} {BZ+16}"); time.sleep(1)
    build()
    time.sleep(1)
    # verify the lake is real SOURCE lava (fill places source blocks)
    lx, lz = BX + 6, BZ
    chk = rcon(f"execute if block {lx} {FLOOR_Y} {lz} minecraft:lava")
    if "passed" not in chk.lower():
        print(f"FAIL: lava lake not built ({chk!r})"); return 2
    # start on the floor a few blocks from the lake
    sx, sy, sz = BX + 0.5, FLOOR_Y + 1, BZ + 0.5
    rcon(f"spawnpoint {BOT} {BX} {FLOOR_Y+1} {BZ}")
    rcon(f"tp {BOT} {sx} {sy} {sz}")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10 true")
    rcon(f"clear {BOT}")
    # the prerequisites, so the task goes straight to the lava search + cast. A real run reaches
    # this phase with stacks of throwaway blocks from mining; the obsidian cast needs a placeable
    # block for its scaffold ("Place structure ... No placeable block in the inventory" -> stuck
    # shimmy, observed when this was omitted), so give cobblestone to match reality.
    rcon(f"give {BOT} minecraft:water_bucket 1")
    rcon(f"give {BOT} minecraft:flint_and_steel 1")
    rcon(f"give {BOT} minecraft:cobblestone 64")
    if OBS:
        # obsidian method: a diamond pickaxe + an empty bucket (to scoop lava for the ground
        # cast) so it GATHERS obsidian itself (cast at ground + mine) and then PLACES the frame --
        # the full path. GIVE_OBS=1 instead hands it the blocks to isolate placement.
        # ⛔ HARNESS LESSON: a plain `give` drops the pickaxe into the pack but the bot does NOT
        # auto-equip an externally-inserted item, so obsidian (unbreakable by hand) never mines and
        # the mine "stalls" -- a TEST artifact, not a bot bug (a real run equips its self-crafted
        # pickaxe, as iron/diamond mining proves). Force it into the main hand so the mine is real.
        rcon(f"give {BOT} minecraft:diamond_pickaxe 1")
        rcon(f"item replace entity {BOT} weapon.mainhand with minecraft:diamond_pickaxe")
        # ⛔ HARNESS LESSON 2: the flat stand has NO iron, so if the builder ever needs a fresh
        # bucket it drops into "Mine And Collect raw_iron -> Wander for Infinity". A real run has
        # iron; give ample buckets so the bench never needs to craft one and the full obsidian path
        # can actually finish (this is what open/surface terrain -- the natural build site -- looks
        # like, where the obsidian method should complete).
        # AMPLE buckets: the ground cast churns buckets (a placed water/lava it cannot always
        # reclaim empties one), and on the flat stand a fresh bucket means "Mine raw_iron -> Wander"
        # (no iron here). 4 ran out mid-gather; 16 lets the full obsidian path finish so the FRAME
        # BUILD (the part the clean-siting fix changed) is actually reached. A real run has iron.
        rcon(f"give {BOT} minecraft:bucket 16")
        if os.environ.get("GIVE_OBS", "0") == "1":
            rcon(f"give {BOT} minecraft:obsidian 12")
    else:
        rcon(f"give {BOT} minecraft:bucket 1")
    time.sleep(2)
    start = py4j("state")
    method = "portalobs (obsidian)" if OBS else "portal (bucket cast)"
    print(f"scene: {LAVA_SIZE}x{LAVA_SIZE} lava lake at ({lx},{FLOOR_Y},{lz})"
          + (" WALLED-IN (no room control)" if NO_ROOM else "")
          + f"; bot at {start['pos']}; method={method}")
    py4j("cmd", c=("@build portalobs" if OBS else "@build portal"))
    # Detection is via the TASK STRING (one py4j call/poll), never a per-poll block scan: the
    # task sets "Done constructing nether portal." on success and shows "Looking for lava lake" /
    # "Getting flint"/"Collecting lava"/"PlaceObsidian" as it works. A block scan (portal_found)
    # is rcon-per-block and far too slow to run each poll; it is used ONCE, at the end, to confirm.
    t0 = time.time(); done = False; seen = set(); wander = 0; phases = set()
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        s = py4j("state")
        tsk = py4j("task")["t"]
        chat = [m for m in py4j("chat", n=12)["chat"] if m not in seen]; seen.update(chat)
        blob = (tsk + " " + " ".join(chat)).lower()
        for w in ("looking for lava", "lava lake not found", "getting flint", "getting buckets",
                  "getting water", "collecting lava", "placing obsidian", "wander for 5",
                  "clearing inside", "flinting and steeling", "done constructing"):
            if w in blob:
                phases.add(w)
        if "looking for lava" in blob or "lava lake not found" in blob or "timeout" in blob:
            wander += 1
        if "done constructing" in blob:
            done = True
        print(f"  t={time.time()-t0:.0f}s pos={s['pos']} busy={s['busy']} wanderHits={wander} "
              f"phases={sorted(phases)} | {tsk[-150:]}")
        if done:
            break
        # task finished without wandering -> likely built; confirm with the scan and stop
        if not s["busy"] and phases and "looking for lava" not in tsk.lower():
            break
    # Confirm with a block scan ONLY when the task did not already report "done" (the scan is
    # rcon-per-block: modest radius, run at most once). A midpoint centre between bot and lake
    # is where the portalable region lands in this scene.
    made = None
    if not done:
        made = portal_found((BX + lx) // 2, FLOOR_Y + 2, lz, rad=6)
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    rcon(f"forceload remove {BX-16} {BZ-16} {BX+16} {BZ+16}")
    print(f"phases reached: {sorted(phases)}")
    if made or done:
        print(f"result: portal {'LIT at '+str(made) if made else 'reported done'} in {time.time()-t0:.1f}s")
        print("PASS: nether portal constructed"); return 0
    print(f"result: no portal after {WINDOW_S}s (wanderHits={wander}, phases={sorted(phases)})")
    print("FAIL: portal not built -- inspect the lava search / cast (the ceiling)"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
