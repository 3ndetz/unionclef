#!/usr/bin/env python3
"""Nether-portal bench: the obsidian method (ConstructNetherPortalObsidianTask) against a lava lake.

WHY. The full @gamer run reaches "Construct Nether Portal" only ~12 minutes in, so testing the
portal builder from a fresh inventory costs a whole run per attempt. This isolates the obsidian
method end to end on the flat stand: FLOOD the lava lake to make obsidian (PlaceObsidianFloodTask),
mine it, then build + light the frame on clean ground beside the pool.

Scene (flat server, own stone platform): a solid stone floor, a lava SOURCE pool
(LAVA_SIZE x LAVA_SIZE, default 5x5 = 25 source blocks) sunk flush into it, and open stone
beside it for the frame. The bot starts on the floor a few blocks from the pool with the
prerequisites already in the pack (water bucket + empty buckets + flint & steel + cobblestone),
so the task goes straight to the flood + mine + build -- not to "Getting flint & steel" /
"Getting buckets", which on a flat server have no source and would mask the mechanism under test.

    python3 deploy/runner/nether_portal_test.py              # bucket-cast method (`@build portal`)
    OBS=1 python3 deploy/runner/nether_portal_test.py        # obsidian FLOOD method (`@build portalobs`) -- the live path
    OBS=1 GIVE_OBS=1 python3 ...                             # give the 10 obsidian, isolate the frame BUILD
    LAVA_SIZE=3 python3 ...                                  # 3x3 = 9 sources (smaller pool)
    NO_ROOM=1  python3 ...                                   # lake walled in (control)

PASS = a real NETHER_PORTAL block exists near the build inside the window. exit 0 = PASS.

⛔ TWO FLAT-STAND HARNESS ARTIFACTS, NEUTRALISED HERE (2026-09-18). Both are harness limits, not
bot bugs, and both bite a real run's OPPOSITE way:
  1. a plain `give diamond_pickaxe` is NOT auto-equipped (getBestToolSlot never sees an rcon-given
     item), so once the flood leaves a bucket in hand the mine cannot re-select the pickaxe and
     punches obsidian bare -> forever. A real run auto-equips its self-crafted pickaxe (iron/diamond
     mining proves the path). Worked around by forcing the pickaxe into the main hand AND re-forcing
     it whenever the task is mining and the hand is not a pickaxe (see the poll loop).
  2. the flat stand has NO iron, so a fresh bucket means "Mine raw_iron -> Wander for Infinity".
     A real run has iron; ample buckets are given so the bucket cycle never needs to craft one.
The FLOOD mechanic itself, the frame BUILD, and the light are all faithfully exercised here; a real
@gamer run is still the final confirmation for natural (chaotic) terrain.
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
def selfd():
    return dict(mc.getGameState().get("self") or {})
def me():
    return selfd().get("pos")
if op=="state":
    obs=0; fns=0; wb=0
    try:
        for s in mc.getInventoryFull().get("slots") or []:
            sd=dict(s)
            if sd.get("empty"): continue
            it=str(sd.get("item") or sd.get("name") or ""); c=int(sd.get("count") or 0)
            if "obsidian" in it: obs+=c
            if "flint_and_steel" in it: fns+=c
            if "water_bucket" in it: wb+=c
    except Exception: pass
    sd=selfd()
    out={"inGame":mc.inGame(),"pos":sd.get("pos"),"busy":mc.hasActiveTask(),"obs":obs,"fns":fns,"wb":wb,"held":sd.get("held")}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chatcmd": mc.ChatMessage(req["c"]); out={"ok":True}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",12))]}
elif op=="task": out={"t": str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-500:]}
elif op=="blk": out={"b": {str(k): str(v) for k,v in dict(mc.getBlockAt(int(req["x"]),int(req["y"]),int(req["z"]))).items()}}
elif op=="findportal":
    cx,cy,cz,rad=int(req["x"]),int(req["y"]),int(req["z"]),int(req.get("rad",16))
    hit=None
    for dy in range(0,9):
        for dx in range(-rad,rad+1):
            for dz in range(-rad,rad+1):
                try:
                    b=dict(mc.getBlockAt(cx+dx,cy+dy,cz+dz))
                    nm=str(b.get("block") or b.get("name") or b)
                except Exception: nm=""
                if "nether_portal" in nm.lower(): hit=[cx+dx,cy+dy,cz+dz]; break
            if hit: break
        if hit: break
    out={"portal":hit}
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
    # ⛔ WIDE WIPE (2026-09-18): the obsidian method sites the frame OUTWARD (up to r~12) on a clean
    # pad, so leftover obsidian AND a leftover NETHER_PORTAL from a previous run land well outside a
    # ±12 box -- and a leftover portal at origin.up() makes the task report "Done constructing" with
    # ZERO obsidian consumed (a false green, caught 2026-09-18). Clear a ±18 box to bedrock-free
    # stone every run so no run can pass on another run's portal.
    R = 18
    rcon(f"fill {BX-R} {FLOOR_Y+1} {BZ-R} {BX+R} {FLOOR_Y+10} {BZ+R} minecraft:air")
    rcon(f"fill {BX-R} {FLOOR_Y-3} {BZ-R} {BX+R} {FLOOR_Y} {BZ+R} minecraft:stone")
    # the lava lake: a pool of SOURCE lava sunk flush into the floor, centred at (BX,BZ)
    # (offset a few blocks from the bot's start so there is a clear region between them)
    lx, lz = BX + 6, BZ
    rcon(f"fill {lx-half} {FLOOR_Y} {lz-half} {lx+half} {FLOOR_Y} {lz+half} minecraft:lava")
    if NO_ROOM:
        # wall the lake in with stone up to head height on all sides + a lid: no 4x6x6
        # portalable box can fit within 20 blocks -> the search must fail.
        rcon(f"fill {lx-half-1} {FLOOR_Y+1} {lz-half-1} {lx+half+1} {FLOOR_Y+5} {lz+half+1} minecraft:stone hollow")


def portal_found(cx, cy, cz, rad=16):
    # A NETHER_PORTAL anywhere in the build region. Client-side, IN-PROCESS scan (one docker exec,
    # getBlockAt looping in the JVM-local python) -- NOT rcon-per-block, which at rad 16 would be
    # ~6500 slow docker-exec calls. Returns the first portal cell or None.
    try:
        p = py4j("findportal", x=cx, y=cy, z=cz, rad=rad).get("portal")
        return tuple(p) if p else None
    except Exception:
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
    rcon(f"forceload add {BX-18} {BZ-18} {BX+18} {BZ+18}"); time.sleep(1)
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
            rcon(f"give {BOT} minecraft:obsidian 16")
    else:
        rcon(f"give {BOT} minecraft:bucket 1")
    time.sleep(2)
    # ⛔ VERIFY SETUP APPLIED (2026-09-18). A death in a prior run can leave the bot on the respawn
    # screen, so `give` silently no-ops and the run tests an empty bot (a harness artifact, not a bot
    # bug). Confirm the bot is in-world and, when we handed it obsidian, that the obsidian actually
    # landed; re-give a few times before giving up so the run is real.
    start = py4j("state")
    if not start.get("inGame"):
        print("FAIL: bot not in-game at setup (dead/respawning?) -- recreate the client"); return 2
    # ensure the prerequisites actually landed (a post-death respawn screen makes `give` no-op).
    need = {"flint_and_steel": ("fns", "minecraft:flint_and_steel 1", 1),
            "water_bucket": ("wb", "minecraft:water_bucket 1", 1)}
    if OBS and os.environ.get("GIVE_OBS", "0") == "1":
        need["obsidian"] = ("obs", "minecraft:obsidian 16", 14)
    for name, (key, give, mincount) in need.items():
        for _ in range(5):
            if start.get(key, 0) >= mincount:
                break
            rcon(f"give {BOT} {give}"); time.sleep(1); start = py4j("state")
        if start.get(key, 0) < mincount:
            print(f"FAIL: {name} give did not apply ({key}={start.get(key)}) -- recreate the client"); return 2
    start_obs = start.get("obs", 0)
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
        # ⛔ KEEP THE PICKAXE EQUIPPED FOR THE MINE (2026-09-18). The obsidian-flood gather leaves an
        # (empty) bucket in the hand after reclaiming its water; the mine then needs the diamond
        # pickaxe back. On a REAL run the miner auto-equips its self-crafted pickaxe (iron/diamond
        # mining proves the path). On the flat stand the pickaxe is rcon-GIVEN, which getBestToolSlot
        # does not see, so the auto-equip cannot re-select it and the bot punches obsidian with a
        # bucket for ever -- a pure harness artifact, not a bot bug. Re-force the pickaxe ONLY while
        # the task is actually mining (never during the flood's water placement/reclaim, which needs
        # the bucket), mimicking the real run's auto-equip so the flood path can be benched to green.
        if OBS:
            tl = tsk.lower(); held = str(s.get("held") or "")
            mining = ("destroy block" in tl or "mine and collect" in tl or "mining or collecting" in tl)
            if mining and "pickaxe" not in held:
                rcon(f"item replace entity {BOT} weapon.mainhand with minecraft:diamond_pickaxe")
        chat = [m for m in py4j("chat", n=12)["chat"] if m not in seen]; seen.update(chat)
        blob = (tsk + " " + " ".join(chat)).lower()
        for w in ("looking for lava", "lava lake not found", "getting flint", "getting buckets",
                  "getting water", "collecting lava", "placing obsidian", "wander for 5",
                  "clearing inside", "flinting and steeling", "done constructing"):
            if w in blob:
                phases.add(w)
        if "looking for lava" in blob or "lava lake not found" in blob or "timeout" in blob:
            wander += 1
        cur_obs = s.get("obs", start_obs)
        print(f"  t={time.time()-t0:.0f}s pos={s['pos']} busy={s['busy']} obs={cur_obs} "
              f"consumed={start_obs-cur_obs} wanderHits={wander} phases={sorted(phases)} | {tsk[-140:]}")
        # ⛔ CONFIRM 'done' WITH A REAL PORTAL BLOCK (2026-09-18). "Done constructing" fires when
        # origin.up() is a NETHER_PORTAL -- which was a leftover from a previous run in one case (0
        # obsidian consumed, a false green). With the wide wipe above, ANY nether_portal found now
        # was built THIS run, so confirm the task-string 'done' against an actual block scan over the
        # wider build area (the obsidian method sites the frame OUTWARD on a clean pad).
        # Scan CENTERED ON THE BOT (the obsidian method sites the frame OUTWARD, so the portal is
        # where the body ended up, not the scene centre) while the chunk is still loaded (before any
        # @stop / forceload remove). rad 10 keeps the in-process getBlockAt scan fast.
        bp = s.get("pos") or [BX, FLOOR_Y, BZ]
        if "done constructing" in blob:
            made = portal_found(int(bp[0]), FLOOR_Y - 1, int(bp[2]), rad=10)
            if made:
                done = True; break
            print("  ('done constructing' but NO portal block near the bot -- continuing)")
        # task finished without wandering -> confirm with a scan and stop
        if not s["busy"] and phases and "looking for lava" not in tsk.lower():
            made = portal_found(int(bp[0]), FLOOR_Y - 1, int(bp[2]), rad=10)
            if made:
                done = True
            break
    # Final confirmation, centred on the bot's last position, BEFORE unloading the chunk.
    bp = (py4j("state").get("pos")) or [BX, FLOOR_Y, BZ]
    made = portal_found(int(bp[0]), FLOOR_Y - 1, int(bp[2]), rad=10)
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    rcon(f"forceload remove {BX-18} {BZ-18} {BX+18} {BZ+18}")
    print(f"phases reached: {sorted(phases)}")
    if made:
        print(f"result: NETHER_PORTAL LIT at {made} in {time.time()-t0:.1f}s")
        print("PASS: nether portal constructed (real portal block confirmed)"); return 0
    print(f"result: no portal block after {WINDOW_S}s (done_flag={done}, wanderHits={wander}, "
          f"phases={sorted(phases)})")
    print("FAIL: portal not built -- inspect the lava search / cast (the ceiling)"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
