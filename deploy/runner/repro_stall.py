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
    # ALWAYS CONNECT -- inGame means the bot is in A world, not in the GAMER one.
    # This used to skip the connect whenever the client was already in a game, and after a suite
    # run it always is: it sits on the flat test arena at y=-60. The rcon teleport below then goes
    # to a server the bot is not on, does nothing, and the repro measures featureless flat ground
    # with every counter honestly zero. gamer_smoke learned this already and connects
    # unconditionally with retries; this is the same routine, for the same reason.
    print("[0] connecting to gamer-server (always, not only when out of a world)")
    joined = False
    for attempt in range(4):
        py4j("connect", ip="gamer-server")
        for _ in range(12):
            time.sleep(5)
            if py4j("state").get("inGame"):
                joined = True
                break
        if joined:
            break
        print(f"    connect attempt {attempt + 1} did not land, retrying")
    if not joined:
        print("    STAND DOWN: client would not join the gamer server")
        sys.exit(3)
    print("    in game:", joined)
    # ⛔ "IN A WORLD" IS NOT "IN THE RIGHT WORLD" -- CHECKLIST RULE 4k.
    # The gate above only asked whether the client was in SOME game, and after a suite run it is:
    # it sits on the flat test arena at y=-60. The teleport below goes over rcon to the GAMER
    # server, which the bot is not on, so it silently does nothing and the whole repro then
    # measures a bot standing on featureless flat ground -- no holes, no terrain, every counter
    # honestly zero. Four arms of an A/B were read that way before the coordinates gave it away.
    # Verify by RESULT rather than by belief: teleport, read the position back, and if the bot did
    # not land near the start it is on the wrong server -- reconnect and try again.
    # ⛔ A TERRAIN REPRO ON A PERSISTENT WORLD IS CONSUMED BY THE RUNS THAT USE IT.
    # The stall this tool exists for is a NOTCH: two solid corners at the body's level with the
    # route threading between them. The bot's job at that moment is to mine wood, and over a few
    # dozen runs it simply DUG THE NOTCH AWAY. Read before and after the same session:
    #
    #     cornerA (85,125,-55)  grass_block  ->  air
    #     cornerB (84,125,-54)  dirt         ->  air
    #     underDest (84,124,-55) grass_block ->  air
    #
    # After that the wedge stopped happening in BOTH arms and the repro measured nothing while
    # looking exactly like a fix. Worse, the arms run in sequence, so the second arm of every pair
    # always got more-eroded ground -- a bias pointing the same way every time.
    #
    # So restore the cells that define the geometry before each run. --restore takes a JSON file of
    # {"x,y,z": "minecraft:block"} and sets each one over rcon.
    _rest = [sys.argv[i + 1] for i, a in enumerate(sys.argv) if a == "--restore"]
    if _rest:
        with open(_rest[0], encoding="utf-8") as fh:
            cells = json.load(fh)
        for key, blk in cells.items():
            bx, by, bz = key.split(",")
            grcon(f"setblock {bx} {by} {bz} {blk} replace")
        print(f"    restored {len(cells)} cells from {_rest[0]}")
        time.sleep(1.0)

    print(f"[1] teleport to {start}, target {tx} {ty} {tz}")
    _want = [float(v) for v in start.split()]
    for _attempt in range(3):
        grcon(f"tp {BOT} {start}")
        time.sleep(2)
        # The gs op stringifies the whole map (see the snippet above: str(...)), so "self" is
        # ALWAYS text that merely LOOKS like a dict -- never a dict. Reading it with .get() was
        # wrong in both of its earlier forms: once as an AttributeError, then as an isinstance
        # check that could not ever be true, which reported "did not land" on a bot that had
        # landed perfectly well. Pull the field out of the text.
        _self = str(py4j("gs").get("self"))
        _mark = "'pos': '"
        _p = ""
        if _mark in _self:
            _p = _self.split(_mark, 1)[1].split("'", 1)[0]
        try:
            _got = [float(v) for v in str(_p).split(",")]
        except ValueError:
            _got = None
        # HORIZONTAL ONLY, AND GENEROUSLY. The question this check exists to answer is "is the
        # bot on the server the teleport was sent to", not "is it standing exactly there". Two
        # seconds after landing at 74,127,-54 the bot has already fallen seven blocks -- the spot
        # is above ground -- so a 3D tolerance rejected a teleport that had worked perfectly and
        # sent the run into a reconnect loop. X and Z do not lie about which world we are in.
        if _got and ((_got[0] - _want[0]) ** 2 + (_got[2] - _want[2]) ** 2) < 36.0:
            break
        print(f"    teleport did not land (at {_p}) -- reconnecting to gamer-server")
        py4j("connect", ip="gamer-server")
        for _ in range(12):
            time.sleep(5)
            if py4j("state").get("inGame"):
                break
    else:
        print("    STAND DOWN: could not put the bot on the gamer server; numbers would be void")
        sys.exit(3)
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
              "navBridgeRescued", "walkerHoleHeld", "diagonalWalled", "staleTail", "ungagged", "walkYield", "walkMode", "qNoMove",
              "pdEnter", "pdWalking", "stuck"):
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
