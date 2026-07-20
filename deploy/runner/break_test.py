#!/usr/bin/env python3
"""Block-breaking autotest for tungsten (;goto through breakable walls).

Courses (flat world, ground top y=-60, floor block at y=-61):

  C "wall": a 2-high dirt wall across the route, too wide to walk around
    cheaply. The block-space planner must accept a break-through child
    (BlockNode.tryPlanBreakThrough), physics leads the bot to the wall,
    PathExecutor mines the passage, the goto retry / continuation search
    drives the rest.

  D "sand": same wall but with 2 sand on top — after mining the passage the
    sand falls into it; the executor keeps mining whatever lands in the
    passage cells (settle loop) and the bot walks through.

Exit code 0 = both pass.
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
    "forceload add -24 -24 40 60",
    # wipe both course areas
    "fill -12 -60 6 24 -45 54 air",
    # course C: dirt wall x=0, 2 high, WIDE (detours must lose to mining)
    "fill 0 -60 8 0 -59 30 dirt",
    # course D: wide dirt wall + sand on top above the route
    "fill 0 -60 32 0 -59 52 dirt",
    "fill 0 -58 36 0 -57 44 sand",
    "gamerule advance_time false",
    "gamerule advance_weather false",
    "gamerule spawn_mobs false",
    "weather clear",
    "time set day",
]


def build_course():
    for c in COURSE_CMDS:
        out = rcon(c)
        print(f"  rcon: {c} -> {out[:60]}")
    for check in ("0 -59 20 dirt", "0 -59 40 dirt", "0 -58 40 sand"):
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


def run_course(name, tp_cmd, goto_cmd, box, timeout_s, passage_check):
    xmin, xmax, ymin, zmin, zmax = box
    print(f"[course {name}]")
    rcon(tp_cmd)
    time.sleep(3)
    print(f"  start pos: {bot_pos()}")
    py4j("chat", msg=";stop")
    time.sleep(1)
    py4j("chat", msg=goto_cmd)
    print(f"  sent: {goto_cmd}")

    t0 = time.time()
    midshot = [False]

    def arrived():
        if not midshot[0] and time.time() - t0 > 25:
            midshot[0] = True
            snap(f"{name}_mid")
        p = bot_pos()
        return (xmin <= p[0] <= xmax and p[1] >= ymin
                and zmin <= p[2] <= zmax) and p or None

    try:
        pos = wait_for(f"{name}: bot past the wall", arrived, timeout_s)
        # arriving is not enough — the wall must actually be mined through
        mined = "passed" in rcon(f"execute if block {passage_check} air").lower()
        if not mined:
            print(f"  FAIL {name}: reached the far side but the wall is intact "
                  f"(detour, no mining) — passage {passage_check} still solid")
            return False
        print(f"  PASS {name}: pos={pos}, passage mined at {passage_check}")
        return True
    except TimeoutError as e:
        print(f"  FAIL {name}: {e}")
        print(f"  final pos: {bot_pos()}")
        snap(f"{name}_fail")
        for line in py4j("recent", n=12).get("chat", []):
            print(f"  chat| {line}")
        return False
    finally:
        py4j("chat", msg=";stop")
        time.sleep(1)


def main():
    print("[1/3] server + course...")
    wait_for("server rcon", lambda: "players" in rcon("list"), 300, 5)
    build_course()

    print("[2/3] client...")
    wait_for("py4j gateway", lambda: py4j("state") is not None, 600, 10)
    st = py4j("state")
    print(f"  client state: {st}")
    if not st["inGame"]:
        py4j("connect", ip="test-server")
        wait_for("bot in game", lambda: py4j("state")["inGame"], 180, 5)
        time.sleep(5)
    rcon(f"clear {BOT}")  # bare hands: dirt/sand mine in ~0.75s each

    print("[3/3] courses...")
    results = {}
    results["C_wall"] = run_course(
        "C_wall",
        f"tp {BOT} -5.5 -60 20.5 -90 0",
        ";goto 5 -60 20",
        (3.5, 8.5, -60.3, 15.5, 25.5),
        120,
        "0 -60 20 air",
    )
    results["D_sand"] = run_course(
        "D_sand",
        f"tp {BOT} -5.5 -60 40.5 -90 0",
        ";goto 5 -60 40",
        (3.5, 8.5, -60.3, 35.5, 45.5),
        150,
        "0 -60 40 air",
    )

    print("\n=== RESULTS ===")
    ok = True
    for k, v in results.items():
        print(f"  {k}: {'PASS' if v else 'FAIL'}")
        ok &= v
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
