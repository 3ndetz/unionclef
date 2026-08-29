#!/usr/bin/env python3
"""Attribute a run's DEAD TIME to the task that was holding it.

deadtime.py says how much of a run the body spent not moving. That number stopped being
actionable the moment the pathfinder was no longer the cause: six runs read a median 26% dead
while the physics leg halted 63 times in total, so the dead time is somewhere else and "where"
was a guess. It does not have to be -- gamer_smoke already prints a TASK line beside every
position sample, so the answer is in logs that were taken hours ago.

A sample is DEAD when position and item count are both unchanged from the previous one (the same
test deadtime.py uses, kept identical on purpose: two dead-time numbers that disagree would be
worse than one). Each dead sample is charged to the DEEPEST subtask in the nearest preceding TASK
line -- the deepest one is the one actually doing something; the outer frames are always
"Beating the game".

    python3 deploy/runner/deadtime_by_task.py <log> [--top N]

Reports per run and then the total, because one run wedged on one spot will otherwise read as a
class of defect -- that mistake has been made in this repo with a hop-shape histogram taken from
a single dump.
"""
import argparse
import io
import re
from collections import Counter

SAMPLE = re.compile(r"t=(\d+)s inGame=\S+ hp=\S+ pos=([-\d.]+),([-\d.]+),([-\d.]+) items=(\d+)")
TASK = re.compile(r"TASK Description of current game pipeline.*")
# "1.1.9. <Destroy block at 80, 133, -44> Getting to block..." -- number, then the angle brackets
LEAF = re.compile(r"\d+(?:\.\d+)+\.\s*<([^>]{0,90})")


def deepest_task(task_line):
    """The innermost subtask named on a TASK line, or None."""
    hits = LEAF.findall(task_line)
    return hits[-1].strip() if hits else None


def walk(body):
    """Yield (is_dead, task) for each position sample, in order."""
    last = None
    task = None
    for line in body.splitlines():
        if TASK.search(line):
            t = deepest_task(line)
            if t:
                task = t
            continue
        m = SAMPLE.search(line)
        if not m:
            continue
        cur = (round(float(m.group(2)), 1), round(float(m.group(3)), 1),
               round(float(m.group(4)), 1), m.group(5))
        if last is not None:
            yield cur == last, task
        last = cur


def report(label, dead, total, top):
    if not total:
        print(f"{label}: no samples")
        return
    n = sum(dead.values())
    print(f"{label}: {n}/{total} samples dead ({100.0 * n / total:.0f}%)")
    for name, k in dead.most_common(top):
        print(f"    {k:4d}  ({100.0 * k / n:4.1f}% of dead)  {name}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--top", type=int, default=8)
    args = ap.parse_args()

    raw = io.open(args.log, encoding="utf-8", errors="ignore").read()
    parts = re.split(r"=+ RUN (\d+)/(\d+) =+", raw)
    chunks = [(parts[i], parts[i + 2]) for i in range(1, len(parts), 3)] or [("1", raw)]

    grand, grand_total = Counter(), 0
    for idx, body in chunks:
        dead, total = Counter(), 0
        for is_dead, task in walk(body):
            total += 1
            if is_dead:
                dead[task or "(no task line)"] += 1
        report(f"RUN {idx}", dead, total, args.top)
        grand.update(dead)
        grand_total += total
    if len(chunks) > 1:
        print()
        report("ALL RUNS", grand, grand_total, args.top)


if __name__ == "__main__":
    main()
