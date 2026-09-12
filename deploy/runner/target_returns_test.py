#!/usr/bin/env python3
"""G63 bench: a target the bot could not reach is not lost for ever, and a target it CAN reach is
preferred over one it cannot.

The operator's standing complaint is "failed to get target, blacklisting -- there must be NO such
cases at all". The machinery behind that message condemned a pig, a log or a drop on a failure
count with no clock, and every chooser FILTERED by the verdict -- so a patch of world whose
candidates had each failed once answered "there is nothing here", which downstream is the wander
and the "Failed exploring" on the recordings, with the thing the bot wants in plain sight.

    python3 deploy/runner/target_returns_test.py        # exit 0 = PASS

Phase A -- the target comes back: a pig sealed in bedrock six blocks away, `@get porkchop 1`. The
bot cannot reach it and steps aside. After 45 seconds the wall opens. PASS = a porkchop within the
window, i.e. the pig was still a candidate when it became reachable.
Phase B -- the reachable one wins: an unreachable drop (sealed in bedrock, two blocks away) and a
reachable one of the same item twenty blocks off. PASS = the reachable drop is picked up.
Flat server, peaceful, the bot with a stone sword.
"""
import functools, json, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z, GROUND = 1000, 300, -61
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
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",10))]}
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


def ready_bot(x):
    """⛔ THE FLAT SERVER'S SPAWN HAD NO FLOOR (2026-09-12): 7203 blocks of air where the ground
    should be, so a bot that died -- or that was never teleported in time -- fell out of the world,
    respawned at spawn and fell again, 35 times in one bench window. Every verdict after that is
    worthless. So: a spawn point ON the arena, full health, and a body actually standing there."""
    rcon(f"spawnpoint {BOT} {x} {GROUND+1} {Z}")
    rcon(f"gamemode survival {BOT}")
    for _ in range(4):
        rcon(f"tp {BOT} {x+0.5} {GROUND+1} {Z+0.5} 270 0")
        rcon(f"effect give {BOT} minecraft:instant_health 1 10")
        time.sleep(1)
        try:
            px, py, pz = (float(v) for v in py4j("gs")["pos"].split(","))
            if abs(px - x) < 6 and abs(pz - Z) < 6 and py > GROUND - 2:
                return True
        except Exception:
            pass
    print("  WARNING: the bot would not stay on the arena")
    return False


def clear_area(x):
    rcon("kill @e[type=pig]")
    rcon(f"kill @e[type=item,x={x},y={GROUND},z={Z},distance=..40]")
    rcon(f"fill {x-8} {GROUND-4} {Z-8} {x+30} {GROUND+8} {Z+8} minecraft:air")
    rcon(f"fill {x-8} {GROUND-4} {Z-8} {x+30} {GROUND} {Z+8} minecraft:stone")
    rcon(f"fill {x-8} {GROUND} {Z-8} {x+30} {GROUND} {Z+8} minecraft:grass_block")


def stats_tokens():
    try:
        st = py4j("stats")["s"]
        return " ".join(t for t in st.split() if t.startswith(("banLifted=", "banExpired=", "dc=", "wander=")))
    except Exception:
        return "?"


def seal(x, z, on):
    """A 3x3x3 bedrock shell around (x, GROUND+1, z), or the same volume opened up."""
    block = "minecraft:bedrock" if on else "minecraft:air"
    rcon(f"fill {x-1} {GROUND+1} {z-1} {x+1} {GROUND+3} {z+1} {block} hollow")
    if not on:
        rcon(f"fill {x-1} {GROUND+1} {z-1} {x+1} {GROUND+3} {z+1} minecraft:air")


def phase_a():
    x = X
    clear_area(x)
    time.sleep(1)
    ready_bot(x)
    rcon(f"clear {BOT}"); rcon(f"give {BOT} minecraft:stone_sword")
    time.sleep(1)
    rcon(f"summon minecraft:pig {x+6.5} {GROUND+1} {Z+0.5} {{NoAI:1b}}")
    seal(x + 6, Z, True)
    time.sleep(1)
    print(f"[A] pig sealed in bedrock 6 east of the bot. @get porkchop 1")
    py4j("cmd", c="@get porkchop 1")
    t0 = time.time(); opened = False; got = False
    while time.time() - t0 < 150:
        time.sleep(5)
        el = time.time() - t0
        if el > 45 and not opened:
            seal(x + 6, Z, False)
            opened = True
            print(f"  t={el:.0f}s WALL OPENED — the pig is now reachable")
        got = any("porkchop" in i for i in py4j("inv")["ids"])
        gs = py4j("gs")
        print(f"  t={el:.0f}s pos={gs['pos']} porkchop={got}")
        if got and opened:
            break
    print(f"  counters: {stats_tokens()}")
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    ok = got and opened
    print(f"[A] result: the target came back = {ok}")
    return ok


def phase_b():
    x = X + 60
    clear_area(x)
    time.sleep(1)
    ready_bot(x)
    rcon(f"clear {BOT}")
    time.sleep(1)
    # the unreachable one, two blocks away, sealed; the reachable one twenty blocks off
    rcon(f"summon minecraft:item {x+2.5} {GROUND+1.5} {Z+0.5} {{Item:{{id:\"minecraft:diamond\",count:1}},PickupDelay:0}}")
    seal(x + 2, Z, True)
    rcon(f"summon minecraft:item {x+20.5} {GROUND+1.5} {Z+0.5} {{Item:{{id:\"minecraft:diamond\",count:1}},PickupDelay:0}}")
    time.sleep(1)
    print("[B] a diamond sealed in bedrock 2 east, another in the open 20 east. @get diamond 1")
    py4j("cmd", c="@get diamond 1")
    t0 = time.time(); got = False
    while time.time() - t0 < 90:
        time.sleep(5)
        got = any("diamond" in i for i in py4j("inv")["ids"])
        gs = py4j("gs")
        print(f"  t={time.time()-t0:.0f}s pos={gs['pos']} diamond={got}")
        if got:
            break
    print(f"  counters: {stats_tokens()}")
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    print(f"[B] result: took the reachable drop = {got}")
    return got


def main():
    if not py4j("state")["inGame"] or BOT not in rcon("list"):
        py4j("connect", ip="test-server")
        t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined test-server"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    for k, v in PINS:
        py4j("chatcmd", c=f";settings {k} {v}"); time.sleep(0.3); print(f"pinned {k}={v}")
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {X-16} {Z-16} {X+96} {Z+16}"); time.sleep(1)
    a = phase_a()
    b = phase_b()
    rcon("kill @e[type=pig]")
    rcon(f"forceload remove {X-16} {Z-16} {X+96} {Z+16}")
    if a and b:
        print("PASS: the unreachable target came back when it opened, and the reachable one won")
        return 0
    print("FAIL")
    return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
