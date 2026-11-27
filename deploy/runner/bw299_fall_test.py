"""bw-299 exact-route fall, rebuilt from the live LuckyWorld receipts (e276fca1, 2026-11-27).

Live 04:02:08-12Z: gotoCell(936,37,-321) from (935.5, 37, -315.5) on quartz, allowPlace=false and
allowBreak=false, "normalized": false (so the goal cell held no collision at request time), plan
complete -- and 3.14 s later the body was at (935.934, 17.530, -320.042), falling; the server then
reported a death with no attacker. The 160-cell read (x 934..937, y 35..39, z -322..-315) was taken
at 04:06:43Z, 4 min 35 s LATER, and shows end_stone IN the goal cell and a smooth_stone_slab floor
at y36 over z -321..-319 -- a floor the body could not have fallen through. So the arena changed in
between, and which way decides the defect. Variants (the box is rebuilt at its own y, so the fall
under it is a ~78-block void like live):
  snapshot  all 160 cells as read, except the goal row's y37 end_stone (the request saw that cell
            empty): if the route is right this arrives;
  noslabs   as snapshot, but the y36 slabs over z -321..-319 absent: the goal has no floor -- the
            planner must not call a plan to it complete, and the body must not walk off;
  break     as snapshot, and the slab under the next step is removed (rcon) while the body walks
            over z -318: a floor lost mid-walk.
  ally      as snapshot, with a second player (tester2) standing in the middle of the corridor on
            the z -318 row, the route's line: the live frames show the bot walking into a
            teammate who hid the corridor centre, "walking dead-ends (11.0 -> 11.0) -> physics owns
            the rest", and the body already below the slab layer.
Slabs take the default (bottom) state: the read records ids only. Inventory cleared; build policy
setBuildPolicy(false, false, false) as on the live client.

Per run: stage at the exact live origin, gotoCell(936,37,-321), 50 ms in-container trace
(ladder_probe_inner.py: pos, onGround, collisions, walker waypoint, nav, forward/jump keys); then
pathStatus outcome and the plan (getLastPlan). Verdict: arrived / fell (y < 30) / stopped.

    python deploy/runner/bw299_fall_test.py [snapshot|noslabs|break] [runs] [secs]
"""
import json
import math
import os
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uctest.actors import Bot  # noqa: E402
from uctest.harness import Rcon  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.join(HERE, "artifacts", "bw299-live-20261127")
D = json.load(open(os.path.join(ART, "bw299-e276-exact-fall-corridor-20261127.json"), encoding="utf-8"))
VARIANT = sys.argv[1] if len(sys.argv) > 1 else "snapshot"
RUNS = int(sys.argv[2]) if len(sys.argv) > 2 else 3
SECS = float(sys.argv[3]) if len(sys.argv) > 3 else 10.0
DX, DZ = 1500 - 935, 1500 + 318          # real 935,-318 -> bench 1500,1500; y kept
ORIGIN = (935.5, 37.0, -315.5)
GOAL = (936, 37, -321)

rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")
if VARIANT == "ally":
    Bot("uctest-mc-tester2", "tester2", rcon).ensure_in_game("test-server")


def b(x, z):
    return x + DX, z + DZ


def cmds(lines):
    for c in lines:
        rcon.cmd(c, allow_reject=True)


def build():
    x0, z0 = b(930, -326)
    x1, z1 = b(941, -311)
    cmds([f"forceload add {x0 - 2} {z0 - 2} {x1 + 2} {z1 + 2}",
          f"fill {x0} 30 {z0} {x1} 42 {z1} minecraft:air",
          # the approach behind the origin, so the start is not a floating island: quartz at y36
          f"fill {b(934, -314)[0]} 36 {b(934, -314)[1]} {b(937, -312)[0]} 36 {b(937, -312)[1]} minecraft:quartz_block"])
    for x, y, z, blk in D["blocks"]:
        if y == 37 and z == GOAL[2] and blk.endswith("end_stone"):
            blk = "minecraft:air"                # the request saw the goal cell empty
        if VARIANT == "noslabs" and y == 36 and -321 <= z <= -319 and blk.endswith("smooth_stone_slab"):
            blk = "minecraft:air"
        bx, bz = b(x, z)
        cmds([f"setblock {bx} {y} {bz} {blk}"])
    cmds(["gamemode survival tester1", "effect clear tester1", "clear tester1"])


def stage():
    sx, sz = b(ORIGIN[0], ORIGIN[2])
    sy = ORIGIN[1]
    bot.py.call("stopPathing")
    p = {}
    for _ in range(3):
        rcon.cmd(f"tp tester1 {sx} {sy} {sz} 180 0", allow_reject=True)     # facing north, toward the goal
        for _ in range(20):
            time.sleep(0.5)
            p = bot.py.call("getSelfPose")
            if abs(p["x"] - sx) < 0.01 and abs(p["z"] - sz) < 0.01 and abs(p["y"] - sy) < 0.01 and p.get("onGround"):
                time.sleep(0.8)
                return bot.py.call("getSelfPose")
    raise RuntimeError(f"stage not confirmed at {sx},{sy},{sz}: {p}")


def breaker():
    """'break' variant: when the body is over z -318, remove the slab floor of the next two rows."""
    t0 = time.time()
    while time.time() - t0 < SECS:
        p = bot.py.call("getSelfPose")
        if p.get("z") is not None and p["z"] - DZ < -317.6:
            cmds([f"fill {b(934, -320)[0]} 36 {b(934, -320)[1]} {b(937, -319)[0]} 36 {b(937, -319)[1]} minecraft:air"])
            return
        time.sleep(0.05)


env = dict(os.environ, MSYS_NO_PATHCONV="1")
subprocess.run(["docker", "cp", os.path.join(HERE, "ladder_probe_inner.py"),
                "uctest-mc-tester1:/mc-data/ladder_probe_inner.py"], check=True, env=env)
bot.py.call("setBuildPolicy", False, False, False)
gx, gz = b(GOAL[0], GOAL[2])
rows = []
for run in range(1, RUNS + 1):
    build()
    p0 = stage()
    if VARIANT == "ally":
        ax, az = b(935.7, -318.4)
        rcon.cmd("gamemode survival tester2", allow_reject=True)
        rcon.cmd(f"tp tester2 {ax} 36.5 {az} 0 0", allow_reject=True)   # facing south, toward the bot
        time.sleep(1.5)
    th = threading.Thread(target=breaker) if VARIANT == "break" else None
    if th:
        th.start()
    r = subprocess.run(["docker", "exec", "uctest-mc-tester1", "python3", "/mc-data/ladder_probe_inner.py",
                        str(gx), str(GOAL[1]), str(gz), str(SECS)], capture_output=True, text=True,
                       timeout=SECS + 60, env=env)
    if th:
        th.join()
    trace = [json.loads(l) for l in r.stdout.splitlines() if l.startswith("{")]
    with open(os.path.join(ART, f"trace_{VARIANT}_run{run}.jsonl"), "w", encoding="utf-8") as f:
        f.write(r.stdout)
    st = bot.py.call("pathStatus")
    lp = bot.py.call("getLastPlan")
    p = bot.py.call("getSelfPose")
    fell = any(t["pos"][1] < 30 for t in trace) or p["y"] < 30
    first_air = next((t for t in trace if t["pos"][1] < 35.9), None)
    cell = [math.floor(p["x"]) - DX, math.floor(p["y"] + 0.1251), math.floor(p["z"]) - DZ]
    # arrival by the navigator's own slab-aware test (pathStatus.arrived = feet cell, settled, done);
    # `cell` below is floor(y + 0.1251) without the slab +1, for the record only
    verdict = "fell" if fell else ("arrived" if st.get("outcome") == "arrived" and st.get("arrived") else "stopped")
    row = {"variant": VARIANT, "run": run, "verdict": verdict, "outcome": st.get("outcome"),
           "planComplete": lp.get("complete"), "planEnd": lp.get("end"),
           "planCells": [c for c in (lp.get("cells") or [])],
           "finalReal": [round(p["x"] - DX, 3), round(p["y"], 3), round(p["z"] - DZ, 3)], "cell": cell,
           "leftFloorAt": None if not first_air else [round(first_air["pos"][0] - DX, 3), first_air["pos"][1],
                                                      round(first_air["pos"][2] - DZ, 3)],
           "traceRows": len(trace)}
    rows.append(row)
    print(json.dumps(row), flush=True)
    bot.py.call("stopPathing")
    if fell:
        time.sleep(2.0)
        rcon.cmd("kill tester1", allow_reject=True)
        time.sleep(4.0)
        bot.ensure_in_game("test-server")
bot.py.call("setBuildPolicy", False, True, True)
print(json.dumps({"summary": {v: sum(1 for r in rows if r["verdict"] == v) for v in ("arrived", "fell", "stopped")}}))
