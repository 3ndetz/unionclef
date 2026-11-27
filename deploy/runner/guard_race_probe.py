"""Can a tapAttackGuarded press escape its check? (2026-11-27)

The guard expires by WORLD time (armed tick + 3). A world-time jump between the call and vanilla's
doAttack -- here rcon "time add" fired continuously, as a lag catch-up sync or a world change would --
can make the check at dispatch read "expired" and let the press through unchecked. Each round: aim
at tester2, then in ONE py4j batch the guarded press at tester2 and a turn to the pig beside it, so
a dispatch that comes after a rendered frame has the pig under the crosshair. A leak = a dispatch
with guard "expired" (or "passed") whose crosshair is not tester2.

    python deploy/runner/guard_race_probe.py [rounds] [off|timeadd|sprint]
"""
import json
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uctest.actors import Bot  # noqa: E402
from uctest.arena import STAND_Y  # noqa: E402
from uctest.harness import Rcon  # noqa: E402

ROUNDS = int(sys.argv[1]) if len(sys.argv) > 1 else 20
# off / timeadd (time of day only: the guard reads GAME time, so this was measured to do nothing) /
# sprint (server "tick sprint": game time runs ahead of the client, every sync jumps it many ticks)
MODE = sys.argv[2] if len(sys.argv) > 2 else "sprint"
JUMP = MODE != "off"
F = STAND_Y - 1
CX, CZ = 450, 450
rcon = Rcon("uctest-server")
b1 = Bot("uctest-mc-tester1", "tester1", rcon)
b2 = Bot("uctest-mc-tester2", "tester2", rcon)
b1.ensure_in_game("test-server")
b2.ensure_in_game("test-server")
for c in [f"forceload add {CX - 8} {CZ - 8} {CX + 8} {CZ + 8}",
          f"fill {CX - 6} {F} {CZ - 6} {CX + 6} {F} {CZ + 6} minecraft:stone",
          f"fill {CX - 6} {STAND_Y} {CZ - 6} {CX + 6} {STAND_Y + 4} {CZ + 6} minecraft:air",
          f"kill @e[type=pig,x={CX},y={STAND_Y},z={CZ},distance=..20]",
          "gamemode survival tester1", "gamemode survival tester2", "clear tester1", "effect clear tester2",
          "effect give tester2 minecraft:resistance 600 0 true", f"spawnpoint tester2 {CX} {STAND_Y} {CZ + 3}",
          f"tp tester1 {CX + 0.5} {STAND_Y} {CZ + 0.5} 0 0",
          f"summon pig {CX + 1.6} {STAND_Y} {CZ + 2.6} {{NoAI:1b,Health:100f,Attributes:[{{Name:\"minecraft:max_health\",Base:100}}]}}",
          "team add uc_gray", "team add uc_red", "team join uc_gray tester1", "team join uc_red tester2"]:
    rcon.cmd(c, allow_reject=True)
time.sleep(1.5)
uuid2 = next(p["uuid"] for p in b1.py.call("getPlayerTeams", 16)["players"] if p["name"] == "tester2")

stop = threading.Event()


def jumper():
    r2 = Rcon("uctest-server")
    while not stop.is_set():
        if MODE == "sprint":
            r2.cmd("tick sprint 400", allow_reject=True)
            time.sleep(0.3)
        else:
            r2.cmd("time add 100", allow_reject=True)


th = threading.Thread(target=jumper, daemon=True)
if JUMP:
    th.start()
rows = []
for i in range(ROUNDS):
    rcon.cmd(f"tp tester2 {CX + 0.5} {STAND_Y} {CZ + 3.0} 180 0", allow_reject=True)
    rcon.cmd("effect give tester2 minecraft:instant_health 1 3 true", allow_reject=True)
    time.sleep(0.8)
    b1.py.call("lookAtPlayer", "tester2")
    time.sleep(0.6)
    r, _ = b1.py.batch([("tapAttackGuarded", uuid2, True), ("lookAt", CX + 1.6, STAND_Y + 0.5, CZ + 2.6)])
    time.sleep(0.5)
    ds = b1.py.call("getInputDispatches", int(r["dispatchSeq"]), 8).get("dispatches", [])
    for d in ds:
        rows.append({"round": i + 1, "accepted": r.get("accepted"), "guard": d.get("guard"),
                     "refusedBy": d.get("refusedBy"), "crosshairType": d["crosshair"].get("type"),
                     "onTarget": d["crosshair"].get("entityUuid") == uuid2,
                     "entity": d["crosshair"].get("entityType")})
stop.set()
leaks = [x for x in rows if x["guard"] in ("expired", "passed") and not x["onTarget"]]
tally = {}
for x in rows:
    k = f'{x["guard"]}/{x["refusedBy"]}/{"target" if x["onTarget"] else x["entity"] or x["crosshairType"]}'
    tally[k] = tally.get(k, 0) + 1
if MODE == "sprint":
    rcon.cmd("tick sprint stop", allow_reject=True)
print(json.dumps({"mode": MODE, "timejump": JUMP, "rounds": ROUNDS, "dispatches": len(rows), "tally": tally, "leaks": len(leaks),
                  "leakRows": leaks[:5]}))
for c in [f"kill @e[type=pig,x={CX},y={STAND_Y},z={CZ},distance=..20]", "team remove uc_gray", "team remove uc_red",
          "time set day", f"tp tester2 {CX + 0.5} {STAND_Y} {CZ + 0.5}"]:
    rcon.cmd(c, allow_reject=True)
sys.exit(1 if leaks else 0)
