#!/usr/bin/env python3
"""Extract stall episodes from a gamer_smoke log, so a sweep doubles as a P2 corpus.

A playthrough sweep already prints a position line and a counter DELTA line every ~22 s. A stall
is visible in that stream without any new instrumentation: the position stops changing AND the
deltas go to zero. Reading them by hand is how the corpus has been built until now -- one episode
out of one run, which is also how "the missing movement class is parkour" ended up resting on a
single edge someone copied out of a log.

Two kinds of stall, and they want opposite fixes, so they are reported apart:

  DEAD    every counter reads +0. Nothing ticks at all -- not the queue, not the executor, not
          the block-destroy ticker. The bot is not failing to move, it is not being driven.
  CHURN   the position is frozen but counters keep moving (mqStart climbing is the classic:
          plan, truncate, replan the same route). That is the capability gap.

Usage:  python deploy/runner/stall_corpus.py <log> [more logs...]
"""
import re
import sys
from collections import Counter

SAMPLE = re.compile(r"t=(\d+)s .*?pos=(-?[\d.]+),(-?[\d.]+),(-?[\d.]+).*?d:(\S+)")
TASK = re.compile(r"TASK .*?Main task: (.*?)(?:\s*\||$)")
RUN = re.compile(r"=+ RUN (\d+)/(\d+)")
RUNG = re.compile(r"RUNG '([^']+)' at ([\d.]+)s")


def episodes(path):
    run, last_task, prev = 0, "?", None
    streak = []
    out = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = RUN.search(line)
        if m:
            # FLUSH BEFORE RESETTING. A stall that was still open when the run ended used to be
            # dropped here, and that is the most interesting stall there is -- the one the run
            # died on. Cost the corpus its first episode before it was noticed.
            if len(streak) >= 2:
                out.append((run, streak, last_task, prev))
            run = int(m.group(1))
            prev, streak = None, []
            continue
        m = TASK.search(line)
        if m:
            last_task = m.group(1).strip()[:70]
            continue
        m = SAMPLE.search(line)
        if not m:
            continue
        t = int(m.group(1))
        pos = (round(float(m.group(2)), 1), round(float(m.group(3)), 1), round(float(m.group(4)), 1))
        deltas = dict(
            (k, int(v)) for k, v in
            (p.split("+") for p in m.group(5).split(",") if "+" in p)
        )
        moving = prev is None or pos != prev
        alive = any(v > 0 for v in deltas.values())
        if not moving:
            streak.append((t, deltas, alive))
        else:
            if len(streak) >= 2:
                out.append((run, streak, last_task, prev))
            streak = []
        prev = pos
    if len(streak) >= 2:
        out.append((run, streak, last_task, prev))
    return out


def main():
    kinds = Counter()
    tasks = Counter()
    total = 0
    for path in sys.argv[1:]:
        for run, streak, task, pos in episodes(path):
            total += 1
            secs = streak[-1][0] - streak[0][0]
            dead = not any(alive for _, _, alive in streak)
            kind = "DEAD " if dead else "CHURN"
            kinds[kind] += 1
            tasks[(kind, task)] += 1
            moved = Counter()
            for _, d, _ in streak:
                for k, v in d.items():
                    moved[k] += v
            busy = " ".join(f"{k}+{v}" for k, v in moved.most_common(4) if v) or "nothing ticked"
            print(f"run {run:>2}  {kind}  {secs:>3}s at {pos}  [{busy}]")
            print(f"          task: {task}")
    print(f"\n{total} stall episodes: " + ", ".join(f"{k.strip()} {v}" for k, v in kinds.items()))
    print("\nby task:")
    for (kind, task), n in tasks.most_common(8):
        print(f"  {n:>3}x {kind} {task}")


if __name__ == "__main__":
    main()
