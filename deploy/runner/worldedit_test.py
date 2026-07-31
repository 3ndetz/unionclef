#!/usr/bin/env python3
"""WorldEdit-like //set autotest (py4j select + fillSelection).

Bot on a stone floor with dirt in hand selects a small reachable region and
fills it. Verifies the cells became dirt. (Larger regions: the agent
repositions and re-issues for the cells buildQueue() reports as deferred.)

//set now HANDS the cells to the tick-driven build queue rather than placing
them inside the call — a placement costs 4 ticks (BlockPlaceHelper), so the
test waits on buildQueue().done instead of assuming instant walls.
Exit 0 = pass.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
CLIENT="uctest-mc-tester1"; SERVER="uctest-server"; BOT="tester1"; PORT=25333
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=req.get("port",25333),auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="selhot": out={"ok":mc.selectHotbar(int(req["s"]))}
elif op=="select": out=dict(mc.select(*[int(v) for v in req["r"]]))
elif op=="fill": out=dict(mc.fillSelection(req["b"]))
elif op=="walls": out=dict(mc.wallsSelection(req["b"]))
elif op=="bq": out=dict(mc.buildQueue())
elif op=="tp": out={"ok":True}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=30): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(op,t=25,**kw):
    r=sh(["docker","exec",CLIENT,"python3","-c",SNIP,json.dumps({"op":op,"port":PORT,**kw})],t)
    if r.returncode!=0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
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
def is_block(c): return "passed" in rcon(f"execute if block {c}").lower()

def drain(op, block, rounds=6):
    """Let the build queue drain, repositioning for cells it could not see a face for.

    THIS LOOP IS THE POINT OF THE TEST NOW. Placement goes through the game's own raytrace, so
    a cell whose only support is hidden behind the block just placed CANNOT be filled from where
    the bot stands — the queue reports it as deferred (deferNoFace) instead of placing through it.
    The old code passed this test without moving an inch because it forged the hit result and
    placed straight through the occluding block. An agent (or a player) walks; so does this test.
    """
    last=None
    for r in range(rounds):
        res=py4j(op, b=block)
        if int(res.get("queued",0))==0: return res, last
        t0=time.time()
        while time.time()-t0 < 45:
            last=py4j("bq")
            if last.get("done"): break
            time.sleep(1)
        d=last.get("deferred") or []
        if isinstance(d,str): d=json.loads(d.replace("'",'"')) if d.strip().startswith("[") else []
        if not d: return res, last
        # stand next to the first cell still owed and try again
        x,y,z=[int(v) for v in d[0].split(",")]
        rcon(f"tp {BOT} {x+0.5} {y} {z+1.5} 0 40"); time.sleep(2)
    return res, last

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    rcon("forceload add 0 0 8 8")
    rcon("fill -2 -61 -2 6 -55 6 air")
    rcon("fill -2 -61 -2 6 -61 6 stone")
    rcon("gamerule advance_time false"); rcon("weather clear"); rcon("time set day")
    wait_for("py4j", lambda: py4j("state") is not None,600,10)
    if not py4j("state")["inGame"]:
        py4j("connect",ip="test-server"); wait_for("ingame",lambda:py4j("state")["inGame"],180,5); time.sleep(4)

    # --- test 1: //set + HONEST blockName (hold dirt, fill cobblestone) ---
    rcon("fill 1 -60 1 3 -60 3 air")
    rcon(f"clear {BOT}")
    rcon(f"item replace entity {BOT} hotbar.0 with dirt 64")        # held
    rcon(f"item replace entity {BOT} hotbar.1 with cobblestone 64") # named target
    rcon(f"tp {BOT} 0.5 -60 0.5 -45 0"); time.sleep(3)
    py4j("selhot",s=0)                                              # hold dirt
    sel = py4j("select", r=[1,-60,1, 2,-60,2])
    print(f"  select: {sel}")
    res, q = drain("fill", "cobblestone")                          # ask for cobblestone
    print(f"  fillSelection(cobblestone): {res}")
    print(f"  buildQueue: {q}")
    setcells = [(1,-60,1),(2,-60,1),(1,-60,2),(2,-60,2)]
    set_cobble = sum(1 for (x,y,z) in setcells if is_block(f"{x} {y} {z} cobblestone"))
    print(f"  //set: {set_cobble}/4 cells are cobblestone (proves blockName equip)")

    # --- test 2: //walls hollow box (8-cell ring dirt, center air) ---
    rcon("fill 1 -60 1 3 -60 3 air")
    rcon(f"tp {BOT} 0.5 -60 0.5 -45 0"); time.sleep(2)
    py4j("selhot",s=0)                                              # hold dirt
    selw = py4j("select", r=[1,-60,1, 3,-60,3])
    resw, qw = drain("walls", "dirt")
    print(f"  wallsSelection(dirt): {resw}")
    print(f"  buildQueue: {qw}")
    ring = [(1,-60,1),(2,-60,1),(3,-60,1),(1,-60,2),(3,-60,2),(1,-60,3),(2,-60,3),(3,-60,3)]
    ring_dirt = sum(1 for (x,y,z) in ring if is_block(f"{x} {y} {z} dirt"))
    center_air = is_block("2 -60 2 air")
    print(f"  //walls: ring {ring_dirt}/8 dirt, center air={center_air} (hollow)")

    print(f"\n=== RESULTS ===")
    ok_set = sel.get("ok") and res.get("ok") and set_cobble>=4
    ok_walls = resw.get("ok") and ring_dirt>=8 and center_air
    print(f"  //set (equip cobblestone): {'PASS' if ok_set else 'FAIL'}")
    print(f"  //walls (hollow box):      {'PASS' if ok_walls else 'FAIL'}")
    ok = ok_set and ok_walls
    print("  WORLDEDIT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
