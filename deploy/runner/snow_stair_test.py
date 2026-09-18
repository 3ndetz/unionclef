#!/usr/bin/env python3
"""G94 bench: a step DOWN onto a snow layer is a hop the physics agent refuses.

The 25-minute run (2026-09-16, t=264-308) stood sixty seconds at (1264.3,66,-1404):
`gaveUp n3 idx2 ... hop[0,-1,1] from (1264,66,-1405)[grass_block|snow|air] to
(1264,65,-1404)[grass_block|snow|air]`, 32898 tests. Each end is a full block (grass)
topped by a snow LAYER, and the descent is down one / forward one -- a plain staircase
step where the tread carries a thin block. G82 taught the PLANNER to read a thin block's
top; G94 is the search's physics leg giving up on the step-down anyway.

Scene (flat server, own stone base): a four-tread staircase descending in +z, each tread a
grass block with a snow layer on top, the bot on the top tread, `@goto` the bottom landing.
The only way down is three [0,-1,+1] steps onto snow. PASS = the bot reaches the bottom
landing inside the window. FAIL = it stalls on a snow step (position stuck, "gave up" /
"unreachable" in chat).

    python3 deploy/runner/snow_stair_test.py            # snow treads (the real case)
    SNOW_LAYERS=3 python3 deploy/runner/snow_stair_test.py   # taller snow
    SNOW_LAYERS=0 python3 deploy/runner/snow_stair_test.py   # CONTROL: bare grass, no snow

exit 0 = PASS.
"""
import functools, json, math, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
BX, BZ = 2340, 340                    # staircase centre-x, top-edge z
TOP_Y = -56                           # y of the top tread's grass block
STEPS = 3                             # number of [0,-1,+1] descents
SNOW_LAYERS = int(os.environ.get("SNOW_LAYERS", "1"))   # 0 = control (bare grass)
WINDOW_S = 90

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


def tread_y(i):
    return TOP_Y - i            # grass block y of tread i (0 = top)


def tread_z(i):
    return BZ + i               # each step advances +1 in z


def build():
    # clear a generous air box over the whole run
    rcon(f"fill {BX-3} {TOP_Y-1} {BZ-2} {BX+3} {TOP_Y+6} {BZ+STEPS+4} minecraft:air")
    # a stone under-fill so nothing is void beneath the treads
    rcon(f"fill {BX-2} {TOP_Y-STEPS-2} {BZ-2} {BX+2} {TOP_Y-STEPS-1} {BZ+STEPS+4} minecraft:stone")
    snow = f"minecraft:snow[layers={SNOW_LAYERS}]" if SNOW_LAYERS >= 1 else None
    for i in range(STEPS + 1):
        y = tread_y(i); z = tread_z(i)
        # tread i is a grass block; the bottom landing (i==STEPS) is 3 cells deep in z
        z_to = z + (3 if i == STEPS else 0)
        rcon(f"fill {BX-1} {y} {z} {BX+1} {y} {z_to} minecraft:grass_block")
        # fill the supporting column below each tread so a tread is never floating
        rcon(f"fill {BX-1} {TOP_Y-STEPS-1} {z} {BX+1} {y-1} {z_to} minecraft:stone")
        if snow:
            rcon(f"fill {BX-1} {y+1} {z} {BX+1} {y+1} {z_to} {snow}")


def main():
    if BOT not in rcon("list"):
        print("connecting to test-server")
        py4j("connect", ip="test-server")
        t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {BX-4} {BZ-4} {BX+4} {BZ+STEPS+6}"); time.sleep(1)
    build()
    time.sleep(1)
    # verify the scene: top tread grass present, and (if snow) the second tread's snow present
    g = rcon(f"execute if block {BX} {TOP_Y} {BZ} minecraft:grass_block")
    if "passed" not in g.lower():
        print(f"FAIL: scene not built (top grass={g!r})"); return 2
    if SNOW_LAYERS >= 1:
        s = rcon(f"execute if block {BX} {tread_y(1)+1} {tread_z(1)} minecraft:snow")
        if "passed" not in s.lower():
            print(f"FAIL: scene not built (snow tread={s!r})"); return 2
    # start on the top tread; goal on the bottom landing, two cells into its 3-deep run
    sx, sy, sz = BX + 0.5, TOP_Y + 1, BZ + 0.5
    gx, gy, gz = BX, tread_y(STEPS) + 1, tread_z(STEPS) + 2
    rcon(f"spawnpoint {BOT} {BX} {TOP_Y+1} {BZ}")
    rcon(f"tp {BOT} {sx} {sy} {sz}")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10 true")
    rcon(f"clear {BOT}")
    time.sleep(2)
    start = py4j("state")
    px, py_, pz = (float(v) for v in start["pos"].split(","))
    if abs(px - sx) > 0.6 or abs(pz - sz) > 0.6 or abs(py_ - (TOP_Y + 1)) > 1.2:
        print(f"FAIL: bot not on the top tread (pos={start['pos']}); scene is wrong, not the bot"); return 2
    label = f"snow[layers={SNOW_LAYERS}]" if SNOW_LAYERS >= 1 else "CONTROL bare grass"
    print(f"on the top tread at {start['pos']}; {STEPS}-step descent ({label}); @goto {gx} {gy} {gz}")
    py4j("cmd", c=f"@goto {gx} {gy} {gz}")
    t0 = time.time(); out = False; min_z = pz; max_z = pz; last = None; stuck = 0; seen = set()
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        s = py4j("state")
        x, y, z = (float(v) for v in s["pos"].split(","))
        max_z = max(max_z, z)
        chat = [m for m in py4j("chat", n=10)["chat"] if m not in seen]; seen.update(chat)
        note = [m for m in chat if any(w in m.lower() for w in ("gave up", "unreachable", "wandering", "no path", "stuck"))]
        if last is not None and abs(x - last[0]) < 0.15 and abs(z - last[1]) < 0.15:
            stuck += 1
        else:
            stuck = 0
        last = (x, z)
        print(f"  t={time.time()-t0:.0f}s pos={s['pos']} busy={s['busy']} maxZ={max_z:.1f}/{gz}"
              + (" | " + " || ".join(m[-70:] for m in note[-2:]) if note else ""))
        if abs(y - gy) < 0.6 and math.hypot(x - (gx + 0.5), z - (gz + 0.5)) <= 2.5:
            out = True; break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    rcon(f"forceload remove {BX-4} {BZ-4} {BX+4} {BZ+STEPS+6}")
    progressed = max_z - pz
    print(f"result: reached={out} maxZ={max_z:.1f} (start {pz:.1f}, goal z {gz}) advanced={progressed:.1f} blocks")
    if out:
        print(f"PASS: descended the snow staircase to the bottom landing ({time.time()-t0:.1f}s)"); return 0
    print(f"FAIL: stalled on the descent (advanced only {progressed:.1f} of {gz-pz:.0f} blocks in {WINDOW_S}s)"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
