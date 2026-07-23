#!/usr/bin/env python3
"""Slime-bounce parkour autotest for tungsten (;goto over slime trampolines).

Runs on the docker host next to deploy/compose.test.yml. Talks to the mod's
py4j gateway the same way the mineswarm gateway does — `docker exec` a tiny
py4j client inside the MC container (the gateway binds 127.0.0.1 there) —
and to the server via rcon-cli.

Courses (built with RCON in a flat world, ground: grass top y=-60):

  A "drop bounce": start platform (feet -55) -> walk off the edge -> fall 4
    onto a slime pad (feet -59) -> bounce ~3.4 -> land on a +3 platform
    (feet -56). Requires the whole slime routing chain: fall-damage pruning
    exemptions, bounce-height block children, SlimeBounceMove.

  B "short bounce": walk off a low ledge (feet -56) -> fall 3 onto a slime
    pad -> bounce ~2.7 -> land on a +2 platform (feet -57). A jump in place
    on slime only reaches ~1.9 (vanilla), so flat-slime routes are not a
    thing — every bounce course needs a drop-in.

Exit code 0 = all courses passed.
"""

import functools
import json
import subprocess
import sys
import time

print = functools.partial(print, flush=True)

CLIENT = "uctest-mc-tester1"
SERVER = "uctest-server"
BOT = "tester1"
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
    out = {"inGame": mc.inGame(), "hasTask": mc.hasActiveTask(),
           "health": mc.getHealth(), "name": mc.getUsername()}
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


def py4j(op, timeout=25, **kw):
    req = json.dumps({"op": op, "port": PY4J_PORT, **kw})
    r = sh(["docker", "exec", CLIENT, "python3", "-c", PY4J_SNIPPET, req], timeout)
    if r.returncode != 0:
        raise RuntimeError(f"py4j {op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])


def rcon(cmd, timeout=20):
    r = sh(["docker", "exec", SERVER, "rcon-cli", cmd], timeout)
    if r.returncode != 0:
        raise RuntimeError(f"rcon `{cmd}`: {r.stderr.strip()[-300:]}")
    return r.stdout.strip()


def bot_pos():
    out = rcon(f"data get entity {BOT} Pos")
    # ... has the following entity data: [-3.5d, -55.0d, 0.5d]
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


COURSE_CMDS = [
    # course chunks must be loaded for fill to work (spawn chunk radius is
    # tiny in 1.21 and the client may not have joined yet)
    "forceload add -24 -24 40 24",
    "setworldspawn 0 -60 0",
    # wipe area
    "fill -12 -60 -8 24 -45 8 air",
    # course A: start platform (top -56, feet -55)
    "fill -6 -56 -1 -4 -56 1 stone",
    # course A: slime pad on the ground (top -59, feet on slime -59)
    "fill -2 -60 -1 2 -60 1 slime_block",
    # course A: target platform +3 above slime feet (top -57, feet -56)
    "fill 4 -57 -1 6 -57 1 stone",
    # course B: start ledge (top -56, feet -56... stands at -56)
    "fill 9 -57 0 10 -57 1 stone",
    # course B: slime pad, drop 3 from the ledge
    "fill 11 -60 0 14 -60 1 slime_block",
    # course B: target platform +2 above slime feet (top -58, feet -57)
    "fill 16 -58 -1 18 -58 1 stone",
    # keep things quiet and deterministic (1.21.11 snake_case gamerule ids)
    "gamerule advance_time false",
    "gamerule advance_weather false",
    "gamerule spawn_mobs false",
    "time set day",
]


def build_course():
    for c in COURSE_CMDS:
        out = rcon(c)
        print(f"  rcon: {c} -> {out[:60]}")
    for check in ("-5 -56 0 stone", "0 -60 0 slime_block", "5 -57 0 stone",
                  "9 -57 0 stone", "12 -60 0 slime_block", "17 -58 0 stone"):
        out = rcon(f"execute if block {check}")
        if "passed" not in out.lower():
            raise RuntimeError(f"course build verification failed at {check}: {out}")
    print("  course verified")


def snap(name):
    try:
        py4j("shot")
        sh(["docker", "cp", f"{CLIENT}:/tmp/shot.png", f"/tmp/{name}.png"])
        print(f"  screenshot: /tmp/{name}.png")
    except Exception as ex:
        print(f"  (screenshot failed: {ex})")


def run_course(name, tp_cmd, goto_cmd, box, timeout_s):
    """box = (xmin, xmax, ymin, zmin, zmax) — success when bot inside."""
    print(f"[course {name}]")
    rcon(f"effect clear {BOT}")
    rcon(tp_cmd)
    time.sleep(3)
    print(f"  start pos: {bot_pos()}")
    py4j("chat", msg=";stop")
    time.sleep(1)
    py4j("chat", msg=goto_cmd)
    print(f"  sent: {goto_cmd}")

    xmin, xmax, ymin, zmin, zmax = box
    t_start = time.time()
    midshot = [False]

    def arrived():
        if not midshot[0] and time.time() - t_start > 20:
            midshot[0] = True
            snap(f"{name}_mid")
        p = bot_pos()
        return (xmin <= p[0] <= xmax and p[1] >= ymin
                and zmin <= p[2] <= zmax) and p or None

    try:
        pos = wait_for(f"{name}: bot on target platform", arrived, timeout_s)
        health = py4j("state")["health"]
        print(f"  PASS {name}: pos={pos} health={health}")
        return True
    except TimeoutError as e:
        print(f"  FAIL {name}: {e}")
        print(f"  final pos: {bot_pos()}")
        snap(f"{name}_fail")
        for line in py4j("recent", n=10).get("chat", []):
            print(f"  chat| {line}")
        return False
    finally:
        py4j("chat", msg=";stop")
        time.sleep(1)


def main():
    print("[1/4] waiting for server rcon...")
    wait_for("server rcon", lambda: "players" in rcon("list"), 300, 5)

    print("[2/4] building course...")
    build_course()

    print("[3/4] waiting for client py4j + join...")
    wait_for("py4j gateway", lambda: py4j("state") is not None, 600, 10)
    st = py4j("state")
    print(f"  client state: {st}")
    if not st["inGame"]:
        py4j("connect", ip="test-server")
        wait_for("bot in game", lambda: py4j("state")["inGame"], 180, 5)
        time.sleep(5)

    print("[4/4] running courses...")
    # Keep verbose drift logging OFF: it prints a per-tick physics-sim dump to STDOUT that
    # floods the log, chokes the py4j gateway, and makes the whole stand flap. The test is
    # position-based and doesn't need it. (Persisted config — leaving it on breaks later runs.)
    py4j("chat", msg=";settings verboseDebugLogging false")
    time.sleep(1)
    results = {}
    # A: start on the high platform, goal on the +3 platform across the slime pit
    results["A_drop_bounce"] = run_course(
        "A_drop_bounce",
        f"tp {BOT} -5.5 -55 0.5 -90 0",
        ";goto 5 -56 0",
        (3.5, 7.5, -56.3, -1.6, 1.6),
        150,
    )
    # B: start on the low ledge, goal on the +2 platform past the slime pad
    results["B_short_bounce"] = run_course(
        "B_short_bounce",
        f"tp {BOT} 9.5 -56 0.5 -90 0",
        ";goto 17 -57 0",
        (15.5, 19.5, -57.3, -1.6, 1.6),
        120,
    )

    print("\n=== RESULTS ===")
    ok = True
    for k, v in results.items():
        print(f"  {k}: {'PASS' if v else 'FAIL'}")
        ok &= v
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
