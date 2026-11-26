"""MCP parity for getInventoryFull / getEntitiesInfo / getCrosshairTarget / getPlayerTeams (2026-11-27).

Sets up a non-empty state on the flat stand (a few items in the pack and one held, a dropped item and
a pig close by, the crosshair on a block), then runs mcp_parity_inner.py inside the client container,
which calls each tool over py4j and over MCP and compares the results. Exit 1 on a mismatch.

    python deploy/runner/mcp_parity_test.py
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

HERE = os.path.dirname(os.path.abspath(__file__))
F = STAND_Y - 1
CX, CZ = 450, 450
rcon = Rcon("uctest-server")
bot = Bot("uctest-mc-tester1", "tester1", rcon)
bot.ensure_in_game("test-server")
for c in [f"forceload add {CX - 8} {CZ - 8} {CX + 8} {CZ + 8}",
          f"fill {CX - 6} {F} {CZ - 6} {CX + 6} {F} {CZ + 6} minecraft:stone",
          f"fill {CX - 6} {STAND_Y} {CZ - 6} {CX + 6} {STAND_Y + 4} {CZ + 6} minecraft:air",
          f"setblock {CX} {STAND_Y} {CZ + 2} minecraft:oak_planks",
          "gamemode survival tester1", "clear tester1",
          "give tester1 minecraft:cobblestone 32", "give tester1 minecraft:iron_pickaxe 1",
          "give tester1 minecraft:bread 5",
          f"tp tester1 {CX + 0.5} {STAND_Y} {CZ + 0.5} 0 30",
          f"kill @e[type=item,x={CX},y={STAND_Y},z={CZ},distance=..20]",
          f"kill @e[type=pig,x={CX},y={STAND_Y},z={CZ},distance=..20]",
          f'summon item {CX - 2.3} {STAND_Y} {CZ - 1.7} {{Item:{{id:"minecraft:diamond",count:1}},PickupDelay:32767s,Age:-32768s}}',
          f"summon pig {CX + 3} {STAND_Y} {CZ - 3} {{NoAI:1b}}"]:
    rcon.cmd(c, allow_reject=True)
time.sleep(2.0)
bot.py.call("stopPathing")
bot.py.call("lookAt", CX + 0.5, STAND_Y + 0.5, CZ + 2.5)     # the crosshair on the planks
time.sleep(1.0)
env = dict(os.environ, MSYS_NO_PATHCONV="1")
subprocess.run(["docker", "cp", os.path.join(HERE, "mcp_parity_inner.py"),
                "uctest-mc-tester1:/mc-data/mcp_parity_inner.py"], check=True, env=env)
r = subprocess.run(["docker", "exec", "uctest-mc-tester1", "python3", "/mc-data/mcp_parity_inner.py"],
                   capture_output=True, text=True, timeout=120, env=env)
print(r.stdout.strip())
if r.returncode != 0:
    print(r.stderr[-500:])
rows = [json.loads(l) for l in r.stdout.splitlines() if l.startswith("{")]
for c in [f"kill @e[type=pig,x={CX},y={STAND_Y},z={CZ},distance=..20]",
          f"kill @e[type=item,x={CX},y={STAND_Y},z={CZ},distance=..20]",
          f"forceload remove {CX - 8} {CZ - 8} {CX + 8} {CZ + 8}"]:
    rcon.cmd(c, allow_reject=True)


# Teams, read-only: tester1 in uc_gray; tester2 with no team, then another team, then the same one.
# The expected value of every field is what the server was told by these rcon lines, nothing else.
def teams_row(label, want_state, want_same):
    time.sleep(1.0)
    r = bot.py.call("getPlayerTeams", 16)
    t2 = next((p for p in r.get("players", []) if p.get("name") == "tester2"), None)
    t1 = next((p for p in r.get("players", []) if p.get("name") == "tester1"), None)
    st = r.get("self", {}).get("team") or {}
    ok = (bool(r.get("ok")) and t1 is not None and t2 is not None and t1.get("self") is True
          and st.get("name") == "uc_gray" and st.get("color") == "gray"
          and "team" in t2 and t2.get("teamState") == want_state and t2.get("sameTeamAsSelf") == want_same
          and (want_state == "assigned") == (t2.get("team") is not None))
    print(json.dumps({"teams": label, "ok": ok, "tester2": t2, "selfTeam": r.get("self", {}).get("team")}))
    return ok


cmds = ["team add uc_gray", "team add uc_red", "team modify uc_gray color gray",
        "team modify uc_red color red", "team leave tester2", "team join uc_gray tester1"]
for c in cmds:
    rcon.cmd(c, allow_reject=True)
team_ok = [teams_row("tester2 no team", "none", None)]
rcon.cmd("team join uc_red tester2", allow_reject=True)
team_ok.append(teams_row("tester2 other team", "assigned", False))
rcon.cmd("team join uc_gray tester2", allow_reject=True)
team_ok.append(teams_row("tester2 same team", "assigned", True))
for c in ["team remove uc_gray", "team remove uc_red"]:
    rcon.cmd(c, allow_reject=True)

ok = len(rows) == 4 and all(x["match"] for x in rows) and all(team_ok)
print(json.dumps({"summary": f"{sum(1 for x in rows if x['match'])}/4 match, teams {sum(team_ok)}/3"}))
sys.exit(0 if ok else 1)
