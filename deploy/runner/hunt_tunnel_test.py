#!/usr/bin/env python3
"""Exercise weapon pre-equipping while hunting through a solid tunnel wall.

The chicken begins beyond melee reach; the bot has to mine with a pickaxe before
it can equip its sword and kill it. Records frequent raw hand/engine samples and
video. The open variant verifies that weapon pre-equipping remains available.
--threat adds hunger, bread and a zombie; --duel targets that zombie directly.
--fed is the full-hunger control. Threat gates require surviving both targets
and finishing any needed meal; hunger values, active eating and combat counters
are recorded. Sampling ends at death to avoid measuring subsequent respawns.
"""
import argparse,json,select,subprocess,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser();ap.add_argument('--tag',required=True);ap.add_argument('--output-dir',type=Path,required=True);ap.add_argument('--open',action='store_true');ap.add_argument('--expect-bug',action='store_true');ap.add_argument('--duel',action='store_true',help='Target the zombie directly instead of hunting a chicken');ap.add_argument('--fed',action='store_true',help='Full-hunger control for the threat case');ap.add_argument('--threat',action='store_true',help='Hungry bot with bread and a nearby zombie');args=ap.parse_args()
root=args.output_dir.resolve();root.mkdir(parents=True,exist_ok=True)
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server');p.call('ConnectToServer','test-server')
for _ in range(45):
 time.sleep(1)
 if p.call('getGameState').get('self'):break
p.call('stopPathing');recording=False
previous_difficulty=next((v for v in ('peaceful','easy','normal','hard') if v in r.cmd('difficulty').lower()),'normal')
try:
 commands=['gamemode spectator tester1','forceload add 1838 458 1856 462','kill @e[tag=hunt_tunnel]',
 'kill @e[type=item,x=1838,y=-62,z=458,dx=18,dy=5,dz=4]',
 'fill 1838 -62 458 1856 -58 462 bedrock','fill 1840 -60 460 1854 -59 460 air',
 'clear tester1','tp tester1 1842.5 -60 460.5 -90 0','gamemode survival tester1',
 'give tester1 iron_pickaxe','give tester1 stone_sword','effect give tester1 instant_health 1 5 true','effect give tester1 saturation 1 5 true',
 'summon chicken 1852.5 -60 460.5 {NoAI:1b,Tags:["hunt_tunnel"]}']
 if args.duel:commands=[c for c in commands if not c.startswith('summon chicken ')]
 if not args.open:commands.insert(6,'fill 1844 -60 460 1846 -59 460 stone')
 for command in commands:r.cmd(command)
 if args.threat:
  r.cmd('difficulty normal')
  if args.open:r.cmd('give tester1 bread 16')
  if not args.fed:
   r.cmd('effect clear tester1 saturation');r.cmd('effect give tester1 hunger 5 255 true');time.sleep(5.5);r.cmd('effect clear tester1 hunger')
  if args.open:r.cmd('summon zombie 1840.5 -60 460.5 {NoAI:1b,PersistenceRequired:1b,CanPickUpLoot:0b,Tags:["hunt_tunnel","hunt_threat"]}')
 time.sleep(2);assert p.call('selectHotbar',0)
 before=p.call('getGameState')['self'];assert before['held']=='minecraft:iron_pickaxe' and before['onGround'] and abs(float(before['pos'].split(',')[0])-1842.5)<.1,before
 rec_start(65);recording=True
 code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters
import time,json
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;a=j.adris.altoclef.AltoClef.getInstance();api=g.entry_point
cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('net.minecraft.class_1428');t=j.adris.altoclef.tasks.entity.KillEntitiesTask(cs)
cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task');m=a.getClass().getMethod('runUserTask',cs);h=j.java.lang.invoke.MethodHandles.publicLookup().unreflect(m).bindTo(a).bindTo(t);run=j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable'),h);j.net.minecraft.class_310.method_1551().execute(run)
victims=[];entities=a.getWorld().method_18112().iterator()
while entities.hasNext():
 v=entities.next()
 if v.method_5805() and str(v.getClass().getName()) in ('net.minecraft.class_1428','net.minecraft.class_1642') and 1838<=v.method_23317()<=1856 and abs(v.method_23318()+60)<.1 and abs(v.method_23321()-460.5)<.6:victims.append(v)
assert len(victims)==EXPECTED_TARGETS,len(victims)
try: cancelCounter=j.java.lang.Class.forName('adris.altoclef.chains.MobDefenseChain').getField('mdBlockWorkCancelled')
except Exception: cancelCounter=None
initialHunger=a.getPlayer().method_7344().method_7586()
armOnMine=ARM_ON_MINE;threatActive=not armOnMine
rows=[];start=time.monotonic();e=j.kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR
while time.monotonic()-start<40:
 p=a.getPlayer();rows.append({'t':round(time.monotonic()-start,3),'breaking':e.isBreakingNow(),'placing':e.isPlacingNow(),'queue':j.kaptainwutax.tungsten.path.movements.MovementQueue.remainingNeedsBlockWork(a.getWorld()),'hand':str(p.method_6047().method_7909()),'x':p.method_23317(),'hp':p.method_6032(),'hunger':p.method_7344().method_7586(),'eatNeed':a.getFoodChain().needsToEat(),'eating':a.getFoodChain().isTryingToEat(),'threatActive':threatActive,'mdWon':j.adris.altoclef.chains.MobDefenseChain.mdWon,'combatCancelled':cancelCounter.getInt(None) if cancelCounter is not None else 0,'mdTungsten':j.adris.altoclef.chains.MobDefenseChain.mdTungstenTicks,'fps':api.getPerfStats().get('fps')});time.sleep(.08)
 if armOnMine and not threatActive and e.isMiningNow():
  print('MINING_READY',flush=True);threatActive=True
 if rows[-1]['hp']<=0 or not 1838<=rows[-1]['x']<=1856:break
 if not armOnMine and rows[-1]['t']>3 and all(not v.method_5805() for v in victims) and not rows[-1]['eatNeed'] and not rows[-1]['eating'] and (not REQUIRE_MEAL or rows[-1]['hunger']>initialHunger):break
print(json.dumps(rows))
'''
 code=code.replace('ARM_ON_MINE',repr(args.threat and not args.open)).replace('EXPECTED_TARGETS',str(2 if args.threat and args.open and not args.duel else 1)).replace('REQUIRE_MEAL',repr(args.threat and not args.fed))
 if args.duel:code=code.replace("net.minecraft.class_1428","net.minecraft.class_1642")
 if args.threat and args.open:r.cmd('data merge entity @e[tag=hunt_threat,limit=1] {NoAI:0b}')
 q=subprocess.Popen(['docker','exec','uctest-mc-tester1','python3','-c',code],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 if args.threat and not args.open:
  if not select.select([q.stdout],[],[],45)[0]:
   q.kill();q.communicate();raise RuntimeError('mining did not start before the ambush deadline')
  signal=q.stdout.readline().strip()
  if signal!='MINING_READY':
   q.kill();_,details=q.communicate();(root/f'{args.tag}-setup.json').write_text(signal);raise RuntimeError('no mining marker: '+signal[:120]+' '+details[-1200:])
  activation=r.cmd('summon zombie 1840.5 -60 460.5 {PersistenceRequired:1b,CanPickUpLoot:0b,Tags:["hunt_tunnel","hunt_threat"]}')
  assert 'Summoned' in activation,activation
  r.cmd('give tester1 bread 16')
 stdout,stderr=q.communicate(timeout=55)
 if q.returncode:raise RuntimeError(stderr)
 rows=json.loads(stdout);alive='passed' in r.cmd('execute if entity @e[tag=hunt_tunnel]').lower()
 collisions=sum(v['breaking'] and not v['queue'] and v['hand']=='minecraft:stone_sword' for v in rows)
 # The executor also holds a queue during its post-dig settling interval.
 # Count interrupted pickaxe episodes separately from an inherited sword at startup.
 switches=sum(prev['breaking'] and cur['breaking'] and prev['hand']=='minecraft:iron_pickaxe' and cur['hand']=='minecraft:stone_sword' for prev,cur in zip(rows,rows[1:]))
 mined=sum(v['breaking'] for v in rows);advanced=max(v['x'] for v in rows)>1847
 inventory=p.call('getInventoryFull');cobble=sum(v.get('count',0) for v in inventory.get('slots',[]) if v.get('item')=='minecraft:cobblestone')
 passed=(collisions>5 and mined>10) if args.expect_bug else (not alive and min(v['hp'] for v in rows)==20 and ((args.open and any(v['hand']=='minecraft:stone_sword' for v in rows)) or (mined>0 and advanced and switches==0 and cobble==6)))
 if args.threat:
  assert rows[0]['hunger']==20 if args.fed else rows[0]['hunger']<=10,rows[0]
  if not args.open:
   assert any(v['threatActive'] for v in rows),'no mining ambush was activated'
   if not args.expect_bug:assert rows[-1]['combatCancelled']>rows[0]['combatCancelled'],'combat never cancelled queued block work'
  blocked=sum(v['eatNeed'] and not v['eating'] for v in rows)
  passed=(blocked>10 and min(v['hp'] for v in rows)<before['hp']-4) if args.expect_bug else (not alive and min(v['hp'] for v in rows)>0 and not rows[-1]['eatNeed'] and (args.fed or rows[-1]['hunger']>rows[0]['hunger']) and (args.open or (advanced and cobble==6)))
 result={'threat':args.threat,'duel':args.duel,'fed':args.fed,'ambush':args.threat and not args.open,'before':before,'samples':rows,'alive':alive,'mining_samples':mined,'sword_during_executor_mining':collisions,'pick_to_sword_during_continuous_queue':switches,'advanced':advanced,'pass':passed,'expected_bug':args.expect_bug,'open':args.open,'final':p.call('getGameState'),'inventory':inventory,'cobblestone':cobble}
 (root/f'{args.tag}.json').write_text(json.dumps(result,indent=2));p.screenshot(str(root/f'{args.tag}.png'));print({k:v for k,v in result.items() if k not in ('samples','inventory')},flush=True)
 if not passed:raise RuntimeError('hunt tunnel gate failed')
finally:
 p.call('stopPathing')
 if recording:rec_stop(str(root/f'{args.tag}.mp4'))
 r.cmd('kill @e[tag=hunt_tunnel]');r.cmd('effect clear tester1 hunger');r.cmd('difficulty '+previous_difficulty);r.cmd('forceload remove 1838 458 1856 462')
