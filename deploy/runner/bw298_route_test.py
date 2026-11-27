"""bw-298 walled pocket, rebuilt from the live LuckyWorld receipts (ac17b9db, 2026-11-27).

Live: from (-285.57752, 19, 88.09235) on cobblestone, gotoCell(-287,19,87), (-284,19,84) and
(-284,19,87) each ended with a partial plan whose end was one cell north (-286,19,87), no position
progress, and no verdict -- busy for 6 s once, idle after 1.6 s otherwise. The 240 cells
(artifacts/bw298-20261127/bw298-approach-map-20261127.json: x -290..-281, y 18..21, z 86..91) show
why: the feet cell and the one north of it are closed by blue wool on every other side, and the
east side is a one-high gap (air at y19, wool at y20). Without breaking wool those goals are
unreachable; the defect is the handoff -- no "unreachable" said, and once a 6 s busy stall.

Positive control from the same receipts: from the slab at (-282.3, 18.5, 89.65) to (-284,18,89)
(normalized by gotoCell to the stand cell -284,19,89), which arrived live.

The box is rebuilt at its own y (live y18 is above the bench floor -- the cells hang in the air with a
stone floor under them at y17, so a walk-off is a short drop, not a void). Cells outside the box are
air except that floor. Stairs and slabs take default states (ids only in the receipt).

Each run: rebuild, stage at the exact live pose, gotoCell, sample pathStatus every 50 ms for SECS.
Recorded: first movement, pathStatus.outcome (running / arrived / unreachable: ... / stopped / none),
time to a terminal outcome, the plan end, final pose. Gate: the controls arrive; the pocket targets
reach a terminal outcome that is not 'arrived', without leaving the pocket.

    python deploy/runner/bw298_route_test.py [runs] [secs] [live|natural|nobreak|any] [empty|blocks] [case,...]
"""
import json
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uctest.actors import Bot  # noqa: E402
from uctest.harness import Rcon  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "artifacts", "bw298-20261127", "bw298-approach-map-20261127.json")
RUNS = int(sys.argv[1]) if len(sys.argv) > 1 else 3
SECS = float(sys.argv[2]) if len(sys.argv) > 2 else 12.0
# The live build policy is not in the receipts, but its partial plan ending at -286,19,87 rules out
# breaking the wool. With the bench default (break anything) the route digs out -- measured: complete
# plans, "arrived" inside the wool cell -287,19,87. "natural" = breakOnlyNatural (wool is not natural),
# "nobreak" = allowBreak false. Bench default restored at the end (False, True, True).
POLICY = sys.argv[3] if len(sys.argv) > 3 else "live"
# "live" = the actual LuckyWorld client, rechecked 2026-11-27 04:46 MSK: allowPlace=false and
# allowBreak=false -- no edit of the map at all, so an unreachable verdict cannot depend on it.
POLICIES = {"live": (False, False, False), "natural": (True, True, True), "nobreak": (False, True, False),
            "any": (False, True, True)}
# The inventory decides whether a place route exists. Live returned partial plans, so it had no
# placeable block (or placing off): "empty" (default) clears it. "blocks" gives 32 cobblestone -- then
# (-284,19,84) is reached by a one-block pillar; kept to repeat that arrival (one run read outcome
# arrived and then a body at y18, i.e. on a block that was gone 0.5 s later).
INVENTORY = sys.argv[4] if len(sys.argv) > 4 else "empty"
ONLY = sys.argv[5].split(",") if len(sys.argv) > 5 else None
M = json.load(open(SRC, encoding="utf-8"))
DX, DZ = 1300 + 286, 1300 - 88          # real -286,88 -> bench 1300,1300; y kept
POCKET = {(-286, 88), (-286, 87)}
CASES = [  # name, origin pose, requested cell, expect
    ("pocket_-287_19_87", (-285.577519693751, 19.0, 88.0923491786777), (-287, 19, 87), "terminal"),
    ("pocket_-284_19_84", (-285.577519693751, 19.0, 88.0923491786777), (-284, 19, 84), "terminal"),
    ("pocket_-284_19_87", (-285.577519693751, 19.0, 88.0923491786777), (-284, 19, 87), "terminal"),
    ("control_slab", (-282.30000001192093, 18.5, 89.65182479051109), (-284, 18, 89), "arrive"),
]

rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")


def b(x, z):
    return x + DX, z + DZ


def cmds(lines):
    for c in lines:
        rcon.cmd(c, allow_reject=True)


def build():
    x0, z0 = b(-293, 82)
    x1, z1 = b(-278, 95)
    cmds([f"forceload add {x0 - 2} {z0 - 2} {x1 + 2} {z1 + 2}",
          f"fill {x0} 17 {z0} {x1} 17 {z1} minecraft:stone",
          f"fill {x0} 18 {z0} {x1} 24 {z1} minecraft:air"])
    cmds([f"setblock {b(x, z)[0]} {y} {b(x, z)[1]} {blk}" for x, y, z, blk in M["cells"]])
    cmds(["gamemode survival tester1", "effect clear tester1", "clear tester1"])
    if INVENTORY == "blocks":
        cmds(["give tester1 minecraft:cobblestone 32"])


def stage(pose):
    sx, sz = b(pose[0], pose[2])
    sy = pose[1]
    bot.py.call("stopPathing")
    p = {}
    for _ in range(3):
        rcon.cmd(f"tp tester1 {sx} {sy} {sz} 0 0", allow_reject=True)
        for _ in range(20):
            time.sleep(0.5)
            p = bot.py.call("getSelfPose")
            if abs(p["x"] - sx) < 0.01 and abs(p["z"] - sz) < 0.01 and abs(p["y"] - sy) < 0.01 and p.get("onGround"):
                time.sleep(0.8)
                return bot.py.call("getSelfPose")
    raise RuntimeError(f"stage not confirmed at {sx},{sy},{sz}: {p}")


rows = []
for name, pose, req, expect in CASES:
    if ONLY and name not in ONLY:
        continue
    for run in range(1, RUNS + 1):
        build()
        bot.py.call("setBuildPolicy", *POLICIES[POLICY])
        p0 = stage(pose)
        tx, tz = b(req[0], req[2])
        t0 = time.time()
        reply = bot.py.call("gotoCell", tx, req[1], tz)
        first_move, terminal_s, st = None, None, {}
        while time.time() - t0 < SECS:
            time.sleep(0.05)
            st = bot.py.call("pathStatus")
            p = bot.py.call("getSelfPose")
            if first_move is None and math.dist((p["x"], p["y"], p["z"]), (p0["x"], p0["y"], p0["z"])) > 0.05:
                first_move = round(time.time() - t0, 2)
            if st.get("outcome") not in (None, "running", "none"):
                terminal_s = round(time.time() - t0, 2)
                break
        time.sleep(0.5)
        p = bot.py.call("getSelfPose")
        st2 = bot.py.call("pathStatus")
        cell = [math.floor(p["x"]), math.floor(p["y"] + 0.1251), math.floor(p["z"])]
        real = (cell[0] - DX, cell[2] - DZ)
        outcome = st2.get("outcome")
        under = bot.py.call("getBlockAt", tx, req[1] - 1, tz).get("id")   # what the goal stands on now
        if expect == "arrive":
            ok = bool(st2.get("arrived")) and outcome == "arrived"
        else:
            ok = terminal_s is not None and outcome != "arrived" and real in POCKET
        row = {"case": name, "policy": POLICY, "inventory": INVENTORY, "run": run, "pass": ok, "goal": reply.get("goal"), "outcome": outcome,
               "terminal_s": terminal_s, "firstMove_s": first_move, "realCell": [real[0], cell[1], real[1]],
               "underGoal": under, "lastPlanEnd": st2.get("lastPlanEnd"), "lastPlanComplete": st2.get("lastPlanComplete"),
               "navActive": st2.get("navActive"), "busy": st2.get("busy"), "arrived": st2.get("arrived")}
        rows.append(row)
        print(json.dumps(row), flush=True)
        bot.py.call("stopPathing")
bot.py.call("setBuildPolicy", False, True, True)
names = [c[0] for c in CASES]
print(json.dumps({"summary": {n: f"{sum(r['pass'] for r in rows if r['case'] == n)}/{RUNS}"
                              for n in names if not ONLY or n in ONLY}}))
sys.exit(0 if all(r["pass"] for r in rows) else 1)
