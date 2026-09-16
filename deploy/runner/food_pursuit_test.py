#!/usr/bin/env python3
"""Separate high-animal routing from food selection on disposable terrain."""
import argparse,json,statistics,subprocess,sys,time,math,re
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser();ap.add_argument('--tag',required=True);ap.add_argument('--direct',action='store_true');ap.add_argument('--competing',action='store_true');ap.add_argument('--depth',type=int,default=45);ap.add_argument('--seconds',type=int,default=180);ap.add_argument('--output-dir',type=Path,required=True);a=ap.parse_args()
if not 4 <= a.depth <= 60 or a.seconds < 1:ap.error('depth must be4..60 and seconds positive')
root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True);p=Py4jClient('uctest-mc-tester1');r=Rcon();old={};recording=False;top=-58+a.depth
try:
 if 'tester1' not in r.cmd('list') or not p.call('getGameState').get('self'):p.call('ConnectToServer','test-server')
 for _ in range(45):
  if 'tester1' in r.cmd('list') and p.call('getGameState').get('self'):break
  time.sleep(1)
 else:raise RuntimeError('flat login not confirmed')
 p.call('stopPathing');p.call('closeOpenScreen');difficulty=next(v for v in ('peaceful','easy','normal','hard') if v in r.cmd('difficulty').lower())
 for k in ['allowBreak','allowPlace','botFpsNoIdleThrottle']:
  old[k]=p.call('tungstenSetting',k,'').split('=',1)[1];p.call('tungstenSetting',k,'true')
 for cmd in ['gamemode spectator tester1','difficulty peaceful','forceload add 4176 2176 4224 2224','execute positioned 4200 -58 2200 run kill @e[type=!player,distance=..80]','clear tester1','effect clear tester1','fill 4176 -61 2176 4224 -61 2224 bedrock']:
  r.cmd(cmd)
 for y in range(-60,top,8):r.cmd(f'fill 4176 {y} 2176 4224 {min(y+7,top-1)} 2224 stone')
 for y in range(top,max(top+8,-5),4):r.cmd(f'fill 4176 {y} 2176 4224 {y+3} 2224 air')
 # A cave floor with several walkable alternatives beneath the animal.
 r.cmd('fill 4194 -58 2194 4206 -56 2206 air')
 r.cmd('fill 4199 -58 2180 4201 -56 2220 air')
 for cmd in ['give tester1 diamond_pickaxe','give tester1 diamond_sword','give tester1 cobblestone 64','tp tester1 4200.5 -58 2200.5 0 0','gamemode survival tester1','effect give tester1 saturation 1 20 true','effect give tester1 instant_health 1 5 true',f'summon pig 4200.5 {top} 2200.5 {{NoAI:1b,PersistenceRequired:1b,Tags:["food_pursuit"]}}']:
  r.cmd(cmd)
 if a.competing:r.cmd(f'summon sheep 4208.5 {top} 2208.5 {{NoAI:1b,PersistenceRequired:1b,Tags:["food_pursuit"]}}')
 assert 'passed' in r.cmd('execute if entity @e[type=pig,tag=food_pursuit]').lower()
 rec_start(a.seconds+20);recording=True;time.sleep(3)
 code=r'''
import json
from py4j.java_gateway import JavaGateway,GatewayParameters
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;mod=j.adris.altoclef.AltoClef.getInstance();c=j.net.minecraft.class_310.method_1551();lk=j.java.lang.invoke.MethodHandles.lookup()
def call(h):
 f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));c.execute(f);return f.get()
task=j.adris.altoclef.tasks.resources.CollectFoodTask(20.0)
if DIRECT:
 tracker=mod.getEntityTracker();m=next(m for m in tracker.getClass().getMethods() if m.getName()=='getTrackedEntities');entities=call(lk.unreflect(m).bindTo(tracker).bindTo(j.java.lang.Class.forName('net.minecraft.class_1452')))
 assert entities.size()==1,entities.size()
 task=j.adris.altoclef.tasks.movement.GetNearEntityTask(entities.get(0),2)
m=next(m for m in mod.getClass().getMethods() if m.getName()=='runUserTask' and m.getParameterCount()==1);call(lk.unreflect(m).bindTo(mod).bindTo(task))
'''.replace('DIRECT',str(a.direct))
 q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,timeout=20)
 if q.returncode:raise RuntimeError(q.stderr)
 rows=[];started=time.monotonic();success=False
 while time.monotonic()-started<a.seconds:
  state=p.call('getGameState')['self'];inv=p.call('getInventoryFull');food=sum(v.get('count',0) for v in inv.get('slots',[]) if v.get('item') in ['minecraft:porkchop','minecraft:cooked_porkchop','minecraft:mutton','minecraft:cooked_mutton'])
  x,y,z=map(float,state['pos'].split(','));target_data=r.cmd('execute as @e[type=pig,tag=food_pursuit,limit=1] run data get entity @s Pos');target_values=re.findall(r'(-?\d+(?:\.\d+)?)d',target_data);target=tuple(map(float,target_values)) if len(target_values)==3 else None;distance=math.dist((x,y,z),target) if target else 999.0;row={'t':round(time.monotonic()-started,2),'state':state,'food':food,'distance':round(distance,2),'target':target,'fps':p.call('getPerfStats').get('fps',0),'chain':p.call('getTaskChainString')};rows.append(row)
  success=(distance<=2.5) if a.direct else food>0
  d={'direct':a.direct,'competing':a.competing,'depth':a.depth,'samples':rows,'success':success,'settings_before':old,'difficulty_before':difficulty};(root/(a.tag+'.json')).write_text(json.dumps(d,indent=2));print(row['t'],state['pos'],state['hp'],'distance',row['distance'],'food',food,'fps',row['fps'],flush=True)
  if len(rows)%4==1:p.screenshot(str(root/f'{a.tag}-{int(row["t"]):03d}.png'))
  if success:break
  time.sleep(2)
 d.update({'median_fps':statistics.median(v['fps'] for v in rows),'healthy':all(v['state']['hp']==20 for v in rows)});d['pass']=success and d['healthy'] and d['median_fps']>=14;(root/(a.tag+'.json')).write_text(json.dumps(d,indent=2));p.screenshot(str(root/(a.tag+'.png')));print({k:v for k,v in d.items() if k!='samples'},flush=True)
 if not d['pass']:raise RuntimeError('high food pursuit gate failed')
finally:
 p.call('stopPathing')
 if recording:rec_stop(str(root/(a.tag+'.mp4')))
 r.cmd('kill @e[tag=food_pursuit]');r.cmd('forceload remove 4176 2176 4224 2224');r.cmd('gamemode survival tester1')
 try:
  if 'difficulty' in locals():r.cmd('difficulty '+difficulty)
 finally:
  for k,v in old.items():p.call('tungstenSetting',k,v)
