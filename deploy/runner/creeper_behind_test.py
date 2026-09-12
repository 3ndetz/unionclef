#!/usr/bin/env python3
"""G70 bench: a creeper walking up BEHIND a bot that is busy climbing a slope.

The 22:34 run, 19:44:35 UTC: "tester1 was blown up by Creeper" at hp 19, mid-climb on a hillside
(MovementQueue CLIMB+9, CLIMB+5), and not one line from the mob-defense chain before it. The avoid
branch (G43) was gated on the creeper's line of sight to the body; on a slope the creeper walks up
behind the body with the hill between their eyes until it is at fuse distance, and the fusing
branch then has thirty ticks and a body that is still climbing. Now a creeper inside seven blocks
is avoided whether or not it sees the body.

    python3 deploy/runner/creeper_behind_test.py            # exit 0 = PASS

Flat server: a long stone staircase going west (a climb of many MovementQueue legs); the bot at
its foot with a stone sword, `@goto` the top; one second in, a creeper is summoned six blocks EAST
of the bot (behind it, below the stairs), and once more when the first is gone. 60 s. PASS = no
death, health never below 16, and the chain actually took the avoid branch at least once
(mdCreeperAvoid or mdCreeperUnseen > 0) -- a round where the creeper never came close proves
nothing (the first draft of this bench "passed" with the bot on the landing and the creeper
wandering below, mdRet all zero).
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 2300, 300
GROUND = -61
STEPS = 26
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


def build():
    rcon(f"fill {X-30} {GROUND+1} {Z-8} {X+20} {GROUND+STEPS+4} {Z+8} minecraft:air")
    rcon(f"fill {X-30} {GROUND} {Z-8} {X+20} {GROUND} {Z+8} minecraft:grass_block")
    # a staircase rising one block per step toward the west, three wide
    for i in range(1, STEPS + 1):
        rcon(f"fill {X-i} {GROUND+1} {Z-1} {X-i} {GROUND+i} {Z+1} minecraft:stone")
    # a landing at the top
    rcon(f"fill {X-STEPS-4} {GROUND+1} {Z-1} {X-STEPS-1} {GROUND+STEPS} {Z+1} minecraft:stone")


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
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty normal")     # a peaceful server removes a summoned creeper on the spot
    rcon(f"forceload add {X-30} {Z-8} {X+20} {Z+8}"); time.sleep(1)
    rcon("kill @e[type=creeper]")
    build()
    time.sleep(1)
    probe = rcon(f"execute if block {X-STEPS} {GROUND+STEPS} {Z} minecraft:stone")
    if "passed" not in probe.lower():
        print(f"FAIL: the staircase was not built (probe={probe!r})"); return 2
    rcon(f"spawnpoint {BOT} {X} {GROUND+1} {Z}")
    rcon(f"tp {BOT} {X+0.5} {GROUND+1} {Z+0.5} 90 0")   # facing west, up the stairs
    rcon(f"clear {BOT}"); rcon(f"give {BOT} minecraft:stone_sword")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10 true")
    time.sleep(1)
    py4j("cmd", c=f"@goto {X-STEPS-2} {GROUND+STEPS+1} {Z}")
    time.sleep(1)
    gs = py4j("gs")
    bx = float(gs["pos"].split(",")[0]); by = float(gs["pos"].split(",")[1])
    r = rcon(f"summon minecraft:creeper {bx+6.5} {GROUND+1} {Z+0.5}")
    print(f"bot at {gs['pos']} hp={gs.get('hp')} climbing west; creeper summoned 6 blocks EAST, behind ({r[:30]!r}). {WINDOW_S} s")
    t0 = time.time(); seen = set(); min_hp = 20.0; died = False; resummoned = False
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        gs = py4j("gs"); hp = float(gs.get("hp") or 0); min_hp = min(min_hp, hp)
        # a second creeper once the first is gone (killed or lost), summoned behind the body again
        if not resummoned and time.time() - t0 > 20 and "creeper" not in rcon("execute if entity @e[type=creeper]").lower():
            bx = float(gs["pos"].split(",")[0])
            rcon(f"summon minecraft:creeper {bx+6.5} {GROUND+1} {Z+0.5}"); resummoned = True
            print("  second creeper summoned behind")
        ch = [c for c in py4j("chat", n=10)["chat"] if c not in seen]; seen.update(ch)
        died = died or any(("взорван" in c) or ("blew up" in c) or ("was blown up" in c) for c in ch)
        note = [c for c in ch if any(w in c for w in ("creeper", "Creeper", "Run", "flee"))]
        try:
            chain = py4j("task")["chain"][-80:]
        except Exception:
            chain = "?"
        print(f"  t={time.time()-t0:.0f}s pos={gs['pos']} hp={hp} | {chain}"
              + (" | " + " || ".join(x[-70:] for x in note[-2:]) if note else ""))
        if died or hp <= 0:
            died = True
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    tok = "?"; avoided = 0
    try:
        st = py4j("stats")["s"]
        toks = [t for t in st.split() if t.startswith(("mdCreeperAvoid=", "mdCreeperUnseen=", "mdRet="))]
        tok = " ".join(toks)
        for t in toks:
            if t.startswith(("mdCreeperAvoid=", "mdCreeperUnseen=")):
                avoided += int(t.split("=", 1)[1])
    except Exception:
        pass
    rcon("kill @e[type=creeper]")
    rcon("difficulty peaceful")
    rcon(f"forceload remove {X-30} {Z-8} {X+20} {Z+8}")
    print(f"result: died={died} min_hp={min_hp} avoided={avoided} counters: {tok}")
    if not died and min_hp >= 16 and avoided > 0:
        print("PASS: the creeper behind was avoided and never got its fuse on the climbing bot"); return 0
    if not died and min_hp >= 16:
        print("FAIL: unharmed, but the chain never took the avoid branch -- the creeper never came close; the scene proves nothing"); return 1
    print("FAIL"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
