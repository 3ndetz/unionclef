"""Bench for G71: a mob seen but out of reach must not thrash the mining hand (2026-09-17).

The 22:50 run stood three minutes on a coal ore, "Found better tool in inventory, equipping."
4532 times: `KillAura.attack`'s `equipSword` branch put a weapon in the hand on every cooldown
for a hostile the force field had in view but out of melee reach, the miner and the fix chain put
the pickaxe back, and a hand that changes item resets vanilla's break progress. The fix (already
in the code, dated 2026-09-12: `KillAura.java` around the `TriggerBot.REACH + 1.0` check) only
equips a weapon for a target within that reach; a mob further out is left alone and the pickaxe
stays put. No bench existed to check it holds.

Layout (flat world, floor at FY, bot feet at FY+1):

    a coal ore embedded in a stone wall directly in front of the bot (immediate, no navigation
    needed) and a zombie caged in iron bars six blocks away -- visible, never reachable.

The bot has only a wooden pickaxe. `@get coal 1`. PASS if coal is collected within TIMEOUT AND
the tool-equip counters stayed low (`fixToolSwaps`, `kaAura=outOfReach/equip`'s equip half) AND
the out-of-reach branch actually fired at least once (proof the zombie was evaluated as a target
at all -- a run where it never fired proves nothing, the same discipline creeper_behind_test.py
already applies to its own creeper).

    python deploy/runner/equip_thrash_test.py             # build the scene and measure
    python deploy/runner/equip_thrash_test.py --no-build  # reuse the scene
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)

SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 3100, 900                    # fresh spot, clear of every other bench's coordinates
FY = -61                            # superflat top solid block; feet at FY+1
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
elif op=="stats": out={"r": str(mc.placeStats() or "")}
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


def counters(stats, keys):
    import re
    return {k: re.search(k + r"=\S+", stats).group(0) if re.search(k + r"=\S+", stats) else f"{k}=?"
            for k in keys}


def pair(tok):
    try:
        a, b = tok.split("=", 1)[1].split("/")
        return int(a), int(b)
    except (ValueError, IndexError):
        return None, None


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
    rcon("difficulty normal")     # a peaceful server removes the summoned zombie on the spot
    rcon(f"forceload add {X - 6} {Z - 8} {X + 6} {Z + 8}"); time.sleep(1)
    rcon("kill @e[type=zombie]")
    if build:
        rcon(f"fill {X - 4} {FY - 1} {Z - 8} {X + 4} {FY + 4} {Z + 6} air")
        rcon(f"fill {X - 4} {FY} {Z - 8} {X + 4} {FY} {Z + 6} stone")                    # floor
        rcon(f"fill {X - 1} {FY + 1} {Z + 1} {X + 1} {FY + 2} {Z + 1} stone")            # ore wall
        rcon(f"setblock {X} {FY + 1} {Z + 1} coal_ore")
        # the cage, six blocks the other way: bars on every side, sight through, no way out
        cz = Z - 6
        rcon(f"fill {X - 1} {FY + 1} {cz - 1} {X + 1} {FY + 3} {cz + 1} iron_bars hollow")
    rcon(f"tp {BOT} {X + 0.5} {FY + 1} {Z + 0.5} 180 0")   # facing the ore
    time.sleep(2)
    ore = rcon(f"execute if block {X} {FY + 1} {Z + 1} coal_ore")
    if "passed" not in ore.lower():
        print(f"FAIL: the scene was not built (ore={ore!r})"); return 2
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} wooden_pickaxe")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10 true")
    rcon(f"effect give {BOT} minecraft:saturation 5 5 true")
    r = rcon(f"summon minecraft:zombie {X + 0.5} {FY + 1} {Z - 6 + 0.5}")
    print(f"bot facing a coal ore at {X},{FY+1},{Z+1}; zombie caged six blocks behind ({r[:30]!r})")
    time.sleep(1)
    py4j("reset")
    py4j("cmd", c="@get coal 1")
    t0 = time.time(); got = False
    while time.time() - t0 < TIMEOUT:
        time.sleep(3)
        inv = rcon(f"data get entity {BOT} Inventory")
        if '"minecraft:coal"' in inv:
            got = True; break
    dt = time.time() - t0
    py4j("cmd", c="@stop")
    stats = py4j("stats").get("r", "")
    c = counters(stats, ["fixToolSwaps", "kaAura"])
    out_of_reach, equipped = pair(c["kaAura"])
    rcon("kill @e[type=zombie]")
    rcon("difficulty peaceful")
    rcon(f"forceload remove {X - 6} {Z - 8} {X + 6} {Z + 8}")
    print(f"  {' '.join(c.values())}")
    if out_of_reach is None:
        print("FAIL: could not read kaAura=outOfReach/equip from placeStats -- bench is broken, not the fix"); return 2
    if out_of_reach == 0:
        print("FAIL: the caged zombie never registered as an out-of-reach aura target -- "
              "the scene proves nothing, it did not exercise the mechanism under test"); return 1
    swaps = int(c["fixToolSwaps"].split("=", 1)[1])
    if got and equipped == 0 and swaps <= 3:
        print(f"PASS: coal mined in {dt:.1f}s, the caged zombie was seen {out_of_reach} times "
              "and never got the sword equipped for it, fixToolSwaps stayed low"); return 0
    if not got:
        print(f"FAIL: no coal after {dt:.0f}s (outOfReach={out_of_reach} equipped={equipped} swaps={swaps})"); return 1
    print(f"FAIL: coal mined but the hand thrashed (equipped={equipped} swaps={swaps})"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
