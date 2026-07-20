#!/usr/bin/env python3
"""Bow trajectory autotest: TrajectorySolver + BowShooter primitive.

Shooter gets a bow + arrows; victim stands at ~18 blocks, then runs a line.
Each shot: py4j shootArrowAt -> aim/charge/release with moving-target lead.

Pass: >=3/5 hits on the standing target, >=2/5 on the runner (vanilla arrow
divergence exists, perfect accuracy is impossible by design).

Requires the pvp profile. Exit code 0 = pass.
"""

import functools
import json
import subprocess
import sys
import time

print = functools.partial(print, flush=True)

SERVER = "uctest-server"
SHOOTER = "tester1"
SHOOTER_CONTAINER = "uctest-mc-tester1"
VICTIM = "tester2"
VICTIM_CONTAINER = "uctest-mc-tester2"
PY4J_PORT = 25333

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
elif op == "shoot":
    out = {"ok": mc.shootArrowAt(req["name"])}
elif op == "aim":
    out = dict(mc.solveArrowAim(req["name"]))
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


def volley(shots, victim_mover=None):
    """Fire `shots` arrows; return hit count (victim hp drops)."""
    hits = 0
    for i in range(shots):
        hp_before = entity_float(VICTIM, "Health")
        r = py4j(SHOOTER_CONTAINER, "shoot", name=VICTIM)
        if not r.get("ok"):
            print(f"  shot {i+1}: shootArrowAt refused")
            continue
        # aim+charge+release ≈ 1.5s, flight ≈ 0.5-1s
        for _ in range(4):
            if victim_mover:
                victim_mover()
            time.sleep(1.0)
        hp_after = entity_float(VICTIM, "Health")
        hit = hp_after < hp_before - 0.01
        hits += 1 if hit else 0
        print(f"  shot {i+1}: hp {hp_before:.1f} -> {hp_after:.1f} {'HIT' if hit else 'miss'}")
        if hp_after < 6:
            rcon(f"kill {VICTIM}")
            wait_for("victim respawn", lambda: entity_float(VICTIM, "Health") >= 19.9, 60, 2)
            reset_positions()
    return hits


MOVE_STATE = {"z": -6, "dz": 3}


def reset_positions():
    rcon(f"tp {SHOOTER} 0.5 -60 0.5 0 0")
    rcon(f"tp {VICTIM} 18.5 -60 0.5 90 0")
    time.sleep(1)


def move_victim():
    MOVE_STATE["z"] += MOVE_STATE["dz"]
    if abs(MOVE_STATE["z"]) >= 9:
        MOVE_STATE["dz"] = -MOVE_STATE["dz"]
    rcon(f"tp {VICTIM} 18.5 -60 {MOVE_STATE['z'] + 0.5}")


def main():
    print("[1/3] arena...")
    wait_for("server rcon", lambda: "players" in rcon("list"), 300, 5)
    for c in ["forceload add -16 -16 32 16",
              "fill -8 -60 -12 28 -45 12 air",
              "gamerule natural_health_regeneration false",
              "gamerule pvp true", "gamerule immediate_respawn true",
              "gamerule advance_time false", "weather clear", "time set day"]:
        print(f"  rcon: {c} -> {rcon(c)[:50]}")

    print("[2/3] clients + gear...")
    wait_for("shooter py4j", lambda: py4j(SHOOTER_CONTAINER, "state") is not None, 600, 10)
    ensure_in_game(SHOOTER_CONTAINER, "shooter")
    wait_for("victim py4j", lambda: py4j(VICTIM_CONTAINER, "state") is not None, 600, 10)
    ensure_in_game(VICTIM_CONTAINER, "victim")
    py4j(SHOOTER_CONTAINER, "chat", msg=";stop")
    rcon(f"kill {VICTIM}")
    wait_for("victim respawn", lambda: entity_float(VICTIM, "Health") >= 19.9, 60, 2)
    rcon(f"clear {SHOOTER}")
    rcon(f"give {SHOOTER} bow")
    rcon(f"give {SHOOTER} arrow 64")
    reset_positions()

    aim = py4j(SHOOTER_CONTAINER, "aim", name=VICTIM)
    print(f"  solveArrowAim: {aim}")
    if "pitch" not in aim:
        print("  FAIL: solver returned no solution at 18 blocks")
        sys.exit(1)

    print("[3/3] volleys...")
    print(" standing target:")
    hits_standing = volley(5)
    print(" running target:")
    reset_positions()
    hits_running = volley(5, victim_mover=move_victim)

    print(f"\n=== RESULTS ===")
    print(f"  standing: {hits_standing}/5 (need >=3)")
    print(f"  running:  {hits_running}/5 (need >=2)")
    ok = hits_standing >= 3 and hits_running >= 2
    print("  BOW:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
