#!/usr/bin/env python3
"""Re-equip autotest (register C5.9): does a bridge survive its stack running out?

BridgeTask and PillarTask used to ABORT the moment the held stack emptied — which mid-bridge
means stopping on a one-block ledge over the gap being crossed. Upstream re-selects a throwaway
on every attempt and only gives up when the inventory has nothing at all
(MovementHelper.attemptToPlaceABlock:819-823).

The test gives the bot TWO dirt in the held slot and a full stack of cobblestone beside it, then
asks for a bridge longer than two blocks. Passing means the bridge continued into the cobblestone
— i.e. it re-equipped instead of stopping. Exit 0 = pass.
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
# "raised": the same bridge, but from a pad ten blocks above the world floor, which is the case
# the demo scenario failed at and the flat arena cannot show — falling there is unrecoverable, so
# the probe recovers the bot first (register C5.14).
RAISED = len(sys.argv) > 1 and sys.argv[1] == "raised"
PAD_Y = -54 if RAISED else -61
SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
try:
    if op=="state": out={"inGame":mc.inGame()}
    elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
    elif op=="stop": mc.ExecuteCommand("@stop"); out={"ok":True}
    elif op=="selhot": out={"ok":mc.selectHotbar(int(req["s"]))}
    elif op=="bridge": out={"ok":mc.bridgeForward(req["d"], int(req["n"]))}
    elif op=="placed": out={"n":mc.bridgePlaced(),"active":mc.bridgeActive()}
    elif op=="chat": out={"c":[str(x) for x in (mc.getRecentChat(30) or [])]}
    elif op=="ground": out={"g":bool(dict(mc.getGameState().get("self",{})).get("onGround")),"pos":str(dict(mc.getGameState().get("self",{})).get("pos"))}
    elif op=="held": out={"h":str(dict(mc.getGameState().get("self",{})).get("held")),"pos":str(dict(mc.getGameState().get("self",{})).get("pos"))}
except Exception as e:
    sys.stderr.write("ERR:"+repr(e)+"\n"); sys.exit(3)
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a, t=40): return subprocess.run(a, capture_output=True, text=True, timeout=t)
def py4j(op, t=30, **kw):
    r = sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})], t)
    if r.returncode != 0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def solid(x, y, z):
    return "passed" not in rcon(f"execute if block {x} {y} {z} air").lower()

def main():
    for _ in range(40):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect", ip="test-server")
        except Exception: pass
        time.sleep(5)
    py4j("stop"); time.sleep(1)

    # A gap to bridge: solid ground at x=0, then air from x=1 to x=8, at y=-61.
    rcon("forceload add -8 -8 16 16")
    # RECOVER FIRST. A bot that fell into the void keeps falling and its position overrides the
    # server's, so every later run measures nothing. spectator takes physics off the client.
    rcon(f"gamemode spectator {BOT}"); time.sleep(1)
    rcon(f"tp {BOT} 0.5 {PAD_Y + 1} 0.5"); time.sleep(2)
    rcon(f"fill -2 {PAD_Y} -4 12 {PAD_Y + 6} 4 air")
    rcon(f"fill -2 {PAD_Y} -4 0 {PAD_Y} 4 stone")     # the near bank only
    rcon(f"tp {BOT} 0.5 {PAD_Y + 1} 0.5"); time.sleep(2)
    rcon(f"gamemode survival {BOT}")
    rcon(f"clear {BOT}")
    rcon(f"item replace entity {BOT} hotbar.0 with dirt 2")          # runs out after two
    rcon(f"item replace entity {BOT} hotbar.1 with cobblestone 64")  # the re-equip target
    rcon(f"tp {BOT} 0.5 {PAD_Y + 1} 0.5 -90 0"); time.sleep(2)
    py4j("selhot", s=0)                       # hold the SHORT stack
    # SETTLE FIRST. A teleport drops the bot a few ticks while the client loads the floor, and
    # BridgeTask aborts on one tick of downward velocity — so starting eagerly kills the bridge
    # before it begins, with "aborted (falling) after 0". That is the harness being impatient,
    # not the bridge being broken; wait for the bot to actually be standing.
    for _ in range(20):
        g = py4j("ground")
        if g["g"]:
            print("  settled:", g)
            break
        time.sleep(0.5)
    time.sleep(1.0)

    print(f"=== bridge 6 blocks holding a stack of 2 (pad y={PAD_Y}, "
          f"{'RAISED over a void' if RAISED else 'flat'}) ===")
    print("  start:", py4j("bridge", d="east", n=6))
    for k in range(40):
        st = py4j("placed")
        if k < 5: print(f"    poll[{k}]: {st}")
        if k > 2 and not st["active"]: break
        time.sleep(1.5)
    time.sleep(1)

    print("  held/pos after:", py4j("held"))
    for line in py4j("chat")["c"]:
        if "bridge" in line.lower() or "block" in line.lower():
            print("    chat:", line)
    # Same re-read as diag_replace: the task can report "Bridge done" a moment before the server
    # has the last blocks, and snapshotting once turned that into a false FAIL.
    paved = []
    for _ in range(10):
        paved = [x for x in range(1, 7) if solid(x, PAD_Y, 0)]
        if len(paved) >= 3:
            break
        time.sleep(1)
    print(f"  paved cells: {paved}")
    # Two dirt can only pave two cells; anything beyond proves the re-equip fired.
    ok = len(paved) >= 3
    print(f"  RE-EQUIP: {'PASS' if ok else 'FAIL'} ({len(paved)} paved, >2 means it re-equipped)")
    py4j("stop")
    sys.exit(0 if ok else 1)

if __name__ == "__main__": main()
