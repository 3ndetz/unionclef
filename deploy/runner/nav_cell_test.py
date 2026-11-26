"""Exact-cell goto, multi-candidate reachability and exact entity coordinates (2026-11-26).

Live reports: gotoXYZ(-104,39,631) said arrived with the feet at (-104.9,39.0,631.4), the next cell;
getEntitiesInfo rounded a drop's position "%.0f" into the neighbouring cell. On the flat stand:
  1 gotoXYZ to a cell on a bottom slab (old contract, recorded): where do the feet end up?
  2 gotoCell to that cell: pathStatus arrived only when feetCell == goal, body at slab height
  3 gotoCell on a full block (asked as the block) and on a feet cell given directly
  4 canReachAny: a cell sealed in bedrock (not reached) beside two open ones (reached), best set
  5 getEntitiesInfo: a dropped item at (-12.3, y, -13.8) -> x/z exact, blockX/blockZ -13/-14,
    the old "position" field unchanged
Prints one JSON line per check; exit 1 on a miss.

    python deploy/runner/nav_cell_test.py
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uctest.actors import Bot  # noqa: E402
from uctest.arena import STAND_Y  # noqa: E402
from uctest.harness import Rcon  # noqa: E402

CX, CZ = 500, 500
F = STAND_Y - 1
rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")
setup = [
    f"forceload add {CX - 12} {CZ - 12} {CX + 12} {CZ + 12}",
    f"fill {CX - 10} {F} {CZ - 10} {CX + 10} {F} {CZ + 10} minecraft:stone",
    f"fill {CX - 10} {STAND_Y} {CZ - 10} {CX + 10} {STAND_Y + 4} {CZ + 10} minecraft:air",
    f"setblock {CX + 3} {STAND_Y} {CZ} minecraft:stone_slab[type=bottom]",          # slab cell
    f"setblock {CX} {STAND_Y} {CZ + 4} minecraft:stone",                            # step up
    f"fill {CX - 7} {STAND_Y - 1} {CZ - 1} {CX - 5} {STAND_Y + 2} {CZ + 1} minecraft:bedrock",
    f"fill {CX - 6} {STAND_Y} {CZ} {CX - 6} {STAND_Y + 1} {CZ} minecraft:air",        # sealed cell
    "gamemode survival tester1",
]
for c in setup:
    rcon.cmd(c, allow_reject=True)
fails = 0


def check(label, ok, got):
    global fails
    fails += 0 if ok else 1
    print(json.dumps({"check": label, "pass": bool(ok), "got": got}, default=str), flush=True)


def home():
    bot.py.call("stopPathing")
    rcon.cmd(f"tp tester1 {CX + 0.5} {STAND_Y} {CZ + 0.5} 0 0", allow_reject=True)
    time.sleep(1.5)


def wait_arrived(limit_s=25):
    t0 = time.time()
    st = {}
    while time.time() - t0 < limit_s:
        time.sleep(0.5)
        st = bot.py.call("pathStatus")
        if st.get("arrived") and st.get("settled", True):
            return round(time.time() - t0, 1), st
    return None, st


slab = (CX + 3, STAND_Y, CZ)            # the slab BLOCK itself: normalized to the cell above
step = (CX, STAND_Y, CZ + 4)            # the full block itself: normalized to the cell above
step_feet = (CX, STAND_Y + 1, CZ + 4)   # already a feet cell: kept as given

# 1 old contract, recorded
home()
bot.py.call("gotoXYZ", *slab)
t, st = wait_arrived()
pose = bot.py.call("getSelfPose")
check("gotoXYZ slab cell (old contract, recorded)", True,
      {"arrived_s": t, "feetCell": st.get("feetCell"), "goal": st.get("goal"), "inCell": st.get("inCell"),
       "y": round(pose["y"], 3)})

# 2 exact cell on a slab, asked as the slab block: normalized to the feet cell above it
home()
g = bot.py.call("gotoCell", *slab)
t, st = wait_arrived()
pose = bot.py.call("getSelfPose")
check("gotoCell slab block -> stands on the slab, feet cell above it", t is not None
      and g.get("normalized") is True and g.get("goal") == f"{slab[0]},{slab[1] + 1},{slab[2]}"
      and st.get("inCell") is True and st.get("feetCell") == g.get("goal")
      and abs(pose["y"] - (STAND_Y + 0.5)) < 0.01,
      {"reply": g, "arrived_s": t, "feetCell": st.get("feetCell"), "y": round(pose["y"], 3),
       "lastPlanComplete": st.get("lastPlanComplete")})

# 3 one block up, asked as the full block
home()
g = bot.py.call("gotoCell", *step)
t, st = wait_arrived()
pose = bot.py.call("getSelfPose")
check("gotoCell full block -> stands on it", t is not None and g.get("normalized") is True
      and g.get("goal") == f"{step_feet[0]},{step_feet[1]},{step_feet[2]}" and st.get("inCell") is True
      and abs(pose["y"] - (STAND_Y + 1)) < 0.01,
      {"reply": g, "arrived_s": t, "feetCell": st.get("feetCell"), "y": round(pose["y"], 3)})

# 3b a feet cell given directly is kept as given
home()
g = bot.py.call("gotoCell", *step_feet)
t, st = wait_arrived()
check("gotoCell feet cell given directly is not moved", t is not None and g.get("normalized") is False
      and g.get("goal") == f"{step_feet[0]},{step_feet[1]},{step_feet[2]}" and st.get("inCell") is True,
      {"reply": g, "arrived_s": t, "feetCell": st.get("feetCell")})

# 4 reachability of several candidates, nothing moves
home()
before = bot.py.call("getSelfPose")
cands = [f"{CX - 6},{STAND_Y},{CZ}", f"{slab[0]},{slab[1]},{slab[2]}", f"{step_feet[0]},{step_feet[1]},{step_feet[2]}"]
r = bot.py.call("canReachAny", cands, False, 600)
after = bot.py.call("getSelfPose")
res = {x.get("cell"): x for x in (r.get("results") or [])}
check("canReachAny sealed / open / step", r.get("ok") is True
      and res.get(cands[0], {}).get("reached") is False
      and res.get(cands[1], {}).get("reached") is True and res.get(cands[1], {}).get("normalized") is True
      and res.get(cands[2], {}).get("reached") is True and res.get(cands[2], {}).get("normalized") is False
      and r.get("best") in (cands[1], cands[2])
      and abs(after["x"] - before["x"]) < 0.01 and abs(after["z"] - before["z"]) < 0.01, r)

# 5 exact entity coordinates at a negative fractional position
rcon.cmd("forceload add -20 -20 0 0", allow_reject=True)
rcon.cmd("kill @e[type=item]", allow_reject=True)
rcon.cmd(f"tp tester1 -10.5 {STAND_Y} -10.5 0 0", allow_reject=True)
time.sleep(1.5)
rcon.cmd(f'summon item -12.3 {STAND_Y} -13.8 {{Item:{{id:"minecraft:diamond",count:1}},PickupDelay:32767s,Motion:[0d,0d,0d]}}',
         allow_reject=True)
time.sleep(1.5)
ents = bot.py.call("getEntitiesInfo", 8) or []
item = next((e for e in ents if e.get("is_item") == "true"), None)
ok = item is not None and abs(float(item.get("x", 99)) + 12.3) < 0.05 \
    and abs(float(item.get("z", 99)) + 13.8) < 0.05 \
    and item.get("blockX") == "-13" and item.get("blockZ") == "-14" and "position" in item
check("getEntitiesInfo exact x/z and block cell, old position kept", ok, item)
rcon.cmd("kill @e[type=item]", allow_reject=True)
rcon.cmd("forceload remove -20 -20 0 0", allow_reject=True)
rcon.cmd(f"forceload remove {CX - 12} {CZ - 12} {CX + 12} {CZ + 12}", allow_reject=True)
print(json.dumps({"summary": f"{6 - fails}/6 pass"}), flush=True)
sys.exit(1 if fails else 0)
