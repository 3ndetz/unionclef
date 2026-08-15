#!/usr/bin/env python3
"""Where does mine_stone's time go? Poll the bot once a second and dump the hole it dug.

WHY THIS EXISTS. mine_stone is the last red rung of the playthrough ladder, and four separate
fixes have now measured nothing against its 4.32 baseline (n=60). Rule ONE says that pattern
means the INPUT is lying, so stop fixing and measure: what is the bot standing on, what task
holds it, and what does the excavation actually look like when the 120 s are up.

WHAT IT ANSWERS THAT THE SUITE CANNOT. The craft timeline records no position at all -- the
verdict can say `cobblestone=0` while saying nothing about where the bot was -- so the pit is
inferred rather than seen. This prints y every second and then reads the blocks back out of the
world, which turns "it digs itself into a hole" from a story into a map.

NOT A COURSE. It builds mine_stone's arena and issues mine_stone's command, but it gates
nothing: the output is a trace to read, not a pass or a fail.
"""
import functools
import os
import sys
import time

print = functools.partial(print, flush=True)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from uctest.actors import Bot                      # noqa: E402
from uctest.arena import STAND_Y, ArenaBuilder     # noqa: E402
from uctest.harness import Rcon                    # noqa: E402

CONTAINER = "uctest-mc-tester1"
NAME = "tester1"
DURATION = 120


def inv_count(bot, needle):
    ok, inv = bot.py.try_call("getInventoryFull")
    if not ok or not inv:
        return -1
    total = 0
    try:
        for slot in dict(inv).get("slots") or []:
            sd = dict(slot)
            if sd.get("empty"):
                continue
            if needle in str(sd.get("item") or sd.get("name") or ""):
                total += int(sd.get("count", 0) or 0)
    except (TypeError, ValueError):
        return -1
    return total


def stat_tokens(bot, wanted):
    ok, s = bot.py.try_call("placeStats")
    if not ok or not s:
        return "unread"
    out = [t for t in str(s).split() if any(t.startswith(w) for w in wanted)]
    return " ".join(out)


def block_at(rcon, x, y, z):
    """The block id at a position, via a handful of `execute if block` probes.

    rcon has no "what block is here" command, so ask about the few ids this arena can contain.
    Anything else reads as `?` -- which is itself informative, and has to be, because guessing
    the id would be exactly the kind of assumption this script exists to remove.
    """
    for name in ("air", "stone", "cobblestone", "barrier", "cave_air"):
        if "passed" in rcon.cmd(f"execute if block {x} {y} {z} minecraft:{name}",
                                allow_reject=True).lower():
            return name
    return "?"


def main():
    rcon = Rcon()
    bot = Bot(CONTAINER, NAME, rcon)
    bot.ensure_in_game(rcon=rcon)

    # TWO RUNGS, ONE TRACER. mine_stone digs underfoot; mine_coal must WALK to its ore, which is the
    # join a stalled navigation breaks. The polling, the world dump and the counter read are
    # identical questions for both, so they share the instrument rather than growing a second copy
    # of it that drifts -- this repo has paid for duplicated benches before.
    coal = any(a == "coal" for a in sys.argv[1:])
    arena = ArenaBuilder(rcon)
    arena.prepare(half=30 if coal else 20)
    y = STAND_Y - 1
    if coal:
        arena.flat_field(half=24, grass=False)
        rcon.cmd(f"fill -26 {y - 3} -26 26 {y - 1} 26 minecraft:stone", allow_reject=True)
        for ox, oz in ((8, 0), (14, 4), (20, -4)):
            rcon.cmd(f"setblock {ox} {y} {oz} minecraft:coal_ore", allow_reject=True)
    else:
        arena.flat_field(half=6, grass=False)
        rcon.cmd(f"fill -8 {y - 3} -8 8 {y - 1} 8 minecraft:stone", allow_reject=True)
    spawn = f"0.5 {STAND_Y} 0.5 -90 0"
    bot.fresh_reset(spawn, kit=[f"give {NAME} " + ("stone_pickaxe 1" if coal else "wooden_pickaxe 1")])

    rcon.cmd("time set day")
    rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
    # Pins go through tungstenSetting, not chat: ";settings k v" runs on a later tick and a
    # read-back straight after shows the OLD value, so an A/B can silently measure the build
    # against itself. This applies and RETURNS the resolved field, so both name and value are
    # checked -- findSettingField falls back to a substring match, and a guard that compared only
    # the value would confirm a pin that landed on the wrong field.
    for pin in [a for a in sys.argv[1:] if "=" in a]:
        k, _, v = pin.partition("=")
        ok, got = bot.py.try_call("tungstenSetting", k, v)
        name, _, read = str(got).partition("=")
        if not ok or name != k or read != v:
            raise SystemExit(f"--pin {k}={v} did not apply (got {got!r}); refusing to run")
        print(f"  PIN {got}")
    bot.py.try_call("resetRunCounters")
    time.sleep(1)
    print(f"=== {'mine_coal' if coal else 'mine_stone'} trace: floor y={y}, bot y={STAND_Y}")
    bot.cmd("@get coal 3" if coal else "@get cobblestone 8")

    t0 = time.time()
    last_task = ""
    while time.time() - t0 < DURATION:
        el = time.time() - t0
        pos = bot.pos() or [float("nan")] * 3
        cob = inv_count(bot, "coal" if coal else "cobblestone")
        ok_task, chain = bot.py.try_call("getTaskChainString")
        task = " ".join(str(chain or "").split())[-110:] if ok_task else "?"
        line = f"{el:5.1f}s pos=({pos[0]:.2f},{pos[1]:.2f},{pos[2]:.2f}) cobble={cob}"
        if task != last_task:
            line += f"\n        TASK {task}"
            last_task = task
        print(line)
        time.sleep(1.0)

    print("\n=== counters")
    print(stat_tokens(bot, ("scan=", "pd", "breakFail=", "cb=", "srch=", "drop=",
                      "fleeSpot=", "navStop=", "lock=", "avoidSrc=")))

    print("\n=== the column the bot stands in, and its neighbours (x from -2 to 2, z=0)")
    # bot.pos() RETURNS None WHEN RCON HICCUPS OR THE BOT IS DEAD, and this used it unguarded --
    # so a trace that had already collected its whole timeline threw at the dump and printed a
    # traceback instead of the data. Guarded in the poll loop and not here, which is the same
    # one-sided hardening this project keeps finding in its own instruments.
    final = bot.pos()
    if final is None:
        print("  (no position at teardown -- skipping the world dump; the timeline above stands)")
        bot.stop_all()
        return
    px, _, pz = [int(v // 1) for v in final]
    for by in range(STAND_Y + 4, STAND_Y - 5, -1):
        row = " ".join(f"{block_at(rcon, px + dx, by, pz):>11}" for dx in (-2, -1, 0, 1, 2))
        print(f"  y={by:4d}  {row}")
    print(f"  (column centred on the bot's final x={px} z={pz})")

    # WHERE ARE THE DROPS? The run that stalled ninety seconds on "Approach entity item" ended with
    # a placed cobblestone at (0,-61,0) and AIR below it -- a capped shaft. If the bot seals its own
    # drops in while climbing out, then it is pathing to items it has walled in, and no amount of
    # work on the pathing or the tracker can matter. This is the question that decides that, and it
    # is one rcon call: the tracker's opinion is not needed, the server knows where the entities are.
    print("\n=== item entities still lying in the arena")
    ents = rcon.cmd("execute as @e[type=minecraft:item] run data get entity @s Pos",
                    allow_reject=True)
    for line in str(ents).splitlines():
        if "[" in line:
            print("  " + line.strip()[-90:])
    if "[" not in str(ents):
        print("  (none)")

    print("\n=== the surface layer, 9x9 around the bot (S=stone, .=air, c=cobble, ?=other)")
    sym = {"stone": "S", "air": ".", "cave_air": ".", "cobblestone": "c", "barrier": "#"}
    for dz in range(-4, 5):
        row = "".join(sym.get(block_at(rcon, px + dx, y, pz + dz), "?")
                      for dx in range(-4, 5))
        print(f"  z={pz + dz:4d}  {row}")

    bot.stop_all()


if __name__ == "__main__":
    main()
