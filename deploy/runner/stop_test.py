"""stopPathing during an exact-cell route that can only be partial: does anything resume? (2026-11-26)

Live (bw-52, 114c3102): after stopPathing on an unfinished gotoCell, busy/navActive still read true
300 ms later; a later snapshot was clean. Host side sets the course up over rcon; each round runs
stop_probe_inner.py INSIDE the client container (direct py4j, ~50 ms polling), so the numbers are
not the bench's docker-exec latency. Per round: how long the stopPathing call itself took, the drift
WHILE it ran (pose before -> pose at ack), the drift AFTER the ack, when each flag went false after
the ack, and whether navActive or motion came back. Odd rounds stop 150 ms in (search still running).
Exit 1 if anything resumed or the body kept moving after the ack.

    python deploy/runner/stop_test.py [rounds]
"""
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uctest.actors import Bot  # noqa: E402
from uctest.arena import STAND_Y  # noqa: E402
from uctest.harness import Rcon  # noqa: E402

ROUNDS = int(sys.argv[1]) if len(sys.argv) > 1 else 4
CX, CZ = 500, 500
F = STAND_Y - 1
HERE = os.path.dirname(os.path.abspath(__file__))
rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")
for c in [f"forceload add {CX - 22} {CZ - 12} {CX + 12} {CZ + 12}",
          f"fill {CX - 20} {F} {CZ - 10} {CX + 10} {F} {CZ + 10} minecraft:stone",
          f"fill {CX - 20} {STAND_Y} {CZ - 10} {CX + 10} {STAND_Y + 4} {CZ + 10} minecraft:air",
          f"fill {CX - 17} {STAND_Y - 1} {CZ - 1} {CX - 15} {STAND_Y + 2} {CZ + 1} minecraft:bedrock",
          f"fill {CX - 16} {STAND_Y} {CZ} {CX - 16} {STAND_Y + 1} {CZ} minecraft:air",
          "gamemode survival tester1"]:
    rcon.cmd(c, allow_reject=True)
subprocess.run(["docker", "cp", os.path.join(HERE, "stop_probe_inner.py"),
                "uctest-mc-tester1:/mc-data/stop_probe_inner.py"], check=True,
               env=dict(os.environ, MSYS_NO_PATHCONV="1"))
sealed = (CX - 16, STAND_Y, CZ)
bad = False
for rnd in range(ROUNDS):
    after = 0.15 if rnd % 2 else 1.5
    bot.py.call("stopPathing")
    rcon.cmd(f"tp tester1 {CX + 0.5} {STAND_Y} {CZ + 0.5} 90 0", allow_reject=True)
    for _ in range(20):                              # the teleport has landed and the floor is loaded
        time.sleep(0.5)
        p = bot.py.call("getSelfPose")
        if abs(p["x"] - (CX + 0.5)) < 0.3 and abs(p["z"] - (CZ + 0.5)) < 0.3 and p.get("onGround"):
            break
    r = subprocess.run(["docker", "exec", "uctest-mc-tester1", "python3", "/mc-data/stop_probe_inner.py",
                        str(sealed[0]), str(sealed[1]), str(sealed[2]), str(after)],
                       capture_output=True, text=True, timeout=60, env=dict(os.environ, MSYS_NO_PATHCONV="1"))
    line = (r.stdout.strip().splitlines() or ["{}"])[-1]
    try:
        res = json.loads(line)
    except json.JSONDecodeError:
        res = {"error": (r.stderr or line)[-300:]}
    res["round"] = rnd
    kept_moving = res.get("max_speed_after_500ms", 0) > 0.05
    resumed = res.get("navActive_came_back") or res.get("motion_came_back")
    res["pass"] = not (kept_moving or resumed) and "error" not in res
    bad |= not res["pass"]
    print(json.dumps(res), flush=True)
rcon.cmd(f"forceload remove {CX - 22} {CZ - 12} {CX + 12} {CZ + 12}", allow_reject=True)
print(json.dumps({"summary": "stopped and stayed stopped" if not bad else "MOVED OR RESUMED", "rounds": ROUNDS}))
sys.exit(1 if bad else 0)
