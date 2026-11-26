"""Down a drop onto a visible lower floor: does canReachAny tell a real route from a partial one,
and does gotoCell report arrival only after the body is physically down? (2026-11-26)

Live (bw-49, 80efea2f): from (72.5,24,-1642.2) to a lower cell (72,18,-1644) the route ended at once,
busy=false arrived=false 6.1 blocks off; a retry stopped twice at (71.8,22,-1644.5). Unknown whether
the goal was a valid feet cell or whether a path existed. Three decks on the flat stand, the bot on
each deck's edge, the target the lower floor block in front (normalized by gotoCell to the feet cell):
  drop3   a 3-block step down, no stair       -> expect reached, physically down
  drop6   a 6-block drop, no stair            -> recorded: partial plan + no arrival, or a real descent
  stair6  a 6-block drop with a stair down     -> expect reached, physically down
Per case: canReachAny (goal, reached, endDistance, lastPlan), gotoCell + pathStatus until arrived or
25 s, the settled pose, health lost. Prints one JSON line per case; exit 1 on a gated miss.

    python deploy/runner/descent_cell_test.py
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uctest.actors import Bot  # noqa: E402
from uctest.arena import STAND_Y  # noqa: E402
from uctest.harness import Rcon  # noqa: E402

F = STAND_Y - 1          # floor block level
CASES = {               # name: (deck x0, deck height, stair?)
    "drop3": (600, 3, False),
    "drop6": (620, 6, False),
    "stair6": (640, 6, True),
}
Z = 600
ONLY = sys.argv[1].split(",") if len(sys.argv) > 1 else None   # e.g. drop3 -- the decks are all built anyway
rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")
rcon.cmd(f"forceload add 590 {Z - 10} 660 {Z + 10}", allow_reject=True)
rcon.cmd(f"fill 590 {F} {Z - 8} 660 {F} {Z + 8} minecraft:stone", allow_reject=True)
rcon.cmd(f"fill 590 {STAND_Y} {Z - 8} 660 {STAND_Y + 8} {Z + 8} minecraft:air", allow_reject=True)
rcon.cmd("gamemode survival tester1", allow_reject=True)
fails = 0
for name, (x0, h, stair) in CASES.items():
    top = F + h                                      # deck floor block level
    rcon.cmd(f"fill {x0} {F + 1} {Z - 2} {x0 + 4} {top} {Z + 2} minecraft:stone", allow_reject=True)
    if stair:                                        # steps down in -x from the deck edge
        for i in range(1, h):
            rcon.cmd(f"fill {x0 - i} {F + 1} {Z - 2} {x0 - i} {top - i} {Z - 2} minecraft:stone",
                     allow_reject=True)
for name, (x0, h, stair) in CASES.items():
    if ONLY and name not in ONLY:
        continue
    top = F + h
    bot.py.call("stopPathing")
    rcon.cmd(f"effect give tester1 minecraft:instant_health 1 5 true", allow_reject=True)
    rcon.cmd(f"tp tester1 {x0 + 0.5} {top + 1} {Z + 0.5} 90 0", allow_reject=True)   # on the deck edge, facing -x
    landed = None
    for _ in range(30):              # the teleport landed ON the deck, and the client has its chunks
        time.sleep(0.5)
        p = bot.py.call("getSelfPose")
        if abs(p["x"] - (x0 + 0.5)) < 0.3 and abs(p["z"] - (Z + 0.5)) < 0.3                 and abs(p["y"] - (top + 1)) < 0.05 and p.get("onGround"):
            landed = p
            break
    if landed is None:
        fails += 1
        print(json.dumps({"case": name, "pass": False, "error": "teleport not confirmed", "pose": p}), flush=True)
        continue
    time.sleep(1.0)
    target_floor = (x0 - 3, F, Z)                     # the lower floor block 3 out from the deck
    cand = f"{target_floor[0]},{target_floor[1]},{target_floor[2]}"
    hp0 = bot.py.call("getGameState").get("self", {}).get("hp")
    r = bot.py.call("canReachAny", [cand], False, 1200)
    res = (r.get("results") or [{}])[0]
    g = bot.py.call("gotoCell", *target_floor)
    t0, st, arrived_s = time.time(), {}, None
    while time.time() - t0 < 25:
        time.sleep(0.5)
        st = bot.py.call("pathStatus")
        if st.get("arrived"):
            arrived_s = round(time.time() - t0, 1)
            break
        if not st.get("navActive") and time.time() - t0 > 3:
            break
    time.sleep(1.0)
    pose = bot.py.call("getSelfPose")
    hp1 = bot.py.call("getGameState").get("self", {}).get("hp")
    down = abs(pose["y"] - STAND_Y) < 0.05
    in_cell = st.get("feetCell") == g.get("goal")
    honest = (arrived_s is not None) == (down and in_cell)      # arrival claimed iff physically there
    if name in ("drop3", "stair6"):
        ok = res.get("reached") is True and arrived_s is not None and down and in_cell
    else:
        ok = honest and (res.get("reached") is True) == (arrived_s is not None)
    fails += 0 if ok else 1
    print(json.dumps({
        "case": name, "pass": ok, "goal": g.get("goal"), "normalized": g.get("normalized"),
        "canReach": {k: res.get(k) for k in ("reached", "endDistance", "pathSize", "found")},
        "arrived_s": arrived_s, "honest_arrival": honest, "feetCell": st.get("feetCell"),
        "lastPlanComplete": st.get("lastPlanComplete"), "lastPlanEndDistance": st.get("lastPlanEndDistance"),
        "settled_y": round(pose["y"], 2), "down": down, "health": [hp0, hp1],
    }, default=str), flush=True)
rcon.cmd(f"forceload remove 590 {Z - 10} 660 {Z + 10}", allow_reject=True)
ran = len(ONLY) if ONLY else len(CASES)
print(json.dumps({"summary": f"{ran - fails}/{ran} pass"}), flush=True)
sys.exit(1 if fails else 0)
