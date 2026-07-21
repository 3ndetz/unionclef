#!/usr/bin/env python3
"""Block-placing autotest (tungsten/altoclef placeBlockAt primitive).

Bot stands on a stone floor with dirt in hand. It places a line of blocks on
the floor via placeBlockAt, then stacks one on top. Verifies each cell became
dirt (rcon) and that inventorySpace accounts for the blocks.

Exit code 0 = pass.
"""

import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)

CLIENT = "uctest-mc-tester1"
SERVER = "uctest-server"
BOT = "tester1"
PORT = 25333

SNIP = r"""
import json, sys
from py4j.java_gateway import JavaGateway, GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=req.get("port",25333),auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame(),"name":mc.getUsername()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="chat": mc.ChatMessage(req["msg"]); out={"ok":True}
elif op=="place": out=dict(mc.placeBlockAt(int(req["x"]),int(req["y"]),int(req["z"])))
elif op=="space": out=dict(mc.inventorySpace())
elif op=="defend": out=dict(mc.buildDefenseAround(int(req["x"]),int(req["y"]),int(req["z"])))
elif op=="selhot": out={"ok":mc.selectHotbar(int(req["s"]))}
print(json.dumps(out,default=str)); gw.close()
"""

def sh(a,t=30): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(op,t=25,**kw):
    r=sh(["docker","exec",CLIENT,"python3","-c",SNIP,json.dumps({"op":op,"port":PORT,**kw})],t)
    if r.returncode!=0: raise RuntimeError(f"py4j {op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c,t=20):
    r=sh(["docker","exec",SERVER,"rcon-cli",c],t)
    if r.returncode!=0: raise RuntimeError(f"rcon {c}: {r.stderr.strip()[-200:]}")
    return r.stdout.strip()
def wait_for(desc,fn,ts,iv=3):
    t0=time.time(); last=None
    while time.time()-t0<ts:
        try:
            last=fn()
            if last: print(f"  [ok] {desc} ({time.time()-t0:.0f}s)"); return last
        except Exception as e: last=e
        time.sleep(iv)
    raise TimeoutError(f"{desc}: {ts}s (last {last})")
def is_block(check):
    return "passed" in rcon(f"execute if block {check}").lower()

def main():
    print("[1/3] server + arena...")
    wait_for("rcon", lambda: "players" in rcon("list"), 300, 5)
    for c in ["forceload add 0 0 16 16",
              "fill 0 -61 0 12 -55 8 air",
              "fill 0 -61 0 12 -61 8 stone",
              "gamerule advance_time false","weather clear","time set day"]:
        rcon(c)
    print("  arena built")

    print("[2/3] client + gear...")
    wait_for("py4j", lambda: py4j("state") is not None, 600, 10)
    if not py4j("state")["inGame"]:
        py4j("connect", ip="test-server")
        wait_for("in game", lambda: py4j("state")["inGame"], 180, 5)
        time.sleep(5)
    rcon(f"clear {BOT}")
    rcon(f"item replace entity {BOT} hotbar.0 with dirt 64")
    rcon(f"tp {BOT} 0.5 -60 4.5 -90 0")
    time.sleep(3)
    py4j("selhot", s=0)

    sp = py4j("space")
    print(f"  inventorySpace: free={sp.get('freeSlots')} blockCount={sp.get('blockCount')}")
    space_ok = sp.get("ok") and sp.get("blockCount", 0) >= 60

    print("[3/3] placing...")
    # line along +x on the floor (support = floor below each), then a stack
    targets = [(1, -60, 4), (2, -60, 4), (3, -60, 4), (2, -59, 4)]
    placed = []
    for (x, y, z) in targets:
        r = py4j("place", x=x, y=y, z=z)
        time.sleep(0.8)
        ok = is_block(f"{x} {y} {z} dirt")
        placed.append(ok)
        print(f"  place ({x},{y},{z}): {r.get('ok')} {r.get('reason') or r.get('side')} -> block={ok}")

    # ---- bed defense: wall up a "bed" cell, repositioning around it ----
    print("[bed defense]")
    rcon("fill 6 -60 4 6 -60 4 red_bed")  # the 'bed' to defend at (6,-60,4)
    bx, by, bz = 6, -60, 4
    # stand on each side and defend; the shell cells covered accumulate
    for (sx, sz, yaw) in [(4, 4, -90), (8, 4, 90), (6, 2, 180), (6, 6, 0)]:
        rcon(f"tp {BOT} {sx}.5 -60 {sz}.5 {yaw} 0")
        time.sleep(2.5)
        r = py4j("defend", x=bx, y=by, z=bz)
        print(f"  from ({sx},{sz}): placed {len(r.get('placed',[]))}, remaining {len(r.get('remaining',[]))}")
    # verify the horizontal ring around the bed at bed level is solid
    ring = [f"{bx+1} {by} {bz}", f"{bx-1} {by} {bz}", f"{bx} {by} {bz+1}", f"{bx} {by} {bz-1}"]
    ring_ok = sum(1 for c in ring if not is_block(f"{c} air"))
    print(f"  bed ring solid: {ring_ok}/4")
    defense_ok = ring_ok >= 4

    print("\n=== RESULTS ===")
    print(f"  inventorySpace ok: {space_ok}")
    print(f"  placed: {sum(placed)}/{len(targets)}")
    print(f"  bed defense ring: {ring_ok}/4")
    ok = space_ok and all(placed) and defense_ok
    print("  PLACE:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
