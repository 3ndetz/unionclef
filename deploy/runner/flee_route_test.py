#!/usr/bin/env python3
"""Measure a real retreat from multiple or already-close creepers on disposable terrain."""
import argparse,json,math,re,statistics,subprocess,sys,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser(description=__doc__)
ap.add_argument('--tag',required=True);ap.add_argument('--output-dir',type=Path,required=True)
ap.add_argument('--layout',choices=['single','cluster','opposed','inside'],default='cluster')
ap.add_argument('--ai',action='store_true');ap.add_argument('--slope',action='store_true')
ap.add_argument('--confined',action='store_true',help='Require climbing the slope rather than going around it')
ap.add_argument('--equipped',action='store_true',help='Use ordinary dig/build permissions with tools and blocks')
a=ap.parse_args()
if a.confined and not a.slope:ap.error('--confined requires --slope')
root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True)
p=Py4jClient('uctest-mc-tester1');r=Rcon();recording=False;objective=False
flags={'botFpsNoIdleThrottle':'true','allowBreak':'true' if a.equipped else 'false','allowPlace':'true' if a.equipped else 'false'};old={}
try:
 if 'tester1' not in r.cmd('list'):p.call('ConnectToServer','test-server')
 for _ in range(45):
  time.sleep(1)
  if 'tester1' in r.cmd('list') and p.call('getGameState').get('self'):break
 else:raise RuntimeError('flat login failed')
 p.call('stopPathing');p.call('closeOpenScreen')
 for k,v in flags.items():old[k]=p.call('tungstenSetting',k,'').split('=',1)[1];p.call('tungstenSetting',k,v)
 for cmd in ['gamemode spectator tester1','difficulty normal','kill @e[tag=flee_trial]',
  'forceload add 3470 1370 3530 1430','fill 3470 -61 1370 3530 -61 1430 bedrock',
  'fill 3470 -60 1370 3530 -53 1430 air','fill 3470 -52 1370 3530 -45 1430 air','clear tester1','effect clear tester1',
  'tp tester1 3500.5 -60 1400.5 90 0','effect give tester1 instant_health 1 5 true',
  'effect give tester1 saturation 1 5 true']:
  r.cmd(cmd)
 if a.slope:
  for n in range(1,8):r.cmd(f'fill {3500-n} -60 1399 {3500-n} {-61+n} 1401 stone')
  r.cmd('fill 3470 -60 1399 3492 -54 1401 stone')
  if a.confined:
   for z in [1398,1402]:r.cmd(f'fill 3470 -60 {z} 3530 -45 {z} bedrock')
 if a.equipped:
  for item in ['stone_pickaxe','stone_sword','cobblestone 32']:r.cmd('give tester1 '+item)
 r.cmd('scoreboard objectives add fleeDeaths deathCount');objective=True;r.cmd('scoreboard players set tester1 fleeDeaths 0')
 points={'single':[(6,0)],'inside':[(3,0)],'cluster':[(6,0)]+[(20,z) for z in [-2,-1,0,1,2,3]],'opposed':[(6,0),(-6,0)]}[a.layout]
 for x,z in points:
  r.cmd(f'summon creeper {3500.5+x} -60 {1400.5+z} '+('{NoAI:1b,PersistenceRequired:1b,Tags:["flee_trial"]}' if a.ai else '{NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Tags:["flee_trial"]}'))
 rec_start(40);recording=True;time.sleep(3)
 r.cmd('tp tester1 3500.5 -60 1400.5 90 0')
 if a.ai:r.cmd('execute as @e[tag=flee_trial] run data merge entity @s {NoAI:0b}')
 r.cmd('gamemode survival tester1')
 code=r'''
import json,math,re,time
from py4j.java_gateway import JavaGateway,GatewayParameters
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;mc=j.net.minecraft.class_310.method_1551();a=j.adris.altoclef.AltoClef.getInstance();lookup=j.java.lang.invoke.MethodHandles.lookup()
def call(o,name,*args):
 m=next(m for m in o.getClass().getMethods() if m.getName()==name and m.getParameterCount()==len(args));h=lookup.unreflect(m).bindTo(o)
 if args:
  ar=g.new_array(j.java.lang.Object,len(args))
  for n,v in enumerate(args):ar[n]=v
  h=j.java.lang.invoke.MethodHandles.insertArguments(h,0,ar)
 f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));mc.execute(f);return f.get()
task=j.adris.altoclef.tasks.movement.RunAwayFromCreepersTask(10.0);call(a,'runUserTask',task)
tracker=a.getEntityTracker();creeper=j.java.lang.Class.forName('net.minecraft.class_1548');rows=[];start=time.monotonic()
while time.monotonic()-start<25:
 s=dict(g.entry_point.getGameState()['self']);x,y,z=map(float,s['pos'].split(','));entities=call(tracker,'getTrackedEntities',creeper)
 positions=[tuple(map(float,m)) for m in re.findall(r'x=([-\d.]+), y=([-\d.]+), z=([-\d.]+)',str(call(entities,'toString')))]
 ds=[math.hypot(x-ex,z-ez) for ex,ey,ez in positions];near=min(ds) if ds else None
 row={'t':round(time.monotonic()-start,2),'state':s,'fps':g.entry_point.getPerfStats().get('fps',0),'nearest':near,'creepers':positions,'chain':g.entry_point.getTaskChainString(),'finished':bool(call(task,'isFinished'))};rows.append(row)
 if near is not None and near>=10 and row['finished'] and s['onGround']:break
 if s['hp']<5 or abs(x-3500)>60 or abs(z-1400)>60:break
 time.sleep(.15)
print(json.dumps(rows))
'''
 q=subprocess.run(['docker','exec','-i','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,timeout=35)
 if q.returncode:raise RuntimeError(q.stderr)
 rows=json.loads(q.stdout);last=rows[-1];score=r.cmd('scoreboard players get tester1 fleeDeaths');match=re.search(r'has (\d+)',score)
 if not match:raise RuntimeError('death counter unreadable: '+score)
 d={'layout':a.layout,'ai':a.ai,'slope':a.slope,'samples':rows,'deaths':int(match.group(1)),'median_fps':statistics.median(v['fps'] for v in rows),'healthy':all(v['state']['hp']==20 for v in rows),'arrived':last['nearest'] is not None and last['nearest']>=10 and last['finished'] and last['state']['onGround'],'route_observed':any('Routing to reachable safety' in v['chain'] for v in rows)}
 d['confined']=a.confined;d['equipped']=a.equipped
 d['valid_initial']=rows[0]['nearest'] is not None and rows[0]['nearest']<(5 if a.layout=='inside' else 7)
 if a.confined:d['arrived']=d['arrived'] and float(last['state']['pos'].split(',')[1])>=-57.1
 d['pass']=d['deaths']==0 and d['median_fps']>=14 and d['healthy'] and d['arrived'] and d['route_observed'] and d['valid_initial']
 (root/f'{a.tag}.json').write_text(json.dumps(d,indent=2));p.screenshot(str(root/f'{a.tag}.png'));print({k:v for k,v in d.items() if k!='samples'},flush=True)
 if not d['pass']:raise RuntimeError('retreat gate failed')
finally:
 try:p.call('stopPathing');r.cmd('kill @e[tag=flee_trial]')
 finally:
  if recording:rec_stop(str(root/f'{a.tag}.mp4'))
  for k,v in old.items():p.call('tungstenSetting',k,v)
  if objective:r.cmd('scoreboard objectives remove fleeDeaths')
  r.cmd('gamemode survival tester1');r.cmd('forceload remove 3470 1370 3530 1430')
