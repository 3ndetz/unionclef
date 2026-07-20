#!/usr/bin/env python3
"""PVP effectiveness autotest for tungsten combat (;punkPlayer).

Two headless clients: tester1 (fighter, gets an iron sword) and tester2
(victim, stands still, no regen). Arena is flat with tall-grass patches
between the fighters — the reported freeze case.

Metrics:
  - time to first damage on the victim (target: < 10s from ;punkPlayer)
  - victim health drop over the fight window (effectiveness)
  - fighter keeps acting the whole window (no freeze: position/attack
    activity keeps changing)

Requires the pvp profile:
  docker compose -f deploy/compose.test.yml --profile pvp up -d

Exit code 0 = pass.
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

FIGHT_WINDOW_S = 60
FIRST_HIT_DEADLINE_S = 15
MIN_DAMAGE = 8.0  # half-hearts over the window; naked victim, iron sword

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
    val = out.rsplit(":", 1)[-1].strip().rstrip("fd")
    return float(val)


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


ARENA_CMDS = [
    "forceload add -24 -24 40 24",
    # clear and floor
    "fill -12 -60 -12 24 -45 12 air",
    "fill -12 -61 -12 24 -61 12 grass_block",
    # tall grass patches between the fighters (the reported freeze case)
    "fill 2 -60 -4 6 -60 4 short_grass",
    "fill 8 -60 -2 10 -60 2 tall_grass",
    # no regen so damage accumulates (1.21.11 renamed gamerules to snake_case)
    "gamerule natural_health_regeneration false",
    "gamerule pvp true",
    "gamerule immediate_respawn true",
    "time set day",
]


def build_arena():
    for c in ARENA_CMDS:
        out = rcon(c)
        print(f"  rcon: {c} -> {out[:60]}")


def ensure_in_game(container, label):
    st = py4j(container, "state")
    print(f"  {label} state: {st}")
    if not st["inGame"]:
        py4j(container, "connect", ip="test-server")
        wait_for(f"{label} in game", lambda: py4j(container, "state")["inGame"], 180, 5)
        time.sleep(3)


def main():
    print("[1/4] server + arena...")
    wait_for("server rcon", lambda: "players" in rcon("list"), 300, 5)
    build_arena()

    print("[2/4] clients...")
    wait_for("fighter py4j", lambda: py4j(FIGHTER_CONTAINER, "state") is not None, 600, 10)
    ensure_in_game(FIGHTER_CONTAINER, "fighter")
    wait_for("victim py4j", lambda: py4j(VICTIM_CONTAINER, "state") is not None, 600, 10)
    ensure_in_game(VICTIM_CONTAINER, "victim")

    print("[3/4] setup fight...")
    py4j(FIGHTER_CONTAINER, "chat", msg=";stop")
    # the client's persisted tungsten.json may carry old defaults
    # (combatMovementsEnabled=false shipped for months) — pin what we test
    py4j(FIGHTER_CONTAINER, "chat", msg=";settings combatMovementsEnabled true")
    time.sleep(1)
    # full health reset — with regen off, leftovers from a previous fight
    # (a 0.1 hp victim) turn the whole measurement into garbage
    rcon(f"kill {FIGHTER}")
    rcon(f"kill {VICTIM}")
    wait_for("fighter respawned", lambda: entity_float(FIGHTER, "Health") >= 19.9, 60, 3)
    wait_for("victim respawned", lambda: entity_float(VICTIM, "Health") >= 19.9, 60, 3)
    rcon(f"effect clear {FIGHTER}")
    rcon(f"effect clear {VICTIM}")
    rcon(f"clear {FIGHTER}")
    rcon(f"give {FIGHTER} iron_sword")
    # fighter west of the grass; victim INSIDE the tall-grass patch — the old
    # combat passed an open-field fight, the freeze case is grass at the
    # fight location itself
    rcon(f"tp {FIGHTER} -5.5 -60 0.5 -90 0")
    rcon(f"tp {VICTIM} 9.5 -60 0.5 90 0")
    time.sleep(3)
    hp0 = entity_float(VICTIM, "Health")
    print(f"  victim hp: {hp0}, fighter pos {entity_pos(FIGHTER)}, victim pos {entity_pos(VICTIM)}")

    print("[4/4] fight...")
    py4j(FIGHTER_CONTAINER, "chat", msg=f";punkPlayer {VICTIM}")
    t0 = time.time()
    first_hit = None
    freeze_windows = 0
    last_fighter_pos = entity_pos(FIGHTER)
    last_move_t = t0
    min_hp = hp0

    while time.time() - t0 < FIGHT_WINDOW_S:
        time.sleep(2)
        try:
            hp = entity_float(VICTIM, "Health")
        except Exception:
            hp = min_hp  # victim died and despawned from data get
        if hp < min_hp:
            min_hp = hp
            if first_hit is None:
                first_hit = time.time() - t0
                print(f"  first damage at {first_hit:.1f}s (hp {hp})")
        fp = entity_pos(FIGHTER)
        moved = sum(abs(a - b) for a, b in zip(fp, last_fighter_pos)) > 0.05
        if moved:
            last_move_t = time.time()
            last_fighter_pos = fp
        elif time.time() - last_move_t > 10 and hp == min_hp:
            freeze_windows += 1
            last_move_t = time.time()
            print(f"  WARNING: fighter static >10s at {fp} (freeze window #{freeze_windows})")
        if hp <= 0.5:
            print("  victim dead")
            break

    py4j(FIGHTER_CONTAINER, "chat", msg=";stop")
    damage = hp0 - min_hp
    ttfh = f"{first_hit:.1f}s" if first_hit is not None else "never"
    print(f"\n=== RESULTS ===")
    print(f"  time to first damage: {ttfh} (deadline {FIRST_HIT_DEADLINE_S}s)")
    print(f"  damage dealt: {damage:.1f} (min {MIN_DAMAGE})")
    print(f"  freeze windows (>10s static, no damage): {freeze_windows}")

    ok = (first_hit is not None and first_hit <= FIRST_HIT_DEADLINE_S
          and damage >= MIN_DAMAGE and freeze_windows == 0)
    if not ok:
        try:
            py4j(FIGHTER_CONTAINER, "shot")
            sh(["docker", "cp", f"{FIGHTER_CONTAINER}:/tmp/shot.png", "/tmp/pvp_fail.png"])
            print("  screenshot: /tmp/pvp_fail.png")
        except Exception as ex:
            print(f"  (screenshot failed: {ex})")
        for line in py4j(FIGHTER_CONTAINER, "recent", n=12).get("chat", []):
            print(f"  chat| {line}")
    print("  PVP:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
