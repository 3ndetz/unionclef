# mob_skeleton point-blank A/B — IN FLIGHT

Launched as a SAME-SESSION pair (the whole point: this course's variance is between series).

    arm ON  (shipped)  -> /tmp/armON.txt    run_suite.py mob --only mob_skeleton --repeat 20
    arm OFF (control)  -> /tmp/armOFF.txt   ... --pin combatDodgePointBlank=false

## How to read it when it lands

    for f in armON armOFF; do
      grep -oE "min_hp=[0-9.]+" /tmp/$f.txt | sed 's/min_hp=//' > /tmp/$f.v
      awk -v L=$f '{s+=(20-$1)/4; n++; d[n]=(20-$1)/4} END {m=s/n;
        for(i=1;i<=n;i++) v+=(d[i]-m)^2; sd=sqrt(v/(n-1));
        printf "%s mean=%.2f sd=%.2f n=%d se=%.3f\n", L, m, sd, n, sd/sqrt(n)}' /tmp/$f.v
    done

min_hp -> arrows landed is (20 - min_hp) / 4. Compare MEAN ARROWS, not pass counts.

## What counts as an answer

n=20 an arm, same session, same clients. If the arms differ by less than about 0.3 arrows
this is still not settled and needs n=40 an arm -- on this course n=12 and n=26 have BOTH
misled, and one shipped result vanished entirely between n=26 and n=40.

If arm ON is not clearly better, REVERT the point-blank sidestep: it is currently kept on
mechanism alone and has no proven effect.
