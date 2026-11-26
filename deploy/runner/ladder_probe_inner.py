"""Runs INSIDE the client container: gotoCell to x y z and record, every ~50 ms for `secs`, the pose,
climbing, collisions, the walker's waypoint and the movement keys -- one JSON line per change.
argv: x y z secs. Used to see what happens at a ladder entry (2026-11-27)."""
import json
import sys
import time

from py4j.java_gateway import GatewayParameters, JavaGateway

x, y, z, secs = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4])
gw = JavaGateway(gateway_parameters=GatewayParameters(port=25333, auto_convert=True))
ep = gw.entry_point
ep.gotoCell(x, y, z)
t0 = time.time()
last = None
while time.time() - t0 < secs:
    p = ep.getSelfPose()
    st = ep.pathStatus()
    keys = p.get("keys")
    row = {"pos": [round(p.get("x"), 2), round(p.get("y"), 2), round(p.get("z"), 2)],
           "climbing": p.get("climbing"), "hColl": p.get("horizontalCollision"), "vColl": p.get("verticalCollision"),
           "onGround": p.get("onGround"), "wp": p.get("walkerWaypoint"), "walker": p.get("walker"),
           "nav": st.get("navActive"), "feet": st.get("feetCell"), "arrived": st.get("arrived"),
           "fwd": keys.get("forward") if keys else None, "jump": keys.get("jump") if keys else None}
    key = json.dumps({k: v for k, v in row.items() if k != "pos"}) + json.dumps([round(c, 1) for c in row["pos"]])
    if key != last:
        row["t"] = round(time.time() - t0, 2)
        print(json.dumps(row), flush=True)
        last = key
    if st.get("arrived") or st.get("stopped") and time.time() - t0 > 3:
        break
    time.sleep(0.05)
gw.close()
