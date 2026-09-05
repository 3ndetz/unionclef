#!/usr/bin/env python3
"""Record a raw clip of the agent ATTEMPTING the real @gamer playthrough -- not a scripted
arena demo of one operation.

T-7bb21f60 (2026-09-05): the founder rejected a text-card video and asked to see the agent
trying to play and failing. capture_demo.py's five scenarios (slime/bridge/worldedit/pvp/bedwars)
are all clean, scripted arena demonstrations built to show ONE mechanism working -- exactly what
he said he does not want. This script instead connects to the real survival gamer-server (the
same one gamer_smoke.py measures against) and records whatever the shipped build actually does:
moving, mining, pathing -- and, if the run runs long enough, the wander/recovery stall already
reported to him (TimeoutWanderTask, 67-70% of the bot's 32% dead time on the current build). If
the honest footage is the bot standing still inside that task, that is what gets shown -- do not
stage a recovery or a success, per lumi's ruling on T-7bb21f60.

Connect/bootstrap sequence mirrors gamer_smoke.py exactly (same retry-on-reconnect, same
respawn-if-dead handling) rather than reinventing it -- that script's own comments record why
each step exists (a dead bot with no position, a client that silently rejoins the wrong server,
a NullPointerException from starting @gamer before the world finished loading).

Prerequisite: the gamer-server profile must be up --
    docker compose -f deploy/compose.test.yml --profile gamer up -d

Usage: capture_gamer_attempt.py [duration_seconds]
    duration_seconds defaults to 240 (4 minutes) -- long enough on a live build to plausibly
    catch a real stall inside the recording without the raw file becoming unwieldy. The clip
    gets TRIMMED to the founder's under-60-seconds limit during editing (post Editor); this
    script's job is only to produce honest raw material to trim FROM, not to already be short.

Output: /mc-data/demo_gamer_attempt.mp4 inside uctest-mc-tester1, which is a bind mount of this
repo's own deploy/run/data/tester1/ -- confirmed via `docker inspect uctest-mc-tester1` (lumi,
T-7bb21f60 second ruling). No file transfer needed: whatever room already mounts this repository
sees the clip appear there directly. Verification FRAMES are extracted for the same reason
capture_demo.py extracts them -- look at them before anything gets cut or sent (checklist rule,
2026-07-24: never ship an unwatched clip).
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
CLIENT = "uctest-mc-tester1"
GSERVER = "uctest-gamer-server"
PORT = 25333
DUR = float(sys.argv[1]) if len(sys.argv) > 1 else 240.0
SCEN = "gamer_attempt"

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=req.get("port",25333),auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="gs":
    gs=mc.getGameState()
    out={"inGame":gs.get("inGame"),"self":dict(gs.get("self") or {})}
elif op=="swapstate": out=dict(mc.pathingMode())
elif op=="perspective": mc.setPerspective(req["persp"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="respawn": out={"r": str(mc.respawnPlayer())}
print(json.dumps(out,default=str)); gw.close()
"""

def sh(a, t=40):
    return subprocess.run(a, capture_output=True, text=True, timeout=t)

def py4j(op, t=30, **kw):
    r = sh(["docker", "exec", CLIENT, "python3", "-c", SNIP, json.dumps({"op": op, "port": PORT, **kw})], t)
    if r.returncode != 0:
        raise RuntimeError(f"{op}: {r.stderr.strip()[-200:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])

def grcon(c, t=20):
    return sh(["docker", "exec", GSERVER, "rcon-cli", c], t).stdout.strip()

def wait_for(desc, fn, ts, iv=3):
    t0 = time.time(); last = None
    while time.time() - t0 < ts:
        try:
            last = fn()
            if last:
                print(f"  [ok] {desc}"); return last
        except Exception as e:
            last = e
        time.sleep(iv)
    raise TimeoutError(f"{desc}: {ts}s ({last})")

def connect_and_join():
    print("[1] wait gamer-server rcon...")
    wait_for("gamer rcon", lambda: "players" in grcon("list"), 120, 6)
    print("[2] connect bot to gamer-server (retrying on a failed rejoin, matching gamer_smoke.py)...")
    joined = False
    for attempt in range(4):
        py4j("connect", ip="gamer-server")
        try:
            wait_for("bot in game (gamer)", lambda: py4j("state")["inGame"], 60, 5)
            joined = True
            break
        except TimeoutError:
            print(f"  connect attempt {attempt + 1} did not land, retrying")
    if not joined:
        raise RuntimeError("client would not rejoin after four attempts")
    print("[3] wait for a real position (world finished loading)...")
    try:
        wait_for("world loaded (bot has a position)",
                 lambda: bool((py4j("gs").get("self") or {}).get("pos")), 60, 5)
    except TimeoutError:
        # A bot with no position after a rejoin is usually dead on the death screen, not a
        # broken stand -- same distinction gamer_smoke.py already draws, using the same
        # mc.respawnPlayer() lever.
        print("  no position -- bot may be dead, respawning (mc.respawnPlayer())")
        for _ in range(8):
            try:
                py4j("respawn")
            except Exception:
                pass
            time.sleep(4)
            if bool((py4j("gs").get("self") or {}).get("pos")):
                break
        if not bool((py4j("gs").get("self") or {}).get("pos")):
            raise RuntimeError("no position even after respawn attempts")
        print("  respawned")
    time.sleep(5)

def record_and_capture(dur):
    print(f"[4] confirming the shipped pathfinder is tungsten (showing the real default, not a special config)...")
    st = py4j("swapstate")
    print("  shipped pathing flags:", st)
    if not st.get("tungstenPrimary"):
        print("  WARNING: tungsten is not the default pathfinder on this build -- footage would "
              "misrepresent what actually ships. Stopping rather than recording a misleading clip.")
        sys.exit(2)
    print("[5] third-person view, so the acting body is visible in shot...")
    py4j("perspective", persp=1)
    mp4 = f"/mc-data/demo_{SCEN}.mp4"
    print(f"[6] recording {dur:.0f}s to {mp4} and starting @gamer...")
    subprocess.run(["docker", "exec", "-d", CLIENT, "ffmpeg", "-y", "-f", "x11grab", "-framerate", "15",
                     "-i", ":0", "-t", str(int(dur)), "-pix_fmt", "yuv420p", mp4])
    time.sleep(0.8)
    py4j("cmd", c="@gamer")
    print(f"  recording, waiting {dur:.0f}s for it to finish...")
    time.sleep(dur + 3)
    return mp4

def extract_frames(mp4, dur):
    print("[7] extracting verification frames -- LOOK AT THESE before cutting or sending anything.")
    gif = f"/mc-data/demo_{SCEN}.gif"
    subprocess.run(["docker", "exec", CLIENT, "ffmpeg", "-y", "-i", mp4, "-vf",
                     "fps=6,scale=640:-1:flags=lanczos", gif], capture_output=True, text=True)
    for tag, frac in [("a", 0.10), ("b", 0.30), ("c", 0.50), ("d", 0.70), ("e", 0.90)]:
        ts = max(0.5, dur * frac)
        subprocess.run(["docker", "exec", CLIENT, "ffmpeg", "-y", "-ss", f"{ts:.1f}", "-i", mp4,
                         "-frames:v", "1", "-vf", "scale=640:-1", f"/mc-data/frame_{SCEN}_{tag}.png"],
                        capture_output=True, text=True)
    r = subprocess.run(["docker", "exec", CLIENT, "sh", "-c", f"ls -la {mp4} {gif}"],
                        capture_output=True, text=True)
    print("  outputs:", r.stdout.strip().replace("\n", " | "))
    print("  frames: /mc-data/frame_gamer_attempt_{a,b,c,d,e}.png -- 10/30/50/70/90% through the clip")

def main():
    connect_and_join()
    mp4 = record_and_capture(DUR)
    extract_frames(mp4, DUR)
    print("DONE", SCEN)
    print(f"Host path (bind-mounted from uctest-mc-tester1's /mc-data): "
          f"deploy/run/data/tester1/demo_{SCEN}.mp4 and frame_{SCEN}_*.png, inside this repo.")
    print("NEXT: look at the extracted frames. If they show a genuine attempt (moving/mining/pathing) "
          "and, ideally, a real stall, this is ready for post Editor to trim to under 60s, caption in "
          "Russian, and send. If the clip shows nothing useful (e.g. the bot died and sat on a respawn "
          "screen the whole time), re-run with a longer duration or at a different moment.")

if __name__ == "__main__":
    main()
