#!/usr/bin/env python3
"""WorldEdit shape generators (//hollow, //cyl, //sphere) autotest.

Selects a region, runs each generator, and checks the placed-cell count is the expected
SHAPE — strictly between empty and the full box (a circle/shell/sphere, not //set). Uses the
count fillCells returns (filled + already), repositioning if any cells are out of reach.
Bot on a stone floor with cobblestone in hand.
"""
import functools, json, subprocess, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; BOT="tester1"
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="select": out=dict(mc.select(*[int(v) for v in req["r"]]))
elif op=="hollow": out=dict(mc.hollowSelection(req["b"]))
elif op=="cyl": out=dict(mc.cylSelection(req["b"]))
elif op=="sphere": out=dict(mc.sphereSelection(req["b"]))
elif op=="goto": out=dict(mc.gotoXYZ(req["x"],req["y"],req["z"]))
elif op=="stop": mc.ExecuteCommand("@stop"); out={"ok":True}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,to=40): return subprocess.run(a,capture_output=True,text=True,timeout=to)
def py4j(op,to=40,**kw):
    last=""
    for _ in range(3):
        try:
            r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],to)
            if r.returncode==0 and r.stdout.strip(): return json.loads(r.stdout.strip().splitlines()[-1])
            last=(r.stderr or r.stdout or "").strip()[-160:]
        except Exception as e: last=repr(e)[-160:]
        time.sleep(2)
    raise RuntimeError(f"{op}: {last}")
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()

def prep(region):
    rcon("forceload add -8 -8 16 16")
    x1,y1,z1,x2,y2,z2=region
    rcon(f"fill {x1-2} {y1-1} {z1-2} {x2+2} {y2+2} {z2+2} air")
    rcon(f"fill {x1-2} {y1-1} {z1-2} {x2+2} {y1-1} {z2+2} stone")   # floor
    rcon(f"tp {BOT} {(x1+x2)//2} {y1} {z1-3} 0 0"); time.sleep(1)
    rcon(f"item replace entity {BOT} weapon.mainhand with cobblestone 64")

def total_placed(op, region, block, secs=25):
    """Call the generator, repositioning until complete or time out; return total placed+already."""
    total=0; t0=time.time()
    x1,y1,z1,x2,y2,z2=region
    while time.time()-t0 < secs:
        res=py4j(op, b=block)
        total = int(res.get("filled",0)) + int(res.get("already",0))
        if res.get("complete") or int(res.get("remaining",0))==0: break
        # reposition toward the far side for out-of-reach cells
        py4j("goto", x=x2, y=y1, z=z2); time.sleep(3); py4j("stop")
    return total, res

def run(name, op, region, lo, hi, full):
    prep(region); time.sleep(0.5)
    py4j("select", r=list(region)); time.sleep(0.3)
    total, res = total_placed(op, region, "cobblestone")
    ok = lo <= total <= hi and total < full
    print(f"  {name}: placed={total} (shape range {lo}-{hi}, full box={full}) -> {'PASS' if ok else 'FAIL'}  {res}")
    return ok

def main():
    for _ in range(30):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(6)
    print("=== WorldEdit shape generators ===")
    ok=[]
    # //cyl on 5x5x1 (25-cell box) -> a circle ~13-21 cells, definitely < 25
    ok.append(run("//cyl 5x5",  "cyl",    (2,-60,2, 6,-60,6),  12, 24, 25))
    # //hollow on 3x3x3 (27 box) -> 6-face shell = 26
    ok.append(run("//hollow 3x3x3","hollow",(2,-60,2, 4,-58,4), 24, 26, 27))
    # //sphere on 5x5x5 (125 box) -> inscribed sphere ~55-90, < 125
    ok.append(run("//sphere 5x5x5","sphere",(2,-60,2, 6,-56,6), 40,110,125))
    print(f"=== PASS {sum(ok)}/{len(ok)} ===")

if __name__=="__main__": main()
