"""Exact routes over mixed heights: slab steps, carpet, and an offset ladder pair (2026-11-27).

Live reports on LuckyWorld (native gotoCell, no edits):
  Ships: from standing on a lower smooth_stone_slab (feet y+0.5) to a cell one up and a few blocks
    off: ack, ~4 s, 0.11 m moved, partial; from a carpet on a sea lantern: zero displacement, partial.
  bw-256: a 2-wide ladder strip on the north face of a 5-high wool wall, the ladders above the wall's
    top shifted one cell south; from the deck at y21 the bot could not go down to the ladder or the
    grass; a same-level exit worked.
Both are rebuilt on the flat stand. Each case: the start PROVEN by pose (on its support), gotoCell,
pathStatus until arrived / navigator stopped / 25 s, then the settled pose. Pass only when the feet
are in the goal cell and settled AND the pose agrees -- a bare route result never counts.
Prints one JSON line per case; exit 1 on a miss.

    python deploy/runner/mixed_height_test.py [case,case,...]
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uctest.actors import Bot  # noqa: E402
from uctest.arena import STAND_Y  # noqa: E402
from uctest.harness import Rcon  # noqa: E402

F = STAND_Y - 1
ONLY = sys.argv[1].split(",") if len(sys.argv) > 1 else None
rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")


def cmds(lines):
    for c in lines:
        rcon.cmd(c, allow_reject=True)


def put(x, y, z):
    bot.py.call("stopPathing")
    p = {}
    for _ in range(3):
        rcon.cmd(f"tp tester1 {x} {y} {z} 0 0", allow_reject=True)
        for _ in range(20):
            time.sleep(0.5)
            p = bot.py.call("getSelfPose")
            if abs(p["x"] - x) < 0.1 and abs(p["z"] - z) < 0.1 and abs(p["y"] - y) < 0.1 and \
                    (p.get("onGround") or abs(p.get("vy", 0)) < 0.2):
                time.sleep(0.8)
                return bot.py.call("getSelfPose")
    raise RuntimeError(f"start not confirmed at {x},{y},{z}: {p}")


# --- Ships-like mixed entrance, around (850, 850) -------------------------------------------
SX, SZ = 850, 850


def build_ships():
    cmds([f"forceload add {SX - 12} {SZ - 12} {SX + 12} {SZ + 12}",
          f"fill {SX - 10} {F} {SZ - 10} {SX + 10} {F} {SZ + 10} minecraft:stone",
          f"fill {SX - 10} {STAND_Y} {SZ - 10} {SX + 10} {STAND_Y + 4} {SZ + 10} minecraft:air",
          # raised platform of full blocks, top at STAND_Y + 1
          f"fill {SX} {STAND_Y} {SZ - 6} {SX + 8} {STAND_Y} {SZ + 6} minecraft:brown_terracotta",
          # the entrance: a row of bottom slabs half a block up, between floor and platform
          f"fill {SX - 1} {STAND_Y} {SZ - 6} {SX - 1} {STAND_Y} {SZ + 6} minecraft:smooth_stone_slab[type=bottom]",
          # a carpet on a sea lantern inside the platform
          f"setblock {SX + 3} {STAND_Y} {SZ - 2} minecraft:sea_lantern",
          f"setblock {SX + 3} {STAND_Y + 1} {SZ - 2} minecraft:white_carpet"])


SHIPS = {
    # name: (start pose, goal cell (feet), goal feet y)
    "slab_to_platform": ((SX - 0.5, STAND_Y + 0.5, SZ + 0.5), (SX + 1, STAND_Y + 1, SZ - 4), STAND_Y + 1),
    "carpet_to_platform": ((SX + 3.5, STAND_Y + 1.0625, SZ - 1.5), (SX + 1, STAND_Y + 1, SZ - 2), STAND_Y + 1),
    "platform_to_slab": ((SX + 3.5, STAND_Y + 1, SZ + 3.5), (SX - 1, STAND_Y + 1, SZ + 3), STAND_Y + 0.5),
    "floor_to_far_platform": ((SX - 4.5, STAND_Y, SZ + 0.5), (SX + 7, STAND_Y + 1, SZ + 5), STAND_Y + 1),
}

# --- bw-256 offset ladder pair: real (x, y, z) -> (x - 368, y - 76, z + 1060) ----------------
def m256(x, y, z):
    return x - 368, y - 76, z + 1060


def build_ladders():
    gx, gy, gz = m256(1268, 15, -160)
    cmds([f"forceload add {gx - 10} {gz - 10} {gx + 10} {gz + 14}",
          f"fill {gx - 6} {F} {gz - 6} {gx + 6} {F} {gz + 12} minecraft:grass_block",
          f"fill {gx - 6} {STAND_Y} {gz - 6} {gx + 6} {STAND_Y + 10} {gz + 12} minecraft:air"])
    # the deck the bot stood on (top at real y21): real y20 blocks at z -158 .. -153
    a = m256(1266, 20, -158)
    b = m256(1271, 20, -153)
    cmds([f"fill {a[0]} {a[1]} {a[2]} {b[0]} {b[1]} {b[2]} minecraft:stone",
          # under it, down to the grass, so the deck is not a floating slab
          f"fill {a[0]} {F + 1} {a[2]} {b[0]} {a[1] - 1} {b[2]} minecraft:stone"])
    for x in (1268, 1269):
        w0 = m256(x, 16, -159)
        w1 = m256(x, 20, -159)
        cmds([f"fill {w0[0]} {w0[1]} {w0[2]} {w1[0]} {w1[1]} {w1[2]} minecraft:yellow_wool"])
        for y in range(16, 21):                       # ladders on the wall's north face
            l = m256(x, y, -160)
            cmds([f"setblock {l[0]} {l[1]} {l[2]} minecraft:ladder[facing=north]"])
        for y in (21, 22):                            # above the wall's top, shifted one cell south
            l = m256(x, y, -159)
            cmds([f"setblock {l[0]} {l[1]} {l[2]} minecraft:ladder[facing=north]"])


LADDERS = {
    "deck_same_level": (m256(1270.6, 21, -156.5), m256(1270, 21, -157), m256(1270, 21, -157)[1]),
    "deck_to_ladder_mid": (m256(1270.6, 21, -156.5), m256(1268, 18, -160), m256(1268, 18, -160)[1]),
    "deck_to_ladder_foot": (m256(1270.6, 21, -156.5), m256(1269, 16, -160), m256(1269, 16, -160)[1]),
    "deck_to_grass": (m256(1270.6, 21, -156.5), m256(1268, 16, -162), m256(1268, 16, -162)[1]),
    "grass_to_deck": (m256(1268.5, 16, -162.5), m256(1270, 21, -157), m256(1270, 21, -157)[1]),
}

cmds(["gamemode survival tester1"])
build_ships()
build_ladders()
fails, ran = 0, 0
for name, (start, goal, goal_y) in list(SHIPS.items()) + list(LADDERS.items()):
    if ONLY and name not in ONLY:
        continue
    ran += 1
    try:
        s = put(*start)
    except RuntimeError as e:
        fails += 1
        print(json.dumps({"case": name, "pass": False, "error": str(e)}), flush=True)
        continue
    g = bot.py.call("gotoCell", *goal)
    t0, st, arrived_s = time.time(), {}, None
    while time.time() - t0 < 25:
        time.sleep(0.5)
        st = bot.py.call("pathStatus")
        if st.get("arrived"):
            arrived_s = round(time.time() - t0, 1)
            break
        if st.get("stopped") and time.time() - t0 > 3:
            break
    time.sleep(1.0)
    pose = bot.py.call("getSelfPose")
    in_goal = int(pose["x"] // 1) == goal[0] and int(pose["z"] // 1) == goal[2] and abs(pose["y"] - goal_y) < 0.6
    ok = arrived_s is not None and st.get("inCell") is True and in_goal
    fails += 0 if ok else 1
    print(json.dumps({
        "case": name, "pass": ok, "start": [round(s["x"], 2), round(s["y"], 3), round(s["z"], 2)],
        "goal": g.get("goal"), "normalized": g.get("normalized"), "arrived_s": arrived_s,
        "inCell": st.get("inCell"), "feetCell": st.get("feetCell"), "lastPlanComplete": st.get("lastPlanComplete"),
        "lastPlanEnd": st.get("lastPlanEnd"), "settled_pose": [round(pose["x"], 2), round(pose["y"], 3), round(pose["z"], 2)],
        "moved": round(((pose["x"] - s["x"]) ** 2 + (pose["z"] - s["z"]) ** 2) ** 0.5, 2),
    }), flush=True)
    bot.py.call("stopPathing")
print(json.dumps({"summary": f"{ran - fails}/{ran} pass"}), flush=True)
sys.exit(1 if fails else 0)
