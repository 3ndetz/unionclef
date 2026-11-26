"""lookAt + holdKey(forward) on a straight floor: does the body go where it was aimed? (2026-11-26)

Live report (LuckyWorld bw-110): lookAt(1343,19,352) then holdKey(forward,220) from about
(1340.5,18.2,354.3), and the snapshot right after read (1338.6,18.0,355.6) -- the other way.
holdKey returns before it releases, so a snapshot right after it shows the START of the move,
not its result. This measures per direction: yaw right after lookAt, yaw and keys while held,
and the SETTLED position after release, against the aimed bearing.

    python deploy/runner/walkdir_test.py [hold_ms]
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

HOLD = int(sys.argv[1]) if len(sys.argv) > 1 else 700
CX, CZ = 400, 400
AIMS = {"east": (4, 0), "west": (-4, 0), "south": (0, 4), "north": (0, -4), "southwest": (-3, 3)}

rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")
for c in [f"forceload add {CX - 12} {CZ - 12} {CX + 12} {CZ + 12}",
          f"fill {CX - 10} {STAND_Y - 1} {CZ - 10} {CX + 10} {STAND_Y - 1} {CZ + 10} minecraft:stone",
          f"fill {CX - 10} {STAND_Y} {CZ - 10} {CX + 10} {STAND_Y + 3} {CZ + 10} minecraft:air",
          "gamemode survival tester1"]:
    rcon.cmd(c, allow_reject=True)
bot.py.call("stopPathing")
fails = 0


def pose():
    return bot.py.call("getSelfPose")


def bearing(dx, dz):
    """Vanilla yaw of a horizontal direction (0 = +Z, 90 = -X)."""
    return math.degrees(math.atan2(-dx, dz))


def angdiff(a, b):
    return abs((a - b + 180) % 360 - 180)


for name, (ax, az) in AIMS.items():
    rcon.cmd(f"tp tester1 {CX + 0.5} {STAND_Y} {CZ + 0.5} 0 0", allow_reject=True)
    time.sleep(1.2)
    start = pose()
    tx, tz = CX + 0.5 + ax, CZ + 0.5 + az
    bot.py.call("lookAt", tx, STAND_Y + 1.6, tz)
    after_look = pose()
    bot.py.call("holdKey", "forward", HOLD)
    snap = pose()                                   # what a caller sees right after holdKey
    time.sleep(HOLD / 1000.0 * 0.6)
    mid = pose()
    time.sleep(HOLD / 1000.0 * 0.4 + 1.0)
    settled = pose()
    want = bearing(ax, az)
    dx, dz = settled["x"] - start["x"], settled["z"] - start["z"]
    moved = math.hypot(dx, dz)
    got = bearing(dx, dz) if moved > 0.05 else None
    ok = moved > 0.3 and got is not None and angdiff(got, want) < 20
    fails += 0 if ok else 1
    print(json.dumps({
        "aim": name, "pass": ok, "want_yaw": round(want, 1),
        "yaw_after_lookAt": round(after_look["yaw"], 1), "yaw_mid_hold": round(mid["yaw"], 1),
        "keys_mid_hold": {k: v for k, v in mid["keys"].items() if v},
        "snapshot_right_after_holdKey": [round(snap["x"], 2), round(snap["z"], 2)],
        "settled": [round(settled["x"], 2), round(settled["z"], 2)],
        "moved": round(moved, 2), "moved_bearing": None if got is None else round(got, 1),
    }), flush=True)

rcon.cmd(f"forceload remove {CX - 12} {CZ - 12} {CX + 12} {CZ + 12}", allow_reject=True)
print(json.dumps({"summary": f"{len(AIMS) - fails}/{len(AIMS)} directions as aimed", "hold_ms": HOLD}))
sys.exit(1 if fails else 0)
