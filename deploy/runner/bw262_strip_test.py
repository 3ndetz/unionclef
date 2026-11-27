"""bw-262 one-block-wide wool strip fall, rebuilt from the live LuckyWorld receipt (e276fca1, 2026-11-27).

Live 04:20:30-35Z: gotoCell(937,37,-334) from (937.2058, 38, -322.7000), exact, plan complete (13 BFS
waypoints), both terrain edits off, the wrapper's 2 s budget; at +3.03 s the body was at
(936.6863, 36.4372, -329.9700) -- in the open column x936, below the strip top, falling -- and then
a void death at full health. The 325-cell read (x 935..939, y 35..39, z -335..-323) shows the strip:
red wool at y36, x937, z -335..-326, air at x936 and x938 beside it. The read was taken later and also
holds wool and yellow wool towers ON the strip (y37..39 at z -326, -329, -330); with those the strip
is blocked and no complete plan exists, so they came after -- this fixture leaves the strip clear above
y36 and keeps the rest of the read (the start platform at z -325..-323).

Modes:
  run     gotoCell and let it go (up to SECS);
  cancel  gotoCell, then stopPathing at CANCEL s (2.0 = the wrapper's --seconds 2), keep sampling.
Per run: stage at the exact live origin (facing north, along the strip), 50 ms in-container trace;
recorded: off-axis maximum |x - 937.5| while over the strip, whether it fell (y < 30), where it left
the strip, the final pose, the navigator outcome, the hop count (onGround -> airborne going up
while not stepping up).

    python deploy/runner/bw262_strip_test.py [run|cancel] [runs] [secs] [cancel_s]
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
ART = os.path.join(HERE, "artifacts", "bw262-live-20261127")
D = json.load(open(os.path.join(ART, "bw262-e276-exact-fall-corridor-20261127.json"), encoding="utf-8"))
MODE = sys.argv[1] if len(sys.argv) > 1 else "run"
RUNS = int(sys.argv[2]) if len(sys.argv) > 2 else 3
SECS = float(sys.argv[3]) if len(sys.argv) > 3 else 8.0
CANCEL = float(sys.argv[4]) if len(sys.argv) > 4 else 2.0
DX, DZ = 1700 - 937, 1700 + 330          # real 937,-330 -> bench 1700,1700; y kept
ORIGIN = (937.2057946035247, 38.0, -322.69999998807907)
GOAL = (937, 37, -334)
STRIP_X = 937.5

rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")


def b(x, z):
    return x + DX, z + DZ


def cmds(lines):
    for c in lines:
        rcon.cmd(c, allow_reject=True)


def build():
    x0, z0 = b(932, -338)
    x1, z1 = b(942, -318)
    cmds([f"forceload add {x0 - 2} {z0 - 2} {x1 + 2} {z1 + 2}",
          f"fill {x0} 30 {z0} {x1} 42 {z1} minecraft:air"])
    for x, y, z, blk in D["blocks"]:
        if x == 937 and y >= 37 and z <= -326:
            blk = "minecraft:air"                # the later towers on the strip (see the docstring)
        bx, bz = b(x, z)
        cmds([f"setblock {bx} {y} {bz} {blk}"])
    cmds(["gamemode survival tester1", "effect clear tester1", "clear tester1"])


def stage():
    sx, sz = b(ORIGIN[0], ORIGIN[2])
    sy = ORIGIN[1]
    bot.py.call("stopPathing")
    p = {}
    for _ in range(3):
        rcon.cmd(f"tp tester1 {sx} {sy} {sz} 180 0", allow_reject=True)
        for _ in range(20):
            time.sleep(0.5)
            p = bot.py.call("getSelfPose")
            if abs(p["x"] - sx) < 0.01 and abs(p["z"] - sz) < 0.01 and abs(p["y"] - sy) < 0.01 and p.get("onGround"):
                time.sleep(0.8)
                return bot.py.call("getSelfPose")
    raise RuntimeError(f"stage not confirmed at {sx},{sy},{sz}: {p}")


env = dict(os.environ, MSYS_NO_PATHCONV="1")
subprocess.run(["docker", "cp", os.path.join(HERE, "ladder_probe_inner.py"),
                "uctest-mc-tester1:/mc-data/ladder_probe_inner.py"], check=True, env=env)
bot.py.call("setBuildPolicy", False, False, False)
gx, gz = b(GOAL[0], GOAL[2])
rows = []
for run in range(1, RUNS + 1):
    build()
    p0 = stage()
    canceller = None
    if MODE == "cancel":
        canceller = threading.Timer(CANCEL, lambda: bot.py.call("stopPathing"))
        canceller.start()
    r = subprocess.run(["docker", "exec", "uctest-mc-tester1", "python3", "/mc-data/ladder_probe_inner.py",
                        str(gx), str(GOAL[1]), str(gz), str(SECS)], capture_output=True, text=True,
                       timeout=SECS + 60, env=env)
    if canceller:
        canceller.join()
    trace = [json.loads(l) for l in r.stdout.splitlines() if l.startswith("{")]
    with open(os.path.join(ART, f"trace_{MODE}_run{run}.jsonl"), "w", encoding="utf-8") as f:
        f.write(r.stdout)
    over = [t for t in trace if t["pos"][2] - DZ <= -325.7]               # over the one-wide strip
    off = max((abs(t["pos"][0] - DX - STRIP_X) for t in over), default=None)
    hops, prev = 0, None
    for t in trace:
        if prev and prev["onGround"] and not t["onGround"] and t["pos"][1] > prev["pos"][1] + 0.1:
            hops += 1
        prev = t
    left = next((t for t in over if t["pos"][1] < 36.9), None)
    st = bot.py.call("pathStatus")
    p = bot.py.call("getSelfPose")
    fell = any(t["pos"][1] < 30 for t in trace) or p["y"] < 30
    row = {"mode": MODE, "run": run, "fell": fell, "outcome": st.get("outcome"), "arrived": st.get("arrived"),
           "maxOffAxis": None if off is None else round(off, 3), "hops": hops,
           "leftStripAt": None if not left else [round(left["pos"][0] - DX, 3), left["pos"][1],
                                                 round(left["pos"][2] - DZ, 3), left["t"]],
           "finalReal": [round(p["x"] - DX, 3), round(p["y"], 3), round(p["z"] - DZ, 3)],
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
print(json.dumps({"summary": {"fell": sum(r["fell"] for r in rows), "arrived": sum(1 for r in rows if r["arrived"]),
                              "maxOffAxis": max((r["maxOffAxis"] or 0) for r in rows), "hops": [r["hops"] for r in rows]}}))
