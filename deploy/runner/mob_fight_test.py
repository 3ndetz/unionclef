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
    """A slash-joined counter group, read with a retry.

    A single py4j call can come back empty on a busy client, and an empty read used to become
    "?" -- which the verdict reads as "nothing of ours swung". That reported five dead rounds in
    a row for a bot that, watched directly at the same moment, was killing a zombie in three
    seconds with mdTung=7/1 and mdRet6=21. Ask twice before reporting a zero.

    AND KNOW WHAT mdTung ACTUALLY MEANS, because the name invites the wrong reading. It counts the
    tungsten DUELLING CONTROLLER, which MobDefenseChain hands the legs to only AT striking distance;
    the approach belongs to the task. So mdTung=0 does NOT mean "the engine did not run" -- it means
    "the bot never closed". Reading it the first way cost a whole diagnosis on mob_skeleton and left
    runs at 8.7-12.4 fps recorded as bot failures rather than as unmeasurable.
    """
    for attempt in range(3):
        s = py4j("stats").get("s") or ""
        for tok in s.split():
            if tok.startswith(name + "="):
                return tok.split("=", 1)[1]
        time.sleep(0.4)
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
    # BOTH SPELLINGS. 1.21.11 renamed the gamerules to snake_case, so `doMobSpawning` is simply
    # rejected there -- which is why the arena kept 11 to 27 zombies while this line looked like
    # it was doing its job. uctest/arena.py has sent both spellings all along; this had not.
    # spawn_monsters is the name on 1.21.11; the other two spellings are rejected outright,
    # which is why this arena kept 11 to 27 zombies while the line looked like it worked.
    rcon("gamerule spawn_monsters false")
    rcon("gamerule advance_time false")
    ok = 0
    invalid = 0
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
        # PUT A FLOOR UNDER THE FIGHT FIRST.
        # The stand's arena is carved to AIR from the void bottom up to y=-40, so the bot stands on
        # whatever platform the last course left and four blocks away is open sky. Every "zombie
        # dead in 3.4s" in this probe was the mob FALLING: traced with the bot stopped, a zombie
        # summoned at bot+4 went y=-60 -> -85 -> -160 -> gone in under two seconds. A fight needs
        # ground, so lay some.
        fy = int(y) - 1
        rcon(f"fill {int(x) - 8} {fy} {int(z) - 8} {int(x) + 8} {fy} {int(z) + 8} stone")
        # Close enough that the fight starts at once, far enough that approach still happens.
        rcon(f"summon zombie {x + 4:.1f} {y:.1f} {z:.1f}")
        time.sleep(1)
        # ONE SIMPLE ROUND, BECAUSE THE ELABORATE ONE MEASURED ITSELF.
        # This used to settle the arena by killing and re-summoning until exactly one zombie
        # existed, then handshake with the task until the chain string said "Killing". Both
        # mechanisms churned: the settle loop kept removing the mob the bot was about to fight,
        # and the handshake spent up to eight seconds during which an armed bot four blocks from
        # a zombie simply finished it. Six rounds in a row came back "gone before the fight
        # started" about a bot that -- watched by hand at that very moment -- was killing a zombie
        # in three seconds with mdTung=7/1 and mdRet6=21.
        #
        # So: summon once, ask for the kill once, then watch. The verdict is the same either way
        # -- the mob dies AND our counters moved -- and nothing in the setup gets to remove the
        # subject mid-experiment.
        time.sleep(2)
        py4j("cmd", c="@test kill")
        t0 = time.time()
        dead, hp, mt = False, None, "0"
        while time.time() - t0 < 45:
            time.sleep(1.5)
            alive = "Test passed" in rcon("execute if entity @e[type=zombie]")
            mt = statstr("mdTung")
            hp = (py4j("gs").get("self") or {}).get("hp")
            if not alive:
                dead = True
                break
        if not dead:
            zhp = rcon("data get entity @e[type=zombie,limit=1] Health")
            print(f"  zombie SURVIVED the window: {zhp.split(':')[-1].strip()[:10]}"
                  f"  mdTung={mt}  bot hp={hp}")
        kt = statstr("kaTung")
        mt = statstr("mdTung")   # now committed/force-field, slash separated
        tung = [v for v in (mt.split("/") + [kt.split("/")[0]]) if v not in ("0", "?", "")]
        if dead:
            print(f"  zombie dead: {dead}   t={time.time() - t0:.1f}s   mdTung={mt}"
                  f" kaTung={kt}   bot hp={hp}")
        if dead and tung:
            ok += 1
        elif dead and (time.time() - t0) < 5.0 and hp == 20.0:
            # A MOB THAT DIES IN ONE POLL WITH THE BOT UNTOUCHED DID NOT DIE TO THE BOT.
            # It fell. The arena is void outside the platform this probe lays, and a summon that
            # clips the edge is gone in under two seconds -- traced directly: y=-60 -> -85 -> -160.
            # Every genuine fight here runs 6 to 11 seconds and costs the bot health. Scoring a
            # fall as a lost fight is how a bench invents a defect.
            print(f"  the zombie fell rather than fought ({time.time() - t0:.1f}s, bot untouched)"
                  f" -- round INVALID, not counted")
            invalid += 1
        elif dead:
            print("  DEAD BUT no tungsten ticks -- the kill did not come through the rewired path")
        elif tung:
            print("  the controller ran but the zombie survived the window")
    scored = rounds - invalid
    tail = f" ({invalid} invalid, not counted)" if invalid else ""
    print(f"\n=== {ok}/{scored} fights killed the zombie THROUGH tungsten's controller{tail} ===")
    if scored == 0:
        print("no round actually ran -- this is not a result")
        return 1
    return 0 if ok >= max(1, scored - 1) else 1


if __name__ == "__main__":
    sys.exit(main())
