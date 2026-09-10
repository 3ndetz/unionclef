#!/usr/bin/env python3
"""Capture WHY the @gamer playthrough stalls reaching iron: video + a rich per-second log of
position, facing (yaw/pitch), velocity, what the crosshair is on, and the current task.

The playthrough reliably reaches stone tools (~150 s) then stops making progress on the way to
iron. This runs the real survival playthrough, records the screen the whole time, and samples the
new getGameState rotation/look fields so the stall can be read from FACING, not just position.

    docker compose -f deploy/compose.test.yml --profile gamer up -d     # once
    python3 deploy/runner/iron_stall_capture.py [seconds]               # default 420 (7 min)

Output (in this repo, via the tester1 bind mount):
  deploy/run/data/tester1/iron_stall.mp4   — the raw screen recording
  deploy/runner/iron_stall_log.txt         — the per-sample rotation/position/task log
"""
import functools, json, os, subprocess, sys, time
print = functools.partial(print, flush=True)
CLIENT = "uctest-mc-tester1"; GSERVER = "uctest-gamer-server"; PORT = 25333
DUR = float(sys.argv[1]) if len(sys.argv) > 1 else 420.0
MP4 = "/mc-data/iron_stall.mp4"
LOG = os.path.join(os.path.dirname(__file__), "iron_stall_log.txt")
SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=req.get("port",25333),auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="swap": out=dict(mc.setTungstenPathing(bool(req["on"])))
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="respawn": out={"r":str(mc.respawnPlayer())}
elif op=="gs":
    gs=mc.getGameState(); out={"inGame":gs.get("inGame"),"self":dict(gs.get("self") or {})}
elif op=="task":
    out={"chain":str(mc.getTaskChainString() or "").replace(chr(10)," | ")[-500:],
         "busy":mc.hasActiveTask()}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(int(req.get("n",6)))]}
elif op=="stats": out={"s":str(mc.placeStats())[:400]}
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a, t=40): return subprocess.run(a, capture_output=True, text=True, timeout=t)
def py4j(op, t=30, **kw):
    r = sh(["docker","exec",CLIENT,"python3","-c",SNIP,json.dumps({"op":op,"port":PORT,**kw})], t)
    if r.returncode != 0: raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])
def grcon(c, t=20): return sh(["docker","exec",GSERVER,"rcon-cli",c], t).stdout.strip()

log = open(LOG, "w", encoding="utf-8")
def out(s):
    print(s); log.write(s + "\n"); log.flush()

# --- bootstrap (mirror gamer_smoke) ---
t0 = time.time()
if not py4j("state")["inGame"]:
    py4j("connect", ip="gamer-server")
while time.time() - t0 < 200 and not py4j("state")["inGame"]:
    time.sleep(5)
if not py4j("state")["inGame"]:
    out("FAIL: not in game after 200s"); sys.exit(2)
py4j("cmd", c="@stop"); time.sleep(1)
grcon("kill tester1"); time.sleep(2); py4j("respawn"); time.sleep(2)
grcon("gamemode survival tester1"); grcon("time set day"); grcon("effect give tester1 minecraft:instant_health 1 5")
try: py4j("swap", on=True)
except Exception as e: out("swap warn: " + str(e))
# fresh spot ~300 blocks out along a diagonal to avoid deforested ground
sx, sz = 700, -700
grcon(f"tp tester1 {sx} 90 {sz}"); time.sleep(3)
py4j("cmd", c="@stop"); time.sleep(1)

out(f"[rec] {MP4} for {DUR:.0f}s")
subprocess.run(["docker","exec","-d",CLIENT,"ffmpeg","-y","-f","x11grab","-framerate","12",
                "-i",":0","-t",str(int(DUR)),"-pix_fmt","yuv420p", MP4])
time.sleep(2)
py4j("cmd", c="@gamer")
out(f"[go] @gamer, sampling {DUR:.0f}s")
out("  t   pos                     yaw    pitch  vel                held/look                 blocks task")

rungs = []; last_task = ""; stall_since = None; last_pos = None; stall_dumped = False
start = time.time()
while time.time() - start < DUR:
    time.sleep(3)
    try:
        gs = py4j("gs"); s = gs.get("self") or {}
        tk = py4j("task")
    except Exception as e:
        out(f"  poll err: {str(e)[:80]}"); continue
    el = time.time() - start
    pos = s.get("pos", "?"); yaw = s.get("yaw"); pit = s.get("pitch")
    vel = s.get("vel", "?"); look = s.get("lookingAt", "?"); held = s.get("held", "?")
    blocks = s.get("blocks"); hp = s.get("hp")
    chain = tk.get("chain", "")
    # compress the task chain to its deepest subtask line for the table
    short = chain.split("|")[-1].strip()[:60] if chain else ""
    out(f"  {el:4.0f} {pos:22s} {str(yaw):6s} {str(pit):6s} {vel:18s} {held.replace('minecraft:',''):12s}/{look:16s} b{blocks} hp{hp} {short}")
    # rung tracking from chat
    for c in py4j("chat", n=5).get("chat", []):
        if "RUNG" in c or " achievement" in c:
            pass
    # stall detection on position
    if pos == last_pos:
        if stall_since is None: stall_since = el
        elif el - stall_since > 20 and not stall_dumped:
            out(f"  >>> STALLED {el-stall_since:.0f}s at {pos} facing yaw={yaw} pitch={pit} looking={look}")
            out("  >>> FULL TASK: " + chain)
            out("  >>> STATS: " + py4j("stats").get("s", ""))
            out("  >>> CHAT: " + " || ".join(x[-90:] for x in py4j("chat", n=8).get("chat", [])))
            stall_dumped = True
    else:
        stall_since = None; stall_dumped = False
    last_pos = pos
    last_task = short

py4j("cmd", c="@stop")
time.sleep(int(DUR) - (time.time() - start) + 3 if time.time() - start < DUR else 3)
# extract a few frames
subprocess.run(["docker","exec",CLIENT,"sh","-c",
                f"ffmpeg -y -i {MP4} -vf fps=1/30 /mc-data/iron_frame_%02d.png >/dev/null 2>&1; ls -la {MP4}"],
               capture_output=True, text=True)
r = sh(["docker","exec",CLIENT,"sh","-c",f"ls -la {MP4}"])
out("[done] " + r.stdout.strip())
log.close()
