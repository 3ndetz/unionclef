"""Runs INSIDE the client container: a plain miss swing, then a guarded press 0.15 s later, while
vanilla's attack timer (10 ticks after a miss) still runs. Over docker exec the two calls are too far
apart: the timer was already 0. argv: targetUuid. Prints both results as JSON. (2026-11-27)"""
import json
import sys
import time

from py4j.java_gateway import GatewayParameters, JavaGateway

gw = JavaGateway(gateway_parameters=GatewayParameters(port=25333, auto_convert=True))
ep = gw.entry_point
miss = ep.tapKeyReceipt("attack")
time.sleep(0.15)
r = ep.tapAttackGuarded(sys.argv[1], True)
print(json.dumps({"miss_accepted": miss.get("accepted"), "accepted": r.get("accepted"),
                  "suppressedBy": r.get("suppressedBy"), "attackCooldown": r.get("attackCooldown"),
                  "crosshairAtCall": str(r.get("crosshairAtCall"))}))
gw.close()
