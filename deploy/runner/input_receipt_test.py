"""tapKeyReceipt / getInputDispatches: a single attack press, accepted or suppressed, and what vanilla's
attack saw at dispatch (2026-11-27). Component fixture on the flat stand, the normal client.

bw-298 had tapKey('attack') return true several times with no attributed hit. tapKey says true
whether or not the press was suppressed (InputControls.tryPress is a no-op while the same input waits
for its release), and says nothing about the crosshair when vanilla's doAttack consumed the press.
Cases:
  entity   aim at a NoAI pig 2.5 blocks away, one receipt press: accepted, crosshair ENTITY at the
           call and at dispatch, not refused by cooldown, vanillaReturn false (doAttack returns true
           only for a block it starts breaking); the pig's health drops (server-side truth);
  miss     aim at the sky: accepted, dispatch crosshair MISS, not refused (a miss swing);
  double   two receipt presses back to back: if they land in one tick the second is suppressed
           (recorded, not forced -- RPC timing decides), and only accepted presses dispatch.
tapKey itself is unchanged and not called here.

    python deploy/runner/input_receipt_test.py
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
CX, CZ = 450, 450
rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")
for c in [f"forceload add {CX - 8} {CZ - 8} {CX + 8} {CZ + 8}",
          f"fill {CX - 6} {F} {CZ - 6} {CX + 6} {F} {CZ + 6} minecraft:stone",
          f"fill {CX - 6} {STAND_Y} {CZ - 6} {CX + 6} {STAND_Y + 4} {CZ + 6} minecraft:air",
          f"kill @e[type=pig,x={CX},y={STAND_Y},z={CZ},distance=..20]",
          "gamemode survival tester1", "clear tester1",
          f"tp tester1 {CX + 0.5} {STAND_Y} {CZ + 0.5} 0 0",
          f"summon pig {CX + 0.5} {STAND_Y} {CZ + 3.0} {{NoAI:1b,Health:10f}}"]:
    rcon.cmd(c, allow_reject=True)
time.sleep(2.0)
bot.py.call("stopPathing")


def pig_hp():
    for e in bot.py.call("getEntitiesInfo", 16):
        if "pig" in str(e.get("type", "")).lower():
            return float(e.get("health", "nan"))
    return None


def press_and_read():
    r = bot.py.call("tapKeyReceipt", "attack")
    time.sleep(0.4)
    d = bot.py.call("getInputDispatches", int(r["dispatchSeq"]), 8)
    return r, d.get("dispatches", [])


checks, rows = {}, {}

bot.py.call("lookAt", CX + 0.5, STAND_Y + 0.6, CZ + 3.0)
time.sleep(1.2)                                   # also lets the attack cooldown run out
hp0 = pig_hp()
r, ds = press_and_read()
hp1 = pig_hp()
rows["entity"] = {"receipt": r, "dispatches": ds, "pigHp": [hp0, hp1]}
checks["entity: accepted, ENTITY at call"] = r.get("accepted") is True and r["crosshairAtCall"].get("type") == "ENTITY"
checks["entity: one dispatch, ENTITY, not refused"] = (
    len(ds) == 1 and ds[0]["crosshair"].get("type") == "ENTITY" and ds[0].get("refusedByCooldown") is False
    and ds[0].get("vanillaReturn") is False)
checks["entity: pig health dropped"] = hp0 is not None and hp1 is not None and hp1 < hp0

bot.py.call("lookAt", CX + 0.5, STAND_Y + 40.0, CZ + 0.6)   # all floats: py4j picks lookAt(double x3)
time.sleep(1.2)
r, ds = press_and_read()
rows["miss"] = {"receipt": r, "dispatches": ds}
checks["miss: accepted, MISS dispatched, not refused"] = (
    r.get("accepted") is True and len(ds) == 1 and ds[0]["crosshair"].get("type") == "MISS"
    and ds[0].get("refusedByCooldown") is False)

time.sleep(1.2)
seq0 = bot.py.call("getInputDispatches", 0, 1)["lastSeq"]
a = bot.py.call("tapKeyReceipt", "attack")
b = bot.py.call("tapKeyReceipt", "attack")
time.sleep(0.4)
ds = bot.py.call("getInputDispatches", int(seq0), 8)["dispatches"]
accepted = [x.get("accepted") for x in (a, b)]
rows["double"] = {"accepted": accepted, "suppressedBy": [a.get("suppressedBy"), b.get("suppressedBy")],
                  "ticks": [a.get("tick"), b.get("tick")], "dispatches": len(ds)}
checks["double: dispatches == accepted presses"] = len(ds) == sum(1 for x in accepted if x)
checks["double: suppressed only in the same tick"] = (accepted[1] is True) or (a.get("tick") == b.get("tick"))

print(json.dumps({"rows": rows}, default=str)[:4000])
print(json.dumps({"checks": checks}))
for c in [f"kill @e[type=pig,x={CX},y={STAND_Y},z={CZ},distance=..20]",
          f"forceload remove {CX - 8} {CZ - 8} {CX + 8} {CZ + 8}"]:
    rcon.cmd(c, allow_reject=True)
print(json.dumps({"summary": f"{sum(checks.values())}/{len(checks)}"}))
sys.exit(0 if all(checks.values()) else 1)
