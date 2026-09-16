"""Deterministic bench for G99: the stroke out of a pool onto a bank at water level.

The second 60-minute playthrough of 2026-09-16 ended with fifteen minutes at the lip of a pool
at (93,107,-113): the one-cell stroke onto the bank declared SUCCESS by distance with the feet
still in the water cell, the keys were released, the water carried the body back, and the
navigator re-planned the same stroke from the same cell, 106 chains dropped. MovementSwim now
arrives on a LAND destination only with the feet on it.

Layout (flat world, surface block at FY, feet on the surface at FY+1): a 3x3 pool two deep
(water at FY-1 and FY) cut into the floor, the bot floating in its middle, the goal the bank
cell just east of the pool at surface level -- the body must rise half a block and step out.

    python deploy/runner/pool_bank_test.py            # build and measure
    python deploy/runner/pool_bank_test.py --no-build

PASS if the feet are on the bank cell within TIMEOUT. Prints mqLost/mqSteps/navStall either way.
"""
import functools, json, re, subprocess, sys, time
print = functools.partial(print, flush=True)

SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 900, 900                     # pool centre
FY = -61
BANK = (X + 2, FY + 1, Z)           # the first dry cell east of the pool
TIMEOUT = 30

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="goto": mc.gotoXYZ(*req["pos"]); out={"ok":True}
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


def bot_pos():
    p = str(py4j("gs").get("pos") or "")
    try:
        x, y, z = (float(v) for v in p.split(","))
        return x, y, z
    except Exception:
        return None


def counters(stats, keys):
    return " ".join(m.group(0) for k in keys for m in [re.search(k + r"=\S+", stats)] if m)


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
        rcon(f"fill {X - 6} {FY + 1} {Z - 6} {X + 6} {FY + 4} {Z + 6} air")
        rcon(f"fill {X - 6} {FY - 1} {Z - 6} {X + 6} {FY} {Z + 6} stone")
        rcon(f"fill {X - 1} {FY - 1} {Z - 1} {X + 1} {FY} {Z + 1} water")
    rcon(f"clear {BOT}")
    rcon(f"tp {BOT} {X + 0.5} {FY} {Z + 0.5}")
    time.sleep(3)
    p0 = bot_pos()
    if p0 is None or abs(p0[0] - (X + 0.5)) > 0.7 or p0[1] > FY + 1.2:
        print(f"FAIL: the scene did not hold — start pos={p0}"); return 2
    py4j("reset")
    py4j("goto", pos=list(BANK))
    t0 = time.time(); on_bank = False; last = p0
    while time.time() - t0 < TIMEOUT:
        time.sleep(2)
        p = bot_pos()
        if p is None:
            continue
        last = p
        if int(p[0] // 1) == BANK[0] and int(p[2] // 1) == BANK[2] and abs(p[1] - BANK[1]) < 0.3:
            on_bank = True; break
    dt = time.time() - t0
    py4j("cmd", c="@stop")
    stats = py4j("stats").get("r", "")
    print("  " + counters(stats, ["mqLost", "mqSteps", "mqStarted", "navStall", "swimAim", "navWet"]))
    print(f"  start={p0} last={tuple(round(v, 2) for v in last)}")
    if on_bank:
        print(f"PASS: on the bank in {dt:.1f}s"); return 0
    print(f"FAIL: not on the bank after {dt:.0f}s"); return 1


if __name__ == "__main__":
    sys.exit(main())
