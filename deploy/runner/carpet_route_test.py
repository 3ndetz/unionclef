"""Blue-carpet route stall, rebuilt from the live LuckyWorld receipt (2a66e748, 2026-11-27).

Live: standing on a 1/16 brown_carpet over a sea_lantern (224,34,-343), pose (224.4684394750657,
35.0625, -342.53742929710097). gotoCell(232,35,-339) acked, the body did not move for 13.33 s,
pathStatus: lastPlanEnd 232,35,-341, lastPlanComplete false, navActive/busy, settled. From the SAME
origin gotoCell toward a generator stance (236,35,-342) moved and settled -- so the start stance is
not the defect; the partial route to 232,35,-339 and its handoff are.

The receipt holds 180 loaded cells, x 223..232, y 34..36, z -344..-339 (ids only). Rebuilt here with
the whole box shifted (real -> bench x + DX, y + DY, z + DZ) so the receipt's floor layer y34 is the
bench floor layer (STAND_Y - 1).
Unknown cells are a choice, named by the variant:
  open   (default) y37 over the box and the ring x233 / z-338 are air, floor continues as stone;
  roof   y37 over the box is stone (a slab at y35 then leaves 1.5 blocks -- impassable).
Slabs take their default (bottom) state; stairs default facing -- the receipt records ids only.

Cases: 'fail' = the live target 232,35,-339; 'control' = 232,35,-341 (the live partial end, inside
the box -- the positive control's real target lies outside the captured cells). Each run stages at
the exact live carpet pose, gotoCell, and samples 50 ms for SECS: moved?, pathStatus, arrival by pose.

    python deploy/runner/carpet_route_test.py [fail,control] [runs] [secs] [open|roof]
"""
import json
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uctest.actors import Bot  # noqa: E402
from uctest.arena import STAND_Y  # noqa: E402
from uctest.harness import Rcon  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "artifacts", "bw-blue-carpet-20261127", "bw-blue-carpet-route-20261127.json")
CASES = sys.argv[1].split(",") if len(sys.argv) > 1 else ["fail", "control"]
RUNS = int(sys.argv[2]) if len(sys.argv) > 2 else 3
SECS = float(sys.argv[3]) if len(sys.argv) > 3 else 15.0
VARIANT = sys.argv[4] if len(sys.argv) > 4 else "open"
D = json.load(open(SRC, encoding="utf-8"))
DX, DZ = 1100 - 228, 1100 + 342          # real 228,-342 -> bench 1100,1100
DY = (STAND_Y - 1) - 34                  # real y34 (the floor layer) -> the bench floor
ORIGIN = D["origin"]
TARGETS = {"fail": (232, 35, -339), "control": (232, 35, -341)}

rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")


def b(x, y, z):
    return x + DX, y + DY, z + DZ


def cmds(lines):
    for c in lines:
        rcon.cmd(c, allow_reject=True)


def build():
    x0, y0, z0 = b(221, 33, -346)
    x1, y1, z1 = b(235, 40, -336)
    cmds([f"forceload add {x0 - 2} {z0 - 2} {x1 + 2} {z1 + 2}",
          f"fill {x0} {y0} {z0} {x1} {y0} {z1} minecraft:stone",            # floor under the box
          f"fill {x0} {y0 + 1} {z0} {x1} {y1} {z1} minecraft:air",
          f"fill {x0} {y0 + 1} {z0} {x1} {y0 + 1} {z1} minecraft:stone"])   # the unknown ring: plain floor
    cmds([f"setblock {b(x, y, z)[0]} {b(x, y, z)[1]} {b(x, y, z)[2]} {blk}" for x, y, z, blk in D["cells"]])
    if VARIANT == "roof":
        a, c = b(223, 37, -344), b(232, 37, -339)
        cmds([f"fill {a[0]} {a[1]} {a[2]} {c[0]} {c[1]} {c[2]} minecraft:stone"])
    cmds(["gamemode survival tester1", "effect clear tester1"])


def stage():
    sx, sy, sz = b(*ORIGIN)
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
for case in CASES:
    tx, ty, tz = b(*TARGETS[case])
    for run in range(1, RUNS + 1):
        build()
        p0 = stage()
        origin = bot.py.call("getOriginStatus")
        t0 = time.time()
        bot.py.call("gotoCell", tx, ty, tz)
        first_move, st, p = None, {}, p0
        while time.time() - t0 < SECS:
            time.sleep(0.05)
            p = bot.py.call("getSelfPose")
            if first_move is None and math.dist((p["x"], p["y"], p["z"]), (p0["x"], p0["y"], p0["z"])) > 0.05:
                first_move = round(time.time() - t0, 2)
            st = bot.py.call("pathStatus")
            if st.get("arrived") and st.get("inCell"):
                break
        cell = [math.floor(p["x"]), math.floor(p["y"] + 0.1251), math.floor(p["z"])]
        row = {"case": case, "variant": VARIANT, "run": run, "t": round(time.time() - t0, 2),
               "firstMove_s": first_move, "arrived": bool(st.get("arrived") and st.get("inCell")) and cell == [tx, ty, tz],
               "final": [round(p["x"], 3), round(p["y"], 3), round(p["z"], 3)], "cell": cell,
               "goal": [tx, ty, tz],
               "origin": {k: origin.get(k) for k in ("feetCell", "feetFree", "standable", "exits")},
               "path": {k: st.get(k) for k in ("navActive", "lastPlanEnd", "lastPlanComplete",
                                               "lastPlanEndDistance", "settled", "stopped")}}
        rows.append(row)
        print(json.dumps(row), flush=True)
        bot.py.call("stopPathing")
print(json.dumps({"summary": {c: f"{sum(r['arrived'] for r in rows if r['case'] == c)}/"
                                 f"{sum(1 for r in rows if r['case'] == c)} arrived, never moved "
                                 f"{sum(1 for r in rows if r['case'] == c and r['firstMove_s'] is None)}"
                              for c in CASES}}))
