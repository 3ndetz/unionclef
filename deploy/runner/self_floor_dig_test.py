"""Deterministic bench for the self-floor dig (2026-09-16, the 110-second stand).

The 25-minute playthrough stood 110 s at (1141.7,65,-1421) over its cobblestone target two
blocks below the feet: DestroyBlockTask sat in "Getting to block..." (the reach ray stopped by
the bot's own floor, dbBlocked=1430/0/0), the navigator's reach route planned the dig down, and
the executor's dig yielded to the miner every tick (execDigYieldMiner=1392) because the miner's
back-off branch stamps the aim claim while it swings at nothing. Nobody dug.

Layout (flat world, surface block at FY, bot feet at FY+1):

    feet level:  [B ]          <- bot at (X, FY+1, Z)
    floor:       [dirt]        <- (X, FY, Z), the bot's own floor
    target:      [stone]       <- (X, FY-1, Z), two below the feet: the only stone nearby

The bot gets a wooden pickaxe and is asked for one cobblestone. The only way to it is to dig the
floor (the navigator's breakDown) and then mine the stone.

    python deploy/runner/self_floor_dig_test.py             # build the scene and measure
    python deploy/runner/self_floor_dig_test.py --no-build  # reuse the scene

PASS if a cobblestone is in the inventory within TIMEOUT; FAIL otherwise. Prints the counters
that name the mechanism either way: execDigYieldMiner (the dig yielded to a miner that mines),
dbBlocked (self-floor refusals), navBreak (the navigator's own dig runs).
"""
import functools, json, re, subprocess, sys, time
print = functools.partial(print, flush=True)

SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 700, 700                     # fresh spot, clear of older benches
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
    return " ".join(m.group(0) for k in keys for m in [re.search(k + r"=\S+", stats)] if m)


def main():
    build = "--no-build" not in sys.argv
    # THE BOT MUST BE ON THIS SERVER. Connect unconditionally and prove it with `list`.
    py4j("connect", ip="test-server")
    for _ in range(40):
        time.sleep(2)
        if py4j("state").get("inGame") and BOT in rcon("list"):
            break
    else:
        print("FAIL: bot not on test-server"); return 2
    py4j("cmd", c="@stop")
    rcon(f"tp {BOT} {X + 0.5} {FY + 1} {Z + 0.5}")
    time.sleep(5)                      # the chunk must be loaded before setblock is trusted
    if build:
        rcon(f"fill {X - 6} {FY - 1} {Z - 6} {X + 6} {FY + 3} {Z + 6} air")
        rcon(f"fill {X - 6} {FY} {Z - 6} {X + 6} {FY} {Z + 6} dirt")
        rcon(f"fill {X - 6} {FY - 1} {Z - 6} {X + 6} {FY - 1} {Z + 6} dirt")
        rcon(f"setblock {X} {FY - 1} {Z} stone")
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} wooden_pickaxe")
    rcon(f"tp {BOT} {X + 0.5} {FY + 1} {Z + 0.5}")
    time.sleep(2)
    py4j("reset")
    py4j("cmd", c="@get cobblestone 1")
    t0 = time.time(); got = False
    while time.time() - t0 < TIMEOUT:
        time.sleep(3)
        inv = rcon(f"data get entity {BOT} Inventory")
        if "cobblestone" in inv:
            got = True; break
    dt = time.time() - t0
    py4j("cmd", c="@stop")
    stats = py4j("stats").get("r", "")
    print("  " + counters(stats, ["execDigYieldMiner", "dbBlocked", "navBreak", "dbStepOver",
                                  "dbNoRetreat", "breakMissWhy", "dbAimWait"]))
    if got:
        print(f"PASS: cobblestone in {dt:.1f}s"); return 0
    print(f"FAIL: no cobblestone after {dt:.0f}s"); return 1


if __name__ == "__main__":
    sys.exit(main())
