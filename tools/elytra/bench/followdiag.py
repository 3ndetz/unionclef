"""Where does a pursuer fall behind? The leader flies a route on the pilot, the follower follows on
the solver; every half second both positions, speeds, phases and rockets go to a JSON file.

    python followdiag.py <follower> <leader> [out.json]
"""
import json
import math
import sys
import time

import bench
from bench import F, mcp

ROUTE = "160,60;280,0;160,-60;40,0;160,60"


def main():
    fol, lead = sys.argv[1], sys.argv[2]
    out = sys.argv[3] if len(sys.argv) > 3 else "followdiag.json"
    bench.reset(0, 0, nick=lead, yaw=-90)
    bench.reset(0, 10, nick=fol, yaw=-90)
    mcp("flySettings", {"solver": False}, nick=lead)
    mcp("flySettings", {"solver": True}, nick=fol)
    mcp("flyRoute", {"points": ROUTE}, nick=lead)
    mcp("flyFollow", {"nick": lead}, nick=fol)
    rows, t0 = [], time.time()
    while time.time() - t0 < 120:
        a, b = mcp("flyStatus", nick=lead), mcp("flyStatus", nick=fol)
        pa, pb = bench.pos(lead), bench.pos(fol)
        if pa and pb:
            rows.append({"t": round(time.time() - t0, 1), "gap": round(math.dist(pa, pb), 1),
                         "lead": [round(v, 1) for v in pa], "fol": [round(v, 1) for v in pb],
                         "leadPhase": a.get("phase"), "folPhase": b.get("phase"),
                         "leadRockets": a.get("rocketsUsed"), "folRockets": b.get("rocketsUsed"),
                         "seen": b.get("leaderRockets"), "wp": a.get("waypoint")})
        if a.get("phase") in ("done", "failed") and b.get("phase") in ("done", "failed", "idle"):
            break
        time.sleep(0.5)
    json.dump(rows, open(out, "w"), indent=0)
    for r in rows[::4]:
        print(r["t"], r["gap"], r["leadPhase"], r["folPhase"], r["wp"], r["leadRockets"], r["folRockets"],
              r["seen"], r["lead"][1], r["fol"][1])


if __name__ == "__main__":
    main()
