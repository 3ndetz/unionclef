"""Deterministic bench for G93: a buried drop must not beat crafting the same item from wood in hand.

The third 60-minute run spent its first seven minutes chasing a wooden_pickaxe DROP left ten
blocks underground by an earlier life, with spruce across the lake, instead of crafting one:
ResourceTask.onTick returns a PickupDroppedItemTask whenever the tracker holds a drop of the
target, with no cost check, so a drop that needs a ten-block dig outranks a craft from planks in
the pack. (The stand's own last-run drops are now killed at the start, so this is the REAL-game
case: a legitimately-dropped item far/under ground while the item is cheaply craftable.)

Scene (flat stand, floor top FY=-61): the bot stands on the surface with two logs and two sticks
in the pack -- enough to craft a wooden pickaxe outright. A wooden_pickaxe ITEM is dropped in a
sealed pocket eight blocks straight down, reachable only by digging. `@get wooden_pickaxe 1`.

    python deploy/runner/buried_drop_vs_craft_test.py            # build + measure
    python deploy/runner/buried_drop_vs_craft_test.py --no-build

PASS = a wooden pickaxe is in the pack within TIMEOUT AND the bot never descended toward the
buried drop (min Y stayed at surface). FAIL = it dug down for the drop (min Y well below the
floor) or produced nothing. Prints min Y and the pickaxe state.
"""
import functools, json, re, subprocess, sys, time
print = functools.partial(print, flush=True)

SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 700, 500
FY = -61
DROP_Y = FY - 8                     # the buried drop, eight below the floor
TIMEOUT = 45

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


def bot_y():
    p = str(py4j("gs").get("pos") or "")
    try:
        return float(p.split(",")[1])
    except Exception:
        return None


def has_pickaxe():
    inv = rcon(f"data get entity {BOT} Inventory")
    return "wooden_pickaxe" in inv


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
        # solid ground, an open surface to stand/chop on, and a sealed pocket 8 down for the drop
        rcon(f"fill {X - 6} {DROP_Y - 1} {Z - 6} {X + 6} {FY} {Z + 6} stone")
        rcon(f"fill {X - 6} {FY + 1} {Z - 6} {X + 6} {FY + 4} {Z + 6} air")
        rcon(f"setblock {X} {DROP_Y} {Z} air")               # the pocket
        rcon(f"kill @e[type=item,distance=..64]")
        rcon(f"summon item {X + 0.5} {DROP_Y + 0.3} {Z + 0.5} {{Item:{{id:\"minecraft:wooden_pickaxe\",count:1}},PickupDelay:0}}")
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} oak_log 2")
    rcon(f"give {BOT} stick 2")
    rcon(f"tp {BOT} {X + 0.5} {FY + 1} {Z + 0.5}")
    time.sleep(2)
    y0 = bot_y()
    if y0 is None or abs(y0 - (FY + 1)) > 1.0:
        print(f"FAIL: scene did not hold -- start Y={y0}"); return 2
    py4j("reset")
    py4j("cmd", c="@gamer")                                   # runner active; then request the item
    time.sleep(1)
    py4j("cmd", c="@get wooden_pickaxe 1")
    t0 = time.time(); min_y = y0; got = False
    while time.time() - t0 < TIMEOUT:
        time.sleep(2)
        y = bot_y()
        if y is not None:
            min_y = min(min_y, y)
        if has_pickaxe():
            got = True; break
    dt = time.time() - t0
    py4j("cmd", c="@stop")
    descended = min_y < FY - 1.5     # went below the floor toward the buried drop
    print(f"  pickaxe={got} min_y={min_y:.1f} (floor top {FY}) descended={descended} t={dt:.0f}s")
    if got and not descended:
        print(f"PASS: crafted the pickaxe from wood without digging for the drop ({dt:.1f}s)"); return 0
    if descended:
        print("FAIL: dug down toward the buried drop instead of crafting from wood in hand"); return 1
    print("FAIL: no pickaxe obtained"); return 1


if __name__ == "__main__":
    sys.exit(main())
