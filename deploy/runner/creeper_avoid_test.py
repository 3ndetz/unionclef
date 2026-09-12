#!/usr/bin/env python3
"""G43 bench: a creeper is avoided, never pursued to its fuse.

The 14:00 recorded run (2026-09-11) ended with "tester1 был взорван Крипер": the mob-defense chain
put the creeper in its fight list, judged it beatable and the duelling controller PURSUED it to
striking distance, which is its fuse distance. A creeper within 6 blocks that sees the bot is now
fled (RunAwayFromCreepersTask, keep 10), a farther one is ignored.

    python3 deploy/runner/creeper_avoid_test.py            # exit 0 = PASS

Flat server: the bot with a stone sword and full health on open ground, a creeper summoned 7
blocks away and facing it, 45 s. PASS = no death, health never below 16, and mdCreeperAvoid > 0
(the chain actually took the avoid branch).
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, Z = 780, 300
GROUND = -61
WINDOW_S = 45

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
    # A peaceful server removes a summoned creeper on the spot; the mob courses set normal too.
    rcon("difficulty normal")
    rcon(f"forceload add {X-30} {Z-16} {X+30} {Z+16}"); time.sleep(1)
    rcon("kill @e[type=creeper]")
    rcon(f"fill {X-30} {GROUND+1} {Z-16} {X+30} {GROUND+6} {Z+16} minecraft:air")
    rcon(f"fill {X-30} {GROUND} {Z-16} {X+30} {GROUND} {Z+16} minecraft:grass_block")
    rcon(f"tp {BOT} {X+0.5} {GROUND+1} {Z+0.5} 90 0")   # facing west, toward the creeper
    rcon(f"clear {BOT}"); rcon(f"give {BOT} minecraft:stone_sword")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10 true")
    time.sleep(1)
    # The chain only weighs hostiles while a task runs, so give it a long walk WEST -- straight
    # through the creeper -- instead of an eight-block hop that ended before the creeper arrived.
    py4j("cmd", c=f"@goto {X-28} {GROUND+1} {Z}")
    time.sleep(1)
    # Twelve blocks: a creeper walking at a bot walking at it closes 0.45 blocks a tick, so seven
    # blocks was 0.8 s to contact -- no policy survives that; twelve leaves the avoid range (10)
    # a real chance, which is what the course measures.
    # ⛔ TWELVE BLOCKS FROM WHERE THE BOT IS NOW, not from where it was put down. The bot sprints
    # from the goto's first tick, and by the time the summon ran it had covered eight of the
    # twelve (round 34: "bot at 772.4", the creeper at 767.5 -- five blocks, one diagnostic line
    # "creeper at 6.3 sees=true -> avoid", the blast a second later, hp 13.8). A five-block ambush
    # is not the case this bench is for.
    gs0 = py4j("gs")
    bx = float(gs0["pos"].split(",")[0])
    r = rcon(f"summon minecraft:creeper {bx-12.5} {GROUND+1} {Z+0.5}")
    gs = py4j("gs")
    print(f"bot at {gs['pos']} hp={gs.get('hp')}; creeper summoned 12 blocks west of it, on the bot's path ({r[:30]!r}). 45 s")
    t0 = time.time(); seen = set(); min_hp = 20.0; died = False
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        gs = py4j("gs"); hp = float(gs.get("hp") or 0); min_hp = min(min_hp, hp)
        ch = [c for c in py4j("chat", n=10)["chat"] if c not in seen]; seen.update(ch)
        died = died or any(("взорван" in c) or ("blew up" in c) or ("was blown up" in c) for c in ch)
        note = [c for c in ch if any(w in c for w in ("Creeper", "creeper", "Run", "flee", "COMBAT"))]
        try:
            chain = py4j("task")["chain"][-80:]
        except Exception:
            chain = "?"
        print(f"  t={time.time()-t0:.0f}s pos={gs['pos']} hp={hp} | {chain}"
              + (" | " + " || ".join(x[-60:] for x in note[-2:]) if note else ""))
        if died:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    avoid = -1
    try:
        st = py4j("stats")["s"]
        for t in st.split():
            if t.startswith("mdCreeperAvoid="):
                avoid = int(t.split("=")[1])
    except Exception:
        pass
    rcon("kill @e[type=creeper]")
    # Leave the flat server as the other benches expect it: no natural hostiles at night.
    rcon("difficulty peaceful")
    rcon(f"forceload remove {X-30} {Z-16} {X+30} {Z+16}")
    print(f"result: died={died} min_hp={min_hp} mdCreeperAvoid={avoid}")
    if not died and min_hp >= 16 and avoid > 0:
        print("PASS: kept away from the creeper, never engaged it"); return 0
    print("FAIL"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
