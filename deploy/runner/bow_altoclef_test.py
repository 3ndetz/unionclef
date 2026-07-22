#!/usr/bin/env python3
"""Altoclef @shoot autotest — the ShootArrowSimpleProjectileTask aim path.

Distinct from bow_test.py (which drives the tungsten BowShooter primitive via
py4j shootArrowAt). This exercises altoclef's own combat shoot task, whose aim
was rewired to call tungsten TrajectorySolver (vanilla-physics ballistics +
position-delta lead) instead of the old g=0.006 closed form.

Shooter gets bow + arrows; fires `@shoot tester2` at a standing then a running
victim. Pass: >=3/5 standing, >=2/5 running (vanilla arrow spread is real).
Exit 0 = pass.
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
elif op == "cmd":
    mc.ExecuteCommand(req["c"]); out = {"ok": True}
elif op == "connect":
    mc.ConnectToServer(req["ip"]); out = {"ok": True}
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
    import re
    out = rcon(f"data get entity {name} Pos")
    m = re.search(r"\[([^\]]+)\]", out)
    return [float(v.strip().rstrip("fd")) for v in m.group(1).split(",")] if m else None


def entity_speed(name):
    """Horizontal speed (blocks/tick) from server-side Motion."""
    import re
    out = rcon(f"data get entity {name} Motion")
    m = re.search(r"\[([^\]]+)\]", out)
    if not m:
        return 0.0
    mx, my, mz = [float(v.strip().rstrip("fd")) for v in m.group(1).split(",")]
    return (mx * mx + mz * mz) ** 0.5


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
    rcon(f"tp {SHOOTER} 0.5 -60 0.5 0 0")
    rcon(f"tp {VICTIM} 18.5 -60 0.5 90 0")
    time.sleep(1)


def volley(shots, running=False):
    """Fire `shots` via @shoot; return hit count. Respawn (hp jump) = kill.

    Running mode sends the victim on a LONG continuous run (to z=+-30 in the
    widened arena) so she is still moving when the arrow releases (~3s in) —
    that is what actually exercises target lead. Motion at release is logged.
    """
    hits = 0
    run_dir = [1]
    for i in range(shots):
        if running:
            z_to = 30 * run_dir[0]
            run_dir[0] = -run_dir[0]
            py4j(VICTIM_CONTAINER, "chat", msg=f";goto 18 -60 {z_to}")
            time.sleep(1.0)  # let her accelerate; long run keeps her moving through the shot
        hp_before = entity_float(VICTIM, "Health")
        py4j(SHOOTER_CONTAINER, "cmd", c=f"@shoot {VICTIM}")
        if running:
            time.sleep(2.5)  # around release time
            spd = entity_speed(VICTIM)
            p_rel = entity_pos(VICTIM)
            time.sleep(2.5)
            print(f"    (release: speed={spd:.3f} b/t, z={p_rel[2]:.1f})")
        else:
            time.sleep(5.0)
        hp_after = entity_float(VICTIM, "Health")
        died = hp_after > hp_before + 5
        hit = died or hp_after < hp_before - 0.01
        hits += 1 if hit else 0
        print(f"  shot {i+1}: hp {hp_before:.1f} -> {hp_after:.1f} "
              f"{'HIT (killed)' if died else 'HIT' if hit else 'miss'}")
        py4j(SHOOTER_CONTAINER, "cmd", c="@stop")
        if running:
            py4j(VICTIM_CONTAINER, "chat", msg=";stop")
        if hp_after < 8 and not died:
            rcon(f"kill {VICTIM}")
            wait_for("victim respawn", lambda: entity_float(VICTIM, "Health") >= 19.9, 60, 2)
        reset_positions()
    return hits


def main():
    print("[1/3] arena...")
    wait_for("server rcon", lambda: "players" in rcon("list"), 300, 5)
    for c in ["forceload add -16 -40 32 40",
              "fill -8 -61 -36 28 -45 36 minecraft:stone",
              "fill -8 -60 -36 28 -45 36 air",
              "gamerule natural_health_regeneration false",
              "gamerule pvp true", "gamerule immediate_respawn true",
              "gamerule advance_time false", "weather clear", "time set day"]:
        print(f"  rcon: {c} -> {rcon(c)[:50]}")

    print("[2/3] clients + gear...")
    wait_for("shooter py4j", lambda: py4j(SHOOTER_CONTAINER, "state") is not None, 600, 10)
    ensure_in_game(SHOOTER_CONTAINER, "shooter")
    wait_for("victim py4j", lambda: py4j(VICTIM_CONTAINER, "state") is not None, 600, 10)
    ensure_in_game(VICTIM_CONTAINER, "victim")
    py4j(SHOOTER_CONTAINER, "cmd", c="@stop")
    rcon(f"kill {VICTIM}")
    wait_for("victim respawn", lambda: entity_float(VICTIM, "Health") >= 19.9, 60, 2)
    rcon(f"clear {SHOOTER}")
    rcon(f"give {SHOOTER} bow")
    rcon(f"give {SHOOTER} arrow 64")
    reset_positions()

    print("[3/3] volleys...")
    print(" standing target:")
    hits_standing = volley(5)
    print(" running target:")
    reset_positions()
    hits_running = volley(5, running=True)

    print("\n=== RESULTS (altoclef @shoot / TrajectorySolver aim) ===")
    print(f"  standing: {hits_standing}/5 (need >=3)")
    print(f"  running:  {hits_running}/5 (need >=2)")
    ok = hits_standing >= 3 and hits_running >= 2
    print("  BOW-ALTOCLEF:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
