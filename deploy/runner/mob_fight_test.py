"""Does the bot actually fight a mob on tungsten? Answer it with a number, not a belief.

WHY THIS EXISTS. Mob combat was moved off the hand-rolled duel in AbstractKillEntityTask and onto
tungsten's CombatController. The obvious places to check both refuse to answer:

  - the PVP SUITE cannot: its courses drive `punk`, which is tungsten's own PunkPlayerTask and
    never enters the class that changed. A score from it is about something else entirely.
  - the GAMER SWEEP did not: three runs of wood-gathering in daylight finished with kaTung=0,
    because a bot chopping trees never commits to a melee. Zero deaths and a passed rung proved
    nothing about the new code -- it had not run.

So put a zombie in front of the bot and tell it to kill it. `@test kill` runs KillEntityTask on
the nearest tracked zombie, which is exactly the path that changed.

  python deploy/runner/mob_fight_test.py [count]

Reports, per fight: whether the zombie died, how long it took, and kaTung -- the ticks the fight
spent inside tungsten's controller. kaTung == 0 with a dead zombie would mean the kill came from
somewhere else and this rewiring is still unproven.
"""
import json, subprocess, sys, time, pathlib

CLIENT = "uctest-mc-tester1"
SERVER = "uctest-server"
BOT = "tester1"
PORT = 25333
SNIP = pathlib.Path(__file__).with_name("gamer_smoke.py")


def sh(a, t=40):
    return subprocess.run(a, capture_output=True, text=True, timeout=t)


def rcon(c, t=20):
    return sh(["docker", "exec", SERVER, "rcon-cli", c], t).stdout.strip()


# Reuse gamer_smoke's py4j snippet verbatim rather than keeping a second copy in step with it.
_SNIP = None
for line in SNIP.read_text(encoding="utf-8").split("SNIP=r\"\"\"")[1].split("\"\"\"")[0:1]:
    _SNIP = line


def py4j(op, t=30, **kw):
    req = dict(op=op, port=PORT)
    req.update(kw)
    r = sh(["docker", "exec", CLIENT, "python3", "-c", _SNIP, json.dumps(req)], t)
    try:
        return json.loads(r.stdout.strip().splitlines()[-1])
    except Exception:
        return {}


def statstr(name):
    """The counter is a slash-joined group now: tungstenTicks/taskTicks/canHitTicks/equipTicks."""
    s = py4j("stats").get("s") or ""
    for tok in s.split():
        if tok.startswith(name + "="):
            return tok.split("=", 1)[1]
    return "?"


def stat(name):
    s = py4j("stats").get("s") or ""
    for tok in s.split():
        if tok.startswith(name + "="):
            v = tok.split("=", 1)[1]
            return int(v) if v.lstrip("-").isdigit() else 0
    return 0


def ensure_in_world():
    """A recreated client is in no world at all, and every reading below would be None.

    The first version of this probe read a position only because the client happened to still be
    connected from an earlier sweep; straight after a deploy it recreates the container and all
    three rounds reported "no position" and scored zero. That is a probe measuring its own setup.
    """
    # CONNECT ONCE, THEN WAIT. ConnectToServer LEAVES the current world to rejoin, so calling it
    # every poll restarts the join for ever: the first version asked every 5s for two minutes and
    # reported "will not join" about a client that was perfectly able to, and had in fact been in
    # game when the loop started. Ask again only if a long wait really produced nothing.
    # IN GAME IS NOT THE SAME AS IN *THIS* GAME.
    # The gate used to accept any world at all. After a gamer_smoke sweep the client sits on the
    # GAMER server while every rcon call here goes to test-server, so the zombie was summoned in
    # one world and the bot fought in another: readings showed the bot at y=-106 and the "target"
    # teleporting a hundred blocks between polls. The server's own player list is the authority,
    # exactly as uctest/actors.py already warns.
    def on_target():
        return BOT in rcon("list")

    for attempt in range(3):
        if on_target():
            return True
        py4j("connect", ip="test-server")
        for _ in range(12):
            time.sleep(5)
            if on_target():
                return True
    return False


def main():
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    print(f"mob fight probe: {rounds} rounds")
    if not ensure_in_world():
        print("client will not join test-server -- nothing to measure")
        return 1
    rcon("difficulty normal")
    # NIGHT, BECAUSE DAYLIGHT KILLS THE ZOMBIE FOR US.
    # With `time set day` the zombie BURNS: measured 17 -> 12 -> 8 -> 3 -> dead at about 1.2 HP a
    # second, with mdCalls=0 -- the defence chain not even being ticked. Every "the bot is fighting
    # now" reading taken that way was sunlight, not the bot. At midnight the only thing that can
    # take the zombie's health is us.
    rcon("time set midnight")
    rcon("gamerule doDaylightCycle false")
    # AND NO OTHER ZOMBIES, OR THE BOT FIGHTS THE WRONG ONE.
    # `@test kill` takes the first TRACKED zombie, and at midnight the world keeps making them.
    # Measured: the bot walked 28 blocks away to a zombie at (-23.5,-60,49) while the one summoned
    # beside it stood untouched at full health -- and every "it never closes the distance" reading
    # was really about a chase 20+ blocks off. One zombie in the world means one answer.
    rcon("gamerule doMobSpawning false")
    ok = 0
    for i in range(rounds):
        print(f"\n--- round {i + 1}/{rounds} ---")
        py4j("cmd", c="@stop")
        time.sleep(1)
        rcon("kill @e[type=zombie]")
        rcon("kill @e[type=skeleton]")
        rcon("kill @e[type=spider]")
        rcon("kill @e[type=creeper]")
        rcon(f"effect give {BOT} minecraft:instant_health 1 10")
        # ARM THE BOT. An unarmed fight measures the mob, not the wiring.
        rcon(f"item replace entity {BOT} weapon.mainhand with iron_sword")
        py4j("zero")
        gs = py4j("gs").get("self") or {}
        pos = gs.get("pos") or gs.get("position")
        if not pos:
            print("  no position from the client; skipping")
            continue
        try:
            x, y, z = [float(v) for v in (pos if isinstance(pos, (list, tuple))
                                          else str(pos).replace(",", " ").split())[:3]]
        except Exception:
            print(f"  cannot read position {pos!r}; skipping")
            continue
        # Close enough that the fight starts at once, far enough that approach still happens.
        rcon(f"summon zombie {x + 4:.1f} {y:.1f} {z:.1f}")
        time.sleep(1)
        # REFUSE AN UNCONTROLLED ARENA RATHER THAN MEASURE IT.
        # `gamerule doMobSpawning false` did not take: a round opened with "Test passed. Count: 24"
        # and the count then wandered between 10 and 27, so @test kill was picking whichever zombie
        # the tracker listed first -- once one 83 blocks away -- and the summoned one was never the
        # subject. A fight against an unknown zombie is not a measurement of anything.
        n = rcon("execute if entity @e[type=zombie]")
        if "Count: 1" not in n:
            print(f"  arena not controlled ({n.strip()}) -- clearing and retrying")
            rcon("kill @e[type=zombie]")
            time.sleep(2)
            rcon(f"summon zombie {x + 4:.1f} {y:.1f} {z:.1f}")
            time.sleep(1)
            n = rcon("execute if entity @e[type=zombie]")
            if "Count: 1" not in n:
                print(f"  STILL not one zombie ({n.strip()}) -- skipping this round rather than"
                      f" reporting a fight against an unknown mob")
                continue
        py4j("cmd", c="@test kill")
        t0 = time.time()
        dead, hp = False, None
        # ASK A QUESTION THE SERVER ANSWERS.
        # This used to run `execute if entity ... run say ALIVE` and look for ALIVE in the reply.
        # rcon returns nothing at all for that form, so every poll read "dead" and every round
        # ended at the first poll -- three fights, all "dead in 3.7s", none of them real. `execute
        # if entity` on its own answers "Test passed. Count: N", which is a fact rather than a
        # silence.
        while time.time() - t0 < 60:
            time.sleep(3)
            n = rcon("execute if entity @e[type=zombie]")
            if "Test passed" not in n:
                dead = True
                break
            zhp = rcon("data get entity @e[type=zombie,limit=1] Health")
            hp = (py4j("gs").get("self") or {}).get("hp")
            print(f"    t={time.time() - t0:4.0f}s  {n.strip()}  zombie {zhp.split(':')[-1].strip()}"
                  f"  bot hp={hp}  ka={statstr('kaTung')}")
        kt = statstr("kaTung")
        print(f"  zombie dead: {dead}   t={time.time() - t0:.1f}s   kaTung={kt}   bot hp={hp}")
        if dead and kt.split("/")[0] not in ("0", "?"):
            ok += 1
        elif dead:
            print("  DEAD BUT kaTung=0 -- the kill did not come through the rewired path")
        elif kt.split("/")[0] not in ("0", "?"):
            print("  the controller ran but the zombie survived the window")
    print(f"\n=== {ok}/{rounds} fights killed the zombie THROUGH tungsten's controller ===")
    return 0 if ok >= max(1, rounds - 1) else 1


if __name__ == "__main__":
    sys.exit(main())
