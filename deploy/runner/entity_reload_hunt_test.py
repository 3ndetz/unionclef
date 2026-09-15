#!/usr/bin/env python3
"""A hunt must replace its bound client entity after the server re-tracks the same ID.

Task ticks pause during reload to model replacement between two task evaluations.
The live client still receives entity packets; geometry is opened after replacement.
"""
import argparse,json,select,subprocess,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--tag',required=True);ap.add_argument('--output-dir',type=Path,required=True);ap.add_argument('--expect-bug',action='store_true');a=ap.parse_args()
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server');root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True);recording=False
try:
 p.call('stopPathing')
 for c in ['gamemode spectator tester1','forceload add 2120 798 2158 802','forceload add 2398 798 2402 802','kill @e[tag=reload_hunt]',
 'fill 2120 -62 798 2158 -57 802 bedrock','fill 2121 -60 800 2157 -59 800 air','fill 2124 -60 800 2124 -59 800 bedrock',
 'clear tester1','give tester1 stone_sword','tp tester1 2122.5 -60 800.5 -90 0','gamemode survival tester1','effect give tester1 instant_health 1 5 true','effect give tester1 saturation 1 5 true',
 'summon chicken 2132.5 -60 800.5 {NoAI:1b,PersistenceRequired:1b,Tags:["reload_hunt"]}']:r.cmd(c)
 time.sleep(1);rec_start(55);recording=True
 code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters
import time,json
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;a=j.adris.altoclef.AltoClef.getInstance();runner=a.getTaskRunner();client=j.net.minecraft.class_310.method_1551()
def runnable(h):return j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable'),h)
def active(value):
 f=runner.getClass().getDeclaredField('active');f.setAccessible(True);cs=g.new_array(j.java.lang.Class,2);cs[0]=j.java.lang.Class.forName('java.lang.Object');cs[1]=cs[0];m=f.getClass().getMethod('set',cs);h=j.java.lang.invoke.MethodHandles.lookup().unreflect(m).bindTo(f).bindTo(runner).bindTo(value);client.execute(runnable(h))
def find():
 it=a.getWorld().method_18112().iterator()
 while it.hasNext():
  e=it.next()
  if e is not None and str(e.getClass().getName())=='net.minecraft.class_1428' and 2120<e.method_23317()<2158 and abs(e.method_23321()-800.5)<1:return e
old=find();assert old is not None
cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('net.minecraft.class_1428');t=j.adris.altoclef.tasks.entity.KillEntitiesTask(cs)
cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task');m=a.getClass().getMethod('runUserTask',cs);client.execute(runnable(j.java.lang.invoke.MethodHandles.publicLookup().unreflect(m).bindTo(a).bindTo(t)))
try:
 for _ in range(100):
  if 'Killing entity.minecraft.chicken' in g.entry_point.getTaskChainString():break
  time.sleep(.05)
 else:raise RuntimeError('hunt did not bind its first target')
 active(False)
 while runner.isActive():time.sleep(.05)
 print('READY',flush=True)
 for _ in range(150):
  new=find()
  if old.method_31481() and new is not None and not new.method_31481():break
  time.sleep(.1)
 else:raise RuntimeError('entity replacement not observed')
 meta={'old_alive':old.method_5805(),'old_removed':old.method_31481(),'entity_equals':old.equals(new),'same_object':j.java.lang.System.identityHashCode(old)==j.java.lang.System.identityHashCode(new),'kill_task_equals':j.adris.altoclef.tasks.entity.KillEntityTask(old).equals(j.adris.altoclef.tasks.entity.KillEntityTask(new))}
 meta['approach_task_equals']=j.adris.altoclef.tasks.movement.GetToEntityTask(old).equals(j.adris.altoclef.tasks.movement.GetToEntityTask(new))
 meta['near_task_equals']=j.adris.altoclef.tasks.movement.GetNearEntityTask(old,2).equals(j.adris.altoclef.tasks.movement.GetNearEntityTask(new,2))
 meta['near_is_for']=j.adris.altoclef.tasks.movement.GetNearEntityTask(old,2).isFor(new)
 meta['same_instance_equal']=j.adris.altoclef.tasks.entity.KillEntityTask(new).equals(j.adris.altoclef.tasks.entity.KillEntityTask(new))
 active(True);rows=[];start=time.monotonic()
 while time.monotonic()-start<25:
  p=a.getPlayer();rows.append({'t':round(time.monotonic()-start,3),'x':p.method_23317(),'hp':p.method_6032(),'target_alive':new.method_5805(),'chain':g.entry_point.getTaskChainString()})
  if not new.method_5805() or rows[-1]['hp']<=0:break
  time.sleep(.2)
 print(json.dumps({'replacement':meta,'samples':rows}),flush=True)
finally:active(True)
'''
 q=subprocess.Popen(['docker','exec','uctest-mc-tester1','python3','-u','-c',code],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 ready,_,_=select.select([q.stdout],[],[],15)
 if not ready:raise RuntimeError('missing hunt marker')
 marker=q.stdout.readline().strip()
 if marker!='READY':raise RuntimeError(marker+q.stderr.read())
 r.cmd('tp @e[tag=reload_hunt] 2400.5 -60 800.5');time.sleep(2)
 r.cmd('tp @e[tag=reload_hunt] 2152.5 -60 800.5');r.cmd('fill 2124 -60 800 2124 -59 800 air')
 out,err=q.communicate(timeout=40)
 if q.returncode:raise RuntimeError(err)
 d=json.loads(out);rows=d['samples'];meta=d['replacement'];assert meta['old_removed'] and not meta['old_alive'] and meta['entity_equals'] and not meta['same_object'],meta
 passed=(rows[-1]['target_alive'] and meta['kill_task_equals']) if a.expect_bug else (not rows[-1]['target_alive'] and not any(meta[k] for k in ('kill_task_equals','approach_task_equals','near_task_equals','near_is_for')) and meta['same_instance_equal'] and min(s['hp'] for s in rows)==20)
 d.update({'pass':passed,'expected_bug':a.expect_bug});(root/f'{a.tag}.json').write_text(json.dumps(d,indent=2));p.screenshot(str(root/f'{a.tag}.png'));print({'pass':passed,'replacement':meta,'last':rows[-1]},flush=True)
 if not passed:raise RuntimeError('reloaded entity hunt failed')
finally:
 p.call('stopPathing')
 if recording:rec_stop(str(root/f'{a.tag}.mp4'))
 r.cmd('kill @e[tag=reload_hunt]');r.cmd('forceload remove 2120 798 2158 802');r.cmd('forceload remove 2398 798 2402 802')
