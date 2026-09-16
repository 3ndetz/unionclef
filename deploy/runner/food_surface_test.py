#!/usr/bin/env python3
"""Observe food exploration in a foodless underground chamber."""
import argparse,json,math,statistics,subprocess,sys,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser();ap.add_argument('--tag',required=True);ap.add_argument('--depth',type=int,default=8);ap.add_argument('--expect-bug',action='store_true');ap.add_argument('--local-food',action='store_true');ap.add_argument('--surface-start',action='store_true');ap.add_argument('--wet-column',action='store_true');ap.add_argument('--seconds',type=int,default=45);ap.add_argument('--output-dir',type=Path,required=True);args=ap.parse_args()
root=args.output_dir.resolve();root.mkdir(parents=True,exist_ok=True)
p=Py4jClient('uctest-mc-tester1');r=Rcon();recording=False;old={};top=-58+args.depth

def surface_height(x,z):
 code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;a=j.adris.altoclef.AltoClef.getInstance();lookup=j.java.lang.invoke.MethodHandles.lookup();mc=j.net.minecraft.class_310.method_1551()
def invoke(h):
 f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));mc.execute(f);return f.get()
m=next(m for m in a.getClass().getMethods() if m.getName()=='getWorld');world=invoke(lookup.unreflect(m).bindTo(a))
m=next(m for m in world.getClass().getMethods() if m.getName()=='method_8624' and m.getParameterCount()==3)
enum=next(v for v in m.getParameterTypes()[0].getEnumConstants() if v.name()=='MOTION_BLOCKING_NO_LEAVES')
params=g.new_array(j.java.lang.Object,3);params[0]=enum;params[1]=j.java.lang.Integer('QUERY_X');params[2]=j.java.lang.Integer('QUERY_Z')
h=j.java.lang.invoke.MethodHandles.insertArguments(lookup.unreflect(m).bindTo(world),0,params)
print(invoke(h))
'''.replace('QUERY_X',str(x)).replace('QUERY_Z',str(z))
 q=subprocess.run(['docker','exec','-i','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,check=True,timeout=15)
 return int(q.stdout.strip())
try:
 p.call('ConnectToServer','test-server')
 for _ in range(45):
  time.sleep(1)
  if 'tester1' in r.cmd('list') and p.call('getGameState').get('self'):break
 else:raise RuntimeError('flat login failed')
 p.call('stopPathing');p.call('closeOpenScreen')
 for k in ['allowBreak','allowPlace','botFpsNoIdleThrottle']:
  old[k]=p.call('tungstenSetting',k,'').split('=',1)[1];p.call('tungstenSetting',k,'true')
 for cmd in ['gamemode spectator tester1','difficulty peaceful','forceload add 3570 1570 3630 1630','execute positioned 3600 -58 1600 run kill @e[type=!player,distance=..70]','clear tester1','effect clear tester1','fill 3570 -61 1570 3630 -61 1630 bedrock']:
  r.cmd(cmd)
 for y in range(-60,top,8):r.cmd(f'fill 3570 {y} 1570 3630 {min(y+7,top-1)} 1630 stone')
 for y in range(top,-25,8):r.cmd(f'fill 3570 {y} 1570 3630 {min(y+7,-26)} 1630 air')
 # Open neighbours suppress the enclosed-body recovery: this is ordinary exploration.
 r.cmd('fill 3598 -58 1598 3604 -56 1602 air')
 if args.wet_column:r.cmd(f'setblock 3600 {top-1} 1600 water')
 for cmd in ['give tester1 diamond_pickaxe','give tester1 cobblestone 64','effect give tester1 instant_health 1 5 true','effect give tester1 saturation 1 5 true']:
  r.cmd(cmd)
 start_y=top if args.surface_start else -58
 r.cmd(f'tp tester1 3600.5 {start_y} 1600.5 0 0')
 rec_start(args.seconds+20);recording=True;time.sleep(3)
 if args.local_food:r.cmd('summon item 3603.5 -58 1600.5 {Item:{id:"minecraft:bread",count:4},PickupDelay:0s,Tags:["food_surface"]}')
 r.cmd('gamemode survival tester1')
 code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;a=j.adris.altoclef.AltoClef.getInstance();task=j.adris.altoclef.tasks.resources.CollectFoodTask(20.0)
cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task');m=a.getClass().getMethod('runUserTask',cs);h=j.java.lang.invoke.MethodHandles.lookup().unreflect(m).bindTo(a).bindTo(task);f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));j.net.minecraft.class_310.method_1551().execute(f);f.get()
'''
 q=subprocess.run(['docker','exec','-i','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,timeout=25)
 if q.returncode:raise RuntimeError(q.stderr)
 rows=[];start=time.monotonic();bread=0;actual_top=None
 while time.monotonic()-start<args.seconds:
  s=p.call('getGameState')['self'];row={'t':round(time.monotonic()-start,2),'state':s,'fps':p.call('getPerfStats').get('fps',0),'chain':p.call('getTaskChainString')};rows.append(row)
  if args.local_food:
   inv=p.call('getInventoryFull');bread=sum(v.get('count',0) for v in inv.get('slots',[]) if v.get('item')=='minecraft:bread')
   if bread>=4:break
  elif not args.surface_start and float(s['pos'].split(',')[1])>=top-1 and s['onGround'] and 'Searching for food on the surface' not in row['chain']:
   x,y,z=map(float,s['pos'].split(','));actual_top=surface_height(math.floor(x),math.floor(z))
   if y>=actual_top-.01:break
  elif args.surface_start and len(rows)>=8:break
  time.sleep(.3)
 last=rows[-1];ys=[float(v['state']['pos'].split(',')[1]) for v in rows]
 d={'wet_column':args.wet_column,'depth':args.depth,'top':top,'local_food':args.local_food,'surface_start':args.surface_start,'samples':rows,'median_fps':statistics.median(v['fps'] for v in rows),'healthy':all(v['state']['hp']==20 for v in rows),'surface_top':actual_top,'emerged':actual_top is not None and ys[-1]>=actual_top-.01 and last['state']['onGround'],'released': 'Searching for food on the surface' not in last['chain'],'bread':bread,'surface_route':any('surface' in v['chain'].lower() for v in rows)}
 success=(bread>=4 and max(ys)<top) if args.local_food else ((not d['surface_route']) if args.surface_start else d['emerged'] and d['surface_route'] and d['released'])
 d['pass']=d['healthy'] and d['median_fps']>=14 and (not success if args.expect_bug else success)
 (root/(args.tag+'.json')).write_text(json.dumps(d,indent=2));p.screenshot(str(root/(args.tag+'.png')));print({k:v for k,v in d.items() if k!='samples'},flush=True)
 if not d['pass']:raise RuntimeError('food exploration gate failed')
finally:
 p.call('stopPathing')
 if recording:rec_stop(str(root/(args.tag+'.mp4')))
 r.cmd('kill @e[tag=food_surface]');r.cmd('forceload remove 3570 1570 3630 1630');r.cmd('gamemode survival tester1')
 for k,v in old.items():p.call('tungstenSetting',k,v)
