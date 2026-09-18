"""Deterministic bench for G100: submerged with no existing air to swim to -> the bot must MAKE air.

The second 60-minute run drowned at y=0 mining diamonds: "Misc World Survival Chain: Reaching
breathable air -- Finding a reachable air pocket", the body under water among gold ore, hp
falling, finished by a zombie. GetToAirTask (movement/GetToAirTask.java) only navigates to the
nearest EXISTING breathable cell (FastNavigator.startNearest(canBreatheAt)); in a flooded pocket
capped by stone there is none reachable, so the search returns nothing every 20 ticks and the bot
drowns. A player digs up to the surface or pillars a block into a bubble; the task cannot.

Scene (flat stand, floor block top at FY=-61): a 1x1 shaft carved DOWN from the floor, filled
with water, and CAPPED with stone so no air sits above the water column -- the only air is by
digging up through the cap (or the surface two blocks above the cap). The bot starts submerged at
the bottom. A player breaks straight up and surfaces; the current task cannot and drowns.

    python deploy/runner/air_pocket_test.py            # build the scene and measure
    python deploy/runner/air_pocket_test.py --no-build

PASS = the bot's air is replenished (it reached/created air) within TIMEOUT and it did not die.
FAIL = air stays drained / hp falls / it dies. Prints the min air and min hp seen.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)

SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 760, 300
FY = -61                            # flat-world floor top; feet stand at FY+1
# BUILD UP, NOT DOWN. Below the flat floor (y < -61) is void death, so the water column and its
# cap are built ABOVE the floor. The bot stands on the floor and is submerged in a walled water
# column; a stone cap sits on top with open air above it, so plain swim-up cannot surface -- the
# only way out is to break the cap. (The first cut dug the shaft downward and the bot fell to
# y=-200 out of the world.)
COL_H = 4                           # water cells FY+1 .. FY+COL_H
CAP_Y = FY + COL_H + 1             # the stone lid over the water
SUBMERGE_Y = FY + 2               # where the bot starts, submerged mid-column
TIMEOUT = 60

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="gs": out=dict(mc.getGameState().get("self") or {})
elif op=="reset": mc.resetValues(); mc.resetRunCounters(); out={"ok":True}
print(json.dumps(out,default=str)); gw.close()
"""


def sh(a, to=60):
    return subprocess.run(a, capture_output=True, text=True, timeout=to)


def py4j(op, to=40, **kw):
    r = sh(["docker", "exec", C1, "python3", "-c", SNIP, json.dumps({"op": op, **kw})], to)
    if r.returncode != 0:
        raise RuntimeError(f"{op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])


def rcon(c, to=30):
    r = sh(["docker", "exec", SERVER, "rcon-cli", c], to)
    return (r.stdout or "").strip()


def air_and_hp():
    # air/hp from the server's entity data (client gs may not expose air)
    a = rcon(f"data get entity {BOT} Air")
    h = rcon(f"data get entity {BOT} Health")
    def num(s):
        try:
            return float(s.split("data:", 1)[1].strip().rstrip("sf"))
        except Exception:
            return None
    return num(a), num(h)


def main():
    build = "--no-build" not in sys.argv
    py4j("connect", ip="test-server")
    for _ in range(40):
        time.sleep(2)
        if py4j("state").get("inGame") and BOT in rcon("list"):
            break
    else:
        print("FAIL: bot not on test-server"); return 2
    py4j("cmd", c="@stop")
    rcon(f"tp {BOT} {X + 0.5} {FY + 1} {Z + 0.5}")
    time.sleep(5)
    if build:
        # a solid stone block FY..CAP_Y+1 around the column, then carve a 1x1 water column inside
        # it (FY+1..FY+COL_H), lid it with stone at CAP_Y, and leave open air above the lid. The
        # only escape from the column is to break the lid.
        rcon(f"fill {X - 4} {FY} {Z - 4} {X + 4} {CAP_Y + 1} {Z + 4} stone")   # solid block
        rcon(f"fill {X - 4} {CAP_Y + 2} {Z - 4} {X + 4} {CAP_Y + 5} {Z + 4} air")  # open sky above the lid
        rcon(f"fill {X} {FY + 1} {Z} {X} {FY + COL_H} {Z} water")              # the walled water column
        rcon(f"setblock {X} {CAP_Y} {Z} stone")                              # the lid over the water
    # It drowns while mining DIAMONDS -- so it has a pickaxe. Mining stone by hand underwater is
    # unrealistically slow; give the pickaxe the real scenario has.
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} iron_pickaxe")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10")
    rcon(f"tp {BOT} {X + 0.5} {SUBMERGE_Y} {Z + 0.5}")
    time.sleep(3)
    p0 = py4j("gs").get("pos")
    if not p0 or abs(float(str(p0).split(",")[1]) - SUBMERGE_Y) > 1.5:
        print(f"FAIL: the scene did not hold -- bot at {p0}, expected y~{SUBMERGE_Y}"); return 2
    print(f"  bot submerged at {p0}; lid at {X},{CAP_Y},{Z}, air above y={CAP_Y + 2}")
    py4j("reset")
    # ⛔ THE SURVIVAL CHAIN ONLY TICKS WHILE ALTOCLEF IS RUNNING. It drowns WHILE mining diamonds
    # under @gamer, so the faithful test runs @gamer -- idle (@stop'd) the chain never fires
    # GetToAirTask and the bot drowns for a reason that is not the bug under test. The drowning
    # survival chain is priority 100 and preempts @gamer's own work the moment air runs low.
    py4j("cmd", c="@gamer")
    time.sleep(1)
    rcon(f"tp {BOT} {X + 0.5} {SUBMERGE_Y} {Z + 0.5}")   # @gamer may nudge; put it back submerged
    # the survival chain runs on its own; nothing to command -- being submerged triggers GetToAir
    t0 = time.time(); min_air = 300; min_hp = 20.0; died = False; breathed = False
    while time.time() - t0 < TIMEOUT:
        time.sleep(2)
        air, hp = air_and_hp()
        if air is not None:
            min_air = min(min_air, air)
        if hp is not None:
            min_hp = min(min_hp, hp)
            if hp <= 0:
                died = True; break
        # ⛔ A DROWN+RESPAWN LOOKS LIKE "REACHED AIR" -- IT IS NOT. After drowning the bot
        # respawns at world spawn with full air and hp, which the naive "air>=290" test below
        # read as success. So arrival must be the bot reaching air WHERE THE COLUMN IS: still
        # near the shaft (within 16 blocks XZ) and above the water. A body that teleported far
        # away has died, not surfaced.
        gs = py4j("gs")
        pos = str(gs.get("pos") or "")
        near = False
        try:
            px, py_, pz = (float(v) for v in pos.split(","))
            near = (px - X) ** 2 + (pz - Z) ** 2 <= 16 * 16
            if not near and py_ > FY + 30:
                died = True; break   # respawned at world spawn, far and high
        except Exception:
            pass
        if air is not None and air >= 290 and hp is not None and hp > 0 and near:
            breathed = True; break
    dt = time.time() - t0
    py4j("cmd", c="@stop")
    print(f"  min air={min_air} min hp={min_hp} died={died} t={dt:.0f}s")
    if breathed and not died:
        print(f"PASS: the bot reached breathable air in {dt:.1f}s"); return 0
    print("FAIL: the bot could not reach/make air (drowned or stayed submerged)"); return 1


if __name__ == "__main__":
    sys.exit(main())
