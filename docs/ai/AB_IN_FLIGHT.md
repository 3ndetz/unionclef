# mob_skeleton ground-distance A/B — IN FLIGHT

Same-session pair (checklist 4j: this course's variance lives BETWEEN series, so arms built
hours apart measure the stand).

    arm ON  (shipped)  -> /tmp/gdON.txt    run_suite.py mob --only mob_skeleton --repeat 20
    arm OFF (control)  -> /tmp/gdOFF.txt   ... --pin combatGroundDistance=false

Pin already verified to reach the behaviour (readback combatGroundDistance=false on every
scenario).

## Reduce it

    for f in gdON gdOFF; do
      grep -oE "min_hp=[0-9.]+" /tmp/$f.txt | sed 's/min_hp=//' > /tmp/$f.v
      awk -v L=$f '{s+=(20-$1)/4; n++; d[n]=(20-$1)/4} END {m=s/n;
        for(i=1;i<=n;i++) v+=(d[i]-m)^2; sd=sqrt(v/(n-1));
        printf "%s mean=%.2f sd=%.2f n=%d se=%.3f\n", L, m, sd, n, sd/sqrt(n)}' /tmp/$f.v
    done

arrows landed = (20 - min_hp) / 4. Compare MEAN ARROWS, never pass counts.

## Decision rule, fixed BEFORE the numbers

- Arms within ~0.2 arrows -> the change does nothing. **REVERT IT**, and remove the flag with
  it (a flag whose ON state changes nothing is dead weight). `dist` here also feeds the sprint
  cut-off, tooClose and kite, so it changes every pvp duel — unproven is not free.
- Arm ON clearly better -> keep, and run pvp/nav/craft before believing it.
- Anything in between -> unsettled, extend to n=40 an arm. Do NOT ship on it.

This is the same rule that made the point-blank sidestep a clean call instead of an argument
about a plausible mechanism. It measured 1.17 vs 1.16 and was reverted.
