#!/usr/bin/env python3
"""Record a demo clip of the bot on the autotest stand via x11grab.

Two capture modes:
  - EXTERNAL cam (single-bot scenarios: bridge/slime/worldedit): tester2 is put in spectator
    mode at a fixed vantage and its screen is recorded while tester1 acts. This gives a clean,
    well-framed shot with NO acting-bot body occlusion and NO debug overlays/chat (tester2
    doesn't run tungsten, so tester1's path/place wireframes + chat never render on it).
  - OWN cam (two-bot scenarios: pvp/bedwars): tester1's own third-person view (tester2 is the
    opponent, so it can't also be the camera).

Each scenario wipes a CLEAN arena, builds unambiguous geometry, and sizes the record duration
to the op (never truncated). Verification FRAMES are extracted so footage is LOOKED AT before
it's ever sent (checklist rule 2026-07-24 — never ship an unwatched clip).
Usage: capture_demo.py <slime|bridge|worldedit|pvp|bedwars>
"""
import functools, json, subprocess, sys, time
print = functools.partial(print, flush=True)
CLIENT="uctest-mc-tester1"; SERVER="uctest-server"; C2="uctest-mc-tester2"; BOT="tester1"; VICTIM="tester2"
SCEN = sys.argv[1] if len(sys.argv)>1 else "slime"

def rcon(c,t=20): return subprocess.run(["docker","exec",SERVER,"rcon-cli",c],capture_output=True,text=True,timeout=t).stdout.strip()
def clean(x1,z1,x2,z2,ytop=25,ybot=-70):
    """Wipe a box to air. /fill caps at 32768 blocks, so slice into y-bands that stay under
    the cap — a single big fill SILENTLY FAILS (the bug that left every arena cluttered)."""
    rcon(f"forceload add {x1} {z1} {x2} {z2}")
    dx=abs(x2-x1)+1; dz=abs(z2-z1)+1
    per=max(1, 30000//(dx*dz))     # y-levels per fill, under the 32768 cap
    y=ybot
    while y<=ytop:
        y2=min(ytop, y+per-1)
        rcon(f"fill {x1} {y} {z1} {x2} {y2} {z2} air")
        y=y2+1
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
    py4j(container,"import time\nif not mc.inGame():\n    mc.ConnectToServer('test-server'); time.sleep(3)\n")
    for _ in range(30):
        if py4j(container,"print(mc.inGame())").stdout.strip().endswith("True"): return
        time.sleep(3)
    print(f"  warn: {container} maybe not in game")

def _frames_and_gif(rec_container, dur):
    mp4=f"/mc-data/demo_{SCEN}.mp4"; gif=f"/mc-data/demo_{SCEN}.gif"
    subprocess.run(["docker","exec",rec_container,"ffmpeg","-y","-i",mp4,"-vf","fps=10,scale=640:-1:flags=lanczos",gif],
                   capture_output=True,text=True)
    for tag,frac in [("a",0.25),("b",0.40),("c",0.55),("d",0.70),("e",0.85)]:
        ts=max(0.5, dur*frac)
        subprocess.run(["docker","exec",rec_container,"ffmpeg","-y","-ss",f"{ts:.1f}","-i",mp4,
                        "-frames:v","1","-vf","scale=640:-1",f"/mc-data/frame_{SCEN}_{tag}.png"],
                       capture_output=True,text=True)
    r=subprocess.run(["docker","exec",rec_container,"sh","-c",f"ls -la {mp4} {gif}"],capture_output=True,text=True)
    print("  outputs:",r.stdout.strip().replace("\n"," | "))

def record_own(dur, trigger_body, persp=1):
    """Record tester1's OWN screen (two-bot scenarios)."""
    py4j(CLIENT, f"mc.setPerspective({persp})")
    subprocess.run(["docker","exec","-d",CLIENT,"ffmpeg","-y","-f","x11grab","-framerate","15",
                    "-i",":0","-t",str(dur),"-pix_fmt","yuv420p",f"/mc-data/demo_{SCEN}.mp4"])
    time.sleep(0.8)
    py4j(CLIENT, "import time\n"+trigger_body, t=dur+40)
    time.sleep(dur+2)
    _frames_and_gif(CLIENT, dur)

def record_ext(dur, cam, look, trigger_body):
    """Record tester2 as a fixed SPECTATOR cam at `cam`=(x,y,z) looking at `look`=(x,y,z),
    while tester1 runs trigger_body. Clean external view — no acting-bot occlusion/overlays."""
    ensure_ingame(C2)
    rcon(f"gamemode spectator {VICTIM}")
    rcon(f"tp {VICTIM} {cam[0]} {cam[1]} {cam[2]} facing {look[0]} {look[1]} {look[2]}")
    time.sleep(1.0)
    # tester2's data dir is /mc-data too (its own volume); record ITS screen
    subprocess.run(["docker","exec","-d",C2,"ffmpeg","-y","-f","x11grab","-framerate","15",
                    "-i",":0","-t",str(dur),"-pix_fmt","yuv420p",f"/mc-data/demo_{SCEN}.mp4"])
    time.sleep(0.8)
    # re-assert the cam right before the action (in case the spectator drifted/loaded)
    rcon(f"tp {VICTIM} {cam[0]} {cam[1]} {cam[2]} facing {look[0]} {look[1]} {look[2]}")
    py4j(CLIENT, "import time\n"+trigger_body, t=dur+40)
    time.sleep(dur+2)
    _frames_and_gif(C2, dur)

# -------- scenarios --------

def setup_slime():
    clean(-16,-10,26,10)
    for c in ["fill -6 -56 -1 -4 -56 1 stone","fill -2 -60 -1 2 -60 1 slime_block","fill 4 -57 -1 6 -57 1 stone",
              "fill 9 -57 0 10 -57 1 stone","fill 11 -60 0 14 -60 1 slime_block","fill 16 -58 -1 18 -58 1 stone"]:
        rcon(c)
    rcon("time set day"); rcon("weather clear")
    rcon(f"tp {BOT} -5 -55 0 90 15"); time.sleep(2)

BRIDGE_N = 18
def setup_bridge():
    clean(-6,-18,40,18,ytop=10,ybot=-75)               # wide+deep enough for a clear void + cam sightline
    rcon("fill -3 -61 -2 1 -61 2 stone")               # start pad, east edge at x=1
    rcon("fill 20 -61 -3 26 -61 3 stone")              # destination platform
    rcon(f"clear {BOT}"); rcon(f"item replace entity {BOT} hotbar.0 with cobblestone 64")
    rcon("time set day"); rcon("weather clear")
    rcon(f"tp {BOT} 1 -60 0 -90 0"); time.sleep(2)     # pad edge, facing east(+x); void x=2..19

def setup_we():
    clean(-12,-10,12,10)
    rcon("fill -8 -61 -8 8 -61 8 grass_block")         # GRASS floor so a STONE wall stands out
    rcon("time set day"); rcon("weather clear")
    rcon(f"item replace entity {BOT} hotbar.1 with stone 64")
    rcon(f"item replace entity {BOT} hotbar.2 with glass 64")
    rcon(f"item replace entity {BOT} hotbar.3 with diamond_pickaxe")
    rcon(f"tp {BOT} 0 -60 3 180 8"); time.sleep(2)     # 3 blocks south of the wall plane (z=0) — the distance where //set builds the 2-tall wall

WE_DUR = 44
WE_BODY = (
    'mc.ExecuteCommand("@stop")\n'
    'time.sleep(1.5)\n'
    'mc.select(-1,-60,0, 1,-59,0)\n'
    'time.sleep(1.5)\n'
    'mc.we("set stone")\n'
    'time.sleep(9)\n'
    'mc.we("replace stone glass")\n'
    'for _ in range(22):\n'
    '    st=dict(mc.we("restat"))\n'
    '    if str(st.get("phase"))=="done": break\n'
    '    time.sleep(1.2)\n'
    'time.sleep(2)\n'
)

def setup_pvp():
    clean(-12,-10,12,10)
    rcon("fill -12 -61 -10 12 -61 10 stone")
    rcon(f"gamemode survival {VICTIM}")
    rcon("gamerule pvp true"); rcon("gamerule immediate_respawn true"); rcon("time set day")
    rcon(f"item replace entity {BOT} weapon.mainhand with iron_sword")
    ensure_ingame(C2)
    rcon(f"tp {VICTIM} 4 -60 0 -90 0"); rcon(f"tp {BOT} -4 -60 0 -90 0"); time.sleep(2)

def setup_bedwars():
    clean(-6,-8,24,8)
    rcon("fill -3 -61 -4 1 -61 4 stone")
    rcon("fill 9 -61 -4 15 -61 4 stone")
    rcon("setblock 12 -60 0 red_bed")
    rcon(f"gamemode survival {VICTIM}")
    rcon("gamerule pvp true"); rcon("gamerule immediate_respawn true")
    rcon("time set day"); rcon("weather clear")
    rcon(f"clear {BOT}")
    rcon(f"item replace entity {BOT} hotbar.0 with cobblestone 64")
    rcon(f"item replace entity {BOT} hotbar.1 with iron_sword")
    ensure_ingame(C2)
    rcon(f"tp {VICTIM} 12 -60 0 90 0")
    rcon(f"tp {BOT} 1 -60 0 -90 0"); time.sleep(2)

BEDWARS_BODY = (
    'mc.selectHotbar(0)\n'
    'time.sleep(0.6)\n'
    'mc.bridgeTo(9, -60, 0)\n'
    'time.sleep(11)\n'
    'mc.selectHotbar(1)\n'
    'mc.ChatMessage(";punkPlayer '+VICTIM+'")\n'
    'time.sleep(12)\n'
)

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    ensure_ingame(CLIENT); time.sleep(2)
    if SCEN=="slime":
        setup_slime()
        record_ext(9, (6,-49,14),(6,-57,0), 'mc.ChatMessage(";goto 5 -56 0")')
    elif SCEN=="bridge":
        setup_bridge()
        record_ext(10, (10,-53,16),(10,-60,0), f'mc.selectHotbar(0); time.sleep(0.6); mc.bridgeForward("east", {BRIDGE_N})')
    elif SCEN=="worldedit":
        setup_we()
        record_ext(WE_DUR, (6,-56,7),(0,-59,0), WE_BODY)
    elif SCEN=="pvp":
        setup_pvp(); record_own(14, 'mc.ChatMessage(";punkPlayer '+VICTIM+'")', persp=1)
    elif SCEN=="bedwars":
        setup_bedwars(); record_own(28, BEDWARS_BODY, persp=1)
    else: print("unknown scenario"); sys.exit(2)
    print("DONE", SCEN)

if __name__=="__main__": main()
