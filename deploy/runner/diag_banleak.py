#!/usr/bin/env python3
"""Does a temporary break-ban survive the job that installed it? Caused, not awaited.

WHY THIS EXISTS. The ban leak was found from one observation -- `avoidSrc=0/0/1/0` on a run that
PASSED, i.e. one avoid predicate present while zero were registered during the run. Present but not
registered is what inherited means. But an A/B on mine_coal could not judge the fix: the leak needs
a ban INHERITED from an earlier run, which needs a break to have FAILED in that earlier run, and on
a clean arena breaks do not fail. `breakFail` read 0/0/0/0/0 in every run of a six-run series, so
the control arm looked exactly like the fixed one and six runs said nothing.

WHEN A FIX TARGETS A RARE TRIGGER, THE GATE MUST CAUSE THE TRIGGER RATHER THAN WAIT FOR IT. That is
the sibling of checklist rule 4n: a criterion that waits for a rare event is satisfied by the event
not happening, which is indistinguishable from a fix that works.

So this installs the ban directly, through the same "extra" slot the survival chain uses, and then
asks the only question that matters: does the NEXT task still see it?

    task 1  ->  install a region ban  ->  task 1 ends
    task 2  ->  read predicates present at the start

With `clearBansOnTaskEnd` on, task 2 must see ZERO. With it off, task 2 sees the inherited ban. Two
runs, no statistics, and the answer does not depend on anything being rare.

NOT A COURSE. It gates nothing; it prints two numbers and what they mean.
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


def preds_now(bot):
    """Avoid predicates currently registered, read out of placeStats' avoidSrc field."""
    ok, s = bot.py.try_call("placeStats")
    if not ok or not s:
        return -1
    for tok in str(s).split():
        if tok.startswith("avoidSrc="):
            try:
                return int(tok.split("=", 1)[1].split("@")[0].split("/")[2])
            except (IndexError, ValueError):
                return -1
    return -1


def main():
    pins = [a for a in sys.argv[1:] if "=" in a]
    rcon = Rcon()
    bot = Bot(CONTAINER, NAME, rcon)
    bot.ensure_in_game(rcon=rcon)

    arena = ArenaBuilder(rcon)
    arena.prepare(half=20)
    arena.flat_field(half=8, grass=False)
    y = STAND_Y - 1
    rcon.cmd(f"fill -10 {y - 3} -10 10 {y - 1} 10 minecraft:stone", allow_reject=True)
    bot.fresh_reset(f"0.5 {STAND_Y} 0.5 -90 0", kit=[f"give {NAME} stone_pickaxe 1"])
    rcon.cmd("time set day")
    rcon.cmd("gamerule spawn_monsters false", allow_reject=True)

    for pin in pins:
        k, _, v = pin.partition("=")
        ok, got = bot.py.try_call("tungstenSetting", k, v)
        name, _, read = str(got).partition("=")
        if not ok or name != k or read != v:
            raise SystemExit(f"pin {k}={v} did not apply (got {got!r}); refusing to run")
        print(f"  PIN {got}")

    bot.py.try_call("resetRunCounters")
    bot.py.try_call("allowBreakingAnywhere")
    time.sleep(1)
    print(f"  predicates before anything: {preds_now(bot)}")

    # TASK ONE: a real task, so the runner is genuinely active and its end is a real task end.
    print("\n[1] task one: @get cobblestone 2, with a region ban installed mid-task")
    bot.cmd("@get cobblestone 2")
    time.sleep(4)
    ok, got = bot.py.try_call("avoidBreakingRegion", 0, STAND_Y - 1, 0, 30)
    print(f"  installed: {got}")
    print(f"  predicates during task one: {preds_now(bot)}")

    # ⛔ @stop DOES NOT RELIABLY REACH THE CLEAR, so wait for a real COMPLETION instead.
    #
    # onTaskFinish opens by RE-ARMING an interrupted task -- "stopped is not finished, and a goal
    # does not expire because we died on the way" -- up to MAX_REARM_ATTEMPTS times, returning
    # before it ever reaches the runner disable and the ban clear. A stopped task therefore may run
    # none of the teardown this check is about, and the fixed arm would report "leak present" for
    # a reason that has nothing to do with the leak.
    #
    # @get cobblestone 2 finishes on its own in seconds, so the honest end is to WAIT for it and
    # verify the runner actually went idle before reading anything.
    for _ in range(40):
        time.sleep(2)
        ok, chain = bot.py.try_call("getTaskChainString")
        if ok and "No tasks" in str(chain):
            break
    ok, chain = bot.py.try_call("getTaskChainString")
    finished = ok and "No tasks" in str(chain)
    print(f"  task one reached idle: {finished}")
    if not finished:
        print("  ⛔ task one never finished -- this run cannot answer, and is not being read as if"
              " it could")
        bot.py.try_call("allowBreakingAnywhere")
        bot.stop_all()
        return
    print(f"  predicates after task one ended: {preds_now(bot)}")

    # TASK TWO: the question. Does a fresh job inherit the previous job's ban?
    print("\n[2] task two: a fresh job -- does it inherit the ban?")
    bot.cmd("@get cobblestone 2")
    time.sleep(4)
    inherited = preds_now(bot)
    print(f"  predicates at the start of task two: {inherited}")

    print("\n=== VERDICT")
    if inherited == 0:
        print("  0 -- the ban did NOT survive the job that installed it. Leak closed.")
    elif inherited > 0:
        print(f"  {inherited} -- the ban SURVIVED into a new job. Leak present.")
    else:
        print("  unreadable -- placeStats did not answer; the run says nothing either way.")

    bot.py.try_call("allowBreakingAnywhere")
    bot.stop_all()


if __name__ == "__main__":
    main()
