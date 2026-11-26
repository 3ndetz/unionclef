"""placeTargetsDiagnostic + getOriginStatus on the geometry of two live receipts (2026-11-27).

bw153 (bed cover, 0 placed / 7 deferNoFace / 183 blockedByOwnBody): a two-block bed on grass with one
lime terracotta, the body standing on the bed's edge at (692.8, 6.6, 172.2), seven wool cells round
the bed. bw185 (yellow cover, 0 placed / 2 deferNoFace): two glass cells on planks five blocks over a
stance, a bed beside them. Both are rebuilt on the flat stand with a coordinate shift and the body put
in the receipt's pose; the diagnostic then answers per cell. Controls: an open floor cell next to the
body must read placeable; getOriginStatus must read enclosed=false on open floor and true in a stone box.
Prints one JSON line per check; exit 1 on a miss.

    python deploy/runner/build_diag_test.py
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
rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")
VERDICTS = {"placeable", "occupied_by_block", "entity_in_cell", "no_support", "interactable_face_needs_sneak",
            "out_of_reach", "occluded", "back_faces_only"}
fails = 0


def check(label, ok, got):
    global fails
    fails += 0 if ok else 1
    print(json.dumps({"check": label, "pass": bool(ok), "got": got}, default=str), flush=True)


def cmds(lines):
    for c in lines:
        rcon.cmd(c, allow_reject=True)


def put(x, y, z, yaw=0):
    bot.py.call("stopPathing")
    for _ in range(3):
        rcon.cmd(f"tp tester1 {x} {y} {z} {yaw} 0", allow_reject=True)
        for _ in range(20):
            time.sleep(0.5)
            p = bot.py.call("getSelfPose")
            if abs(p["x"] - x) < 0.05 and abs(p["z"] - z) < 0.05 and abs(p["y"] - y) < 0.1:
                time.sleep(1.0)
                return bot.py.call("getSelfPose")
    raise RuntimeError(f"teleport not confirmed: {p}")


def diag(cells):
    r = bot.py.call("placeTargetsDiagnostic", [f"{a},{b},{c}" for a, b, c in cells])
    return r, {x["cell"]: x for x in r.get("cells", [])}


cmds(["gamemode survival tester1", "forceload add 690 690 810 710"])

# --- bw153: real (x, y, z) -> (x + 8, y - 66, z + 528) ---------------------------------------
def m153(x, y, z):
    return x + 8, y - 66, z + 528


cmds([f"fill 695 {F} 695 706 {F} 705 minecraft:grass_block",
      f"fill 695 {STAND_Y} 695 706 {STAND_Y + 4} 705 minecraft:air"])
tx, ty, tz = m153(693, 5, 170)
cmds([f"setblock {tx} {ty} {tz} minecraft:lime_terracotta"])
fx, fy, fz = m153(692, 6, 171)
hx, hy, hz = m153(693, 6, 171)
cmds([f"setblock {fx} {fy} {fz} minecraft:green_bed[facing=east,part=foot]",
      f"setblock {hx} {hy} {hz} minecraft:green_bed[facing=east,part=head]"])
cells153 = [m153(*c) for c in [(693, 6, 172), (694, 6, 171), (691, 6, 171), (693, 6, 170), (692, 6, 170),
                               (693, 7, 171), (692, 7, 171)]]
px, py_, pz = 692.8 + 8, 6.5625 - 66, 172.2 + 528
pose = put(px, py_, pz)
r, by = diag(cells153)
self_cells = [c for c, x in by.items() if "self" in (x.get("entities") or [])]
check("bw153: every cell answered with a known verdict",
      r.get("ok") and len(by) == 7 and all(x.get("verdict") in VERDICTS for x in by.values()),
      {"pose": {k: round(pose[k], 3) for k in ("x", "y", "z")}, "eye": r.get("eye"), "reach": r.get("reach"),
       "verdicts": {c: x.get("verdict") for c, x in by.items()}})
check("bw153: cells the body's own box overlaps are named entity_in_cell(self)",
      len(self_cells) >= 1 and all(by[c]["verdict"] == "entity_in_cell" for c in self_cells),
      {"self_cells": self_cells})
for c, x in by.items():
    print(json.dumps({"cell153": c, "verdict": x.get("verdict"), "entities": x.get("entities"),
                      "faces": [{k: f.get(k) for k in ("support", "block", "side", "facing", "distance", "inReach",
                                                        "ray", "interactable")} for f in x.get("faces", [])]},
                     default=str), flush=True)
# control: an open floor cell beside the body, away from the bed
ctrl = (int(px) - 3, STAND_Y, int(pz) + 2)
r, by = diag([ctrl])
check("control: open floor cell in reach is placeable", list(by.values())[0].get("verdict") == "placeable",
      list(by.values())[0])

# --- bw185: real (x, y, z) -> (x + 870, y - 97, z + 631) --------------------------------------
def m185(x, y, z):
    return x + 870, y - 97, z + 631


cmds([f"fill 795 {F} 695 806 {F} 706 minecraft:stone",
      f"fill 795 {STAND_Y} 695 806 {STAND_Y + 10} 706 minecraft:air"])
for x in (-71, -70):
    a, b, c = m185(x, 42, 70)
    cmds([f"setblock {a} {b} {c} minecraft:jungle_planks"])
f1 = m185(-71, 43, 71)
f2 = m185(-70, 43, 71)
cmds([f"setblock {f1[0]} {f1[1]} {f1[2]} minecraft:yellow_bed[facing=east,part=foot]",
      f"setblock {f2[0]} {f2[1]} {f2[2]} minecraft:yellow_bed[facing=east,part=head]"])
sx, sy, sz = m185(-70, 37, 69)
pose = put(sx + 0.5, sy, sz + 0.5)
cells185 = [m185(-70, 43, 70), m185(-71, 43, 70)]
r, by = diag(cells185)
check("bw185: both cells answered, neither placeable from the stance below",
      r.get("ok") and len(by) == 2 and all(x.get("verdict") in VERDICTS - {"placeable"} for x in by.values()),
      {"eye": r.get("eye"), "reach": r.get("reach"), "verdicts": {c: x.get("verdict") for c, x in by.items()}})
for c, x in by.items():
    print(json.dumps({"cell185": c, "verdict": x.get("verdict"),
                      "faces": [{k: f.get(k) for k in ("support", "block", "side", "facing", "distance", "inReach",
                                                        "ray", "interactable")} for f in x.get("faces", [])]},
                     default=str), flush=True)

# --- getOriginStatus: open floor, then a stone box round the body ----------------------------
o = bot.py.call("getOriginStatus")
check("origin on open floor: standable, not enclosed", o.get("standable") is True and o.get("enclosed") is False, o)
bx, by_, bz = sx, sy, sz
cmds([f"fill {bx - 1} {by_} {bz - 1} {bx + 1} {by_ + 2} {bz + 1} minecraft:stone",
      f"fill {bx} {by_} {bz} {bx} {by_ + 1} {bz} minecraft:air"])
time.sleep(1.0)
o = bot.py.call("getOriginStatus")
check("origin in a stone box: standable, enclosed", o.get("standable") is True and o.get("enclosed") is True, o)
cmds([f"fill {bx - 1} {by_} {bz - 1} {bx + 1} {by_ + 2} {bz + 1} minecraft:air",
      "forceload remove 690 690 810 710"])
print(json.dumps({"summary": f"{6 - fails}/6 pass"}), flush=True)
sys.exit(1 if fails else 0)
