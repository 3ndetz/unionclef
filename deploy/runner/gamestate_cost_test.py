"""getGameState(includeBeds=false): same self/players, no bed scan, and what it saves on the client thread
(2026-11-27).

Stands tester1 near two beds, then:
  1. default getGameState() keeps its keys (inGame, self, players, playerCount, beds) and finds the beds;
  2. getGameState(false) has the same self keys and the same player names, no "beds" key, bedsIncluded=false;
  3. N calls of each at ~4 Hz, getGameStateCost(reset) before and after: client-thread mean/max per path.
Exit 1 if 1 or 2 fails. The cost numbers are recorded, not gated.

    python deploy/runner/gamestate_cost_test.py [N]
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uctest.actors import Bot  # noqa: E402
from uctest.arena import STAND_Y  # noqa: E402
from uctest.harness import Rcon  # noqa: E402

N = int(sys.argv[1]) if len(sys.argv) > 1 else 40
F = STAND_Y - 1
CX, CZ = 450, 450
rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")
for c in [f"forceload add {CX - 8} {CZ - 8} {CX + 8} {CZ + 8}",
          f"fill {CX - 6} {F} {CZ - 6} {CX + 6} {F} {CZ + 6} minecraft:stone",
          f"fill {CX - 6} {STAND_Y} {CZ - 6} {CX + 6} {STAND_Y + 4} {CZ + 6} minecraft:air",
          f"setblock {CX + 3} {STAND_Y} {CZ} minecraft:red_bed[facing=south,part=foot]",
          f"setblock {CX + 3} {STAND_Y} {CZ + 1} minecraft:red_bed[facing=south,part=head]",
          f"setblock {CX - 3} {STAND_Y} {CZ} minecraft:blue_bed[facing=south,part=foot]",
          f"setblock {CX - 3} {STAND_Y} {CZ + 1} minecraft:blue_bed[facing=south,part=head]",
          f"tp tester1 {CX + 0.5} {STAND_Y} {CZ - 2.5} 0 0"]:
    rcon.cmd(c, allow_reject=True)
time.sleep(2.0)
bot.py.call("stopPathing")

full = bot.py.call("getGameState")
lite = bot.py.call("getGameState", False)
names = lambda s: sorted(p.get("name") for p in s.get("players", []))
checks = {
    "default keys": sorted(full) == sorted(["inGame", "self", "players", "playerCount", "beds"]),
    "default finds beds": len(full.get("beds", [])) >= 2,
    "noBeds has no beds key": "beds" not in lite and lite.get("bedsIncluded") is False,
    "same self keys": sorted(full.get("self", {})) == sorted(lite.get("self", {})),
    "same players": names(full) == names(lite) and full.get("playerCount") == lite.get("playerCount"),
}
print(json.dumps({"checks": checks, "beds": full.get("beds")}))

bot.py.call("getGameStateCost", True)
for include in (True, False):
    for _ in range(N):
        bot.py.call("getGameState", include)
        time.sleep(0.25)
cost = bot.py.call("getGameStateCost", True)
print(json.dumps({"cost": cost, "n": N}))
for c in [f"fill {CX - 6} {STAND_Y} {CZ - 6} {CX + 6} {STAND_Y + 4} {CZ + 6} minecraft:air",
          f"forceload remove {CX - 8} {CZ - 8} {CX + 8} {CZ + 8}"]:
    rcon.cmd(c, allow_reject=True)
sys.exit(0 if all(checks.values()) else 1)
