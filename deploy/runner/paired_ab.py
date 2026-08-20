#!/usr/bin/env python3
"""Score a PAIRED playthrough A/B from a gamer_smoke log.

Written because this analysis was being retyped inline for every series, and twice carried a
bug that changed the reading -- once losing the episode a run died on, once labelling an arm
from a segment the runner had stood down. A measurement people act on should be one command.

Reports both outcomes, because they disagree in power and it matters which one is quoted:

  rungs       what the ladder reached. Coarse: 0-5 with a pair spread near 3.
  stall time  seconds the body spent not moving. Continuous, and for any fix aimed at stalls it
              is the quantity actually being changed -- it resolved the same effect at t=-1.48
              where rungs managed +0.93 on identical runs.

A pair only counts when BOTH arms ran on the same resolved ground, which is what
gamer_smoke prints as "pair ground saved/REUSED".

⛔ DO NOT "FIX" THE VARIANCE BY PINNING THE START. It is the obvious idea and gamer_smoke's own
comment refutes it: the world is never wiped and the bot fells the trees around wherever it
starts, so a fixed point gets harder every run -- measured 21.5s to first log on untouched
ground against 300-585 seconds, or never, at a spot the bot had been working all session, and
that decay was read as code regressions for hours. The spiral exists to avoid exactly that.

The price is that some pairs are uninformative: the run dies of something else before it ever
reaches the state the flag changes, which shows up as the mechanism counter reading 0 in the FIX
arm. Report those, do not delete them, and do not pin.

    python deploy/runner/paired_ab.py <log> --flag stallCheckNeedsMovement [--counter airProg]
"""
import argparse
import math
import re


SAMPLE = re.compile(r"t=(\d+)s .*?pos=(-?[\d.]+),(-?[\d.]+),(-?[\d.]+)")
GROUND = re.compile(r"pair ground (?:saved|REUSED): ([-0-9 ]+)")


def stall_seconds(body):
    """Seconds across all episodes where the sampled position did not change."""
    prev, streak, total = None, [], 0
    for line in body.split("\n"):
        m = SAMPLE.search(line)
        if not m:
            continue
        t = int(m.group(1))
        pos = tuple(round(float(m.group(k)), 1) for k in (2, 3, 4))
        if prev is not None and pos == prev:
            streak.append(t)
        else:
            if len(streak) >= 2:
                total += streak[-1] - streak[0]
            streak = []
        prev = pos
    if len(streak) >= 2:
        total += streak[-1] - streak[0]
    return total


def stats(deltas):
    if len(deltas) < 2:
        return None
    mean = sum(deltas) / len(deltas)
    sd = (sum((d - mean) ** 2 for d in deltas) / (len(deltas) - 1)) ** 0.5
    # ⛔ ZERO SPREAD IS NOT INFINITE CONFIDENCE. Six identical deltas of 0 gave "t +inf", which
    # reads like the strongest result in the file and means the arms did nothing at all -- the
    # exact case where the mechanism counter is also 0 and the pairs carry no information.
    if not sd:
        return mean, sd, (float("inf") if mean else 0.0)
    t = mean / (sd / math.sqrt(len(deltas)))
    return mean, sd, t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--flag", required=True, help="the pinned setting name; picks arm B")
    ap.add_argument("--counter", help="mechanism counter that must read 0 in the control arm")
    args = ap.parse_args()

    txt = open(args.log, encoding="utf-8", errors="replace").read()
    parts = re.split(r"=+ RUN (\d+)/\d+ =+", txt)
    pairs = {}
    for i in range(1, len(parts), 2):
        body = parts[i + 1]
        arm = "B" if re.search(rf"PIN {re.escape(args.flag)}=(?:true|[1-9])", body) else "A"
        grounds = GROUND.findall(body)
        if not grounds:
            continue
        mech = "-"
        if args.counter:
            got = re.findall(rf"{re.escape(args.counter)}=(\d+)", body)
            mech = got[-1] if got else "?"
        pairs.setdefault(grounds[-1].strip(), {})[arm] = (
            len(re.findall(r"RUNG '", body)), stall_seconds(body), mech, int(parts[i]))

    rung_d, stall_d, dirty = [], [], []
    print(f"{'ground':<17} {'rungs fix/ctrl':>15} {'stall fix/ctrl':>16}   mech")
    for ground, arms in pairs.items():
        if "A" not in arms or "B" not in arms:
            continue
        b, a = arms["B"], arms["A"]
        rung_d.append(b[0] - a[0])
        stall_d.append(b[1] - a[1])
        if args.counter and a[2] not in ("0", "-", "?"):
            dirty.append((ground, a[2]))
        print(f"{ground:<17} {b[0]:>7} /{a[0]:<6} {b[1]:>8}s /{a[1]:<6}s   {b[2]}/{a[2]}")

    for label, deltas, unit, better in (("rungs", rung_d, "", "higher"),
                                        ("stall time", stall_d, "s", "lower")):
        s = stats(deltas)
        if not s:
            continue
        mean, sd, t = s
        print(f"\n{label:<11} {mean:+.2f}{unit} per run over {len(deltas)} pairs   "
              f"sd {sd:.2f}   t {t:+.2f}   ({better} is better for the fix)")
        print(f"            {deltas}")
        if sd == 0 and mean == 0:
            print("            EVERY PAIR IDENTICAL -- the arms did not differ. Check the "
                  "mechanism counter: if it reads 0 in the fix arm these pairs measured nothing.")
        elif abs(t) < 2:
            print("            NOT ESTABLISHED at the 2-sigma bar this repo uses.")

    if dirty:
        print("\n⛔ CONTROL ARM IS NOT CLEAN -- the mechanism counter should read 0 there:")
        for ground, val in dirty:
            print(f"   {ground}: {args.counter}={val}")


if __name__ == "__main__":
    main()
