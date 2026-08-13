"""OUTCOME OF THE PRE-REGISTERED SERIES BELOW (2026-08-13). THE FLAG IS FINISHED.

40 launches, interleaved, 0 invalid. Scored twice -- by this tool on the summary, and by splitting
the console log on its per-run `PIN combatEngageBand=` lines -- and the two agree exactly:

    arm A (off)  n=20  mean 1.32  sd 0.79
    arm B (on)   n=20  mean 1.62  sd 0.93
    difference  -0.30 arrows   SE 0.27   1.10 sigma

Under the 2-sigma bar, and in the WORSE direction. The rule below said a third sub-threshold
result finishes the idea, so it is finished and the flag stays off.

⭐ THE PART WORTH KEEPING. The two earlier series read +0.83 and +0.88, both favouring the flag.
This one -- the largest, at n=20 an arm against their 3-6 -- reversed the sign. Three careful
series, and the direction was not stable until the n was. That is the fourth time on this course
that a promising sub-threshold reading turned out to be noise, and the first time the reversal was
large enough to be unmissable.

WHAT THE COUNTERS SAID, which the arrows could not: the flag's mechanism fired exactly as designed
(controller ticks 55 -> 175) and made the bot stand FURTHER OUT, reachMean 3.55 -> 4.56, with
corr(controller ticks, reachMean) = +0.91. The cause is an arbitration line, not this flag --
see TungstenConfig#combatCloseOwnsBand.

PRE-REGISTRATION #2, WRITTEN BEFORE ITS DATA EXISTS (2026-08-13, the PAIR).

Next series: 40 launches, interleaved, both combatEngageBand and combatCloseOwnsBand pinned on in
arm B, both off in arm A. Declared now:

  * statistic: MEAN ARROWS LANDED. Bar: 2 sigma. Not pooled with anything above;
  * A GATE BEFORE THE ARROWS ARE READ AT ALL: cqTookFromPursue must be > 0 in arm B and 0 in arm A.
    That counter is the mechanism. If it reads 0 in arm B the flag did nothing, the arrows are
    about something else, and the series is void rather than negative -- exactly the error that
    let combatCloseToReach be argued about for three passes;
  * secondary, recorded but not gated: reachMean, and band ticks to the first swing. If arrows move
    and reachMean does not, the explanation is wrong even if the number is good;
  * if it clears 2 sigma: mob_melee and mob_trio are re-run BEFORE it ships, because this changes
    who drives the legs on every mob course;
  * if it does not: the whole "the controller should own the approach" line is closed, both flags
    stay off, and the next pass goes at the residual instead -- reachMean sits at 3.4-3.7 against a
    3.0 reach even when closeQuarters already owns 80-90% of ticks, and that is a different defect.

THE OBJECTION, STATED RATHER THAN AVOIDED. combatEngageBand was just refuted, and here it is
switched on again inside the very next experiment. That looks like measuring until it passes, so
the distinction has to be load-bearing rather than rhetorical: the refuted claim was "ticking the
controller earlier is worth arrows", and it is dead -- the sign reversed at n=20 an arm. The new
claim is about a DIFFERENT line of code, found afterwards and from counters rather than from a
hunch: past REACH+1.0 the legs go to the pursue walk, so the earlier ticks were never able to
drive. combatEngageBand appears in arm B as a PRECONDITION of the thing under test, not as the
thing under test. If the pair fails, both are finished together and the line is closed for good --
that is the commitment that makes this a test and not a retry.

PRE-REGISTRATION, WRITTEN BEFORE THE DATA EXISTS (2026-08-13, third engage-band series).

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

Usage:  python ab_arrows.py <summary.json> <pinName>              interleaved, one file
        python ab_arrows.py <arm-A.json> <arm-B.json>             two separate series

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
import os
import sys

# Every reason a completed run is not evidence. `invalid` is the harness's own verdict; the other
# three are runs it let through as verdicts while printing "NOT comparable against a healthy
# baseline" next to them. Counting those was a hole in this scorer: a starved run PASSES the
# validity check, enters an arm, and drags a mean built to resolve 1.5 arrows. They are dropped
# here and the count is PRINTED -- a scorer that quietly discards half an arm reads exactly like
# one that had a small effect to report.
DROP_REASONS = ("invalid", "starved", "drift_from", "jar_changed")


def _truthy(v):
    return str(v).strip().lower() in ("true", "1", "yes", "on")


def arrows(rows):
    """Arrows landed per run, plus a tally of what was thrown away and why."""
    out, dropped = [], {}
    for r in rows:
        why = next((k for k in DROP_REASONS if r.get(k)), None)
        if why:
            dropped[why] = dropped.get(why, 0) + 1
            continue
        for c in r.get("criteria", []):
            if c["name"] == "at most one arrow landed":
                hp = float(c["detail"].split("min_hp=")[1].split()[0])
                out.append((20.0 - hp) / 4.0)
    return out, dropped


def load(path):
    rows = json.load(io.open(path, encoding="utf-8"))
    return rows if isinstance(rows, list) else [rows]


def split_on_pin(rows, pin):
    """Separate an INTERLEAVED series into arms, preferring what was APPLIED over what was meant.

    `pins` records the flags the run actually carried; `arm` records the letter the loop intended.
    They agree except where a run was retried on fresh clients -- and before the pins were recorded
    the label was stamped on the attempt that got thrown away, so on exactly those runs the letter
    is the less trustworthy of the two. Prefer `pins`, fall back to `arm` for summaries written
    before the recording existed, and say out loud which one was used: the fallback is scoring a
    series by the loop's intention.
    """
    if all(pin in r.get("pins", {}) for r in rows):
        key, basis = (lambda r: _truthy(r["pins"][pin])), "pins"
    elif all(r.get("arm") in ("A", "B") for r in rows):
        # ⛔ AND THE NAME IS NOT CONFIRMED ON THIS PATH, so the report must not print it as though
        # it were. A summary from before pin recording says which arm a run was in and NOTHING
        # about which flag the series varied -- feed it the wrong name and the arms come out
        # confidently labelled with a flag that series never touched. Caught by doing exactly that
        # to a real 26-run file while testing this. The letters are all this data supports.
        print("[!] no `pins` recorded -- splitting on the arm LETTER. That is what the loop "
              "intended rather than what each run carried (retried runs may be mislabelled), and "
              f"it does NOT confirm the series varied {pin!r}. Check the console log.")
        key, basis = (lambda r: r["arm"] == "B"), "arm"
    else:
        raise SystemExit(
            f"cannot split this summary: rows carry neither a `pins` entry for {pin!r} nor an "
            f"arm letter. Was it run with --pin-alt?")
    return [r for r in rows if not key(r)], [r for r in rows if key(r)], basis


def stats(xs):
    n = len(xs)
    if n == 0:
        return 0, 0.0, 0.0
    m = sum(xs) / n
    var = sum((x - m) ** 2 for x in xs) / n if n > 1 else 0.0
    return n, m, var ** 0.5


def _report(label, xs, dropped):
    n, m, s = stats(xs)
    lost = "  ".join(f"-{v} {k}" for k, v in sorted(dropped.items())) or "none dropped"
    print(f"{label}: n={n}  mean arrows={m:.2f}  sd={s:.2f}  ({lost})  {xs}")
    return n, m, s


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    first, second = sys.argv[1], sys.argv[2]
    if os.path.exists(second):
        rows_a, rows_b = load(first), load(second)
        label_a, label_b = "arm A (flag off)", "arm B (pinned)  "
    else:
        rows_a, rows_b, basis = split_on_pin(load(first), second)
        if basis == "pins":
            label_a, label_b = f"arm A ({second}=false)", f"arm B ({second}=true) "
        else:
            label_a, label_b = "arm A (baseline, unverified)", "arm B (alternate, unverified)"
    a, drop_a = arrows(rows_a)
    b, drop_b = arrows(rows_b)
    na, ma, sa = _report(label_a, a, drop_a)
    nb, mb, sb = _report(label_b, b, drop_b)
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
