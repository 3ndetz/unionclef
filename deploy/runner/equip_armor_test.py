#!/usr/bin/env python3
"""Does the bot put on armor it is holding? A STEP test, not an outcome test.

Every pvp/mob course kits the bot with gear already worn (CHECKLIST rule six), so the
whole "find the piece in the pack and move it to the right body slot" path was invisible
to the bench -- and on 1.21.11 it was dead for as long as the port existed
(isArmorEquipped returned false for everything, EquipArmorTask forced every piece into the
CHEST slot; fixed in 7aea6ea4, first executed 2026-09-10). This script asks for the step:

    empty body slots, a full iron set in the HOTBAR (given, not worn), `@equip iron`
    -> the server must read head/chest/legs/feet = the four iron pieces.

It is an inventory outcome, so it survives a starved host, and "the bot started with
NOTHING worn" is its own gate, so a kit that silently failed cannot pass it.

    python3 deploy/runner/equip_armor_test.py            # exit 0 = PASS

Needs the flat stand up (uctest-server + uctest-mc-tester1). Talks py4j through
`docker exec` like every other runner script, rcon through the server container.
"""
import functools
import json
import subprocess
import sys
import time

print = functools.partial(print, flush=True)
SERVER = "uctest-server"
C1 = "uctest-mc-tester1"
BOT = "tester1"
PIECES = ("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots")
SLOTS = ("head:", "chest:", "legs:", "feet:")

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame(),"active":mc.hasActiveTask()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chat": out={"chat":[str(l) for l in mc.getRecentChat(req.get("n",15))]}
print(json.dumps(out,default=str)); gw.close()
"""


def sh(a, to=40):
    return subprocess.run(a, capture_output=True, text=True, timeout=to)


def py4j(op, to=30, **kw):
    r = sh(["docker", "exec", C1, "python3", "-c", SNIP, json.dumps({"op": op, **kw})], to)
    if r.returncode != 0:
        raise RuntimeError(f"{op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])


def rcon(c):
    return sh(["docker", "exec", SERVER, "rcon-cli", c]).stdout.strip()


def equipment():
    return rcon(f"data get entity {BOT} equipment")


def main():
    st = py4j("state")
    if not st["inGame"]:
        print("not in game: connecting to test-server")
        py4j("connect", ip="test-server")
        t0 = time.time()
        while time.time() - t0 < 120 and not py4j("state")["inGame"]:
            time.sleep(5)
        if not py4j("state")["inGame"]:
            print("FAIL: bot never reached the server")
            return 2
    py4j("cmd", c="@stop")
    time.sleep(2)
    rcon(f"gamemode survival {BOT}")
    rcon(f"clear {BOT}")
    for p in PIECES:
        rcon(f"give {BOT} minecraft:{p}")
    time.sleep(1)
    before = equipment()
    print("equipment before:", before)
    if any(s in before for s in SLOTS):
        print("FAIL: body slots not empty before the run -- the kit is the wrong shape")
        return 1

    py4j("cmd", c="@equip iron")
    t0 = time.time()
    active = True
    while time.time() - t0 < 60:
        time.sleep(2)
        active = py4j("state")["active"]
        if not active:
            break
    after = equipment()
    print(f"task active after {time.time() - t0:.0f}s: {active}")
    print("equipment after:", after)
    missing = [s for s, p in zip(SLOTS, PIECES) if not (s in after and p in after)]
    if missing:
        print("chat:", py4j("chat", n=12)["chat"][-8:])
        print("FAIL: not worn:", missing)
        return 1
    if active:
        print("FAIL: everything is worn but the task never finished -- isArmorEquipped does not see it")
        return 1
    print("PASS: full iron set worn in its own slots, task finished")
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
