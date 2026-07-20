#!/usr/bin/env python3
"""Shield primitive test: two primitives duel each other.

tester2 (archer) fires lead-predicted arrows (BowShooter) at tester1.
Phase 1 (control): tester1 unshielded — arrows must actually land (>=1/2).
Phase 2: tester1 raises the shield (ShieldBlocker via py4j), faces the
archer — 3 arrows must deal ZERO damage.

Requires the pvp profile. Exit code 0 = pass.
"""

import functools
import json
import subprocess
import sys
import time

print = functools.partial(print, flush=True)

SERVER = "uctest-server"
BLOCKER = "tester1"
BLOCKER_CONTAINER = "uctest-mc-tester1"
ARCHER = "tester2"
ARCHER_CONTAINER = "uctest-mc-tester2"
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
elif op == "shield":
    out = {"ok": mc.shieldBlock(int(req["ticks"]))}
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


def reset_positions():
    # blocker faces the archer (+x, yaw -90): the shield only covers the front
    rcon(f"tp {BLOCKER} 0.5 -60 0.5 -90 0")
    rcon(f"tp {ARCHER} 15.5 -60 0.5 90 0")
    time.sleep(1)


def arrow_volley(shots, shielded):
    hits = 0
    for i in range(shots):
        hp_before = entity_float(BLOCKER, "Health")
        if shielded:
            py4j(BLOCKER_CONTAINER, "shield", ticks=120)
        r = py4j(ARCHER_CONTAINER, "shoot", name=BLOCKER)
        if not r.get("ok"):
            print(f"  shot {i+1}: archer refused")
            continue
        time.sleep(4.0)
        hp_after = entity_float(BLOCKER, "Health")
        hit = hp_after < hp_before - 0.01
        hits += 1 if hit else 0
        print(f"  shot {i+1}: blocker hp {hp_before:.1f} -> {hp_after:.1f} {'HIT' if hit else 'blocked/miss'}")
        reset_positions()
    return hits


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
    wait_for("blocker py4j", lambda: py4j(BLOCKER_CONTAINER, "state") is not None, 600, 10)
    ensure_in_game(BLOCKER_CONTAINER, "blocker")
    wait_for("archer py4j", lambda: py4j(ARCHER_CONTAINER, "state") is not None, 600, 10)
    ensure_in_game(ARCHER_CONTAINER, "archer")
    py4j(BLOCKER_CONTAINER, "chat", msg=";stop")
    rcon(f"kill {BLOCKER}")
    wait_for("blocker respawn", lambda: entity_float(BLOCKER, "Health") >= 19.9, 60, 2)
    rcon(f"clear {BLOCKER}")
    rcon(f"item replace entity {BLOCKER} weapon.offhand with shield")
    rcon(f"clear {ARCHER}")
    rcon(f"give {ARCHER} bow")
    rcon(f"give {ARCHER} arrow 64")
    reset_positions()

    print("[3/3] volleys...")
    print(" control (no shield):")
    control_hits = arrow_volley(2, shielded=False)
    rcon(f"kill {BLOCKER}")
    wait_for("blocker respawn", lambda: entity_float(BLOCKER, "Health") >= 19.9, 60, 2)
    rcon(f"item replace entity {BLOCKER} weapon.offhand with shield")
    reset_positions()
    print(" shielded:")
    shielded_hits = arrow_volley(3, shielded=True)

    print(f"\n=== RESULTS ===")
    print(f"  control hits (no shield): {control_hits}/2 (need >=1 — archer must be able to hit)")
    print(f"  shielded hits: {shielded_hits}/3 (need 0)")
    ok = control_hits >= 1 and shielded_hits == 0
    print("  SHIELD:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
