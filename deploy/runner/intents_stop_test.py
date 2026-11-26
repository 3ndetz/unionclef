"""stopIntents / getIntentStatus: drop follow, flee and combat intents, and nothing else (2026-11-27).

Live bw-269: attackPlayer left isAttacking true (a 30 s ThreatTable mark) while pursuit stood still,
and stopPathing does not clear such marks. On the flat stand with tester2 close by (resistance, so
nothing gets hurt):
  1. stopPathing keeps its semantics: an attackPlayer mark survives it (recorded and gated);
  2. attackPlayer + followPlayer, then stopIntents: before shows both, after shows none;
  3. 2 s later an independent getIntentStatus still shows none, and isAttacking is false;
  4. avoidPlayer, then stopIntents clears the avoid mark too.

    python deploy/runner/intents_stop_test.py
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
          "gamemode survival tester1", "gamemode survival tester2",
          "effect give tester2 minecraft:resistance 120 4 true",
          "effect give tester1 minecraft:resistance 120 4 true",
          f"tp tester1 {CX + 0.5} {STAND_Y} {CZ + 0.5} 0 0",
          f"tp tester2 {CX + 0.5} {STAND_Y} {CZ + 4.5} 180 0"]:
    rcon.cmd(c, allow_reject=True)
time.sleep(3.0)
bot.py.call("stopPathing")
bot.py.call("stopIntents")
time.sleep(0.5)


def status():
    s = bot.py.call("getIntentStatus")
    return {k: s.get(k) for k in ("legacyAttack", "legacyAvoid", "punkActive", "followActive",
                                  "followTarget", "userTask", "taskChain")}


def clear(s):
    return not s["legacyAttack"] and not s["legacyAvoid"] and not s["punkActive"] and not s["followActive"]


checks = {}
marked = bot.py.call("attackPlayer", "tester2")
checks["attackPlayer marked"] = bool(marked) and "tester2" in status()["legacyAttack"]
bot.py.call("stopPathing")
time.sleep(0.3)
checks["stopPathing keeps the mark"] = "tester2" in status()["legacyAttack"]

bot.py.call("followPlayer", "tester2", 2.0)
time.sleep(1.0)
r = bot.py.call("stopIntents")
before, after = r.get("before", {}), r.get("after", {})
checks["before had attack+follow"] = "tester2" in before.get("legacyAttack", []) and bool(before.get("followActive"))
checks["after clear"] = clear({k: after.get(k) for k in ("legacyAttack", "legacyAvoid", "punkActive", "followActive")})
time.sleep(2.0)
later = status()
checks["2 s later clear"] = clear(later)
checks["isAttacking false"] = bot.py.call("isAttacking", "tester2") is False

bot.py.call("avoidPlayer", "tester2")
checks["avoid marked"] = "tester2" in status()["legacyAvoid"]
bot.py.call("stopIntents")
time.sleep(1.0)
checks["avoid cleared"] = clear(status()) and bot.py.call("isAvoiding", "tester2") is False

print(json.dumps({"checks": checks, "before": before, "after": after, "later": later}))
bot.py.call("stopPathing")
for c in [f"fill {CX - 6} {STAND_Y} {CZ - 6} {CX + 6} {STAND_Y + 4} {CZ + 6} minecraft:air",
          "effect clear tester1", "effect clear tester2",
          f"forceload remove {CX - 8} {CZ - 8} {CX + 8} {CZ + 8}"]:
    rcon.cmd(c, allow_reject=True)
print(json.dumps({"summary": f"{sum(checks.values())}/{len(checks)}"}))
sys.exit(0 if all(checks.values()) else 1)
