#!/usr/bin/env python3
"""Measure navigation beneath a submerged ceiling without water-breathing effects."""
import argparse,json,statistics,subprocess,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser(description=__doc__)
ap.add_argument('--tag',required=True);ap.add_argument('--output-dir',type=Path,required=True)
ap.add_argument('--expect-bug',action='store_true');ap.add_argument('--dive-only',action='store_true')
ap.add_argument('--auto-air',action='store_true',help='Let survival choose an escape instead of giving a route goal')
ap.add_argument('--decoy-air',action='store_true',help='Put a nearer air pocket behind an unbreakable wall')
ap.add_argument('--open-water',action='store_true',help='Allow surfacing directly above the start')
ap.add_argument('--working',action='store_true',help='Interrupt and resume a real underwater mining task')
a=ap.parse_args()
if a.working and not a.auto_air:ap.error('--working requires --auto-air')
root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True)
p=Py4jClient('uctest-mc-tester1');r=Rcon();recording=False
flags={'botFpsNoIdleThrottle':'true','allowBreak':'true' if a.working else 'false','allowPlace':'false'}
old={k:p.call('tungstenSetting',k,'').split('=',1)[1] for k in flags}
try:
 if 'tester1' not in r.cmd('list'):p.call('ConnectToServer','test-server')
 for _ in range(45):
  time.sleep(1)
  if 'tester1' in r.cmd('list') and p.call('getGameState').get('self'):break
 else:raise RuntimeError('flat login failed')
 p.call('stopPathing');p.call('closeOpenScreen')
 for k,v in flags.items():p.call('tungstenSetting',k,v)
 for cmd in ['gamemode spectator tester1','forceload add 3297 1198 3308 1202',
  'fill 3297 -62 1198 3308 -48 1202 bedrock',
  'fill 3298 -60 1200 3307 -54 1200 water',
  'fill 3301 -54 1200 3304 -54 1200 bedrock',
  'fill 3305 -53 1200 3307 -48 1200 air',
  'clear tester1','effect clear tester1','tp tester1 3300.5 -55 1200.5 -90 0',
  'gamemode survival tester1','effect give tester1 instant_health 1 5 true',
  'effect give tester1 saturation 1 5 true']:r.cmd(cmd)
 r.cmd('gamemode spectator tester1')
 if a.decoy_air:
  r.cmd('fill 3298 -54 1200 3298 -52 1200 air')
  r.cmd('fill 3299 -60 1200 3299 -48 1200 bedrock')
 if a.open_water:r.cmd('fill 3300 -53 1200 3300 -48 1200 air')
 if a.working:
  r.cmd('fill 3298 -61 1200 3307 -61 1200 stone')
  r.cmd('give tester1 iron_pickaxe')
 rec_start(35);recording=True
 time.sleep(3)
 r.cmd('tp tester1 3300.5 -55 1200.5 -90 0')
 r.cmd('gamemode survival tester1')
 code=r'''
import json,time
from py4j.java_gateway import JavaGateway,GatewayParameters,get_field
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;c=j.net.minecraft.class_310.method_1551();a=j.adris.altoclef.AltoClef.getInstance();lookup=j.java.lang.invoke.MethodHandles.lookup()
def on_client(h):
 f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));c.execute(f);return f.get()
def method(cls,name,n):return next(m for m in j.java.lang.Class.forName(cls).getMethods() if m.getName()==name and m.getParameterCount()==n and (name!='getItemTask' or str(m.getParameterTypes()[0].getName())=='java.lang.String'))
def call(cls,name,*args):
 h=lookup.unreflect(method(cls,name,len(args)))
 if args:
  ar=g.new_array(j.java.lang.Object,len(args))
  for i,v in enumerate(args):ar[i]=v
  h=j.java.lang.invoke.MethodHandles.insertArguments(h,0,ar)
 return on_client(h)
call('kaptainwutax.tungsten.TungstenMod','resetAllState')
cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task')
task=call('adris.altoclef.TaskCatalogue','getItemTask','cobblestone',j.java.lang.Integer('128')) if WORKING else j.adris.altoclef.tasks.movement.IdleTask()
on_client(lookup.unreflect(a.getClass().getMethod('runUserTask',cs)).bindTo(a).bindTo(task))
empty=g.new_array(j.java.lang.Class,0);world=on_client(lookup.unreflect(a.getClass().getMethod('getWorld',empty)).bindTo(a))
player=on_client(lookup.unreflect(a.getClass().getMethod('getPlayer',empty)).bindTo(a))
air_handle=lookup.unreflect(player.getClass().getMethod('method_5669',empty)).bindTo(player)
submerged_handle=lookup.unreflect(player.getClass().getMethod('method_5869',empty)).bindTo(player)
goal=j.net.minecraft.class_2338(GOAL_X,GOAL_Y,1200)
res=call('kaptainwutax.tungsten.path.fast.FastPlanner','plan',world,j.net.minecraft.class_2338(3300,-55,1200),goal,j.java.lang.Long('1000'),None,j.java.lang.Boolean(True))
plan=[str(get_field(w,'pos')) for w in get_field(res,'path')]
q=j.java.lang.Class.forName('kaptainwutax.tungsten.path.movements.MovementQueue')
before={k:int(q.getField(k).get(None)) for k in ['qShort','qOffRoute','qAdmitMismatch']}
if not AUTO_AIR:call('kaptainwutax.tungsten.task.FastNavigator','startExact',goal)
rows=[];start=time.monotonic()
while time.monotonic()-start<22:
 s=dict(g.entry_point.getGameState()['self']);rows.append({'t':round(time.monotonic()-start,2),'state':s,'fps':g.entry_point.getPerfStats().get('fps',0),'air':int(on_client(air_handle)),'submerged':bool(on_client(submerged_handle)),'chain':g.entry_point.getTaskChainString() if AUTO_AIR else ''})
 x,y,z=map(float,s['pos'].split(','))
 if (3297.7<x<3307.8 if AUTO_AIR else abs(x-3306.5)<.8) and not rows[-1]['submerged'] and rows[-1]['air']==300 and (not AUTO_AIR or 'Reaching breathable air' not in rows[-1]['chain']):break
 if abs(x-3300.5)<.8 and y<-56.1 and DIVE_ONLY:break
 if abs(x-3300)>20 or s['hp']<5:break
 time.sleep(.1)
print(json.dumps({'complete':bool(get_field(res,'complete')),'plan':plan,'samples':rows,'queue_delta':{k:int(q.getField(k).get(None))-v for k,v in before.items()}}))
'''.replace('GOAL_X','3300' if a.dive_only else '3306').replace('GOAL_Y','-57' if a.dive_only else '-54').replace('DIVE_ONLY',str(a.dive_only)).replace('AUTO_AIR',str(a.auto_air)).replace('WORKING',str(a.working))
 q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,timeout=35)
 if q.returncode:raise RuntimeError(q.stderr)
 d=json.loads(q.stdout);s=d['samples'][-1]['state'];x,y,z=map(float,s['pos'].split(','))
 d['arrived']=(3297.7<x<3307.8 if a.auto_air else abs(x-(3300.5 if a.dive_only else 3306.5))<.8) and (y<-56.1 if a.dive_only else not d['samples'][-1]['submerged'] and d['samples'][-1]['air']==300)
 if a.decoy_air:d['arrived']=d['arrived'] and x>3304.7
 if a.open_water:d['arrived']=d['arrived'] and abs(x-3300.5)<.8
 d['recovery_released']='Reaching breathable air' not in d['samples'][-1]['chain']
 if a.auto_air:d['arrived']=d['arrived'] and d['recovery_released']
 d['working']=a.working
 if a.working:
  d['mining_observed']=any('Mine And Collect' in v['chain'] for v in d['samples'])
  d['escape_observed']=any('Reaching breathable air' in v['chain'] for v in d['samples'])
  d['arrived']=d['arrived'] and d['mining_observed'] and d['escape_observed']
 d['auto_air']=a.auto_air;d['decoy_air']=a.decoy_air;d['open_water']=a.open_water
 d['median_fps']=statistics.median(s['fps'] for s in d['samples'])
 d['healthy']=all(s['state']['hp']==20 for s in d['samples'])
 d['pass']=d['median_fps']>=14 and (not d['arrived'] if a.expect_bug else d['arrived'] and d['healthy'])
 (root/f'{a.tag}.json').write_text(json.dumps(d,indent=2));p.screenshot(str(root/f'{a.tag}.png'))
 print({k:v for k,v in d.items() if k!='samples'},flush=True)
 if not d['pass']:raise RuntimeError('submerged route gate failed')
finally:
 try:p.call('stopPathing')
 finally:
  if recording:rec_stop(str(root/f'{a.tag}.mp4'))
  for k,v in old.items():p.call('tungstenSetting',k,v)
  r.cmd('gamemode survival tester1');r.cmd('forceload remove 3297 1198 3308 1202')
