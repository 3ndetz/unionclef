#!/usr/bin/env python3
"""G48 bench: an animal forty blocks away is reached and killed -- the long haul goes through the
drive, not through a 30-second physics lock that moves the body zero.

The 15:38 recorded playthrough stood still for its last five minutes on "Collect food -> Killing
chicken -> Approach entity -> Failed to get to target, wandering": the chicken was 45 blocks away,
the physics lock read m0.0, the wander was refused 4014 times.

    python3 deploy/runner/far_mob_test.py            # exit 0 = PASS

Flat server: a chicken (NoAI, so it stays put) forty blocks east across open ground with a
two-block ledge halfway, the bot with a stone sword, `@get chicken 1`. PASS = raw chicken in the
inventory within the window and zero "Failed to get to target" lines.
"""
import functools, json, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 820, 360
# UC_PINS="flag=value,flag=value" applies `;settings flag value` before the course (an A/B knob;
# the setting PERSISTS in the client's tungsten.json, so a run that flips a flag off must be
# followed by one that flips it back).
PINS = [kv.split("=", 1) for kv in os.environ.get("UC_PINS", "").split(",") if "=" in kv]
GROUND = -61
DIST = 40
WINDOW_S = 120

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
        py4j("chatcmd", c=f";settings {k} {v}"); time.sleep(0.3)
        print(f"pinned {k}={v}")
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {X-4} {Z-6} {X+DIST+6} {Z+6}"); time.sleep(1)
    rcon("kill @e[type=chicken]"); rcon(f"kill @e[type=item,x={X},y={GROUND},z={Z},distance=..60]")
    rcon(f"fill {X-4} {GROUND+1} {Z-6} {X+DIST+6} {GROUND+6} {Z+6} minecraft:air")
    rcon(f"fill {X-4} {GROUND} {Z-6} {X+DIST+6} {GROUND} {Z+6} minecraft:grass_block")
    # a two-block ledge halfway: the far side is two up, so walking alone does not reach it
    r = rcon(f"fill {X+20} {GROUND+1} {Z-6} {X+DIST+6} {GROUND+2} {Z+6} minecraft:stone")
    time.sleep(1)
    ok = rcon(f"execute if block {X+25} {GROUND+2} {Z} minecraft:stone")
    print(f"scene: ledge fill -> {r[:40]!r}; present -> {ok[:30]!r}")
    if "passed" not in ok:
        print("FAIL: the scene was not built (chunk not loaded?)"); return 2
    rcon(f"tp {BOT} {X+0.5} {GROUND+1} {Z+0.5} 270 0")
    rcon(f"clear {BOT}"); rcon(f"give {BOT} minecraft:stone_sword"); rcon(f"give {BOT} minecraft:cobblestone 8")
    time.sleep(1)
    r = rcon(f"summon minecraft:chicken {X+DIST+0.5} {GROUND+3} {Z+0.5} {{NoAI:1b}}")
    time.sleep(2)
    n = rcon(f"execute if entity @e[type=chicken,x={X+DIST},y={GROUND+3},z={Z},distance=..3]")
    gs = py4j("gs")
    print(f"bot at {gs['pos']}; a chicken {DIST} blocks east on a two-block ledge ({r[:30]!r}; present: {n[:30]!r}). @get chicken 1")
    py4j("cmd", c="@get chicken 1")
    t0 = time.time(); seen = set(); got = False
    bad = {"Failed to get to target": 0, "Failed exploring": 0, "lock moved nothing": 0}
    while time.time() - t0 < WINDOW_S:
        time.sleep(5)
        gs = py4j("gs"); pos = gs["pos"]
        ch = [c for c in py4j("chat", n=14)["chat"] if c not in seen]; seen.update(ch)
        for k in bad:
            bad[k] += sum(1 for c in ch if k in c)
        got = any(i.endswith("chicken") for i in py4j("inv")["ids"])
        note = [c for c in ch if any(w in c for w in ("Failed", "Pillar", "NO ROUTE", "wander", "lock"))]
        try:
            chain = py4j("task")["chain"][-90:]
        except Exception:
            chain = "?"
        print(f"  t={time.time()-t0:.0f}s pos={pos} chicken={got} | {chain}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    try:
        st = py4j("stats")["s"]
        tok = [t for t in st.split() if t.startswith(("entLongHaul=", "pdFnBuild=", "pdNearBuild=", "navStall=", "lock=",
                                                          "pdRouteStopped=", "navStop=", "dte=", "mqStarted=", "mqSteps=",
                                                          "mqTimeout=", "mqLost=", "walkerHeldAbove=", "dc="))]
        print(f"  counters: {' '.join(tok)}")
    except Exception:
        pass
    rcon("kill @e[type=chicken]")
    rcon(f"forceload remove {X-4} {Z-6} {X+DIST+6} {Z+6}")
    flaws = {k: v for k, v in bad.items() if v}
    print(f"result: chicken={got} flaws={flaws or 'none'}")
    if got and not flaws:
        print("PASS: walked the long haul through the drive and took the chicken"); return 0
    print("FAIL"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
