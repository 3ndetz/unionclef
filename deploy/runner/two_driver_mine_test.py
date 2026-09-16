"""Deterministic bench for the two-driver mining fight (the 00:12 stare, 2026-09-16).

Two mining drivers on adjacent blocks in the same tick: tungsten's executor dig (mineBlocks, the
queue FastNavigator hands over "at the dig") on a stone, and altoclef's DestroyBlockTask
(destroyBlockAt, the "Block in range, mining..." driver) on the crafting table beside it. Vanilla
resets block-break progress whenever the crosshair leaves the block, so two aimers on alternate
ticks break neither. Before the dig-path yield: breakMissWhy=0/77 in four seconds and both
blocks intact; after: the dig stands down while the miner mines (execDigYieldMiner counts the
ticks), the table breaks, the dig resumes, the stone breaks.

Layout (flat world, surface block at FY, bot feet at FY+1):

    head level:  [stone][table]     <- (X, FY+2, Z+2) and (X+1, FY+2, Z+2)
    feet level:  [B ]               <- bot at (X, FY+1, Z), two cells back

    python deploy/runner/two_driver_mine_test.py

PASS if both blocks are gone within TIMEOUT and breakMissWhy's transit count stays under 10.
Prints breakMissWhy, execDigYieldMiner, dbAimWait, breakAim either way.
"""
import functools, json, re, subprocess, sys, time
print = functools.partial(print, flush=True)

SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 820, 820
FY = -61
SY = FY + 2
TIMEOUT = 20

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="mine": out=dict(mc.mineBlocks(req["cells"]) or {})
elif op=="destroy": out=dict(mc.destroyBlockAt(*req["pos"]) or {})
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


def block_is(x, y, z, block):
    return "Test passed" in rcon(f"execute if block {x} {y} {z} {block}")


def counters(stats, keys):
    return " ".join(m.group(0) for k in keys for m in [re.search(k + r"=\S+", stats)] if m)


def main():
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
    rcon(f"fill {X - 5} {FY + 1} {Z - 5} {X + 5} {FY + 4} {Z + 5} air")
    rcon(f"setblock {X} {SY} {Z + 2} stone")
    rcon(f"setblock {X + 1} {SY} {Z + 2} crafting_table")
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} iron_pickaxe")
    rcon(f"tp {BOT} {X + 0.5} {FY + 1} {Z + 0.5} 0 0")
    time.sleep(2)
    py4j("reset")
    py4j("mine", cells=[[X, SY, Z + 2]])
    time.sleep(0.5)
    py4j("destroy", pos=[X + 1, SY, Z + 2])
    t0 = time.time(); done = False
    while time.time() - t0 < TIMEOUT:
        time.sleep(2)
        if block_is(X, SY, Z + 2, "air") and block_is(X + 1, SY, Z + 2, "air"):
            done = True; break
    dt = time.time() - t0
    py4j("cmd", c="@stop")
    stats = py4j("stats").get("r", "")
    line = counters(stats, ["breakMissWhy", "execDigYieldMiner", "dbAimWait", "breakAim"])
    print("  " + line)
    m = re.search(r"breakMissWhy=(\d+)/(\d+)", stats)
    transit = int(m.group(2)) if m else -1
    if done and 0 <= transit < 10:
        print(f"PASS: both blocks broken in {dt:.1f}s, transit={transit}"); return 0
    print(f"FAIL: done={done} transit={transit} after {dt:.0f}s"); return 1


if __name__ == "__main__":
    sys.exit(main())
