#!/usr/bin/env python3
"""G61 bench: a pig four blocks away is walked at and killed -- the bot never stands looking at
it. Two phases: open ground, and the same pig with an eight-block pit two blocks behind it, which
is the "near a dangerous drop" case in which the kill task stops its own rush and hands the last
blocks to the entity approach.

The 19:57 recording (2026-09-11): the bot facing a pig, aimed at it, motionless -- the strike gate
says "cannot hit" at 3-4 blocks, the kill task says "approach", and inside 3.5 blocks the approach
had nothing that moved the body for the first six seconds (the close walk is a last resort behind
the progress checker) or held a thirty-second physics lock that moved it zero.

    python3 deploy/runner/pig_stare_test.py            # exit 0 = PASS

Flat server, difficulty peaceful, the bot with a stone sword, `@get porkchop 1`. PASS = a porkchop
in the inventory within the window in both phases, and no "Failed to get to target".
"""
import functools, json, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 900, 300
GROUND = -61
WINDOW_S = 60
PINS = [kv.split("=", 1) for kv in os.environ.get("UC_PINS", "").split(",") if "=" in kv]

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame(),"busy":mc.hasActiveTask()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chatcmd": mc.ChatMessage(req["c"]); out={"ok":True}
elif op=="gs": out=dict(mc.getGameState().get("self") or {})
elif op=="inv":
    ids=[]
    try:
        for s in mc.getInventoryFull().get("slots") or []:
            sd=dict(s)
            if not sd.get("empty"):
                nm=str(sd.get("item") or sd.get("name") or "")
                if nm: ids.append(nm)
    except Exception: pass
    out={"ids":ids}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",8))]}
elif op=="task": out={"chain": str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-300:]}
elif op=="stats": out={"s": str(mc.placeStats())}
print(json.dumps(out,default=str)); gw.close()
"""


def sh(a, to=60):
    return subprocess.run(a, capture_output=True, text=True, timeout=to)


def py4j(op, to=40, **kw):
    r = sh(["docker", "exec", C1, "python3", "-c", SNIP, json.dumps({"op": op, **kw})], to)
    if r.returncode != 0:
        raise RuntimeError(f"{op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])


def rcon(c):
    return sh(["docker", "exec", SERVER, "rcon-cli", c]).stdout.strip()


def phase(name, x, pit):
    rcon("kill @e[type=pig]"); rcon(f"kill @e[type=item,x={x},y={GROUND},z={Z},distance=..30]")
    rcon(f"fill {x-6} {GROUND-9} {Z-6} {x+12} {GROUND+6} {Z+6} minecraft:air")
    rcon(f"fill {x-6} {GROUND-9} {Z-6} {x+12} {GROUND} {Z+6} minecraft:stone")
    rcon(f"fill {x-6} {GROUND} {Z-6} {x+12} {GROUND} {Z+6} minecraft:grass_block")
    if pit:
        # an eight-deep pit two blocks beyond the pig: the kill task's edge caution fires here
        rcon(f"fill {x+6} {GROUND-8} {Z-6} {x+8} {GROUND} {Z+6} minecraft:air")
    time.sleep(1)
    rcon(f"tp {BOT} {x+0.5} {GROUND+1} {Z+0.5} 270 0")   # facing +x (east)
    rcon(f"clear {BOT}"); rcon(f"give {BOT} minecraft:stone_sword")
    time.sleep(1)
    r = rcon(f"summon minecraft:pig {x+4.5} {GROUND+1} {Z+0.5} {{NoAI:1b}}")
    time.sleep(1.5)
    gs = py4j("gs")
    print(f"[{name}] bot at {gs['pos']}; pig 4 east{' with an 8-deep pit 2 blocks behind it' if pit else ''} "
          f"({r[:30]!r}). @get porkchop 1")
    py4j("cmd", c="@get porkchop 1")
    t0 = time.time(); seen = set(); got = False; still = 0; last = None
    bad = {"Failed to get to target": 0, "lock moved nothing": 0, "Close but no line of sight": 0}
    while time.time() - t0 < WINDOW_S:
        time.sleep(4)
        gs = py4j("gs"); pos = gs["pos"]
        ch = [c for c in py4j("chat", n=12)["chat"] if c not in seen]; seen.update(ch)
        for k in bad:
            bad[k] += sum(1 for c in ch if k in c)
        got = any("porkchop" in i for i in py4j("inv")["ids"])
        try:
            chain = py4j("task")["chain"][-90:]
        except Exception:
            chain = "?"
        if pos == last: still += 1
        last = pos
        print(f"  t={time.time()-t0:.0f}s pos={pos} porkchop={got} | {chain}")
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    try:
        st = py4j("stats")["s"]
        tok = [t for t in st.split() if t.startswith(("entLongHaul=", "lock=", "dte=", "kaTung=", "entityCloseWalk=",
                                                          "nearLockDropped=", "entityReleased=", "pdRouteStopped="))]
        print(f"  counters: {' '.join(tok)}")
    except Exception:
        pass
    flaws = {k: v for k, v in bad.items() if v}
    print(f"[{name}] result: porkchop={got} took={time.time()-t0:.0f}s stillSamples={still} flaws={flaws or 'none'}")
    return got and not flaws


def main():
    if not py4j("state")["inGame"]:
        py4j("connect", ip="test-server"); time.sleep(3)
    if BOT not in rcon("list"):
        py4j("connect", ip="test-server"); t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined test-server"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    for k, v in PINS:
        py4j("chatcmd", c=f";settings {k} {v}"); time.sleep(0.3); print(f"pinned {k}={v}")
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {X-10} {Z-10} {X+40} {Z+10}"); time.sleep(1)
    a = phase("open ground", X, False)
    b = phase("pit behind", X + 20, True)
    rcon("kill @e[type=pig]")
    rcon(f"forceload remove {X-10} {Z-10} {X+40} {Z+10}")
    if a and b:
        print("PASS: walked at the pig and killed it, on open ground and at the pit's edge"); return 0
    print("FAIL"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
