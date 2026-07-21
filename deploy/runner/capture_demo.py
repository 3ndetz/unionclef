#!/usr/bin/env python3
"""Record a demo clip of the bot on the autotest stand via x11grab.

Records the client's actual rendered screen (real FPS, no getScreenshot load on
the render thread — which was breaking the flaky movement) while a scenario runs,
then makes an mp4 + gif in /mc-data (host: deploy/run/data/tester1).
Usage: capture_demo.py <slime|breakplace|pvp> [persp]
"""
import functools, json, subprocess, sys, time, os
print = functools.partial(print, flush=True)
CLIENT="uctest-mc-tester1"; SERVER="uctest-server"; C2="uctest-mc-tester2"; BOT="tester1"; VICTIM="tester2"
SCEN = sys.argv[1] if len(sys.argv)>1 else "slime"
PERSP = int(sys.argv[2]) if len(sys.argv)>2 else 1
HOSTDIR = f"deploy/run/data/{BOT}"

def rcon(c,t=20): return subprocess.run(["docker","exec",SERVER,"rcon-cli",c],capture_output=True,text=True,timeout=t).stdout.strip()
def wait_for(desc,fn,ts,iv=3):
    t0=time.time(); last=None
    while time.time()-t0<ts:
        try:
            last=fn()
            if last: print(f"  [ok] {desc}"); return last
        except Exception as e: last=e
        time.sleep(iv)
    raise TimeoutError(f"{desc}: {ts}s ({last})")

def py4j(container, body, t=40):
    snip=("from py4j.java_gateway import JavaGateway,GatewayParameters\n"
          "gw=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True))\n"
          "mc=gw.entry_point\n"+body+"\ngw.close()")
    return subprocess.run(["docker","exec",container,"python3","-c",snip],capture_output=True,text=True,timeout=t)

def ensure_ingame(container):
    r=py4j(container,"print(mc.inGame())\n"
           "import time\n"
           "if not mc.inGame():\n"
           "    mc.ConnectToServer('test-server')\n"
           "    [time.sleep(3) for _ in range(1)]\n")
    for _ in range(30):
        if py4j(container,"print(mc.inGame())").stdout.strip().endswith("True"): return
        time.sleep(3)
    print(f"  warn: {container} maybe not in game")

def record(dur, trigger_body):
    """Start x11grab (detached), fire the scenario (py4j body), wait, make gif."""
    mp4=f"/mc-data/demo_{SCEN}.mp4"; gif=f"/mc-data/demo_{SCEN}.gif"
    py4j(CLIENT, f"mc.setPerspective({PERSP})")
    # start detached screen recording
    subprocess.run(["docker","exec","-d",CLIENT,"ffmpeg","-y","-f","x11grab","-framerate","15",
                    "-i",":0","-t",str(dur),"-pix_fmt","yuv420p",mp4])
    time.sleep(0.8)  # let the recorder spin up
    py4j(CLIENT, "import time\n"+trigger_body)
    time.sleep(dur+2)
    # mp4 -> gif (downscaled, 10fps)
    subprocess.run(["docker","exec",CLIENT,"ffmpeg","-y","-i",mp4,"-vf","fps=10,scale=600:-1:flags=lanczos",gif],
                   capture_output=True,text=True)
    r=subprocess.run(["docker","exec",CLIENT,"sh","-c",f"ls -la {mp4} {gif}"],capture_output=True,text=True)
    print("  outputs:",r.stdout.strip())

def setup_slime():
    rcon("forceload add -16 -16 24 8")
    for c in ["fill -12 -60 -8 24 -45 8 air",
              "fill -6 -56 -1 -4 -56 1 stone","fill -2 -60 -1 2 -60 1 slime_block","fill 4 -57 -1 6 -57 1 stone",
              "fill 9 -57 0 10 -57 1 stone","fill 11 -60 0 14 -60 1 slime_block","fill 16 -58 -1 18 -58 1 stone"]:
        rcon(c)
    rcon("time set day"); rcon("weather clear")
    rcon(f"tp {BOT} -5 -55 0 90 15"); time.sleep(2)

def setup_bridge():
    # a starting platform, then a wide void the bot godbridges across (places blocks)
    rcon("forceload add 0 0 24 4")
    rcon("fill -2 -61 -3 2 -61 3 stone")               # start pad
    rcon("fill 3 -61 -3 24 -70 3 air")                 # the void ahead
    rcon("fill -2 -60 -3 24 -55 3 air")
    rcon(f"clear {BOT}"); rcon(f"item replace entity {BOT} hotbar.0 with cobblestone 64")
    rcon("time set day"); rcon("weather clear")
    rcon(f"tp {BOT} 0 -60 0 -90 0"); time.sleep(2)

def setup_pvp():
    rcon("forceload add -8 -8 8 8"); rcon("fill -8 -61 -8 8 -61 8 stone"); rcon("fill -8 -60 -8 8 -55 8 air")
    rcon("gamerule pvp true"); rcon("gamerule immediate_respawn true"); rcon("time set day")
    rcon(f"item replace entity {BOT} weapon.mainhand with iron_sword")
    ensure_ingame(C2)
    rcon(f"tp {VICTIM} 4 -60 0 -90 0"); rcon(f"tp {BOT} -1 -60 0 -90 0"); time.sleep(2)

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    ensure_ingame(CLIENT); time.sleep(2)
    if SCEN=="slime":     setup_slime();  record(9,  'mc.ChatMessage(";goto 5 -56 0")')
    elif SCEN=="bridge":  setup_bridge(); record(14, 'mc.selectHotbar(0); mc.bridgeForward("east", 14)')
    elif SCEN=="pvp":     setup_pvp();    record(13, 'mc.ChatMessage(";punk '+VICTIM+'")')
    else: print("unknown scenario"); sys.exit(2)
    print("DONE", SCEN)

if __name__=="__main__": main()
