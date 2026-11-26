"""A block placed INTO the bot's own feet cell while it stands (bw-232 Gray, 2026-11-27).

Live receipt: pose (345.700, 39, -880.300) on the ground, end_stone under the feet, then the server's
automatic defence put light_gray_wool in the feet cell 345,39,-881 and in all four horizontal
neighbours at y39 (the north column also had wool at y38); y40 open everywhere. getOriginStatus said
feetFree=false, standable=false, four step_up exits, and the planner ended a found-incomplete path
at the origin.

The same five columns are built here around (620, 620), the wool placed by rcon AFTER the start
pose is confirmed (so the body is embedded the way the live one was). Cases:

  jump    one ordinary jump (holdKey jump, 150 ms) and nothing else; the final pose must settle
          on the wool top: y = floor+2 within 0.05, onGround, feet cell one above the start.
  goto    gotoCell to a floor cell outside the ring; arrival = pathStatus arrived + inCell and
          the pose in that cell (never the bare route result).
  capped  control for 'jump': wool also at y+2 over the feet, so a jump cannot lift the body;
          expected to stay put and say so (not gated, recorded).

    python deploy/runner/embedded_start_test.py [jump,goto,capped] [repeats]
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

F = STAND_Y - 1
X, Z = 620, 620                      # feet cell (X, STAND_Y, Z); the receipt's cell offsets 0.7 / 0.7
PX, PZ = X + 0.7, Z + 0.7
ONLY = sys.argv[1].split(",") if len(sys.argv) > 1 else ["jump", "goto", "capped"]
REPEATS = int(sys.argv[2]) if len(sys.argv) > 2 else 3
WOOL = "minecraft:light_gray_wool"
rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")


def cmds(lines):
    for c in lines:
        rcon.cmd(c, allow_reject=True)


def cell(p):
    return [math.floor(p["x"]), math.floor(p["y"] + 0.1251), math.floor(p["z"])]


def build(capped):
    cmds([f"forceload add {X - 10} {Z - 10} {X + 10} {Z + 10}",
          f"fill {X - 8} {F} {Z - 8} {X + 8} {F} {Z + 8} minecraft:end_stone",
          f"fill {X - 8} {STAND_Y} {Z - 8} {X + 8} {STAND_Y + 5} {Z + 8} minecraft:air",
          "gamemode survival tester1", "effect clear tester1"])
    put()
    # The neighbours FIRST, the feet cell LAST: with a free side the client's own push-out
    # (ClientPlayerEntity.pushOutOfBlocks, cardinal directions only) moves the body out in a tick,
    # which is not the live state -- all four sides were wool there. Measured: centre-first ended
    # every run in the free diagonal cell.
    cmds([f"setblock {X} {F} {Z - 1} {WOOL}"])                     # the receipt's north column
    cmds([f"setblock {X + dx} {STAND_Y} {Z + dz} {WOOL}" for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))])
    if capped:
        cmds([f"setblock {X} {STAND_Y + 2} {Z} {WOOL}"])
    cmds([f"setblock {X} {STAND_Y} {Z} {WOOL}"])
    time.sleep(1.0)
    o = bot.py.call("getOriginStatus")
    p = bot.py.call("getSelfPose")
    if o.get("feetFree") is not False or cell(p) != [X, STAND_Y, Z]:
        raise RuntimeError(f"start not embedded: pose {p.get('x')},{p.get('y')},{p.get('z')} origin {o}")


def put():
    bot.py.call("stopPathing")
    p = {}
    for _ in range(3):
        rcon.cmd(f"tp tester1 {PX} {STAND_Y} {PZ} 0 0", allow_reject=True)
        for _ in range(20):
            time.sleep(0.5)
            p = bot.py.call("getSelfPose")
            if abs(p["x"] - PX) < 0.05 and abs(p["z"] - PZ) < 0.05 and abs(p["y"] - STAND_Y) < 0.05 \
                    and p.get("onGround"):
                time.sleep(0.8)
                return
    raise RuntimeError(f"start not confirmed at {PX},{STAND_Y},{PZ}: {p}")


def settle(limit=4.0):
    t0 = time.time()
    p = bot.py.call("getSelfPose")
    while time.time() - t0 < limit:
        time.sleep(0.1)
        p = bot.py.call("getSelfPose")
        if p.get("onGround") and abs(p.get("vy", 0)) < 0.01 and time.time() - t0 > 0.6:
            break
    return p


def origin():
    o = bot.py.call("getOriginStatus")
    return {k: o.get(k) for k in ("feetCell", "feetFree", "standable", "exits")}


def case_jump(capped=False):
    build(capped)
    before = origin()
    bot.py.call("holdKey", "jump", 150)
    p = settle()
    after = origin()
    want = STAND_Y + (0 if capped else 1)
    # one jump straight up: the body lands back over its own column, one block higher
    ok = abs(p["y"] - want) < 0.05 and bool(p.get("onGround")) and cell(p) == [X, want, Z]
    return {"pass": ok, "final": [round(p["x"], 3), round(p["y"], 3), round(p["z"], 3)],
            "onGround": p.get("onGround"), "cell": cell(p), "before": before, "after": after}


def case_goto():
    build(False)
    before = origin()
    goal = [X + 4, STAND_Y, Z]
    t0 = time.time()
    bot.py.call("gotoCell", *goal)
    st, p = {}, {}
    while time.time() - t0 < 20:
        time.sleep(0.25)
        st = bot.py.call("pathStatus")
        p = bot.py.call("getSelfPose")
        if st.get("arrived") and st.get("inCell") and cell(p) == goal and abs(p["y"] - goal[1]) < 0.6:
            break
    t = time.time() - t0
    ok = bool(st.get("arrived") and st.get("inCell")) and cell(p) == goal
    bot.py.call("stopPathing")
    return {"pass": ok, "t": round(t, 1), "final": [round(p["x"], 3), round(p["y"], 3), round(p["z"], 3)],
            "cell": cell(p), "before": before,
            "path": {k: st.get(k) for k in ("arrived", "inCell", "lastPlanComplete", "lastPlanEnd",
                                            "lastPlanEndDistance", "navActive", "stopped")}}


rows = []
for name in ONLY:
    for i in range(REPEATS):
        r = case_jump() if name == "jump" else case_goto() if name == "goto" else case_jump(capped=True)
        r.update(case=name, run=i + 1)
        rows.append(r)
        print(json.dumps(r), flush=True)
cmds([f"fill {X - 8} {STAND_Y} {Z - 8} {X + 8} {STAND_Y + 5} {Z + 8} minecraft:air",
      f"forceload remove {X - 10} {Z - 10} {X + 10} {Z + 10}"])
gated = [r for r in rows if r["case"] != "capped"]
print(json.dumps({"summary": {c: f"{sum(r['pass'] for r in rows if r['case'] == c)}/"
                                 f"{sum(1 for r in rows if r['case'] == c)}" for c in ONLY}}))
sys.exit(0 if all(r["pass"] for r in gated) else 1)
