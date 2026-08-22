#!/usr/bin/env python3
"""Reproduce a KNOWN navigation stall on demand, instead of waiting for a run to wander into it.

The playthrough's navigation wall is deterministic: four runs in one series stalled at zero rungs
on <Getting to block ...>, three of them at the SAME block (68,127,-60). Waiting for that to
recur costs a six-minute run and hits maybe one time in two. Walking to it costs a minute.

That difference is the whole point. Three remedies at the neighbouring wall were built on a shape
read off one run and measured away afterwards; a repro that can be run twenty times in an hour is
what stops the next one being built the same way.

    python deploy/runner/repro_stall.py 68 127 -60 [--from 74 127 -57] [--secs 60]

Prints the scene and the counters, and says plainly whether the bot got there.
"""
import json
import subprocess
import sys
import time

CLIENT = "uctest-mc-tester1"
GSERVER = "uctest-gamer-server"
BOT = "tester1"
PORT = 25333

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(port=req["port"],auto_field=True))
mc=gw.entry_point
op=req["op"]
if   op=="gs":    out={"self": str(dict(mc.getGameState()).get("self"))}
elif op=="stats": out={"s": str(mc.placeStats() or "")}
elif op=="chatcmd": mc.ChatMessage(req["c"]); out={"ok":True}
elif op=="resetstats": mc.resetValues(); mc.resetRunCounters(); out={"ok":True}  # resetValues() only rewrites three server dict entries -- resetRunCounters() is the one that zeroes the counters, and for a long time this op called only the former
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="state": out={"inGame": bool(mc.inGame())}
elif op=="shapes": out={"r": str(mc.noClassShapes() or "")}
elif op=="task":  out={"chain": str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-800:]}
else: out={"err":"?"}
print(json.dumps(out))
"""


def sh(cmd, t=40):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=t)


def py4j(op, **kw):
    r = sh(["docker", "exec", CLIENT, "python3", "-c", SNIP,
            json.dumps({"op": op, "port": PORT, **kw})])
    if r.returncode != 0:
        raise RuntimeError(f"{op}: {r.stderr.strip()[:900]}")
    return json.loads(r.stdout.strip().splitlines()[-1])


def grcon(c):
    return sh(["docker", "exec", GSERVER, "rcon-cli", c]).stdout.strip()


def field(stats, name):
    for tok in stats.split():
        if tok.startswith(name + "="):
            return tok[len(name) + 1:]
    return "-"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) < 3:
        raise SystemExit(__doc__)
    tx, ty, tz = (int(a) for a in args[:3])
    secs = int(sys.argv[sys.argv.index("--secs") + 1]) if "--secs" in sys.argv else 60
    if "--from" in sys.argv:
        i = sys.argv.index("--from")
        start = " ".join(sys.argv[i + 1:i + 4])
    else:
        # Default: a few blocks short of the target, on the same level.
        start = f"{tx + 6} {ty} {tz + 6}"

    # ⛔ CONNECT FIRST. A deploy restarts the client, so the bot is in no world at all and every
    # counter reads 0 -- which looks exactly like "the fix changed nothing" and is not.
    if not py4j("state").get("inGame"):
        print("[0] bot is not in a world, connecting to gamer-server")
        py4j("connect", ip="gamer-server")
        for _ in range(12):
            time.sleep(5)
            if py4j("state").get("inGame"):
                break
        print("    in game:", py4j("state").get("inGame"))
    print(f"[1] teleport to {start}, target {tx} {ty} {tz}")
    grcon(f"tp {BOT} {start}")
    time.sleep(2)
    # PIN A FLAG BEFORE THE STATS ARE ZEROED, so the run measures the setting it names.
    # ";settings" is tungsten's own chat command and needs ChatMessage, not ExecuteCommand.
    # Both arms SET the value explicitly -- omitting the pin measures whatever the last run
    # happened to leave behind, which is how a pinned experiment leaks into a baseline.
    for _p in [sys.argv[i + 1] for i, a in enumerate(sys.argv) if a == "--pin"]:
        _k, _, _v = _p.partition("=")
        py4j("chatcmd", c=f";settings {_k} {_v}")
        print(f"    pinned {_k}={_v}")
    time.sleep(1.0)
    py4j("resetstats")
    before = py4j("gs").get("self")

    print("[2] issue the goto")
    py4j("chatcmd", c=f"@goto {tx} {ty} {tz}")

    for step in range(secs // 10):
        time.sleep(10)
        pos = py4j("gs").get("self")
        print(f"    t={10 * (step + 1):>3}s pos={pos}")

    stats = py4j("stats").get("s") or ""
    after = py4j("gs").get("self")
    print(f"\nstart {before}\nend   {after}")
    for k in ("mqStarted", "mqSteps", "mqNoClass", "mqExpand", "mqRefused", "qNoMove",
              "navBridgeRescued", "pdEnter", "pdWalking", "stuck"):
        print(f"  {k}={field(stats, k)}")
    # WHICH SHAPE IS BEING TRUNCATED? mqNoClass counts them and never says what they are, and
    # this repro just showed queueParkour changing that count by nothing (477 against 479, the
    # flag verified on both sides). So the missing class here is NOT parkour, and this tally is
    # the only thing that can name what it is.
    print("")
    print("truncated edge shapes (dx,dy,dz):", py4j("shapes").get("r", "?"))
    print("\ntask:", py4j("task").get("chain", "")[-300:])


if __name__ == "__main__":
    main()
