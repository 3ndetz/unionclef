#!/usr/bin/env python3
"""Capture a demo clip of the bot on the autotest stand.

Runs a scenario (slime / breakplace / pvp), captures third-person frames via
getScreenshot into /mc-data (mounted to deploy/run/data/tester1), then stitches
mp4 + gif with ffmpeg. Usage: capture_demo.py <scenario>
"""
import functools, json, subprocess, sys, time, os
print = functools.partial(print, flush=True)
CLIENT="uctest-mc-tester1"; SERVER="uctest-server"; C2="uctest-mc-tester2"; BOT="tester1"; VICTIM="tester2"
SCEN = sys.argv[1] if len(sys.argv)>1 else "slime"
OUTDIR_HOST = f"deploy/run/data/{BOT}/demo_{SCEN}"      # on the Mac host
OUTDIR_CT   = f"/mc-data/demo_{SCEN}"                    # same dir inside the container

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

# capture snippet runs INSIDE the client container (one py4j connection, tight loop)
CAP=r"""
import os,time,json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point
os.makedirs(req["outdir"],exist_ok=True)
try: mc.setPerspective(req.get("persp",0))  # 0=first-person, 1=third-back, 2=third-front
except Exception as e: print("persp",e)
for cmd in req.get("chat",[]):
    mc.ChatMessage(cmd); time.sleep(req.get("chat_gap",0.1))
n=req["frames"]; gap=req["gap"]; saved=0
for i in range(n):
    try:
        b=mc.getScreenshot()
        if b is not None:
            data=bytes(b)
            if len(data)>100:
                open(os.path.join(req["outdir"],"f%03d.png"%i),"wb").write(data); saved+=1
    except Exception as e:
        if i==0: print("shot_err",e)
    time.sleep(gap)
print(json.dumps({"saved":saved}))
gw.close()
"""
def capture(container, outdir_ct, frames, gap, chat=None, chat_gap=0.1, persp=0):
    req={"outdir":outdir_ct,"frames":frames,"gap":gap,"chat":chat or [],"chat_gap":chat_gap,"persp":persp}
    r=subprocess.run(["docker","exec",container,"python3","-c",CAP,json.dumps(req)],
                     capture_output=True,text=True,timeout=frames*gap+60)
    print("  capture:",r.stdout.strip()[-200:], r.stderr.strip()[-200:] if r.returncode else "")
    return r

def py4j_call(container, snippet_body, t=30):
    r=subprocess.run(["docker","exec",container,"python3","-c",snippet_body],capture_output=True,text=True,timeout=t)
    return r

def ensure_ingame(container):
    snip=("from py4j.java_gateway import JavaGateway,GatewayParameters;"
          "gw=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));mc=gw.entry_point;"
          "print(mc.inGame()) if mc.inGame() else (mc.ConnectToServer('test-server'),print('connecting'))")
    r=py4j_call(container,snip); print(f"  {container} ingame:",r.stdout.strip()[-60:])

def setup_slime():
    rcon("forceload add -16 -16 24 8")
    cmds=["fill -12 -60 -8 24 -45 8 air",
          "fill -6 -56 -1 -4 -56 1 stone","fill -2 -60 -1 2 -60 1 slime_block","fill 4 -57 -1 6 -57 1 stone",
          "fill 9 -57 0 10 -57 1 stone","fill 11 -60 0 14 -60 1 slime_block","fill 16 -58 -1 18 -58 1 stone"]
    for c in cmds: rcon(c)
    rcon("time set day"); rcon("weather clear")
    rcon(f"tp {BOT} -5 -55 0 90 0"); time.sleep(2)

def setup_breakplace():
    rcon("forceload add 0 0 24 4")
    rcon("fill 0 -61 -2 24 -61 2 stone"); rcon("fill 0 -60 -2 24 -55 2 air")
    # a gap the bot must bridge, then a wall it must break
    rcon("fill 6 -61 -2 10 -61 2 air")             # a 4-wide void to bridge
    rcon(f"clear {BOT}"); rcon(f"item replace entity {BOT} hotbar.0 with dirt 64")
    rcon(f"tp {BOT} 2 -60 0 -90 0"); time.sleep(2)

def setup_pvp():
    rcon("forceload add -8 -8 8 8"); rcon("fill -8 -61 -8 8 -61 8 stone"); rcon("fill -8 -60 -8 8 -55 8 air")
    rcon("gamerule pvp true"); rcon("gamerule immediate_respawn true"); rcon("time set day")
    rcon(f"item replace entity {BOT} weapon.mainhand with iron_sword")
    ensure_ingame(C2)
    rcon(f"tp {VICTIM} 4 -60 0 -90 0"); rcon(f"tp {BOT} -2 -60 0 -90 0"); time.sleep(2)

def stitch():
    host=OUTDIR_HOST
    n=len([f for f in os.listdir(host) if f.endswith(".png")]) if os.path.isdir(host) else 0
    print(f"  frames on host: {n}")
    if n<3: raise RuntimeError("too few frames captured")
    mp4=f"{host}/../demo_{SCEN}.mp4"; gif=f"{host}/../demo_{SCEN}.gif"
    # renumber to contiguous for ffmpeg glob safety
    subprocess.run(f"cd {host} && i=0; for f in $(ls f*.png|sort); do mv \"$f\" \"s$(printf %03d $i).png\"; i=$((i+1)); done",shell=True)
    subprocess.run(["ffmpeg","-y","-framerate","8","-i",f"{host}/s%03d.png","-vf","scale=720:-2:flags=lanczos","-pix_fmt","yuv420p",mp4],
                   capture_output=True,text=True)
    subprocess.run(["ffmpeg","-y","-framerate","8","-i",f"{host}/s%03d.png","-vf","scale=480:-1:flags=lanczos",gif],
                   capture_output=True,text=True)
    print(f"  wrote {mp4} and {gif}")

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    ensure_ingame(CLIENT); time.sleep(2)
    if SCEN=="slime":
        setup_slime(); capture(CLIENT, OUTDIR_CT, 44, 0.18, chat=[";goto 5 -56 0"]);
    elif SCEN=="breakplace":
        setup_breakplace(); capture(CLIENT, OUTDIR_CT, 60, 0.2, chat=[";goto 22 -60 0"])
    elif SCEN=="pvp":
        setup_pvp(); capture(CLIENT, OUTDIR_CT, 55, 0.2, chat=[";punk "+VICTIM])
    else:
        print("unknown scenario"); sys.exit(2)
    time.sleep(1); stitch()
    print("DONE", SCEN)

if __name__=="__main__": main()
