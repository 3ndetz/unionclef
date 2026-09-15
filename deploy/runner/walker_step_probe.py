#!/usr/bin/env python3
"""Measure a two-cell walker edge on existing stand geometry.

Stops the bot task and teleports it to --start before each trial. Does not build
or mine the fixture. Example for the preserved 2026-09-15 survival stall:
  python deploy/runner/walker_step_probe.py --server uctest-gamer-server \
    --start 109.5 140 -46.7 --dest 109 141 -48 --ascent true
Use --ascent false as the control. The switch is restored after the probe.
"""
import argparse
import json
import math
import subprocess
import time
from pathlib import Path

from uctest.harness import Py4jClient, Rcon

START_WALK = r'''
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True))
p=g.jvm.java.util.ArrayList()
for xyz in json.loads(sys.argv[1]):
    p.add(g.jvm.net.minecraft.class_2338(*xyz))
g.jvm.kaptainwutax.tungsten.task.BlockPathWalker.startBFS(p,True)
g.close()
'''


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--client',default='uctest-mc-tester1')
    ap.add_argument('--server',default='uctest-server')
    ap.add_argument('--bot',default='tester1')
    ap.add_argument('--start',nargs=3,type=float,required=True)
    ap.add_argument('--dest',nargs=3,type=int,required=True)
    ap.add_argument('--ascent',choices=['true','false'],default='true')
    ap.add_argument('--repeat',type=int,default=6)
    ap.add_argument('--output',type=Path,required=True)
    a=ap.parse_args()
    p=Py4jClient(a.client); r=Rcon(a.server)
    if not p.call('inGame'):
        raise RuntimeError('Connect the client to the fixture server first')
    flag='walkerAscentNeedsAscent'
    old=p.call('tungstenSetting',flag,'')
    if not old.startswith(flag+'='):
        raise RuntimeError('The build does not expose the ascent switch')
    print(p.call('tungstenSetting',flag,a.ascent),flush=True)
    start=[math.floor(a.start[0]),math.floor(a.start[1]+.1251),math.floor(a.start[2])]
    yaw=math.degrees(-math.atan2(a.dest[0]+.5-a.start[0],a.dest[2]+.5-a.start[2]))
    rows=[]
    try:
        for i in range(a.repeat):
            p.call('stopPathing'); time.sleep(.4)
            r.cmd(f"tp {a.bot} {' '.join(map(str,a.start))} {yaw} 0")
            time.sleep(.7)
            before=p.call('getGameState')['self']
            subprocess.run(['docker','exec',a.client,'python3','-c',START_WALK,
                            json.dumps([start,a.dest])],check=True,capture_output=True,timeout=30)
            time.sleep(2)
            state=p.call('getGameState')['self']
            xyz=[float(v) for v in state['pos'].split(',')]
            feet=[math.floor(xyz[0]),math.floor(xyz[1]+.1251),math.floor(xyz[2])]
            row={'trial':i+1,'ascent':a.ascent,'state':state,
                 'pass':feet==a.dest and state['hp']>=before['hp'] and state['onGround']}
            rows.append(row); print(json.dumps(row),flush=True)
    finally:
        p.call('stopPathing')
        p.call('tungstenSetting',flag,old.split('=',1)[1])
        a.output.parent.mkdir(parents=True,exist_ok=True)
        a.output.write_text(json.dumps(rows,indent=2))
    return 0 if rows and all(row['pass'] for row in rows) else 1


if __name__=='__main__':
    raise SystemExit(main())
