#!/usr/bin/env python3
"""Stale-aim auto-release test (#29 in-session root) — a frozen camera can't persist.

Simulate the exact bug: a task sets a mine/combat aim (WindMouse target) and then
DIES without clearing it. The static singleton would otherwise steer the camera at
that target forever (the "camera frozen on a block" report). With the STALE_MS
auto-release, the aim must clear itself within ~1s even though nothing refreshes it.

PASS: hasTarget True right after the poke, False after waiting past STALE_MS. Exit 0.
"""
import functools, json, subprocess, time
print = functools.partial(print, flush=True)
C1 = "uctest-mc-tester1"
SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="poke": out=dict(mc.pokeStaleAim(req["dyaw"]))
elif op=="hastarget": out=dict(mc.windMouseHasTarget())
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a, to=40): return subprocess.run(a, capture_output=True, text=True, timeout=to)
def py4j(op, to=30, **kw):
    r = sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})], to)
    if r.returncode != 0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])

def has(): return py4j("hastarget").get("hasTarget") in (True, "true", "True")

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect", ip="test-server")
        except Exception: pass
        time.sleep(6)
    print("poke a one-shot aim (simulate a task that set a mine aim then died)...")
    py4j("poke", dyaw=50.0)
    time.sleep(0.25)
    set_now = has()
    print(f"  hasTarget right after poke (should be True): {set_now}")
    # active tasks refresh every ~50ms; STALE_MS=600 -> released well under 2s
    time.sleep(2.0)
    cleared = not has()
    print(f"  hasTarget after 2s of no refresh (should be False): {not cleared and 'True' or 'False'}")
    print("\n=== RESULTS (#29 stale-aim auto-release) ===")
    ok = set_now and cleared
    print(f"  aim set, then auto-released: {ok}")
    print("  STALE-AIM:", "PASS" if ok else "FAIL")
    import sys; sys.exit(0 if ok else 1)

if __name__ == "__main__": main()
