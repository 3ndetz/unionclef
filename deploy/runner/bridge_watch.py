"""Side monitor for a nav course run (2026-11-27): every second, the navigator's own account --
pathStatus (outcome, navActive, lastPlanEnd/Complete) and getLastPlan's waypoint count and end --
plus the exact pose and FPS-relevant flags, one JSON line each, until killed or `secs` pass.
For explaining a stall the suite only reports as a position ("freeze window at x=14.38").

    python deploy/runner/bridge_watch.py <out.jsonl> [secs]
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uctest.harness import Py4jClient  # noqa: E402

out = sys.argv[1]
secs = float(sys.argv[2]) if len(sys.argv) > 2 else 600
py = Py4jClient("uctest-mc-tester1")
t0 = time.time()
with open(out, "w", encoding="utf-8") as f:
    while time.time() - t0 < secs:
        try:
            st = py.call("pathStatus")
            lp = py.call("getLastPlan")
            p = py.call("getSelfPose")
            row = {"t": round(time.time() - t0, 1), "pos": [p.get("x"), p.get("y"), p.get("z")],
                   "onGround": p.get("onGround"), "speed": p.get("speed"),
                   "outcome": st.get("outcome"), "navActive": st.get("navActive"), "busy": st.get("busy"),
                   "lastPlanEnd": st.get("lastPlanEnd"), "lastPlanComplete": st.get("lastPlanComplete"),
                   "planGoal": lp.get("goal"), "planCells": len(lp.get("cells") or []),
                   "planTail": (lp.get("cells") or [])[-4:]}
        except Exception as e:  # the client may be between worlds
            row = {"t": round(time.time() - t0, 1), "error": str(e)[:160]}
        f.write(json.dumps(row) + "\n")
        f.flush()
        time.sleep(1.0)
