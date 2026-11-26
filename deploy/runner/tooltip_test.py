"""getOpenScreenTooltip contract test on the flat stand (2026-11-26).

A chest beside the bot holds a named sword with two lore lines; the bot opens it with an ordinary
right click (lookAt + mouseClick), then the test reads:
  slot 0 with the current syncId   -> ok, lines contain the name and both lore lines
  an empty chest slot              -> ok, empty=true, lines=[]
  slot 0 with syncId+1             -> ok=false reason "syncId changed"
  slot 9999                        -> ok=false reason "slot out of range"
  after closing the screen         -> ok=false reason "no screen"
and that nothing in the chest moved (read-only). Prints one JSON line per check, exit 1 on a miss.

    python deploy/runner/tooltip_test.py
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uctest.actors import Bot  # noqa: E402
from uctest.arena import STAND_Y  # noqa: E402
from uctest.harness import Rcon  # noqa: E402

X, Z = 300, 300                      # away from the course arena around the origin
LORE = ["Cost: 7 test tokens", "Second lore line"]
NAME = "Tooltip Probe Sword"

rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")
for c in [f"forceload add {X - 4} {Z - 4} {X + 4} {Z + 4}",
          f"fill {X - 3} {STAND_Y - 1} {Z - 3} {X + 3} {STAND_Y - 1} {Z + 3} minecraft:stone",
          f"fill {X - 3} {STAND_Y} {Z - 3} {X + 3} {STAND_Y + 3} {Z + 3} minecraft:air",
          "gamemode survival tester1",
          f"tp tester1 {X + 0.5} {STAND_Y} {Z + 0.5} 0 0"]:
    rcon.cmd(c, allow_reject=True)
lore = ",".join(json.dumps(s) for s in LORE)
rcon.cmd(f'setblock {X} {STAND_Y} {Z + 2} minecraft:chest{{Items:[{{Slot:0b,id:"minecraft:diamond_sword",'
         f'count:1,components:{{"minecraft:custom_name":{json.dumps(NAME)},"minecraft:lore":[{lore}]}}}}]}}',
         allow_reject=True)
time.sleep(1.5)
bot.py.call("closeOpenScreen")
bot.py.call("lookAt", X + 0.5, STAND_Y + 0.5, Z + 2.5)
time.sleep(0.5)
bot.py.call("mouseClick", "right")
screen = {}
for _ in range(20):
    time.sleep(0.5)
    screen = bot.py.call("getOpenScreen")
    if screen.get("open") and screen.get("syncId", 0) != 0:
        break
sync = screen.get("syncId")
before = [(s.get("item"), s.get("count")) for s in (screen.get("slots") or [])[:27]]

fails = 0


def check(label, ok, got):
    global fails
    fails += 0 if ok else 1
    print(json.dumps({"check": label, "pass": bool(ok), "got": got}, default=str), flush=True)


check("chest opened by right click", screen.get("open") and sync not in (None, 0),
      {"screen": screen.get("screen"), "syncId": sync})
r = bot.py.call("getOpenScreenTooltip", 0, sync)
lines = r.get("lines") or []
check("slot 0 tooltip lines", r.get("ok") is True and lines and lines[0] == NAME
      and all(any(l == x for l in lines) for x in LORE), r)
r = bot.py.call("getOpenScreenTooltip", 5, sync)
check("empty slot", r.get("ok") is True and r.get("empty") is True and not r.get("lines"), r)
r = bot.py.call("getOpenScreenTooltip", 0, sync + 1)
check("stale syncId refused", r.get("ok") is False and r.get("reason") == "syncId changed", r)
r = bot.py.call("getOpenScreenTooltip", 9999, sync)
check("slot out of range refused", r.get("ok") is False and r.get("reason") == "slot out of range", r)
r = bot.py.call("getOpenScreenTooltip", 0, -1)
check("syncId -1 skips the check", r.get("ok") is True and (r.get("lines") or [None])[0] == NAME, r)
after = [(s.get("item"), s.get("count")) for s in (bot.py.call("getOpenScreen").get("slots") or [])[:27]]
check("read-only: chest unchanged", after == before, {"before": before[:3], "after": after[:3]})
bot.py.call("closeOpenScreen")
time.sleep(0.8)
r = bot.py.call("getOpenScreenTooltip", 0, sync)
check("closed screen refused", r.get("ok") is False and r.get("reason") == "no screen", r)
rcon.cmd(f"setblock {X} {STAND_Y} {Z + 2} minecraft:air", allow_reject=True)
rcon.cmd(f"forceload remove {X - 4} {Z - 4} {X + 4} {Z + 4}", allow_reject=True)
print(json.dumps({"summary": f"{8 - fails}/8 pass"}), flush=True)
sys.exit(1 if fails else 0)
