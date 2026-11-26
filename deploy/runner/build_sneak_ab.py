"""bw153 four 'placeable' cells: does the builder place them while crouched? A/B on the aim origin.
(2026-11-27)

Live bw153: blueprint.sneak=true, sneak held for the whole batch, 0 placed, deferNoFace=7; the
read-only diagnostic says four of the cells have a clear floor face from that stance. Hypothesis
(integration review): aim vs ray origin while crouched -- measured 0.000 apart here; the real
difference is one aim point a quarter block inside the support vs baritone's face sampling. Per arm (builderSamplesFacePoints false = old one point, true = sampled) and
pose (standing / crouched): rebuild the bed fixture, put the body on the bed's edge in the receipt's
pose, read placeTargetsDiagnostic.builderAim (both origins, the builder's own accept), hand the four
cells to buildBlocks, and count what was placed after 8 s. Also one experience read after xp set.

    python deploy/runner/build_sneak_ab.py
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


def m153(x, y, z):
    return x + 8, y - 66, z + 528


CELLS = [m153(*c) for c in [(694, 6, 171), (691, 6, 171), (693, 6, 170), (692, 6, 170)]]
POSE = (692.8 + 8, 6.5625 - 66, 172.2 + 528)


def cmds(lines):
    for c in lines:
        rcon.cmd(c, allow_reject=True)


def fixture():
    cmds([f"fill 695 {F} 695 706 {F} 705 minecraft:grass_block",
          f"fill 695 {STAND_Y} 695 706 {STAND_Y + 4} 705 minecraft:air"])
    tx, ty, tz = m153(693, 5, 170)
    fx, fy, fz = m153(692, 6, 171)
    hx, hy, hz = m153(693, 6, 171)
    cmds([f"setblock {tx} {ty} {tz} minecraft:lime_terracotta",
          f"setblock {fx} {fy} {fz} minecraft:green_bed[facing=east,part=foot]",
          f"setblock {hx} {hy} {hz} minecraft:green_bed[facing=east,part=head]",
          "clear tester1", "give tester1 minecraft:lime_wool 16"])


def put():
    bot.py.call("stopPathing")
    x, y, z = POSE
    for _ in range(3):
        rcon.cmd(f"tp tester1 {x} {y} {z} 0 0", allow_reject=True)
        for _ in range(20):
            time.sleep(0.5)
            p = bot.py.call("getSelfPose")
            if abs(p["x"] - x) < 0.05 and abs(p["z"] - z) < 0.05 and abs(p["y"] - y) < 0.1:
                time.sleep(0.8)
                return p
    raise RuntimeError(f"teleport not confirmed: {p}")


def placed_cells():
    out = []
    for (x, y, z) in CELLS:
        if "passed" in str(rcon.cmd(f"execute if block {x} {y} {z} minecraft:lime_wool", allow_reject=True)).lower():
            out.append(f"{x},{y},{z}")
    return out


cmds(["gamemode survival tester1", "forceload add 690 690 710 710"])
rows = []
bot.py.call("setBuilderWalks", False)       # as live: walkToBuild=False, the builder works from its stance
for arm in (False, True):
    for crouch in (False, True):
        bot.py.call("tungstenSetting", "builderSamplesFacePoints", "true" if arm else "false")
        fixture()
        put()
        if crouch:
            bot.py.call("holdKey", "sneak", 10000)
            time.sleep(0.8)
        pose = bot.py.call("getSelfPose")
        d = bot.py.call("placeTargetsDiagnostic", [f"{a},{b},{c}" for a, b, c in CELLS])
        aims = {}
        for c in d.get("cells", []):
            for f in c.get("faces", []):
                ba = f.get("builderAim") or {}
                if f.get("side") == "up":
                    aims[c["cell"]] = {k: ba.get(k) for k in ("accepted", "acceptedPoint", "points", "centreRayHit",
                                                             "originGapY")}
        bot.py.call("buildBlocks", [[a, b, c, "lime_wool"] for a, b, c in CELLS])
        time.sleep(8)
        q = bot.py.call("buildQueue")
        got = placed_cells()
        row = {"samplesFacePoints": arm, "crouched": crouch, "sneak_key": pose.get("keys", {}).get("sneak"),
               "pose_y": round(pose["y"], 3), "placed": len(got), "placed_cells": got,
               "queue": {k: q.get(k) for k in ("placed", "deferNoFace", "blockedByOwnBody", "deferredCount")},
               "builderAim_up_faces": aims}
        rows.append(row)
        print(json.dumps(row, default=str), flush=True)
        bot.py.call("stopPathing")
        time.sleep(10.5 if crouch else 0.5)      # the sneak hold expires

# experience: one read-only control
cmds(["xp set tester1 7 levels", "xp set tester1 3 points"])
time.sleep(1.0)
me = bot.py.call("getGameState").get("self", {})
print(json.dumps({"experience": {k: me.get(k) for k in ("experienceLevel", "experienceProgress")}}))
cmds(["xp set tester1 0 levels", "xp set tester1 0 points", "forceload remove 690 690 710 710"])
bot.py.call("tungstenSetting", "builderSamplesFacePoints", "true")
bot.py.call("setBuilderWalks", True)
