"""PRE-REGISTRATION #5, WRITTEN BEFORE ITS DATA EXISTS (2026-08-13, combatDodgeOnDraw).

The model above says the pass rate IS the rate at which the skeleton misses, and that lateral
velocity at release is the only thing that changes it. Exactly one flag in the tree targets that,
and it is off on evidence that no longer counts: n=12, 1.42 vs 1.94 arrows at 1.02 sigma, taken in
the era when ~50% of every series was being discarded and the invalid guard was misfiring.

  * 40 launches, interleaved, --pin-alt combatDodgeOnDraw=true, ~20 an arm;
  * STATISTIC: MEAN ARROWS LANDED, 2 sigma. The zero-rate is recorded but NOT promoted -- #4 is
    what promoting it after the fact costs;
  * MECHANISM GATE, and note it points the OTHER WAY this time: dodgeDrive must RISE in the pinned
    arm, because this flag ADDS dodge episodes (one per draw) rather than shortening them. Judged
    on the MEDIAN, per rule 4t, since the mean of that counter is what broke #4;
  * secondary, recorded not gated: arrows FIRED (the flag must not simply make fights longer) and
    bandToSwing (it must not wreck the approach to buy the dodge);
  * if it clears: mob_melee and mob_trio before shipping -- the draw test is on RangedAttackMob, so
    zombie courses should be inert by construction, and that is a PREDICTION worth checking rather
    than an assumption;
  * if it fails: the arrow-avoidance lever is closed too, and what remains is that this course's
    gate asks for a skeleton to miss every shot in a 12-block open field. That would be a statement
    about the COURSE, and it belongs in TODOS as one -- not as another bot hypothesis.

HONEST PRIOR, recorded before the run as in #3: the earlier measurement was negative, and the
mechanism argument for re-testing is that the stand was broken then, not that the idea has improved.
A null here is a real null and closes the line.

*** WHAT THIS COURSE ACTUALLY MEASURES (2026-08-13, derived then confirmed on 167 runs).

Every pass is an arrow MISSING. Not one is an arrow being outrun.

    167 runs, 33 passes (20%)
    arrows FIRED at the bot in PASSING runs:  mean 3.97, min 1, and ZERO of the 33 had none
    arrows FIRED at the bot in FAILING runs:  mean 4.86, min 1

All 33 passing runs were shot at and took nothing. The arithmetic says why the approach cannot
help: the skeleton spawns at x=12.5 against a bot at ~0.5, its draw is 20 ticks, and an arrow
covers twelve blocks in ~4.5 -- so the first shot lands about 24-25 ticks after the AI wakes. The
best traced approach crossed 11.57 -> 3.60 blocks in 24 ticks at 0.33 blocks/tick, which is about
what sprint allows. It arrived exactly as the arrow did. One 4-damage arrow puts min_hp at 16 and
the gate wants 19, so PASSING REQUIRES THE FIRST ARROW TO MISS -- and the pass rate is simply the
rate at which the skeleton misses.

THIS RETIRES THE ENTIRE APPROACH LINE, and explains all five nulls in one sentence: they were
tuning a variable that does not determine the outcome. combatCloseToReach, combatApproachNoOrbit,
combatEngageBand, combatCloseOwnsBand and combatDodgeHoldByRange each shaved ticks off a 32-tick
approach that would have to beat 24, and none of them could have closed that gap even if they had
worked perfectly. The one that DID work perfectly (combatCloseOwnsBand, mechanism gate passed)
still measured nothing, which is exactly what this model predicts.

It also explains the bimodality that produced the false 2.65-sigma finding: outcomes are "the
first arrow missed" or "it did not", and a hit costs 2.78 blocks of knockback (measured over 7
hurt events) which buys the skeleton another shot -- so a miss tends to stay a miss and a hit
tends to compound.

WHAT IS LEFT, and it is the only thing: make the arrows miss. A mob aims at RELEASE, so lateral
velocity AFTER release is what defeats it. At twelve blocks the flight is ~4.5 ticks, in which a
sprinting bot covers ~1.2 blocks against a 0.6-block hitbox -- comfortably enough. At five blocks
the flight is under two ticks and nothing can be done, which is why the in-flight dodge measured
nothing and why shortening its hold measured nothing either.



OUTCOME OF THE PRE-REGISTERED SERIES BELOW (2026-08-13). THE FLAG IS FINISHED.

40 launches, interleaved, 0 invalid. Scored twice -- by this tool on the summary, and by splitting
the console log on its per-run `PIN combatEngageBand=` lines -- and the two agree exactly:

    arm A (off)  n=20  mean 1.32  sd 0.79
    arm B (on)   n=20  mean 1.62  sd 0.93
    difference  -0.30 arrows   SE 0.27   1.10 sigma

Under the 2-sigma bar, and in the WORSE direction. The rule below said a third sub-threshold
result finishes the idea, so it is finished and the flag stays off.

* THE PART WORTH KEEPING. The two earlier series read +0.83 and +0.88, both favouring the flag.
This one -- the largest, at n=20 an arm against their 3-6 -- reversed the sign. Three careful
series, and the direction was not stable until the n was. That is the fourth time on this course
that a promising sub-threshold reading turned out to be noise, and the first time the reversal was
large enough to be unmissable.

WHAT THE COUNTERS SAID, which the arrows could not: the flag's mechanism fired exactly as designed
(controller ticks 55 -> 175) and made the bot stand FURTHER OUT, reachMean 3.55 -> 4.56, with
corr(controller ticks, reachMean) = +0.91. The cause is an arbitration line, not this flag --
see TungstenConfig#combatCloseOwnsBand.

OUTCOME OF #4 (2026-08-13). THE POST-HOC FINDING DID NOT REPLICATE, AND MY OWN GATE WAS CONFOUNDED.

48 launches, 0 invalid, 23/24 an arm.

PRE-REGISTERED STATISTIC (zero-arrow rate):  A 5/23 = 0.22   B 3/24 = 0.12
                                             -0.09, SE 0.11, 0.84 sigma -- DOES NOT REPLICATE.
Pooled with #3 it is 6/43 vs 11/44, +0.11 at 1.30 sigma. Series #3's 2.65 sigma was noise, which
is what the caution written beside it said it probably was. The flag stays off.

!! AND THE MECHANISM GATE I WROTE WAS ITSELF A CONFOUNDED TOTAL -- the fourth of these in one day.
The gate said "mean dodgeDrive must FALL in the pinned arm". It ROSE: 35.3 -> 108.2, which by the
letter voids the series. But dodgeDrive is a per-run TOTAL, and a run where the bot never engages
accumulates it for two thousand ticks. De-confounded:

    median dodgeDrive        A 21     B 15
    dodgeDrive per arrow     A 8.79   B 6.65

Both lower in the pinned arm. The flag fired exactly as designed; five catastrophic runs in arm B
(dodgeDrive 334-503) dragged the mean over the gate. Band ticks, strafeFar, and now this: three
totals read as rates, and this one was in a gate I had pre-registered specifically to keep myself
honest. THE RULE THAT FOLLOWS: a gate metric must be a RATE or a MEDIAN unless the denominator is
fixed by construction.

The verdict does not depend on resolving the void. The secondary mean reads A 1.14 vs B 2.07 --
2.78 sigma WORSE -- and the arm B tail is 4.25, 4.5, 4.75, 4.75. Every reading of this series
points the same way, so the flag stays off under all of them; there is no rescue here that would
turn it on, which is the only kind of re-reading worth distrusting.

** WHAT THE DIAGNOSIS FOUND, WHICH IS WORTH MORE THAN THE FLAG: A CATASTROPHIC STALL, 9% OF RUNS.
Across all 167 runs of the four series, 15 have the skeleton firing 8-42 arrows while the bot sits
beyond 4.5 blocks for 333-2094 ticks -- up to 104 seconds of a 120-second course. In most of them
mdTung is 13-69, so the combat controller is barely ticking: the bot is parked just OUTSIDE the
inRange test that would let it fight, and it stays there. lastGap clusters at 4.5-5.0, with
outliers at 7.9 and 20.6.

That is a stall state, not a tuning question, and it is the same 4.5-block line three refuted flags
were built around -- approached from the failure side this time. Sizing it honestly before anyone
spends a series on it: at 9% of runs it caps the pass rate at 91%, while the current rate is ~20%,
so it is NOT what keeps the course red. The binding constraint is still that an ordinary run takes
about one arrow and the gate allows none. Fix it as a defect, not as a lever on this gate.

OUTCOME OF #3, AND PRE-REGISTRATION #4 (2026-08-13). THE MEAN SAID NOTHING; THE SHAPE DID NOT.

40 launches, 0 invalid. The mechanism gate passed -- dodgeDrive 31.1 -> 18.9 -- so the flag fired.

    arrows   A 1.19 (sd 0.53)   B 1.12 (sd 1.23)   +0.06, SE 0.30, 0.21 sigma

Nothing, on the pre-registered statistic. THE FLAG STAYS OFF, as declared. The secondary checks
say why the arithmetic did not pay: the returned ticks did not go into closing. toSwing 38.1 ->
40.1, inReachRate 0.36 -> 0.35, reachMean 3.53 -> 3.58 -- all flat. Whatever the dodge was
costing, the approach did not pick it up.

* BUT THE DISTRIBUTIONS ARE NOT THE SAME DISTRIBUTION, and the course is gated on their left tail:

    arm A (off)  0.0 x1,  0.75 x3,  1.0 x10, 1.75 x2, 2.0 x4
    arm B (on)   0.0 x8,  0.75 x1,  1.0 x4,  1.5 x1, 1.75 x1, 2.0 x2, 3.0 x1, 3.25 x1, 4.25 x1
    zero-arrow rate  A 0.05   B 0.40    +0.35, SE 0.13, 2.65 sigma
    passes           A 1/20   B 8/20

Same mean, opposite shape: half of arm A lands on exactly one arrow, while arm B either takes
nothing or takes a beating. 8/20 would be the best pass rate this course has produced.

!! THIS IS POST-HOC AND IS NOT A RESULT. The statistic was declared as the MEAN before the run,
the mean read 0.21 sigma, and finding a better statistic afterwards is the move that produced
"+0.83 and +0.88" and then reversed at n=20. Two further reasons for caution, both against the
finding: arm A's sd of 0.53 is the tightest of any arm measured today (0.79, 0.76, 0.53), so the
ANOMALOUS arm may be the baseline rather than the treatment; and a variance difference is exactly
what small samples manufacture.

So it is tested prospectively instead. PRE-REGISTRATION #4, written before its data exists:

  * 48 launches, interleaved, --pin-alt combatDodgeHoldByRange=true, ~24 an arm;
  * STATISTIC: THE ZERO-ARROW RATE (equivalently the pass rate), declared in advance this time.
    It is the quantity the gate is made of, and checklist rule 4i says the gate's statistic is
    rarely the measurement's -- here they came apart by 2.4 sigma in one series;
  * bar: 2 sigma on the pooled SE of the difference in proportions;
  * mechanism gate, unchanged: dodgeDrive must be lower in the pinned arm or the series is VOID;
  * the MEAN is recorded too. If the zero-rate clears and the mean does not, that is what shipping
    would buy: more zeros AND more disasters, better against a gate that counts only zeros, worse
    for a bot that has to survive. That trade gets stated in the release notes rather than hidden;
  * if it fails to replicate, the flag stays off and the dodge overhang is closed for good.

PRE-REGISTRATION #3, WRITTEN BEFORE ITS DATA EXISTS (2026-08-13, combatDodgeHoldByRange).

40 launches, interleaved, --pin-alt combatDodgeHoldByRange=true. Declared now:

  * statistic: MEAN ARROWS LANDED. Bar: 2 sigma. Not pooled with anything else here;
  * MECHANISM GATE, checked before the arrows are read: dodgeDrive must be LOWER in the pinned arm.
    The flag shortens a hold, so if dodgeDrive does not fall it did not fire and the series is VOID
    rather than negative. Same gate that made #2 readable;
  * secondary, recorded not gated: bandToSwing and inReachRate. The whole claim is that returned
    ticks go into closing, so if arrows move while those two do not, the explanation is wrong even
    if the number is good;
  * if it clears the bar: mob_melee and mob_trio re-run before it ships. The dodge is not
    skeleton-specific and a shorter hold changes every course where something shoots;
  * if it does not: the flag stays off and the dodge overhang is closed as a lever. What would
    remain is the ~2 ticks that ARE flight time, and those are not recoverable by tuning.

WHY THIS IS A DEFECT AND NOT ANOTHER POLICY GUESS. The three flags closed above were all
hypotheses about who SHOULD own the approach. This is a unit mismatch with the arithmetic on
record: DODGE_HOLD_TICKS = 6 carries the comment "an arrow crosses twelve blocks in about eight
ticks", and this course's shots are released at a mean of 5.4-5.7 blocks -- about two ticks of
flight at 2.65 blocks a tick. Four of the six ticks sidestep an arrow that has already arrived,
while the primitive overrides the approach at the final-word position for every one of them. The
change is clamped to [2, 6], so it can only return ticks and never extend a dodge.

!! AND THE HONEST PRIOR: the effect is small. About 4 wasted ticks per arrow at ~4.5 arrows a run
is ~18 ticks against a 109-tick band -- perhaps 0.3 arrows, right at the edge of what n=20 an arm
resolves. A null result here is genuinely uninformative about the mechanism, and that is recorded
NOW so a null is not later written up as a refutation of the arithmetic.

OUTCOME OF PRE-REGISTRATION #2 (2026-08-13). REFUTED, AND THE LINE IS CLOSED AS PROMISED.

40 interleaved launches, 0 invalid. The mechanism gate passed cleanly -- cqTookFromPursue 0 in all
20 arm-A runs and 8-213 in all 20 arm-B runs -- so this is a real negative and not a void series.

    arrows       A 0.88   B 1.23    -0.35, SE 0.32, 1.11 sigma  (under the bar, WORSE)
    passes       A 6/20   B 6/20
    ctl          A 52     B 166     combat drove 3x more
    reachMean    A 3.53   B 4.71    ...and stood a FULL BLOCK further out
    inReachRate  A 0.375  B 0.143   share of control ticks inside 3.0, more than halved
    bandToSwing  A 53     B 84      longer before the first swing landed

The pre-registration said that if this failed, the whole "the controller should own the approach"
line closes and both flags stay off. It failed. They stay off.

* THE RESULT IS BIGGER THAN THE VERDICT, and it is the opposite of the hypothesis: closeQuarters()
is a WORSE closer than the BFS pursue walk it was written to displace. Every closing metric moved
the wrong way when it took the legs. The premise -- that a path-follower cannot close because it
chases a vacated square -- was simply wrong on this course.

What survives as a target: corr(inReachRate, arrows) = -0.40 over the 40 runs and -0.52 within arm
B. The share of control ticks spent inside reach predicts the result, and the pathfinder is what
maximises it.

!! ONE NUMBER FROM THIS SERIES IS NOT EVIDENCE AND MUST NOT BE QUOTED. corr(strafeFar, reachMean)
= +0.93 looked like the circle-strafe diluting the approach -- a clean mechanism, nearly a fourth
hypothesis. It is an IDENTITY: strafeFarTicks counts strafe ticks taken beyond reach, so per
control tick it is one minus the in-reach rate, and corr(strafeRate, inReachRate) came out exactly
-1.00. A correlation of exactly +/-1.00 between two derived quantities is the signature of an
identity, not of a discovery. That counter measures distance, not strafing, and cannot test the
orbit at all.

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
        # !! AND THE NAME IS NOT CONFIRMED ON THIS PATH, so the report must not print it as though
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
