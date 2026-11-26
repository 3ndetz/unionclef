"""Runs INSIDE the client container (direct py4j, ~50 ms polling): one gotoCell -> stopPathing round.
argv: x y z stop_after_s. Prints one JSON line. Called by stop_test.py; see there."""
import json
import math
import sys
import time

from py4j.java_gateway import GatewayParameters, JavaGateway

x, y, z, after = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4])
gw = JavaGateway(gateway_parameters=GatewayParameters(port=25333, auto_convert=True))
ep = gw.entry_point


def d(m):
    return {k: m.get(k) for k in m.keySet()} if hasattr(m, "keySet") else dict(m)


ep.gotoCell(x, y, z)
time.sleep(after)
pre = d(ep.getSelfPose())
t0 = time.time()
ep.stopPathing()
t_ack = time.time()
ack = d(ep.getSelfPose())
first = {}
nav_off = moving_off = False
nav_back = move_back = False
max_speed_late = 0.0
samples = 0
last = ack
while time.time() - t_ack < 3.0:
    st = d(ep.pathStatus())
    ps = d(ep.getSelfPose())
    dt = round((time.time() - t_ack) * 1000)
    samples += 1
    for k in ("navActive", "busy", "pathfinderActive", "walker", "executor"):
        if st.get(k) is False and k not in first:
            first[k] = dt
    if st.get("stopped") is True and "stopped" not in first:
        first["stopped"] = dt
    if not st.get("navActive"):
        nav_off = True
    elif nav_off:
        nav_back = True
    sp = float(ps.get("speed") or 0.0)
    if dt > 500:
        max_speed_late = max(max_speed_late, sp)
        if sp < 0.01:
            moving_off = True
        elif sp > 0.05 and moving_off:
            move_back = True
    last = ps
    time.sleep(0.05)
print(json.dumps({
    "stop_after_s": after,
    "stopPathing_call_ms": round((t_ack - t0) * 1000),
    "speed_at_ack": round(float(ack.get("speed") or 0), 3),
    "drift_during_call": round(math.hypot(ack["x"] - pre["x"], ack["z"] - pre["z"]), 2),
    "drift_after_ack": round(math.hypot(last["x"] - ack["x"], last["z"] - ack["z"]), 2),
    "ms_after_ack_until_false": first,
    "max_speed_after_500ms": round(max_speed_late, 3),
    "navActive_came_back": nav_back, "motion_came_back": move_back,
    "samples": samples,
    "stats": str(ep.navRetargetStats()),
    "final": {k: last.get(k) for k in ("navActive", "walker", "executor", "userTask", "speed")},
}))
gw.close()
