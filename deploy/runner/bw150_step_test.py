"""bw-150 near-void rising step, rebuilt from the live receipts (2026-11-27). See
artifacts/bw150-20261127/FIXTURE.md.

The live columns (bw150-escape-map-all.json levels y10..12, bw150-escape-survey.json level y13) are
rebuilt at the SAME y with x/z shifted (real -> bench: x + DX, z + DZ), so the void under them is the
same ~70-block fall to the superflat floor. Each run:
  1. rebuild the area, place the white wool the bot placed live (-1361,11,574);
  2. stage at the live settle pose (-1360.38384, 12, 574.4628) -- near the wool's east edge, NOT the
     cell centre -- and verify it;
  3. gotoCell(-1360,13,574) with the live arguments, inside the client container, 50 ms trace
     (ladder_probe_inner.py) for SECS seconds;
  4. verdict: 'arrived' (settled in cell -1360,13,574), 'fell' (pose below y 9 at any point), or 'stuck'.
Stairs and ladders take their default block states: the receipts record ids only. Leaves are
persistent so they do not decay mid-run.

    python deploy/runner/bw150_step_test.py [runs] [secs] [stage yaw]
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
ART = os.path.join(HERE, "artifacts", "bw150-20261127")
DX, DZ = 2361, 426                      # real -1361,574 -> bench 1000,1000
RUNS = int(sys.argv[1]) if len(sys.argv) > 1 else 5
SECS = float(sys.argv[2]) if len(sys.argv) > 2 else 8.0
# Facing at the stage. The live yaw is not in the receipts; the bot had just walked NORTH onto the wool.
# 0 = south (solid side), 180 = north (void), 90 = west (void). The c3843ab2 trace takes off along the
# staged facing before turning to the target, so the facing decides which side the take-off drifts to.
YAW = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
STAGE = (-1360.38384, 12.0, 574.4628)
TARGET = (-1360, 13, 574)
WOOL = (-1361, 11, 574)

# The receipts name blocks in the client's (Russian) language; these are those literal names.
NAMES = {"белая шерсть": "minecraft:white_wool", "доски из тёмного дуба": "minecraft:dark_oak_planks",
         "дубовые листья": "minecraft:oak_leaves[persistent=true]", "еловые доски": "minecraft:spruce_planks",
         "еловые ступеньки": "minecraft:spruce_stairs", "полированный андезит": "minecraft:polished_andesite",
         "еловое бревно": "minecraft:spruce_log", "каменные кирпичи": "minecraft:stone_bricks",
         "лестница": "minecraft:ladder"}

rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")


def cmds(lines):
    for c in lines:
        rcon.cmd(c, allow_reject=True)


def grid(fname, levels):
    d = json.load(open(os.path.join(ART, fname), encoding="utf-8"))
    out = []
    for lv in levels:
        for row_i, row in enumerate(d["levels"][str(lv)]):
            for col_i, ch in enumerate(row.split(" ")):
                if ch in d["legend"] and ch != ".":
                    out.append((d["x_first"] + col_i, lv, d["z_first"] + row_i, NAMES[d["legend"][ch]]))
    return out


BLOCKS = grid("bw150-escape-map-all.json", (10, 11, 12)) + grid("bw150-escape-survey.json", (13,))


def b(x, y, z):
    return x + DX, y, z + DZ


def build():
    x0, z0 = b(-1366, 0, 570)[0], b(0, 0, 570)[2]
    cmds([f"forceload add {x0 - 4} {z0 - 4} {x0 + 16} {z0 + 16}",
          f"fill {x0} 4 {z0} {x0 + 11} 18 {z0 + 11} minecraft:air"])
    cmds([f"setblock {b(x, y, z)[0]} {y} {b(x, y, z)[2]} {blk}" for x, y, z, blk in BLOCKS])
    cmds([f"setblock {b(*WOOL)[0]} {WOOL[1]} {b(*WOOL)[2]} minecraft:white_wool",
          "gamemode survival tester1", "effect clear tester1"])


def stage():
    sx, sy, sz = b(STAGE[0], STAGE[1], STAGE[2])
    bot.py.call("stopPathing")
    p = {}
    for _ in range(3):
        rcon.cmd(f"tp tester1 {sx} {sy} {sz} {YAW} 0", allow_reject=True)
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
tx, ty, tz = b(*TARGET)
rows = []
for run in range(1, RUNS + 1):
    build()
    p0 = stage()
    r = subprocess.run(["docker", "exec", "uctest-mc-tester1", "python3", "/mc-data/ladder_probe_inner.py",
                        str(tx), str(ty), str(tz), str(SECS)], capture_output=True, text=True,
                       timeout=SECS + 60, env=env)
    trace = [json.loads(l) for l in r.stdout.splitlines() if l.startswith("{")]
    with open(os.path.join(ART, f"trace_yaw{int(YAW)}_run{run}.jsonl"), "w", encoding="utf-8") as f:
        f.write(r.stdout)
    fell = any(t["pos"][1] < 9 for t in trace)
    p = bot.py.call("getSelfPose")
    st = bot.py.call("pathStatus")
    cell = [math.floor(p["x"]), math.floor(p["y"] + 0.1251), math.floor(p["z"])]
    verdict = "fell" if fell or p["y"] < 9 else (
        "arrived" if cell == [tx, ty, tz] and p.get("onGround") else "stuck")
    row = {"run": run, "yaw": YAW, "verdict": verdict, "stage": [round(p0["x"], 5), p0["y"], round(p0["z"], 4)],
           "final": [round(p["x"], 3), round(p["y"], 3), round(p["z"], 3)], "cell": cell,
           "arrived": st.get("arrived"), "inCell": st.get("inCell"), "lastPlanEnd": st.get("lastPlanEnd"),
           "lastPlanComplete": st.get("lastPlanComplete"), "traceRows": len(trace)}
    rows.append(row)
    print(json.dumps(row), flush=True)
    bot.py.call("stopPathing")
    if verdict == "fell":
        time.sleep(3.0)
        rcon.cmd("kill tester1", allow_reject=True)      # respawn cleanly for the next run
        time.sleep(4.0)
print(json.dumps({"summary": {v: sum(1 for r in rows if r["verdict"] == v) for v in ("arrived", "fell", "stuck")}}))
sys.exit(0 if all(r["verdict"] == "arrived" for r in rows) else 1)
