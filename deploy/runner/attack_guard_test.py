"""tapAttackGuarded: one vanilla attack press only at a named target, checked at the call and again at
vanilla's doAttack (2026-11-27). Component fixture on the flat stand, the normal client, two testers.

tester1 presses; tester2 stands 2.5 blocks in front (resistance, so it lives) as the player target; a
NoAI pig beside it is the wrong target; a chest behind tester1 opens a screen. Teams are vanilla
scoreboard teams set by rcon. Cases (each waits out vanilla's attack timer first):
  enemy      tester2 in another team, crosshair on it: accepted, dispatch guard "passed", ENTITY with
             tester2's UUID, tester2 loses health;
  ally       tester2 in our team, requireOtherTeam: suppressedBy "ally", no dispatch, no damage;
  mismatch   crosshair on the pig, target = tester2: suppressedBy "targetMismatch", no dispatch;
  noteam     tester2 in no team: requireOtherTeam -> "teamUnknown"; without it -> accepted, passed;
  cooldown   a plain miss swing first (vanilla's timer -> 10), then the guarded press: "cooldown";
  gui        a chest screen open: "gui"; closed afterwards;
  change     crosshair on tester2 at the call, tester2 teleported away right after it, repeated: each
             dispatch is either refused "targetChanged" or, if the press was consumed first, attacked
             tester2 -- never anything else (the race decides which; both are recorded);
  plain      tapKeyReceipt("attack") is unchanged: dispatch guard "none".

    python deploy/runner/attack_guard_test.py
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
b1 = Bot("uctest-mc-tester1", "tester1", rcon)
b2 = Bot("uctest-mc-tester2", "tester2", rcon)
b1.ensure_in_game("test-server")
b2.ensure_in_game("test-server")


def cmds(lines):
    for c in lines:
        rcon.cmd(c, allow_reject=True)


def place_tester2():
    cmds([f"tp tester2 {CX + 0.5} {STAND_Y} {CZ + 3.0} 180 0"])
    time.sleep(0.8)


cmds([f"forceload add {CX - 8} {CZ - 8} {CX + 8} {CZ + 8}",
      f"fill {CX - 6} {F} {CZ - 6} {CX + 6} {F} {CZ + 6} minecraft:stone",
      f"fill {CX - 6} {STAND_Y} {CZ - 6} {CX + 6} {STAND_Y + 4} {CZ + 6} minecraft:air",
      f"kill @e[type=pig,x={CX},y={STAND_Y},z={CZ},distance=..20]",
      f"setblock {CX} {STAND_Y} {CZ - 2} minecraft:chest",
      "gamemode survival tester1", "gamemode survival tester2", "clear tester1",
      "effect give tester2 minecraft:resistance 600 1 true", "effect give tester2 minecraft:regeneration 600 2 true",
      f"spawnpoint tester2 {CX} {STAND_Y} {CZ + 3}",
      f"tp tester1 {CX + 0.5} {STAND_Y} {CZ + 0.5} 0 0",
      f"summon pig {CX + 2.5} {STAND_Y} {CZ + 3.0} {{NoAI:1b,Health:10f}}",
      "team add uc_gray", "team add uc_red", "team join uc_gray tester1"])
place_tester2()
b1.py.call("stopPathing")
time.sleep(1.5)
uuid2 = next(p["uuid"] for p in b1.py.call("getPlayerTeams", 16)["players"] if p["name"] == "tester2")


def hp2():
    return float(b2.py.call("getGameState", False)["self"]["hp"])


def aim_tester2():
    b1.py.call("lookAtPlayer", "tester2")
    time.sleep(0.6)


def aim_pig():
    b1.py.call("lookAt", CX + 2.5, STAND_Y + 0.5, CZ + 3.0)
    time.sleep(0.6)


def guarded(other=True, wait=0.5):
    r = b1.py.call("tapAttackGuarded", uuid2, other)
    time.sleep(wait)
    ds = b1.py.call("getInputDispatches", int(r["dispatchSeq"]), 8).get("dispatches", [])
    return r, ds


checks, rows = {}, {}

# enemy
cmds(["team join uc_red tester2"]); time.sleep(1.2); aim_tester2()
h0 = hp2(); r, ds = guarded(); time.sleep(0.4); h1 = hp2()
rows["enemy"] = {"receipt": r, "dispatches": ds, "hp": [h0, h1]}
checks["enemy: accepted"] = r.get("accepted") is True and r.get("suppressedBy") == "none"
checks["enemy: dispatch passed on tester2"] = (len(ds) == 1 and ds[0].get("guard") == "passed"
                                              and ds[0]["crosshair"].get("entityUuid") == uuid2)
checks["enemy: tester2 lost health"] = h1 < h0

# ally
cmds(["team join uc_gray tester2"]); time.sleep(1.2); aim_tester2()
h0 = hp2(); r, ds = guarded(); h1 = hp2()
rows["ally"] = {"receipt": r, "dispatches": ds, "hp": [h0, h1]}
checks["ally: suppressed 'ally', no dispatch, no damage"] = (r.get("accepted") is False and r.get("suppressedBy") == "ally"
                                                              and len(ds) == 0 and h1 >= h0)

# mismatch
cmds(["team join uc_red tester2"]); time.sleep(1.2); aim_pig()
r, ds = guarded()
rows["mismatch"] = {"receipt": r, "dispatches": ds}
checks["mismatch: suppressed 'targetMismatch', no dispatch"] = (r.get("suppressedBy") == "targetMismatch" and len(ds) == 0)

# no team
cmds(["team leave tester2"]); time.sleep(1.2); aim_tester2()
r, ds = guarded(True)
rows["noteam_required"] = {"receipt": r, "dispatches": ds}
checks["noteam: requireOtherTeam -> 'teamUnknown'"] = r.get("suppressedBy") == "teamUnknown" and len(ds) == 0
time.sleep(1.2)
r, ds = guarded(False)
rows["noteam_notrequired"] = {"receipt": r, "dispatches": ds}
checks["noteam: without team check -> accepted, passed"] = (r.get("accepted") is True and len(ds) == 1
                                                            and ds[0].get("guard") == "passed")

# cooldown: a plain miss swing sets vanilla's attack timer to 10
cmds(["team join uc_red tester2"]); time.sleep(1.2)
b1.py.call("lookAt", CX + 0.5, STAND_Y + 40.0, CZ + 0.6); time.sleep(0.5)
b1.py.call("tapKeyReceipt", "attack"); time.sleep(0.1)
b1.py.call("lookAtPlayer", "tester2")
r, ds = guarded(wait=0.3)
rows["cooldown"] = {"receipt": r, "dispatches": ds}
checks["cooldown: suppressed 'cooldown'"] = r.get("suppressedBy") == "cooldown" and r.get("attackCooldown", 0) > 0

# gui: open the chest behind
time.sleep(1.0)
b1.py.call("lookAt", CX + 0.5, STAND_Y + 0.5, CZ - 1.5); time.sleep(0.5)
b1.py.call("mouseClick", "right"); time.sleep(0.8)
screen = b1.py.call("getOpenScreen")
r, ds = guarded(wait=0.3)
b1.py.call("closeOpenScreen"); time.sleep(0.5)
rows["gui"] = {"screenOpen": bool(screen.get("open", screen.get("ok"))), "receipt": r}
checks["gui: suppressed 'gui'"] = r.get("suppressedBy") == "gui"

# change: tester2 moved away right after an accepted call
change = []
for i in range(8):
    place_tester2(); time.sleep(1.0); aim_tester2()
    r = b1.py.call("tapAttackGuarded", uuid2, True)
    cmds([f"tp tester2 {CX + 4.5} {STAND_Y} {CZ - 4.0}"])
    time.sleep(0.5)
    ds = b1.py.call("getInputDispatches", int(r["dispatchSeq"]), 8).get("dispatches", [])
    change.append({"accepted": r.get("accepted"), "suppressedBy": r.get("suppressedBy"),
                   "dispatch": [{k: d.get(k) for k in ("guard", "refusedBy", "crosshair")} for d in ds]})
rows["change"] = change
checks["change: every dispatch attacked tester2 or was refused"] = all(
    all((d["guard"] == "refused") or (d["guard"] == "passed" and d["crosshair"].get("entityUuid") == uuid2)
        for d in c["dispatch"]) for c in change)
rows["change_tally"] = {
    "refused_targetChanged": sum(1 for c in change for d in c["dispatch"] if d.get("refusedBy") == "targetChanged"),
    "passed_on_tester2": sum(1 for c in change for d in c["dispatch"] if d.get("guard") == "passed"),
    "suppressed_at_call": sum(1 for c in change if not c["accepted"])}

# plain tap unchanged
place_tester2(); time.sleep(1.0); aim_tester2()
r = b1.py.call("tapKeyReceipt", "attack"); time.sleep(0.4)
ds = b1.py.call("getInputDispatches", int(r["dispatchSeq"]), 8).get("dispatches", [])
rows["plain"] = {"receipt": r, "dispatches": ds}
checks["plain tapKeyReceipt: dispatch guard 'none'"] = len(ds) == 1 and ds[0].get("guard") == "none"

print(json.dumps({"rows": rows}, default=str)[:6000])
print(json.dumps({"checks": checks}))
cmds([f"kill @e[type=pig,x={CX},y={STAND_Y},z={CZ},distance=..20]", f"setblock {CX} {STAND_Y} {CZ - 2} minecraft:air",
      "team remove uc_gray", "team remove uc_red", "effect clear tester2",
      f"tp tester2 {CX + 0.5} {STAND_Y} {CZ + 0.5}", f"forceload remove {CX - 8} {CZ - 8} {CX + 8} {CZ + 8}"])
print(json.dumps({"summary": f"{sum(checks.values())}/{len(checks)}"}))
sys.exit(0 if all(checks.values()) else 1)
