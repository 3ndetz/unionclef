#!/usr/bin/env python3
"""G68 bench: a goal behind a one-block slot no body fits through -- the route must be GIVEN UP
in seconds, not computed for ever.

The operator watched it live: the bot at the mouth of a one-block hole it could not squeeze into,
the physics search drawn out toward it, nothing moving. Walking dead-ends, the goal goes to the
physics engine, the engine runs its fifteen-second budget and its twenty-second no-progress cap,
the navigator re-plans from the same feet and hands the same cell over again. Baritone plans for
500 ms, once more for 2000 ms, and then says "Unable to find path". Ours now does the same: the
hand-off carries its own budget, a hand-off that moved the body nowhere is asked once more with
the longer budget, the second failure gives the route up and the cell is refused for a minute.

    python3 deploy/runner/slot_hole_test.py          # exit 0 = PASS

Flat server: a bedrock box with the goal inside and a single one-high slot at floor level as its
only opening; the bot outside with an empty inventory (nothing to dig or tower with), `@goto` the
cell inside. PASS = within the window the navigator reports the route given up after the physics
engine failed twice (navPhysics=failed/gaveUp with gaveUp >= 1), the first give-up within 25 s of
the goto, and no search ever ran to the old twenty-second cap.
"""
import functools, json, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
BX, BY, BZ = 2100, -60, 300          # the bot's start column; the flat world's floor is at -60
BOXX0, BOXX1 = BX + 6, BX + 14       # bedrock box, outer walls inclusive
BOXZ0, BOXZ1 = BZ - 4, BZ + 4
GOAL = (BX + 10, BY + 1, BZ)         # inside the box, on the floor
SLOT = (BOXX0, BY + 1, BZ)           # the one-high opening in the west wall, at floor level
WINDOW_S = 60
FIRST_GIVEUP_S = 25
PINS = [kv.split("=", 1) for kv in os.environ.get("UC_PINS", "").split(",") if "=" in kv]

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
def me():
    s=dict(mc.getGameState().get("self") or {}); return s.get("pos")
if op=="state": out={"inGame":mc.inGame(),"pos":me(),"busy":mc.hasActiveTask()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chatcmd": mc.ChatMessage(req["c"]); out={"ok":True}
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


def build():
    # open ground between the bot and the box
    rcon(f"fill {BX-3} {BY+1} {BZ-6} {BOXX1+3} {BY+6} {BZ+6} minecraft:air")
    rcon(f"fill {BX-3} {BY} {BZ-6} {BOXX1+3} {BY} {BZ+6} minecraft:stone")
    # the box: bedrock shell, hollow inside, floor kept
    rcon(f"fill {BOXX0} {BY+1} {BOXZ0} {BOXX1} {BY+5} {BOXZ1} minecraft:bedrock")
    rcon(f"fill {BOXX0+1} {BY+1} {BOXZ0+1} {BOXX1-1} {BY+4} {BOXZ1-1} minecraft:air")
    sx, sy, sz = SLOT
    rcon(f"setblock {sx} {sy} {sz} minecraft:air")      # the slot: one high, bedrock above it


def counters():
    st = py4j("stats")["s"]
    out = {}
    for t in st.split():
        if t.startswith(("navPhysics=", "physicsBudgetOut=", "navStall=", "navDeadEnd=")):
            k, v = t.split("=", 1); out[k] = v
    return out


def main():
    if not py4j("state")["inGame"] or BOT not in rcon("list"):
        py4j("connect", ip="test-server")
        t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    py4j("chatcmd", c=";settings fireReleaseNeedsFire true"); time.sleep(0.3)
    for k, v in PINS:
        py4j("chatcmd", c=f";settings {k} {v}"); time.sleep(0.3); print(f"pinned {k}={v}")
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {BX-12} {BZ-12} {BOXX1+12} {BZ+12}"); time.sleep(1)
    build()
    time.sleep(1)
    sx, sy, sz = SLOT
    slot = rcon(f"execute if block {sx} {sy} {sz} minecraft:air")
    lid = rcon(f"execute if block {sx} {sy+1} {sz} minecraft:bedrock")
    if "passed" not in slot.lower() or "passed" not in lid.lower():
        print(f"FAIL: the scene was not built (slot={slot!r}, lid={lid!r})"); return 2
    rcon(f"spawnpoint {BOT} {BX} {BY+1} {BZ}")
    rcon(f"tp {BOT} {BX+0.5} {BY+1} {BZ+0.5}")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10")
    rcon(f"clear {BOT}")
    time.sleep(2)
    counters()   # reads AND resets the counters, so the window below is this run's alone
    start = py4j("state")
    gx, gy, gz = GOAL
    print(f"outside the box at {start['pos']}, the only way in a one-high slot at {SLOT}; @goto {gx} {gy} {gz}")
    py4j("cmd", c=f"@goto {gx} {gy} {gz}")
    t0 = time.time(); seen = set(); first_giveup = None; old_cap = 0; gave_up = 0; failed = 0
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        s = py4j("state")
        chat = [c for c in py4j("chat", n=12)["chat"] if c not in seen]; seen.update(chat)
        note = [c for c in chat if any(w in c for w in ("physics", "giving the route", "Search gave up", "no route within", "dead-ends"))]
        for c in chat:
            if "giving the route up" in c and first_giveup is None:
                first_giveup = time.time() - t0
            if "goal unreachable after" in c:
                old_cap += 1
        c = counters()
        try:
            failed, gave_up = (int(x) for x in c.get("navPhysics", "0/0").split("/"))
        except ValueError:
            pass
        print(f"  t={time.time()-t0:.0f}s pos={s['pos']} busy={s['busy']} navPhysics={c.get('navPhysics','?')}"
              f" budgetOut={c.get('physicsBudgetOut','?')}" + (" | " + " || ".join(x[-80:] for x in note) if note else ""))
        if gave_up >= 1 and first_giveup is not None and time.time() - t0 > 12:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    rcon(f"forceload remove {BX-12} {BZ-12} {BOXX1+12} {BZ+12}")
    print(f"result: navPhysics={failed}/{gave_up} firstGiveUp={first_giveup} oldCapSearches={old_cap}")
    if gave_up >= 1 and first_giveup is not None and first_giveup <= FIRST_GIVEUP_S and old_cap == 0:
        print("PASS: the route through the slot was given up in seconds, after two bounded searches"); return 0
    if gave_up < 1:
        print("FAIL: the physics engine never gave the slot up"); return 1
    if old_cap:
        print(f"FAIL: {old_cap} search(es) still ran to the twenty-second cap"); return 1
    print(f"FAIL: the first give-up took {first_giveup:.0f}s (> {FIRST_GIVEUP_S}s)"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
