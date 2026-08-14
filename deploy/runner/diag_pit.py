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

    arena = ArenaBuilder(rcon)
    arena.prepare(half=20)
    arena.flat_field(half=6, grass=False)
    y = STAND_Y - 1
    rcon.cmd(f"fill -8 {y - 3} -8 8 {y - 1} 8 minecraft:stone", allow_reject=True)
    spawn = f"0.5 {STAND_Y} 0.5 -90 0"
    bot.fresh_reset(spawn, kit=[f"give {NAME} wooden_pickaxe 1"])

    rcon.cmd("time set day")
    rcon.cmd("gamerule spawn_monsters false", allow_reject=True)
    bot.py.try_call("resetRunCounters")
    time.sleep(1)
    print(f"=== mine_stone trace: floor at y={y}, stone down to y={y - 3}, bot at y={STAND_Y}")
    bot.cmd("@get cobblestone 8")

    t0 = time.time()
    last_task = ""
    while time.time() - t0 < DURATION:
        el = time.time() - t0
        pos = bot.pos() or [float("nan")] * 3
        cob = inv_count(bot, "cobblestone")
        ok_task, chain = bot.py.try_call("getTaskChainString")
        task = " ".join(str(chain or "").split())[-110:] if ok_task else "?"
        line = f"{el:5.1f}s pos=({pos[0]:.2f},{pos[1]:.2f},{pos[2]:.2f}) cobble={cob}"
        if task != last_task:
            line += f"\n        TASK {task}"
            last_task = task
        print(line)
        time.sleep(1.0)

    print("\n=== counters")
    print(stat_tokens(bot, ("scan=", "pd", "breakFail=", "cb=", "srch=", "drop=")))

    print("\n=== the column the bot stands in, and its neighbours (x from -2 to 2, z=0)")
    px, _, pz = [int(v // 1) for v in bot.pos()]
    for by in range(STAND_Y + 4, STAND_Y - 5, -1):
        row = " ".join(f"{block_at(rcon, px + dx, by, pz):>11}" for dx in (-2, -1, 0, 1, 2))
        print(f"  y={by:4d}  {row}")
    print(f"  (column centred on the bot's final x={px} z={pz})")

    print("\n=== the surface layer, 9x9 around the bot (S=stone, .=air, c=cobble, ?=other)")
    sym = {"stone": "S", "air": ".", "cave_air": ".", "cobblestone": "c", "barrier": "#"}
    for dz in range(-4, 5):
        row = "".join(sym.get(block_at(rcon, px + dx, y, pz + dz), "?")
                      for dx in range(-4, 5))
        print(f"  z={pz + dz:4d}  {row}")

    bot.stop_all()


if __name__ == "__main__":
    main()
