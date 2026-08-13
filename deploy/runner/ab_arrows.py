"""PRE-REGISTRATION, WRITTEN BEFORE THE DATA EXISTS (2026-08-13, third engage-band series).

Running 40 launches, interleaved, --pin-alt combatEngageBand=true, expecting ~20 valid an arm.
Declared NOW so the result cannot be reinterpreted afterwards:

  * statistic: MEAN ARROWS LANDED, as always;
  * the bar is 2 sigma, unchanged. Two earlier series read +0.83 and +0.88 (1.90 sigma) and the
    flag stayed OFF both times. If this one lands under 2 sigma again, it stays off and the
    engage-band idea is finished -- three sub-threshold results are not evidence, they are a
    quantity too small to matter;
  * arms are NOT pooled with the earlier series. This stands or falls alone;
  * if it clears the bar, the flag goes ON and mob_melee and mob_trio are re-run before anything
    else, because widening the engage test changes who drives the legs on every mob course.

WHY THIS SERIES IS JUSTIFIED AND NOT MEASURING-UNTIL-IT-PASSES. The mechanism was confirmed
independently of the flag: pooled over 33 runs, arrows correlate with TOTAL band time (+0.43) and
not with reach refusals (+0.17), and the counters show the combat controller ticking for only 44 of
135 band ticks. At a 12-tick cooldown those 44 ticks permit about three swings; the missing 91 would
permit eleven. The engage test is what gates them. That is a mechanism argument that did not exist
when the first two series were run, and it is what changed -- not the appetite for a positive.
"""

"""Score a two-arm mob_skeleton A/B on ARROWS LANDED, with the rule fixed in advance.

Usage:  python ab_arrows.py <arm-A-summary.json> <arm-B-summary.json>

WHY THIS EXISTS. This course's pass count cannot separate arms at any affordable n — the ruler is
min_hp, and arrows = (20 - min_hp)/4, a small integer per run. Pooled n=14 on the repaired course
gives mean 1.46, sd 1.20, so at 2 sigma the arm size is 8*sd^2/delta^2:

    delta 0.3 -> 128 per arm      delta 0.7 -> 24
    delta 0.5 ->  46 per arm      delta 1.0 -> 12

At the six-run arms used before, nothing below ~1.4 arrows was ever visible, which is why a string
of careful hypotheses each "measured nothing". The gate wants ZERO arrows against a mean of 1.46,
so the effect that matters is ~1.5 — visible at n=12.

THE DECISION RULE, written before the numbers exist so it cannot be fitted to them:
  * the statistic is MEAN ARROWS LANDED, not passes;
  * INVALID runs are dropped (they measured the machine, not the bot);
  * a difference counts only at >= 2 sigma on the pooled SE of the difference;
  * anything else is reported as "no effect at this resolution" and the flag stays off.
"""
import io
import json
import sys


def arms(path):
    rows = json.load(io.open(path, encoding="utf-8"))
    rows = rows if isinstance(rows, list) else [rows]
    out = []
    for r in rows:
        if r.get("invalid"):
            continue
        for c in r.get("criteria", []):
            if c["name"] == "at most one arrow landed":
                hp = float(c["detail"].split("min_hp=")[1].split()[0])
                out.append((20.0 - hp) / 4.0)
    return out


def stats(xs):
    n = len(xs)
    if n == 0:
        return 0, 0.0, 0.0
    m = sum(xs) / n
    var = sum((x - m) ** 2 for x in xs) / n if n > 1 else 0.0
    return n, m, var ** 0.5


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    a, b = arms(sys.argv[1]), arms(sys.argv[2])
    na, ma, sa = stats(a)
    nb, mb, sb = stats(b)
    print(f"arm A (flag off): n={na}  mean arrows={ma:.2f}  sd={sa:.2f}  {a}")
    print(f"arm B (pinned)  : n={nb}  mean arrows={mb:.2f}  sd={sb:.2f}  {b}")
    if na < 2 or nb < 2:
        print("REFUSING TO JUDGE: an arm has fewer than two valid runs.")
        return 1
    se = (sa ** 2 / na + sb ** 2 / nb) ** 0.5
    if se == 0:
        print("REFUSING TO JUDGE: zero spread in both arms — check the instrument, not the bot.")
        return 1
    d = ma - mb
    print(f"difference = {d:+.2f} arrows   SE = {se:.2f}   {abs(d) / se:.2f} sigma")
    if abs(d) / se >= 2.0:
        print("VERDICT: real at 2 sigma." if d > 0 else "VERDICT: real at 2 sigma — and WORSE.")
    else:
        print("VERDICT: no effect at this resolution. The flag stays off.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
