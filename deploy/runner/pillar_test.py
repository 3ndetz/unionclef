#!/usr/bin/env python3
"""PillarTask test (#46) — pillar up by placing blocks under self.

Give the bot a stack of stone, put it on flat ground, pillarTo 5 blocks up.
PASS: the bot rises ~5 blocks (places blocks under itself), no crash. Exit 0.
"""
import functools, json, subprocess, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="pillar": out={"ok":mc.pillarTo(int(req["y"])),"active":mc.pillarActive()}
elif op=="pstate": out={"active":mc.pillarActive(),"placed":mc.pillarPlaced()}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a, to=40): return subprocess.run(a, capture_output=True, text=True, timeout=to)
def py4j(op, to=30, **kw):
    r = sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})], to)
    if r.returncode != 0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def pos():
    for _ in range(6):
        o = rcon(f"data get entity {BOT} Pos")
        try:
            p = o.split("[")[1].split("]")[0].split(",")
            return [round(float(v.strip().rstrip("d")),1) for v in p]
        except Exception:
            time.sleep(1)
    return None

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect", ip="test-server")
        except Exception: pass
        time.sleep(6)
    py4j("cmd", c="@stop"); time.sleep(1)
    rcon("forceload add -8 -8 8 8")
    rcon("fill -4 -60 -4 4 5 4 air"); rcon("fill -4 -61 -4 4 -61 4 stone")
    rcon(f"tp {BOT} 0 -60 0 0 0"); time.sleep(1)
    rcon(f"item replace entity {BOT} weapon.mainhand with minecraft:stone 64")
    time.sleep(1)
    p0 = pos()
    if p0 is None:
        print("FAIL: could not read bot position (not loaded?)"); import sys; sys.exit(1)
    y0 = p0[1]
    print(f"start y={y0}, block in hand, pillar to {int(y0)+5}...")
    print("pillar:", py4j("pillar", y=int(y0)+5))
    best = y0; t0 = time.time()
    while time.time() - t0 < 30:
        time.sleep(2)
        p = pos()
        if p: best = max(best, p[1])
        st = py4j("pstate")
        if not st.get("active") and time.time() - t0 > 4:
            print(f"  pillar finished (placed={st.get('placed')}) at {time.time()-t0:.0f}s")
            break
    py4j("cmd", c="@stop")
    alive = py4j("state").get("inGame", False)
    risen = best - y0
    print("\n=== RESULTS (#46 pillar) ===")
    print(f"  rose {risen:.1f} blocks (want >=4), maxY={best}")
    print(f"  client alive (no crash): {alive}")
    ok = risen >= 4 and alive
    print("  PILLAR:", "PASS" if ok else "FAIL")
    import sys; sys.exit(0 if ok else 1)

if __name__ == "__main__": main()
