#!/usr/bin/env python3
"""Record a demo clip of the bot on the autotest stand via x11grab.

Records the client's real rendered screen (no getScreenshot render-thread load) while a
scenario runs, makes an mp4 + gif in /mc-data (host: deploy/run/data/tester1), and extracts
verification FRAMES so the footage is LOOKED AT before it's ever sent (checklist rule
2026-07-24 — never ship an unwatched clip).

Each scenario: (1) wipes a CLEAN arena (no leftover cruft that makes a void ambiguous),
(2) builds unambiguous geometry (deep void, wall, enemy in frame), (3) picks a camera
perspective that keeps the SUBJECT visible, (4) sizes the record duration to the op so it's
never truncated. Usage: capture_demo.py <slime|bridge|worldedit|pvp|bedwars>
"""
import functools, json, subprocess, sys, time, os
print = functools.partial(print, flush=True)
CLIENT="uctest-mc-tester1"; SERVER="uctest-server"; C2="uctest-mc-tester2"; BOT="tester1"; VICTIM="tester2"
SCEN = sys.argv[1] if len(sys.argv)>1 else "slime"

def rcon(c,t=20): return subprocess.run(["docker","exec",SERVER,"rcon-cli",c],capture_output=True,text=True,timeout=t).stdout.strip()
def clean(x1,z1,x2,z2,ytop=20,ybot=-80):
    """Wipe a big box to air so leftover blocks from prior scenarios never clutter the shot."""
    rcon(f"forceload add {x1} {z1} {x2} {z2}")
    rcon(f"fill {x1} {ybot} {z1} {x2} {ytop} {z2} air")
def wait_for(desc,fn,ts,iv=3):
    t0=time.time(); last=None
    while time.time()-t0<ts:
        try:
            last=fn()
            if last: print(f"  [ok] {desc}"); return last
        except Exception as e: last=e
        time.sleep(iv)
    raise TimeoutError(f"{desc}: {ts}s ({last})")

def py4j(container, body, t=60):
    snip=("from py4j.java_gateway import JavaGateway,GatewayParameters\n"
          "gw=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True))\n"
          "mc=gw.entry_point\n"+body+"\ngw.close()")
    return subprocess.run(["docker","exec",container,"python3","-c",snip],capture_output=True,text=True,timeout=t)

def ensure_ingame(container):
    py4j(container,"import time\n"
         "if not mc.inGame():\n"
         "    mc.ConnectToServer('test-server'); time.sleep(3)\n")
    for _ in range(30):
        if py4j(container,"print(mc.inGame())").stdout.strip().endswith("True"): return
        time.sleep(3)
    print(f"  warn: {container} maybe not in game")

def record(dur, trigger_body, persp=1):
    """Start x11grab (detached), fire the scenario (py4j body), wait, make gif + frames."""
    mp4=f"/mc-data/demo_{SCEN}.mp4"; gif=f"/mc-data/demo_{SCEN}.gif"
    py4j(CLIENT, f"mc.setPerspective({persp})")
    subprocess.run(["docker","exec","-d",CLIENT,"ffmpeg","-y","-f","x11grab","-framerate","15",
                    "-i",":0","-t",str(dur),"-pix_fmt","yuv420p",mp4])
    time.sleep(0.8)
    py4j(CLIENT, "import time\n"+trigger_body, t=dur+40)
    time.sleep(dur+2)
    subprocess.run(["docker","exec",CLIENT,"ffmpeg","-y","-i",mp4,"-vf","fps=10,scale=640:-1:flags=lanczos",gif],
                   capture_output=True,text=True)
    # 5 verification frames spread across the clip so the actual behaviour is captured, not
    # just the before/after (a fast op can finish between two sparse frames).
    for tag,frac in [("a",0.25),("b",0.40),("c",0.55),("d",0.70),("e",0.85)]:
        ts=max(0.5, dur*frac)
        subprocess.run(["docker","exec",CLIENT,"ffmpeg","-y","-ss",f"{ts:.1f}","-i",mp4,
                        "-frames:v","1","-vf","scale=640:-1",f"/mc-data/frame_{SCEN}_{tag}.png"],
                       capture_output=True,text=True)
    r=subprocess.run(["docker","exec",CLIENT,"sh","-c",f"ls -la {mp4} {gif}"],capture_output=True,text=True)
    print("  outputs:",r.stdout.strip().replace("\n"," | "))

# -------- scenarios --------

def setup_slime():
    clean(-16,-10,26,10)
    for c in ["fill -6 -56 -1 -4 -56 1 stone","fill -2 -60 -1 2 -60 1 slime_block","fill 4 -57 -1 6 -57 1 stone",
              "fill 9 -57 0 10 -57 1 stone","fill 11 -60 0 14 -60 1 slime_block","fill 16 -58 -1 18 -58 1 stone"]:
        rcon(c)
    rcon("time set day"); rcon("weather clear")
    rcon(f"tp {BOT} -5 -55 0 90 15"); time.sleep(2)

BRIDGE_N = 18   # long enough that the paving spans the whole clip (not a 2s blip)
def setup_bridge():
    # CLEAN DEEP void with a tiny pad at its west edge; bot on the edge so the first step is
    # already over the void. Camera behind (persp 1) looks EAST along the bridge — you SEE the
    # leading edge where blocks drop into the void ahead as the bot advances.
    clean(-6,-4,60,4)
    rcon("fill -3 -61 -3 1 -61 3 stone")               # small pad, east edge at x=1
    rcon(f"clear {BOT}"); rcon(f"item replace entity {BOT} hotbar.0 with cobblestone 64")
    rcon("time set day"); rcon("weather clear")
    rcon(f"tp {BOT} 1 -60 0 -90 0"); time.sleep(2)     # pad edge, facing east(+x); void x>=2

def setup_we():
    clean(-10,-8,10,10)
    rcon("fill -8 -61 -8 8 -61 8 stone")               # a clean floor to stand on / build against
    rcon("time set day"); rcon("weather clear")
    rcon(f"item replace entity {BOT} hotbar.1 with stone 64")
    rcon(f"item replace entity {BOT} hotbar.2 with glass 64")
    rcon(f"item replace entity {BOT} hotbar.3 with diamond_pickaxe")
    rcon(f"tp {BOT} 0 -60 3 180 8"); time.sleep(2)     # 3 blocks south of the wall plane (z=0), facing north at it

# first-person: the 3x2 wall APPEARS in stone (@@set) then turns to GLASS (@@replace),
# directly in view, no bot body in the way. Fixed windows sized to the op (never truncated).
WE_SET_WIN = 10
WE_REP_WIN = 22
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

def setup_pvp():
    clean(-12,-10,12,10)
    rcon("fill -12 -61 -10 12 -61 10 stone")           # arena floor
    rcon("gamerule pvp true"); rcon("gamerule immediate_respawn true"); rcon("time set day")
    rcon(f"item replace entity {BOT} weapon.mainhand with iron_sword")
    ensure_ingame(C2)
    rcon(f"tp {VICTIM} 4 -60 0 -90 0"); rcon(f"tp {BOT} -4 -60 0 -90 0"); time.sleep(2)  # enemy ahead, in frame

def setup_bedwars():
    # two islands over a CLEAN DEEP void; bot at the near-island east edge, enemy on the far
    # island. bridge the gap, then fight — the whole thing in one clip.
    clean(-6,-8,24,8)
    rcon("fill -3 -61 -4 1 -61 4 stone")               # near island, east edge x=1
    rcon("fill 9 -61 -4 15 -61 4 stone")               # far island (enemy)
    rcon("setblock 12 -60 0 red_bed")
    rcon("gamerule pvp true"); rcon("gamerule immediate_respawn true")
    rcon("time set day"); rcon("weather clear")
    rcon(f"clear {BOT}")
    rcon(f"item replace entity {BOT} hotbar.0 with cobblestone 64")
    rcon(f"item replace entity {BOT} hotbar.1 with iron_sword")
    ensure_ingame(C2)
    rcon(f"tp {VICTIM} 12 -60 0 90 0")
    rcon(f"tp {BOT} 1 -60 0 -90 0"); time.sleep(2)     # near-island edge; void x=2..8

BEDWARS_BODY = (
    'mc.selectHotbar(0)\n'
    'time.sleep(0.6)\n'                 # let the slot select land before the bridge checks the hand
    'mc.bridgeTo(9, -60, 0)\n'          # godbridge across the void to the far island
    'time.sleep(11)\n'
    'mc.selectHotbar(1)\n'
    'mc.ChatMessage(";punkPlayer '+VICTIM+'")\n'
    'time.sleep(12)\n'
)

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    ensure_ingame(CLIENT); time.sleep(2)
    if SCEN=="slime":       setup_slime();  record(9,  'mc.ChatMessage(";goto 5 -56 0")', persp=1)
    elif SCEN=="bridge":    setup_bridge(); record(BRIDGE_N//2+8, f'mc.selectHotbar(0); time.sleep(0.6); mc.bridgeForward("east", {BRIDGE_N})', persp=1)
    elif SCEN=="worldedit": setup_we();     record(3+WE_SET_WIN+WE_REP_WIN+2, WE_BODY, persp=0)
    elif SCEN=="pvp":       setup_pvp();    record(14, 'mc.ChatMessage(";punkPlayer '+VICTIM+'")', persp=1)
    elif SCEN=="bedwars":   setup_bedwars(); record(28, BEDWARS_BODY, persp=1)
    else: print("unknown scenario"); sys.exit(2)
    print("DONE", SCEN)

if __name__=="__main__": main()
