#!/usr/bin/env python3
"""Exercise completed versus missing iron output through the real gamer priority provider."""
import sys,subprocess,time,json,argparse,textwrap
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
ap=argparse.ArgumentParser(description='Iron smelting must only run while its output target is unmet.');ap.add_argument('--tag',required=True);ap.add_argument('--output-dir',type=Path,required=True);ap.add_argument('--ingots',type=int,choices=[0,1,2],required=True);ap.add_argument('--expect-bug',action='store_true');args=ap.parse_args()
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
p=Py4jClient('uctest-mc-tester1');r=Rcon();root=args.output_dir.resolve();root.mkdir(parents=True,exist_ok=True)
old_idle=p.call('tungstenSetting','botFpsNoIdleThrottle','').split('=',1)[1]
recording=False
try:
 p.call('ConnectToServer','test-server')
 for _ in range(45):
  time.sleep(1)
  if 'tester1' in r.cmd('list'):break
 else:raise RuntimeError('wrong server')
 p.call('stopPathing');time.sleep(.3);p.call('closeOpenScreen');p.call('tungstenSetting','botFpsNoIdleThrottle','true')
 reset_code=r'''
 from py4j.java_gateway import JavaGateway,GatewayParameters
 j=(g:=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True))).jvm
 cs=g.new_array(j.java.lang.Class,0);m=j.java.lang.Class.forName('kaptainwutax.tungsten.TungstenMod').getMethod('resetAllState',cs);h=j.java.lang.invoke.MethodHandles.publicLookup().unreflect(m);f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));j.net.minecraft.class_310.method_1551().execute(f);f.get()
 '''
 subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',textwrap.dedent(reset_code)],check=True,timeout=15)
 for c in ['gamemode spectator tester1','forceload add 2400 940 2430 950','fill 2400 -61 940 2430 -61 950 bedrock','fill 2400 -60 940 2430 -55 950 air','setblock 2420 -61 945 water','clear tester1','tp tester1 2405.5 -60 945.5 -90 0','gamemode survival tester1','effect give tester1 instant_health 1 5 true','effect give tester1 saturation 1 5 true']:
  r.cmd(c)
 for item,n in [('iron_pickaxe',1),('stone_axe',1),('stone_pickaxe',1),('stone_sword',1),('shield',1),('bucket',2),('iron_ingot',args.ingots),('raw_iron',1),('coal',8),('cobblestone',64),('oak_log',12),('crafting_table',1),('furnace',1),('cooked_beef',20)]:
  if n:r.cmd(f'give tester1 {item} {n}')
 time.sleep(1)
 stable=0
 for _ in range(30):
  fps=p.call('getPerfStats').get('fps',0);stable=stable+1 if fps>=14 else 0
  if stable>=3:break
  time.sleep(1)
 else:raise RuntimeError('stand did not reach startup FPS')
 before=p.call('getGameState')['self'];assert before['onGround'] and before['hp']==20,before
 inventory=p.call('getInventoryFull');actual=sum(v.get('count',0) for v in inventory['slots'] if v.get('item')=='minecraft:iron_ingot');assert actual==args.ingots,inventory
 code=r'''
 import json
 from py4j.java_gateway import JavaGateway,GatewayParameters
 j=(g:=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True))).jvm;a=j.adris.altoclef.AltoClef.getInstance();c=j.net.minecraft.class_310.method_1551()
 def call(obj,name,types=(),args=()):
  cs=g.new_array(j.java.lang.Class,len(types))
  for i,t in enumerate(types):cs[i]=j.java.lang.Class.forName(t)
  m=obj.getClass().getMethod(name,cs);h=j.java.lang.invoke.MethodHandles.publicLookup().unreflect(m).bindTo(obj)
  for arg in args:h=h.bindTo(arg)
  f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));c.execute(f);return f.get()
 call(a.getItemStorage(),'setDirty')
 cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('adris.altoclef.AltoClef');ctor=j.java.lang.Class.forName('adris.altoclef.tasks.speedrun.beatgame.BeatMinecraftTask').getConstructor(cs);h=j.java.lang.invoke.MethodHandles.publicLookup().unreflectConstructor(ctor).bindTo(a);f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));c.execute(f);t=f.get()
 field=t.getClass().getDeclaredField('gatherResources');field.setAccessible(True);candidates=field.get(t);rows=[]
 for candidate in candidates:
  priority=call(candidate,'calculatePriority',('adris.altoclef.AltoClef',),(a,))
  if priority==350:
   child=call(candidate,'getTask',('adris.altoclef.AltoClef',),(a,));rows.append({'priority':priority,'task':str(child),'finished':call(child,'isFinished') if child else None})
 print(json.dumps(rows));call(a,'runUserTask',('adris.altoclef.tasksystem.Task',),(t,))
 '''
 rec_start(60);recording=True
 q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',textwrap.dedent(code)],capture_output=True,text=True,timeout=30)
 if q.returncode:raise RuntimeError(q.stderr)
 rows=[]
 for _ in range(6):
  rows.append({'chain':p.call('getTaskChainString'),'inventory':p.call('getInventoryFull'),'state':p.call('getGameState'),'performance':p.call('getPerfStats')});time.sleep(3)
 probe=json.loads(q.stdout);iron=[v for v in probe if 'iron_ingot' in v['task']];expected=args.ingots==0 or args.expect_bug
 seen=any('350.00' in v['chain'] and 'iron_ingot' in v['chain'] for v in rows)
 final_slots=rows[-1]['inventory']['slots']
 final_ingots=sum(v.get('count',0) for v in final_slots if v.get('item')=='minecraft:iron_ingot')
 final_raw=sum(v.get('count',0) for v in final_slots if v.get('item')=='minecraft:raw_iron')
 outcome=(final_ingots>=1) if args.ingots==0 else (args.expect_bug or final_raw==1)
 passed=(bool(iron)==expected and (not iron or iron[0]['finished']==(args.ingots>0)) and seen==expected and outcome and all(v['state']['self']['hp']==20 for v in rows))
 data={'probe':probe,'samples':rows,'ingots':args.ingots,'expected_bug':args.expect_bug,'final_ingots':final_ingots,'final_raw':final_raw,'pass':passed};(root/f'{args.tag}.json').write_text(json.dumps(data,indent=2));print({'probe':probe,'seen':seen,'pass':passed},flush=True);p.screenshot(str(root/f'{args.tag}.png'))
 if not passed:raise RuntimeError('smelt priority gate failed')
finally:
 try:
  p.call('stopPathing')
 finally:
  try:
   if recording:rec_stop(str(root/f'{args.tag}.mp4'))
  finally:
   p.call('tungstenSetting','botFpsNoIdleThrottle',old_idle)
   r.cmd('gamemode survival tester1')
   r.cmd('forceload remove 2400 940 2430 950')
