#!/usr/bin/env python3
"""Live scaffold reservation regression: a three-block pillar must preserve recipe material.

Builds six isolated columns on the flat stand. With two reserved cobblestone and eight
unreserved dirt, the pillar must climb three blocks using dirt. --unprotected is the
negative control: the initially selected cobblestone remains eligible and is consumed.
The behaviour stack is restored after each trial. Requires the normal test stand.
"""
import argparse,json,subprocess,sys,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
ap=argparse.ArgumentParser(description=__doc__)
ap.add_argument('--unprotected',action='store_true')
ap.add_argument('--connected',action='store_true',help='client is already on test-server')
ap.add_argument('--repeat',type=int,default=6)
ap.add_argument('--output',type=Path,required=True)
args=ap.parse_args()
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server')
SNIP=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters
import sys,json
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));a=g.jvm.adris.altoclef.AltoClef.getInstance();b=a.getBehaviour()
if sys.argv[1]=='start':
 st=a.getPlayer().method_6047()
 b.push();items=g.new_array(g.jvm.net.minecraft.class_1792,1);items[0]=st.method_7909();
 if sys.argv[2]=='protected':b.addProtectedItems(items)
 print('stack',str(st),'mode',sys.argv[2],'eligible',g.jvm.kaptainwutax.tungsten.helpers.BlockPlaceHelper.isScaffold(st))
 print('start',g.jvm.kaptainwutax.tungsten.task.PillarTask.startTo(-57))
else:
 g.jvm.kaptainwutax.tungsten.task.PillarTask.stop();b.pop()
g.close()
'''
def java(op):
 q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',SNIP,op,'unprotected' if args.unprotected else 'protected'],check=True,capture_output=True,text=True,timeout=25)
 print(q.stdout.strip(),flush=True)
if not args.connected:p.call('ConnectToServer','test-server');time.sleep(2)
for i in range(30):
 if p.call('inGame'):break
 time.sleep(1)
else:raise RuntimeError('connect failed')
rows=[]
for i in range(args.repeat):
 x=1040+6*i;z=300
 p.call('stopPathing');time.sleep(.5)
 r.cmd(f'forceload add {x-2} {z-2} {x+2} {z+2}')
 r.cmd(f'fill {x-2} -60 {z-2} {x+2} -53 {z+2} air')
 r.cmd(f'fill {x-2} -61 {z-2} {x+2} -61 {z+2} bedrock')
 r.cmd('gamemode survival tester1');r.cmd('clear tester1')
 r.cmd('item replace entity tester1 hotbar.0 with cobblestone 2')
 r.cmd('item replace entity tester1 hotbar.1 with dirt 8')
 r.cmd(f'tp tester1 {x+.5} -60 {z+.5} 0 0');time.sleep(.8);p.call('selectHotbar',0)
 java('start');t0=time.monotonic()
 try:
  while time.monotonic()-t0<20:
   time.sleep(.8);s=p.call('getGameState')['self']
   if float(s['pos'].split(',')[1])>=-57.05 and s['onGround']:break
  inv=p.call('getInventoryFull');n=sum(v.get('count',0) for v in inv['slots'] if v.get('item')=='minecraft:cobblestone')
  row={'run':i+1,'state':s,'cobble':n,'time':round(time.monotonic()-t0,2)};row['pass']=float(s['pos'].split(',')[1])>=-57.05 and s['onGround'] and s['hp']==20 and n==(0 if args.unprotected else 2);rows.append(row);print(json.dumps(row),flush=True)
 finally:java('stop');r.cmd(f'forceload remove {x-2} {z-2} {x+2} {z+2}')
args.output.parent.mkdir(parents=True,exist_ok=True)
args.output.write_text(json.dumps(rows,indent=2))
raise SystemExit(0 if rows and all(row['pass'] for row in rows) else 1)
