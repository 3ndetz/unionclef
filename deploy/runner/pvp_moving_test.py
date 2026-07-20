#!/usr/bin/env python3
"""PVP vs a MOVING victim (;punkPlayer while the target runs a loop).

The victim is teleported along a rectangle at ~3 blocks/s. The fighter must
chase AND land hits. This is the real-fight shape of the reported problems:
"waits forever for something", "rarely clicks".

Metrics:
  - damage over the 120s window (>= 10 half-hearts)
  - time to first damage (<= 25s — needs a chase first)
  - freeze windows (fighter static >10s without damage progress): 0 allowed

Requires the pvp profile. Exit code 0 = pass.
"""

import functools
import json
import subprocess
import sys
import time

print = functools.partial(print, flush=True)

SERVER = "uctest-server"
FIGHTER = "tester1"
FIGHTER_CONTAINER = "uctest-mc-tester1"
VICTIM = "tester2"
VICTIM_CONTAINER = "uctest-mc-tester2"
PY4J_PORT = 25333

WINDOW_S = 120
STEP_S = 2.0
STEP_BLOCKS = 6
FIRST_HIT_DEADLINE_S = 25
MIN_DAMAGE = 10.0

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
elif op == "shot":
    data = mc.getScreenshot()
    open("/tmp/shot.png", "wb").write(bytes(data) if data else b"")
    out = {"ok": bool(data)}
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


def entity_float(name, path):
    out = rcon(f"data get entity {name} {path}")
    return float(out.rsplit(":", 1)[-1].strip().rstrip("fd"))


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
              "gamerule natural_health_regeneration false",
              "gamerule pvp true", "gamerule immediate_respawn true",
              "gamerule advance_time false", "gamerule advance_weather false",
              "weather clear", "time set day"]:
        print(f"  rcon: {c} -> {rcon(c)[:50]}")

    print("[2/3] clients + reset...")
    wait_for("fighter py4j", lambda: py4j(FIGHTER_CONTAINER, "state") is not None, 600, 10)
    ensure_in_game(FIGHTER_CONTAINER, "fighter")
    wait_for("victim py4j", lambda: py4j(VICTIM_CONTAINER, "state") is not None, 600, 10)
    ensure_in_game(VICTIM_CONTAINER, "victim")
    py4j(FIGHTER_CONTAINER, "chat", msg=";stop")
    py4j(FIGHTER_CONTAINER, "chat", msg=";settings combatMovementsEnabled true")
    rcon(f"kill {FIGHTER}")
    rcon(f"kill {VICTIM}")
    wait_for("fighter respawn", lambda: entity_float(FIGHTER, "Health") >= 19.9, 60, 3)
    wait_for("victim respawn", lambda: entity_float(VICTIM, "Health") >= 19.9, 60, 3)
    rcon(f"clear {FIGHTER}")
    rcon(f"give {FIGHTER} iron_sword")
    rcon(f"tp {FIGHTER} 0.5 -60 0.5 0 0")
    rcon(f"tp {VICTIM} -20.5 -60 -7.5 0 0")
    time.sleep(3)
    hp0 = entity_float(VICTIM, "Health")
    print(f"  victim hp: {hp0}")

    print("[3/3] moving fight...")
    py4j(FIGHTER_CONTAINER, "chat", msg=f";punkPlayer {VICTIM}")
    route = loop_route()
    t0 = time.time()
    min_hp = hp0
    first_hit = None
    freeze_windows = 0
    last_fp = entity_pos(FIGHTER)
    last_move_t = t0
    i = 0
    while time.time() - t0 < WINDOW_S:
        x, z = route[i % len(route)]
        i += 1
        try:
            rcon(f"tp {VICTIM} {x + 0.5} -60 {z + 0.5}")
        except Exception:
            pass  # dead victim mid-window
        time.sleep(STEP_S)
        try:
            hp = entity_float(VICTIM, "Health")
        except Exception:
            hp = 0.0  # dead
        if hp < min_hp - 0.01:
            min_hp = hp
            if first_hit is None:
                first_hit = time.time() - t0
                print(f"  first damage at {first_hit:.1f}s (hp {hp:.1f})")
        fp = entity_pos(FIGHTER)
        moved = sum(abs(a - b) for a, b in zip(fp, last_fp)) > 0.1
        if moved:
            last_move_t = time.time()
            last_fp = fp
        elif time.time() - last_move_t > 10:
            freeze_windows += 1
            last_move_t = time.time()
            print(f"  WARNING: fighter static >10s at {fp} (freeze #{freeze_windows})")
        if hp <= 0.5:
            print(f"  victim dead at {time.time()-t0:.1f}s")
            break

    py4j(FIGHTER_CONTAINER, "chat", msg=";stop")
    damage = hp0 - min_hp
    ttfh = f"{first_hit:.1f}s" if first_hit is not None else "never"
    print(f"\n=== RESULTS ===")
    print(f"  first damage: {ttfh} (deadline {FIRST_HIT_DEADLINE_S}s)")
    print(f"  damage dealt: {damage:.1f} (min {MIN_DAMAGE})")
    print(f"  freeze windows: {freeze_windows}")
    ok = (first_hit is not None and first_hit <= FIRST_HIT_DEADLINE_S
          and damage >= MIN_DAMAGE and freeze_windows == 0)
    if not ok:
        try:
            py4j(FIGHTER_CONTAINER, "shot")
            sh(["docker", "cp", f"{FIGHTER_CONTAINER}:/tmp/shot.png", "/tmp/pvp_moving_fail.png"])
            print("  screenshot: /tmp/pvp_moving_fail.png")
        except Exception:
            pass
        for line in py4j(FIGHTER_CONTAINER, "recent", n=15).get("chat", []):
            print(f"  chat| {line}")
    print("  PVP_MOVING:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
