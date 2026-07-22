#!/usr/bin/env python3
"""Multi-target / avoid-target combat selection autotest (tungsten PunkPlayerTask).

With one victim (tester2) as a candidate, validates the target-SELECTION policy
the agent controls: punkAny(avoid=[tester2]) must NOT acquire it; punkAny(
allow=[tester2]) must acquire it. Proves the brain picks who to hit; the mod
executes. Bring up the victim first:
  docker compose -f deploy/compose.test.yml --profile pvp up -d
Exit 0 = pass.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; C2="uctest-mc-tester2"
BOT="tester1"; VICTIM="tester2"; PORT=25333
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=req.get("port",25333),auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="punkany": out=dict(mc.punkAny(req.get("allow") or [], req.get("avoid") or []))
elif op=="punkstop": out=dict(mc.punkStop())
elif op=="punkstatus": out=dict(mc.punkStatus())
elif op=="chat": mc.ChatMessage(req["msg"]); out={"ok":True}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=30): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(container,op,t=25,**kw):
    r=sh(["docker","exec",container,"python3","-c",SNIP,json.dumps({"op":op,"port":PORT,**kw})],t)
    if r.returncode!=0: raise RuntimeError(f"{op}@{container}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c,t=20): return sh(["docker","exec",SERVER,"rcon-cli",c],t).stdout.strip()
def wait_for(desc,fn,ts,iv=3):
    t0=time.time(); last=None
    while time.time()-t0<ts:
        try:
            last=fn()
            if last: print(f"  [ok] {desc}"); return last
        except Exception as e: last=e
        time.sleep(iv)
    raise TimeoutError(f"{desc}: {ts}s ({last})")
def ensure(container,label):
    if not py4j(container,"state")["inGame"]:
        py4j(container,"connect",ip="test-server")
        wait_for(f"{label} in game", lambda: py4j(container,"state")["inGame"],180,5); time.sleep(3)

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    rcon("forceload add 0 0 4 4"); rcon("fill -4 -61 -4 8 -61 8 stone"); rcon("fill -4 -60 -4 8 -55 8 air")
    rcon("gamerule pvp true"); rcon("time set day")
    wait_for("t1 py4j", lambda: py4j(C1,"state") is not None,600,10); ensure(C1,"bot")
    wait_for("t2 py4j", lambda: py4j(C2,"state") is not None,600,10); ensure(C2,"victim")
    py4j(C1,"chat",msg=";stop"); py4j(C1,"punkstop")
    rcon(f"tp {VICTIM} 3 -60 0 -90 0"); rcon(f"tp {BOT} 0 -60 0 -90 0"); time.sleep(3)

    # avoid the only candidate -> must NOT acquire a target
    py4j(C1,"punkany", avoid=[VICTIM])
    time.sleep(4)
    st_avoid=py4j(C1,"punkstatus")
    print(f"  punkAny(avoid=[{VICTIM}]) -> active={st_avoid.get('active')} target={st_avoid.get('target')}")

    # allow the candidate -> must acquire it
    py4j(C1,"punkany", allow=[VICTIM])
    time.sleep(4)
    st_allow=py4j(C1,"punkstatus")
    print(f"  punkAny(allow=[{VICTIM}]) -> active={st_allow.get('active')} target={st_allow.get('target')}")

    py4j(C1,"punkstop")
    st_stop=py4j(C1,"punkstatus")
    print(f"  punkStop -> active={st_stop.get('active')} target={st_stop.get('target')}")

    print("\n=== RESULTS ===")
    avoided = (st_avoid.get("active")==True and st_avoid.get("target") in (None,"None",""))
    acquired = (st_allow.get("target")==VICTIM)
    stopped = (st_stop.get("active")==False)
    print(f"  avoid honored (no target): {avoided}")
    print(f"  allow acquired {VICTIM}: {acquired}")
    print(f"  stop cleared: {stopped}")
    ok = avoided and acquired and stopped
    print("  MULTITARGET:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
