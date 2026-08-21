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
elif op=="resetstats": mc.resetValues(); out={"ok":True}
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

    print(f"[1] teleport to {start}, target {tx} {ty} {tz}")
    grcon(f"tp {BOT} {start}")
    time.sleep(2)
    py4j("resetstats")
    before = py4j("gs").get("self")

    print("[2] issue the goto")
    py4j("chatcmd", c=f";goto {tx} {ty} {tz}")

    for step in range(secs // 10):
        time.sleep(10)
        pos = py4j("gs").get("self")
        print(f"    t={10 * (step + 1):>3}s pos={pos}")

    stats = py4j("stats").get("s") or ""
    after = py4j("gs").get("self")
    print(f"\nstart {before}\nend   {after}")
    for k in ("mqStarted", "mqSteps", "mqNoClass", "qNoMove", "pdEnter", "pdWalking", "stuck"):
        print(f"  {k}={field(stats, k)}")
    print("\ntask:", py4j("task").get("chain", "")[-300:])


if __name__ == "__main__":
    main()
