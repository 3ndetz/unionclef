#!/usr/bin/env python3
"""Verify that empty mining jobs preserve server-owned flight permissions.

The historical executor restored its pre-login fallback (true) after a mining
job that bypassed setPath. Run on the disposable stand, including after a
creative-to-survival transition. Does not change terrain or inventory.
"""
import argparse
import json
import subprocess
import time
from pathlib import Path
from uctest.harness import Py4jClient, Rcon

ap = argparse.ArgumentParser(description=__doc__)
ap.add_argument('--server', default='uctest-server')
ap.add_argument('--bot', default='uctest-mc-tester1')
ap.add_argument('--player', default='tester1')
ap.add_argument('--repeat', type=int, default=6)
ap.add_argument('--output', type=Path, required=True)
args = ap.parse_args()
p = Py4jClient(args.bot)
r = Rcon(args.server)
SNIP = r'''
import json,time
from py4j.java_gateway import JavaGateway,GatewayParameters,get_field
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True))
j=g.jvm;p=j.adris.altoclef.AltoClef.getInstance().getPlayer();a=p.method_31549()
w=j.adris.altoclef.AltoClef.getInstance().getWorld()
b=j.net.minecraft.class_2338(int(p.method_23317()),int(p.method_23318())+8,int(p.method_23321()))
if not w.method_8320(b).method_26215():raise RuntimeError('Probe needs air eight blocks above player')
before=get_field(a,'field_7478')
blocks=j.java.util.ArrayList();blocks.add(b)
e=j.kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;e.startBreaking(blocks)
for _ in range(60):
 time.sleep(.05)
 if not e.isRunning():break
print(json.dumps({'before':before,'after':get_field(a,'field_7478'),'flying':get_field(a,'field_7479'),'finished':not e.isRunning()}))
g.close()
'''
rows=[]
try:
 for i in range(args.repeat):
  for mode in ('creative','survival'):
   p.call('stopPathing');r.cmd(f'gamemode {mode} {args.player}');time.sleep(.2)
   q=subprocess.run(['docker','exec',args.bot,'python3','-c',SNIP],capture_output=True,text=True,timeout=15)
   if q.returncode:raise RuntimeError(q.stderr[-2000:])
   row=json.loads(q.stdout.strip().splitlines()[-1]);expected=mode=='creative'
   row.update(run=i+1,mode=mode)
   row['pass']=row['finished'] and row['before']==expected and row['after']==expected and not row['flying']
   rows.append(row);print(json.dumps(row),flush=True)
finally:
 p.call('stopPathing');r.cmd(f'gamemode survival {args.player}')
 args.output.parent.mkdir(parents=True,exist_ok=True)
 args.output.write_text(json.dumps(rows,indent=2))
raise SystemExit(0 if rows and all(x['pass'] for x in rows) else 1)
