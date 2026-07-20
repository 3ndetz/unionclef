#!/usr/bin/env python3
"""Moving-target follow autotest (;followPlayer vs a running victim).

The victim is teleported along a rectangular loop at ~3 blocks/s (approximates
a running player; tp steps are HARSHER than real walking, so passing this
means real chases are easier). The follower must keep up.

Metrics:
  - average distance over the chase window (target: < 10)
  - the follower must keep moving (no eternal re-plan freeze)

Requires the pvp profile (two clients).
Exit code 0 = pass.
"""

import functools
import json
import subprocess
import sys
import time

print = functools.partial(print, flush=True)

SERVER = "uctest-server"
FOLLOWER = "tester1"
FOLLOWER_CONTAINER = "uctest-mc-tester1"
VICTIM = "tester2"
VICTIM_CONTAINER = "uctest-mc-tester2"
PY4J_PORT = 25333

CHASE_S = 90
STEP_S = 2.0
STEP_BLOCKS = 6         # per step → ~3 blocks/s
AVG_DIST_LIMIT = 10.0
FREEZE_LIMIT = 3        # windows of >8s without follower movement

PY4J_SNIPPET = r"""
import json, sys
from py4j.java_gateway import JavaGateway, GatewayParameters
req = json.loads(sys.argv[1])
gw = JavaGateway(gateway_parameters=GatewayParameters(
    address="127.0.0.1", port=req.get("port", 25333), auto_convert=True))
mc = gw.entry_point
op = req["op"]
out = {}
if op == "state":
    out = {"inGame": mc.inGame(), "health": mc.getHealth(), "name": mc.getUsername()}
elif op == "chat":
    mc.ChatMessage(req["msg"]); out = {"ok": True}
elif op == "connect":
    mc.ConnectToServer(req["ip"]); out = {"ok": True}
elif op == "recent":
    out = {"chat": [str(x) for x in mc.getRecentChat(int(req.get("n", 20)))]}
print(json.dumps(out, default=str))
gw.close()
"""


def sh(args, timeout=30):
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout)


def py4j(container, op, timeout=25, **kw):
    req = json.dumps({"op": op, "port": PY4J_PORT, **kw})
    r = sh(["docker", "exec", container, "python3", "-c", PY4J_SNIPPET, req], timeout)
    if r.returncode != 0:
        raise RuntimeError(f"py4j {container} {op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])


def rcon(cmd, timeout=20):
    r = sh(["docker", "exec", SERVER, "rcon-cli", cmd], timeout)
    if r.returncode != 0:
        raise RuntimeError(f"rcon `{cmd}`: {r.stderr.strip()[-300:]}")
    return r.stdout.strip()


def entity_pos(name):
    out = rcon(f"data get entity {name} Pos")
    inner = out[out.index("[") + 1:out.index("]")]
    return [float(p.strip().rstrip("d")) for p in inner.split(",")]


def wait_for(desc, fn, timeout_s, interval=3):
    t0 = time.time()
    last = None
    while time.time() - t0 < timeout_s:
        try:
            last = fn()
            if last:
                print(f"  [ok] {desc} ({time.time()-t0:.0f}s)")
                return last
        except Exception as e:
            last = e
        time.sleep(interval)
    raise TimeoutError(f"{desc}: timed out after {timeout_s}s (last: {last})")


def ensure_in_game(container, label):
    st = py4j(container, "state")
    print(f"  {label}: {st}")
    if not st["inGame"]:
        py4j(container, "connect", ip="test-server")
        wait_for(f"{label} in game", lambda: py4j(container, "state")["inGame"], 180, 5)
        time.sleep(3)


def loop_route():
    """Rectangular loop, corners at (-20,-8) (20,-8) (20,8) (-20,8)."""
    pts = []
    for x in range(-20, 21, STEP_BLOCKS):
        pts.append((x, -8))
    for z in range(-8, 9, STEP_BLOCKS):
        pts.append((20, z))
    for x in range(20, -21, -STEP_BLOCKS):
        pts.append((x, 8))
    for z in range(8, -9, -STEP_BLOCKS):
        pts.append((-20, z))
    return pts


def main():
    print("[1/3] arena...")
    wait_for("server rcon", lambda: "players" in rcon("list"), 300, 5)
    for c in ["forceload add -32 -16 32 16",
              "fill -26 -60 -12 26 -45 12 air",
              "gamerule advance_time false", "gamerule advance_weather false",
              "weather clear", "time set day"]:
        print(f"  rcon: {c} -> {rcon(c)[:50]}")

    print("[2/3] clients...")
    wait_for("follower py4j", lambda: py4j(FOLLOWER_CONTAINER, "state") is not None, 600, 10)
    ensure_in_game(FOLLOWER_CONTAINER, "follower")
    wait_for("victim py4j", lambda: py4j(VICTIM_CONTAINER, "state") is not None, 600, 10)
    ensure_in_game(VICTIM_CONTAINER, "victim")

    py4j(FOLLOWER_CONTAINER, "chat", msg=";stop")
    rcon(f"tp {FOLLOWER} -20.5 -60 0.5 0 0")
    rcon(f"tp {VICTIM} -20.5 -60 -7.5 0 0")
    time.sleep(3)

    print("[3/3] chase...")
    py4j(FOLLOWER_CONTAINER, "chat", msg=f";followPlayer {VICTIM}")
    route = loop_route()
    t0 = time.time()
    dists = []
    freeze_windows = 0
    last_follower = entity_pos(FOLLOWER)
    last_move_t = t0
    i = 0
    while time.time() - t0 < CHASE_S:
        x, z = route[i % len(route)]
        i += 1
        rcon(f"tp {VICTIM} {x + 0.5} -60 {z + 0.5}")
        time.sleep(STEP_S)
        fp = entity_pos(FOLLOWER)
        vp = entity_pos(VICTIM)
        d = ((fp[0]-vp[0])**2 + (fp[1]-vp[1])**2 + (fp[2]-vp[2])**2) ** 0.5
        dists.append(d)
        moved = sum(abs(a - b) for a, b in zip(fp, last_follower)) > 0.1
        if moved:
            last_move_t = time.time()
            last_follower = fp
        elif time.time() - last_move_t > 8:
            freeze_windows += 1
            last_move_t = time.time()
            print(f"  WARNING: follower static >8s at {fp} (freeze #{freeze_windows})")

    py4j(FOLLOWER_CONTAINER, "chat", msg=";stop")
    # ignore the first quarter (spin-up) for the average
    settled = dists[len(dists)//4:]
    avg = sum(settled) / max(1, len(settled))
    print(f"\n=== RESULTS ===")
    print(f"  samples: {len(dists)}, avg dist (settled): {avg:.1f} (limit {AVG_DIST_LIMIT})")
    print(f"  final dist: {dists[-1]:.1f}, freeze windows: {freeze_windows} (limit {FREEZE_LIMIT})")
    ok = avg <= AVG_DIST_LIMIT and freeze_windows <= FREEZE_LIMIT
    if not ok:
        for line in py4j(FOLLOWER_CONTAINER, "recent", n=12).get("chat", []):
            print(f"  chat| {line}")
    print("  FOLLOW:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
