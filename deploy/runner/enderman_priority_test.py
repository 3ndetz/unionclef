#!/usr/bin/env python3
"""An already-angry closer Enderman must participate in full-health threat selection."""
import argparse,json,subprocess,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--tag',required=True);ap.add_argument('--output-dir',type=Path,required=True);ap.add_argument('--expect-bug',action='store_true');ap.add_argument('--calm',action='store_true');ap.add_argument('--battle',action='store_true',help='Release both mobs after selection setup and require surviving both');a=ap.parse_args()
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server');root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True);recording=False
try:
 p.call('stopPathing')
 for c in ['gamemode spectator tester1','forceload add 2190 860 2220 870','kill @e[tag=enderman_priority]','fill 2190 -61 860 2220 -61 870 bedrock','fill 2190 -60 860 2220 -55 870 air','clear tester1','give tester1 stone_sword','item replace entity tester1 weapon.offhand with shield','tp tester1 2200.5 -60 865.5 90 45','gamemode survival tester1','effect give tester1 instant_health 1 5 true','effect give tester1 saturation 1 5 true','summon enderman 2205.5 -60 865.5 {PersistenceRequired:1b,Tags:["enderman_priority","priority_ender"]}','summon zombie 2192.5 -60 865.5 {NoAI:1b,PersistenceRequired:1b,Tags:["enderman_priority"]}']:r.cmd(c)
 if not a.calm:r.cmd('damage @e[tag=priority_ender,limit=1] 1 minecraft:player_attack by tester1')
 time.sleep(.4);r.cmd('data merge entity @e[tag=priority_ender,limit=1] {NoAI:1b}');time.sleep(.2)
 rec_start(60 if a.battle else 20);recording=True
 code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters
import json,time
j=(g:=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True))).jvm;a=j.adris.altoclef.AltoClef.getInstance();md=a.getMobDefenseChain();f=md.getClass().getDeclaredField('lockedOnEntity');f.setAccessible(True)
ender=None;zombie=None;it=a.getWorld().method_18112().iterator()
while it.hasNext():
 e=it.next()
 if e is None or not 2190<e.method_23317()<2220 or not 860<e.method_23321()<870:continue
 if str(e.getClass().getName())=='net.minecraft.class_1560':ender=e
 if str(e.getClass().getName())=='net.minecraft.class_1642':zombie=e
assert ender is not None and zombie is not None
pl=a.getPlayer();meta={'angry':j.adris.altoclef.util.helpers.EntityHelper.isProbablyHostileToPlayer(a,ender),'visible_hostile':j.adris.altoclef.util.helpers.EntityHelper.isAngryAtPlayer(a,ender),'ender_distance':abs(ender.method_23317()-pl.method_23317()),'zombie_distance':abs(zombie.method_23317()-pl.method_23317()),'hp':pl.method_6032()}
t=j.adris.altoclef.tasks.movement.IdleTask();cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task');m=a.getClass().getMethod('runUserTask',cs);h=j.java.lang.invoke.MethodHandles.publicLookup().unreflect(m).bindTo(a).bindTo(t);j.net.minecraft.class_310.method_1551().execute(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable'),h))
if BATTLE:print('READY',flush=True)
rows=[];start=time.monotonic()
while time.monotonic()-start<(40 if BATTLE else 5):
 e=f.get(md);rows.append({'t':round(time.monotonic()-start,3),'hp':a.getPlayer().method_6032(),'target':str(e.getClass().getName()) if e else None,'chain':g.entry_point.getTaskChainString(),'ender_alive':ender.method_5805(),'zombie_alive':zombie.method_5805(),'ender_hp':ender.method_6032(),'zombie_hp':zombie.method_6032(),'hand':str(a.getPlayer().method_6047().method_7909()),'combat_ticks':j.adris.altoclef.chains.MobDefenseChain.mdTungstenTicks,'fps':g.entry_point.getPerfStats().get('fps')})
 if BATTLE:
  if rows[-1]['hp']<=0 or (not rows[-1]['ender_alive'] and not rows[-1]['zombie_alive']):break
 elif e is not None and e.method_5805():break
 time.sleep(.1 if BATTLE else .05)
print(json.dumps({'initial':meta,'samples':rows}))
'''
 code=code.replace('BATTLE',repr(a.battle))
 q=subprocess.Popen(['docker','exec','uctest-mc-tester1','python3','-u','-c',code],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 if a.battle:
  import select
  if not select.select([q.stdout],[],[],15)[0]:q.kill();raise RuntimeError('missing battle marker')
  marker=q.stdout.readline().strip()
  if marker!='READY':raise RuntimeError(marker+q.stderr.read())
  r.cmd('execute as @e[tag=enderman_priority] run data merge entity @s {NoAI:0b}')
 out,err=q.communicate(timeout=50 if a.battle else 15)
 if q.returncode:raise RuntimeError(err)
 d=json.loads(out);m=d['initial'];assert m['hp']==20 and m['ender_distance']<m['zombie_distance'],m;assert m['angry']==(not a.calm) and m['visible_hostile']==(not a.calm),m
 server_alive='passed' in r.cmd('execute if entity @e[tag=enderman_priority]').lower()
 d['server_alive']=server_alive
 expected='net.minecraft.class_1642' if a.expect_bug or a.calm else 'net.minecraft.class_1560';passed=(not server_alive and min(x['hp'] for x in d['samples'])>0 and not d['samples'][-1]['ender_alive'] and not d['samples'][-1]['zombie_alive']) if a.battle else d['samples'][-1]['target']==expected;d.update({'pass':passed,'expected_bug':a.expect_bug,'calm':a.calm,'battle':a.battle});(root/f'{a.tag}.json').write_text(json.dumps(d,indent=2));p.screenshot(str(root/f'{a.tag}.png'));print({k:v for k,v in d.items() if k!='samples'}|{'last':d['samples'][-1]},flush=True)
 if not passed:raise RuntimeError('threat selection failed')
finally:
 p.call('stopPathing')
 if recording:rec_stop(str(root/f'{a.tag}.mp4'))
 r.cmd('kill @e[tag=enderman_priority]');r.cmd('forceload remove 2190 860 2220 870')
