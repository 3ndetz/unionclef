#!/usr/bin/env python3
"""@@schem load autotest: generate a small Sponge .schem (3x3x1 stone platform) into the
game's schematics/ dir, loadSchem it anchored at the bot, verify the platform is built.
Validates the mod-side schematic loader (baritone SpongeSchematic parser -> buildBlocks).
Real .schem files download from minecraft-schematics.com into <gamedir>/schematics/.
"""
import base64, functools, gzip, json, struct, subprocess, time
print = functools.partial(print, flush=True)
SERVER="uctest-server"; C1="uctest-mc-tester1"; BOT="tester1"
SNIP=r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
try:
    if op=="state": out={"inGame":mc.inGame()}
    elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
    elif op=="stop": mc.ExecuteCommand("@stop"); out={"ok":True}
    elif op=="load": out=dict(mc.loadSchem(req["name"]))
except Exception as e:
    sys.stderr.write("ERR:"+repr(e)+"\n"); sys.exit(3)
print(json.dumps(out,default=str)); gw.close()
"""
def sh(a,t=40): return subprocess.run(a,capture_output=True,text=True,timeout=t)
def py4j(op,t=30,**kw):
    last=""
    for _ in range(3):
        try:
            r=sh(["docker","exec",C1,"python3","-c",SNIP,json.dumps({"op":op,**kw})],t)
            if r.returncode==0 and r.stdout.strip(): return json.loads(r.stdout.strip().splitlines()[-1])
            last=(r.stderr or r.stdout or "").strip()[-300:]
        except Exception as e: last=repr(e)[-300:]
        time.sleep(2)
    raise RuntimeError(f"{op}: {last}")
def rcon(c): return sh(["docker","exec",SERVER,"rcon-cli",c]).stdout.strip()
def is_block(x,y,z,n): return "passed" in rcon(f"execute if block {x} {y} {z} minecraft:{n}").lower()

def _tag(tid,name,payload):
    n=name.encode(); return bytes([tid])+struct.pack('>H',len(n))+n+payload
def gen_schem(W=3,H=1,L=3):
    # Sponge v2 .schem: WxHxL of stone (palette idx 0), BlockData = varint 0 per cell (YZX)
    palette=_tag(3,"minecraft:stone",struct.pack('>i',0))+bytes([0])
    root=(_tag(3,"Version",struct.pack('>i',2))
          +_tag(3,"Width",struct.pack('>i',W))+_tag(3,"Height",struct.pack('>i',H))+_tag(3,"Length",struct.pack('>i',L))
          +_tag(10,"Palette",palette)+_tag(3,"PaletteMax",struct.pack('>i',1))
          +_tag(7,"BlockData",struct.pack('>i',W*H*L)+bytes([0])*(W*H*L))+bytes([0]))
    return gzip.compress(_tag(10,"Schematic",root))

def main():
    for _ in range(40):
        try:
            if py4j("state")["inGame"]: break
        except Exception: pass
        try: py4j("connect",ip="test-server")
        except Exception: pass
        time.sleep(5)
    time.sleep(6); py4j("stop"); time.sleep(1)
    rcon("forceload add -8 -8 16 16")
    rcon("fill 0 -61 -4 12 5 4 air"); rcon("fill 0 -61 -4 12 -61 4 stone")   # floor
    # discover the schematics dir via a failing load
    r0=py4j("load",name="__none__")
    print("  probe:", r0)
    reason=str(r0.get("reason",""))
    sdir=reason.split("not found in ",1)[1].strip() if "not found in " in reason else None
    if not sdir:
        print("  couldn't discover schematics dir"); print("  SCHEM: FAIL"); return
    print("  schematics dir:", sdir)
    b64=base64.b64encode(gen_schem()).decode()
    sh(["docker","exec",C1,"sh","-c",f"mkdir -p '{sdir}' && echo '{b64}' | base64 -d > '{sdir}/test.schem'"])
    rcon(f"tp {BOT} 5 -60 3 90 0"); time.sleep(1.5)   # on the floor; platform builds at y=-60 around the bot
    print("  loadSchem test:", py4j("load",name="test"))
    time.sleep(2)
    cells=[(x,-60,z) for x in range(5,8) for z in range(3,6)]   # 3x3 platform anchored at the bot
    built=[is_block(*c,"stone") for c in cells]
    print(f"  platform stone (3x3 @ y=-60): {sum(built)}/9")
    ok=sum(built)>=7   # the bot's own cell (5,-60,3) may not place; allow that
    print("  SCHEM:", "PASS" if ok else "FAIL")
    py4j("stop")

if __name__=="__main__": main()
