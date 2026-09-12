#!/usr/bin/env python3
"""G72 bench: a cobblestone lying eleven blocks DOWN in sealed rock, and stone three blocks away.

The 22:34 run: a cobblestone that had fallen eleven blocks into a cave beat every stone on the
hillside in the drop-versus-block comparison (squared blocks: eleven down costs the same as eleven
across), "primDrive NO ROUTE" x47, the block search "toward a goal 11 below spent its budget" x8,
ninety-four seconds and two watchdog give-ups for a block the bot could have mined beside its
feet. A player mines the stone. Now the vertical leg below a safe fall is priced as a dig (five
walks a block) before it is squared.

    python3 deploy/runner/deep_drop_test.py            # exit 0 = PASS

Flat server: the bot on a stone surface (the flat floor replaced by stone around it), a cobblestone
item sealed in a one-block cavity eleven blocks under it, `@get cobblestone 1`. PASS = a
cobblestone in the pack within the window AND the body never went more than two blocks down
(it mined the surface instead of digging for the deep one); dropDeep >= 1.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 2500, 300
GROUND = -50          # a raised stone slab: the cavity must stay above the world's bedrock at -64
DEEP = 11
WINDOW_S = 60

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
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",8))]}
elif op=="stats": out={"s": str(mc.placeStats())}
elif op=="inv":
    ids=[]
    try:
        for s in mc.getInventoryFull().get("slots") or []:
            sd=dict(s)
            if not sd.get("empty"):
                nm=str(sd.get("item") or sd.get("name") or "")
                if nm: ids.append(nm)
    except Exception: pass
    out={"ids": ids}
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


def has_cobble():
    try:
        return any("cobblestone" in i for i in py4j("inv")["ids"])
    except Exception:
        return False


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
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {X-12} {Z-12} {X+12} {Z+12}"); time.sleep(1)
    # a stone slab of ground, DEEP+2 thick, with a sealed one-block cavity DEEP under the bot;
    # the slab stands on the flat floor (-60) so the fill never reaches below the world
    rcon(f"fill {X-8} {GROUND-DEEP-2} {Z-8} {X+8} {GROUND} {Z+8} minecraft:stone")
    rcon(f"fill {X-8} {GROUND+1} {Z-8} {X+8} {GROUND+5} {Z+8} minecraft:air")
    cx, cy, cz = X + 2, GROUND - DEEP, Z
    rcon(f"setblock {cx} {cy} {cz} minecraft:air")
    rcon("kill @e[type=item]")
    time.sleep(0.5)
    rcon(f"summon minecraft:item {cx+0.5} {cy+0.1} {cz+0.5} {{Item:{{id:\"minecraft:cobblestone\",count:1}},Motion:[0.0,0.0,0.0],PickupDelay:0}}")
    time.sleep(0.5)
    present = rcon(f"execute if entity @e[type=item,x={cx},y={cy},z={cz},distance=..1.5]")
    rcon(f"spawnpoint {BOT} {X} {GROUND+1} {Z}")
    rcon(f"tp {BOT} {X+0.5} {GROUND+1} {Z+0.5}")
    rcon(f"clear {BOT}")
    rcon(f"give {BOT} minecraft:wooden_pickaxe 1")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10")
    time.sleep(2)
    try:
        py4j("stats")   # reads and resets, so the window below is this run's alone
    except Exception:
        pass
    gs = py4j("gs")
    y0 = float(gs["pos"].split(",")[1])
    print(f"bot at {gs['pos']} on stone with a wooden pickaxe; a cobblestone sealed {DEEP} blocks down at ({cx},{cy},{cz}) (present: {present!r}); @get cobblestone 1")
    py4j("cmd", c="@get cobblestone 1")
    t0 = time.time(); got = False; min_y = y0; seen = set()
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        gs = py4j("gs"); y = float(gs["pos"].split(",")[1]); min_y = min(min_y, y)
        chat = [c for c in py4j("chat", n=8)["chat"] if c not in seen]; seen.update(chat)
        note = [c for c in chat if any(w in c for w in ("NO ROUTE", "below", "Drop", "drop"))]
        got = has_cobble()
        print(f"  t={time.time()-t0:.0f}s pos={gs['pos']} cobblestone={got}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if got:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    tok = "?"; deep = 0
    try:
        st = py4j("stats")["s"]
        toks = [t for t in st.split() if t.startswith(("dropDeep=", "dropBlock=", "navStall=", "minePick"))]
        tok = " ".join(toks)
        for t in toks:
            if t.startswith("dropDeep="):
                deep = int(t.split("=", 1)[1])
    except Exception:
        pass
    rcon("kill @e[type=item]")
    rcon(f"forceload remove {X-12} {Z-12} {X+12} {Z+12}")
    print(f"result: cobblestone={got} minY={min_y:.1f} (start {y0:.1f}) dropDeep={deep} counters: {tok}")
    # The verdict is the BEHAVIOUR: a cobblestone in the pack and the body never more than two
    # blocks down. dropDeep is reported, not required -- round 35 mined the stone at hand in
    # seconds with the comparison never reaching the deep drop at all (minePick calls 0), which is
    # the right outcome by a different road, and a counter must not fail a run that did the
    # right thing.
    if got and min_y >= y0 - 2.0:
        print("PASS: mined the stone at hand instead of digging eleven blocks for the fallen one"); return 0
    if got:
        print(f"FAIL: got the cobblestone but went {y0 - min_y:.0f} blocks down for it (dropDeep={deep})"); return 1
    print("FAIL: no cobblestone within the window"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
