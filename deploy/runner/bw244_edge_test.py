"""bw-244 one-block wool bridge start, rebuilt from the live LuckyWorld read (2026-11-27).

Live: legacy gotoXYZ(419,34,1019) from (418.3,34,1019.1) "arrived" without moving (0.7 from the cell,
inside gotoXYZ's 2-block arrival by design, inCell=false). The planned exact stance uses gotoCell
instead; this runs that on the same 90 cells (x 417..422, y 32..36, z 1018..1020): sandstone stand at
(418,33,1019), the goal over one blue_wool (419,33,1019) with air north, south and east of it and a
drop east of that. Stage at the exact live pose, gotoCell(419,34,1019), 50 ms trace; recorded: arrival,
the furthest east the body went (x > 420.3 = its centre past the wool, over the drop), final pose.
Build policy off (no place, no break), inventory cleared, as the live client.

    python deploy/runner/bw244_edge_test.py [runs]
"""
import json
import math
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uctest.actors import Bot  # noqa: E402
from uctest.harness import Rcon  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.join(HERE, "artifacts", "bw244-20261127")
D = json.load(open(os.path.join(ART, "bw244-blue-bridge-edge-geometry-20261127.json"), encoding="utf-8"))
RUNS = int(sys.argv[1]) if len(sys.argv) > 1 else 5
DX, DZ = 1900 - 418, 1900 - 1019      # real 418,1019 -> bench 1900,1900; y kept
ORIGIN = (418.3, 34.0, 1019.1)
GOAL = (419, 34, 1019)
rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")


def cmds(lines):
    for c in lines:
        rcon.cmd(c, allow_reject=True)


def build():
    x0, z0 = 417 + DX - 3, 1018 + DZ - 3
    cmds([f"forceload add {x0 - 2} {z0 - 2} {x0 + 12} {z0 + 9}",
          f"fill {x0} 26 {z0} {x0 + 10} 38 {z0 + 7} minecraft:air"])
    cmds([f"setblock {x + DX} {y} {z + DZ} {b}" for (x, y, z), b, *_ in D["observations"]])
    cmds(["gamemode survival tester1", "effect clear tester1", "clear tester1"])


def stage():
    sx, sy, sz = ORIGIN[0] + DX, ORIGIN[1], ORIGIN[2] + DZ
    bot.py.call("stopPathing")
    p = {}
    for _ in range(3):
        rcon.cmd(f"tp tester1 {sx} {sy} {sz} -90 0", allow_reject=True)
        for _ in range(20):
            time.sleep(0.5)
            p = bot.py.call("getSelfPose")
            if abs(p["x"] - sx) < 0.01 and abs(p["z"] - sz) < 0.01 and abs(p["y"] - sy) < 0.01 and p.get("onGround"):
                time.sleep(0.8)
                return p
    raise RuntimeError(f"stage not confirmed: {p}")


env = dict(os.environ, MSYS_NO_PATHCONV="1")
subprocess.run(["docker", "cp", os.path.join(HERE, "ladder_probe_inner.py"),
                "uctest-mc-tester1:/mc-data/ladder_probe_inner.py"], check=True, env=env)
bot.py.call("setBuildPolicy", False, False, False)
rows = []
for run in range(1, RUNS + 1):
    build()
    stage()
    r = subprocess.run(["docker", "exec", "uctest-mc-tester1", "python3", "/mc-data/ladder_probe_inner.py",
                        str(GOAL[0] + DX), str(GOAL[1]), str(GOAL[2] + DZ), "6"],
                       capture_output=True, text=True, timeout=70, env=env)
    trace = [json.loads(l) for l in r.stdout.splitlines() if l.startswith("{")]
    time.sleep(0.5)
    st = bot.py.call("pathStatus")
    p = bot.py.call("getSelfPose")
    maxx = max((t["pos"][0] - DX for t in trace), default=None)
    row = {"run": run, "outcome": st.get("outcome"), "arrived": st.get("arrived"), "inCell": st.get("inCell"),
           "feetCell": st.get("feetCell"), "maxXReal": None if maxx is None else round(maxx, 3),
           "finalReal": [round(p["x"] - DX, 3), round(p["y"], 3), round(p["z"] - DZ, 3)],
           "fell": p["y"] < 33.5}
    rows.append(row)
    print(json.dumps(row), flush=True)
    bot.py.call("stopPathing")
bot.py.call("setBuildPolicy", False, True, True)
print(json.dumps({"summary": {"arrived": sum(1 for r in rows if r["arrived"]), "fell": sum(r["fell"] for r in rows),
                              "maxXReal": max((r["maxXReal"] or 0) for r in rows)}}))
