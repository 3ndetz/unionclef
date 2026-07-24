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
    # Extract verification FRAMES (start / mid / end) as PNGs so a human/agent can LOOK at
    # the footage before sending it — never ship an unwatched clip (checklist rule 2026-07-24).
    for tag,frac in [("a",0.30),("b",0.60),("c",0.92)]:
        ts=max(0.5, dur*frac)
        subprocess.run(["docker","exec",CLIENT,"ffmpeg","-y","-ss",f"{ts:.1f}","-i",mp4,
                        "-frames:v","1","-vf","scale=640:-1",f"/mc-data/frame_{SCEN}_{tag}.png"],
                       capture_output=True,text=True)
    r=subprocess.run(["docker","exec",CLIENT,"sh","-c",f"ls -la {mp4} {gif} /mc-data/frame_{SCEN}_*.png"],capture_output=True,text=True)
    print("  outputs:",r.stdout.strip())

def setup_slime():
    rcon("forceload add -16 -16 24 8")
    for c in ["fill -12 -60 -8 24 -45 8 air",
              "fill -6 -56 -1 -4 -56 1 stone","fill -2 -60 -1 2 -60 1 slime_block","fill 4 -57 -1 6 -57 1 stone",
              "fill 9 -57 0 10 -57 1 stone","fill 11 -60 0 14 -60 1 slime_block","fill 16 -58 -1 18 -58 1 stone"]:
        rcon(c)
    rcon("time set day"); rcon("weather clear")
    rcon(f"tp {BOT} -5 -55 0 90 15"); time.sleep(2)

BRIDGE_N = 10   # blocks to godbridge over the void
def setup_bridge():
    # a small pad at the EDGE of a wide void; bot stands ON THE EDGE so the very first
    # step is already over the void (no walking inland on stone first — that made the old
    # clip look like "walks on the floor, doesn't bridge"). Places blocks across the gap.
    rcon("forceload add -4 -4 30 4")
    rcon("fill -3 -61 -3 2 -61 3 stone")               # small start pad, east edge at x=2
    rcon("fill 3 -61 -3 30 -70 3 air")                 # the void ahead (x>=3)
    rcon("fill -3 -60 -3 30 -50 3 air")                # clear space above
    rcon(f"clear {BOT}"); rcon(f"item replace entity {BOT} hotbar.0 with cobblestone 64")
    rcon("time set day"); rcon("weather clear")
    rcon(f"tp {BOT} 2 -60 0 -90 0"); time.sleep(2)     # AT the pad's east edge, over the void next step

def setup_pvp():
    rcon("forceload add -8 -8 8 8"); rcon("fill -8 -61 -8 8 -61 8 stone"); rcon("fill -8 -60 -8 8 -55 8 air")
    rcon("gamerule pvp true"); rcon("gamerule immediate_respawn true"); rcon("time set day")
    rcon(f"item replace entity {BOT} weapon.mainhand with iron_sword")
    ensure_ingame(C2)
    rcon(f"tp {VICTIM} 4 -60 0 -90 0"); rcon(f"tp {BOT} -1 -60 0 -90 0"); time.sleep(2)

def setup_we():
    rcon("forceload add -16 -16 16 16")
    rcon("fill -8 -60 -8 8 -50 8 air")
    rcon("fill -8 -61 -8 8 -61 8 stone")             # floor
    rcon("time set day"); rcon("weather clear")
    rcon(f"item replace entity {BOT} hotbar.1 with stone 64")
    rcon(f"item replace entity {BOT} hotbar.2 with glass 64")
    rcon(f"item replace entity {BOT} hotbar.3 with diamond_pickaxe")
    rcon(f"tp {BOT} 0 -60 2 180 12"); time.sleep(2)  # face -z toward z=0, look slightly down

# @@ WorldEdit: a 5x2 wall APPEARS (@@set stone), then turns to GLASS (@@replace) — real
# survival placement/breaking through the tungsten executor, on camera.
# 3 wide x 2 high = 6 cells. Fixed-length windows sized to the real op so record(dur)
# never stops mid-replace (that truncation is exactly what cut the last video off).
WE_SET_WIN = 10   # //set: place 6 blocks
WE_REP_WIN = 22   # //replace: break 6 stone + place 6 glass (diamond pickaxe ~1.3s/break)
WE_BODY = (
    'mc.ExecuteCommand("@stop")\n'
    'time.sleep(1.5)\n'
    'mc.select(-1,-60,0, 1,-59,0)\n'
    'time.sleep(1.5)\n'
    'mc.we("set stone")\n'
    f'time.sleep({WE_SET_WIN})\n'
    'mc.we("replace stone glass")\n'
    f'time.sleep({WE_REP_WIN})\n'
)

def setup_bedwars():
    # two islands over a void: near (bot) + far (victim), bridge the gap then fight
    rcon("forceload add -8 -8 24 8")
    rcon("fill -8 -60 -8 24 -50 8 air")                 # clear the arena
    rcon("fill -8 -70 -8 24 -61 8 air")                 # void below
    rcon("fill -2 -61 -4 2 -61 4 stone")               # near island (bot), east edge at x=2
    rcon("fill 9 -61 -4 15 -61 4 stone")               # far island (victim + bed), west edge at x=9
    rcon("setblock 12 -60 0 red_bed")                  # the 'bed' on the far island
    rcon("gamerule pvp true"); rcon("gamerule immediate_respawn true")
    rcon("time set day"); rcon("weather clear")
    rcon(f"clear {BOT}")
    rcon(f"item replace entity {BOT} hotbar.0 with cobblestone 64")
    rcon(f"item replace entity {BOT} hotbar.1 with iron_sword")
    ensure_ingame(C2)
    rcon(f"tp {VICTIM} 12 -60 0 90 0")
    rcon(f"tp {BOT} 2 -60 0 -90 0"); time.sleep(2)     # at the near-island east edge; void x=3..8 next

# comprehensive bedwars: BRIDGE across the void to the enemy island, then FIGHT — all on tungsten
BEDWARS_BODY = (
    'mc.selectHotbar(0)\n'
    'mc.bridgeTo(9, -60, 0)\n'       # godbridge across the void (x=3..8) to the far island
    'time.sleep(13)\n'
    'mc.selectHotbar(1)\n'
    'mc.ChatMessage(";punkPlayer '+VICTIM+'")\n'   # fight on the enemy island
    'time.sleep(12)\n'
)

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    ensure_ingame(CLIENT); time.sleep(2)
    if SCEN=="slime":     setup_slime();  record(9,  'mc.ChatMessage(";goto 5 -56 0")')
    elif SCEN=="bridge":  setup_bridge(); record(BRIDGE_N*2+6, f'mc.selectHotbar(0); mc.bridgeForward("east", {BRIDGE_N})')
    elif SCEN=="pvp":     setup_pvp();    record(13, 'mc.ChatMessage(";punkPlayer '+VICTIM+'")')
    elif SCEN=="worldedit": setup_we();   record(3+WE_SET_WIN+WE_REP_WIN+2, WE_BODY)
    elif SCEN=="bedwars": setup_bedwars(); record(28, BEDWARS_BODY)
    else: print("unknown scenario"); sys.exit(2)
    print("DONE", SCEN)

if __name__=="__main__": main()
