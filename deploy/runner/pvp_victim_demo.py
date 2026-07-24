#!/usr/bin/env python3
"""PvP demo from the VICTIM's point of view — the cleanest 2-client combat shot. tester1
hunts+fights tester2 (;punkPlayer); we record tester2's screen with its camera kept locked
onto tester1 (re-aimed every tick), so the clip shows tester1 rushing in and swinging at the
viewer. tester1's own combat camera is too jittery to film; the victim POV is stable.
Extracts verification frames. Output: /mc-data on tester2 (deploy/run/data/tester2)."""
import functools, subprocess, time, threading
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; C2="uctest-mc-tester2"; BOT="tester1"; VICTIM="tester2"
DUR=14
def rcon(c,t=20): return subprocess.run(["docker","exec",SERVER,"rcon-cli",c],capture_output=True,text=True,timeout=t).stdout.strip()
def py4j(container, body, t=40):
    snip=("from py4j.java_gateway import JavaGateway,GatewayParameters\n"
          "gw=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True))\n"
          "mc=gw.entry_point\n"+body+"\ngw.close()")
    return subprocess.run(["docker","exec",container,"python3","-c",snip],capture_output=True,text=True,timeout=t)
def ensure(c):
    py4j(c,"import time\nif not mc.inGame():\n mc.ConnectToServer('test-server'); time.sleep(3)\n")
    for _ in range(30):
        if py4j(c,"print(mc.inGame())").stdout.strip().endswith("True"): return
        time.sleep(3)

def main():
    ensure(C1); ensure(C2); time.sleep(2)
    # clean arena (sliced under the 32768 /fill cap, not below world floor -64)
    rcon("forceload add -14 -12 14 12")
    for y in (-64,-58,-52):
        rcon(f"fill -14 {y} -12 14 {min(y+5,20)} 12 air")
    rcon("fill -14 20 -12 14 20 12 air")
    rcon("fill -14 -61 -12 14 -61 12 stone")
    rcon("gamerule pvp true"); rcon("gamerule immediate_respawn true"); rcon("time set day"); rcon("weather clear")
    rcon(f"gamemode survival {VICTIM}"); rcon(f"gamemode survival {BOT}")
    rcon(f"item replace entity {BOT} weapon.mainhand with iron_sword")
    rcon(f"tp {VICTIM} 4 -60 0 -90 0"); rcon(f"tp {BOT} -5 -60 0 -90 0"); time.sleep(2)
    py4j(C2, f"mc.setPerspective(0)")   # victim first-person
    mp4="/mc-data/demo_pvpfight.mp4"; gif="/mc-data/demo_pvpfight.gif"
    subprocess.run(["docker","exec","-d",C2,"ffmpeg","-y","-f","x11grab","-framerate","15","-i",":0","-t",str(DUR),"-pix_fmt","yuv420p",mp4])
    time.sleep(0.8)
    # keep the victim's camera locked on the attacker for the whole clip
    stop=threading.Event()
    def reaim():
        while not stop.is_set():
            rcon(f"execute as {VICTIM} at {VICTIM} run tp @s ~ ~ ~ facing entity {BOT} eyes")
            time.sleep(0.4)
    t=threading.Thread(target=reaim,daemon=True); t.start()
    py4j(C1, f"mc.ChatMessage(';punkPlayer {VICTIM}')", t=DUR+20)
    time.sleep(DUR+2); stop.set(); time.sleep(0.5)
    subprocess.run(["docker","exec",C2,"ffmpeg","-y","-i",mp4,"-vf","fps=10,scale=640:-1:flags=lanczos",gif],capture_output=True,text=True)
    for tag,frac in [("a",0.25),("b",0.4),("c",0.55),("d",0.7),("e",0.85)]:
        subprocess.run(["docker","exec",C2,"ffmpeg","-y","-ss",f"{DUR*frac:.1f}","-i",mp4,"-frames:v","1","-vf","scale=640:-1",f"/mc-data/frame_pvpfight_{tag}.png"],capture_output=True,text=True)
    r=subprocess.run(["docker","exec",C2,"sh","-c",f"ls -la {mp4} {gif}"],capture_output=True,text=True)
    print("outputs:",r.stdout.strip().replace("\n"," | "))
    # confirm damage actually happened
    print("victim hp:", rcon(f"data get entity {VICTIM} Health"))
    print("PVPFIGHT_DONE")

if __name__=="__main__": main()
