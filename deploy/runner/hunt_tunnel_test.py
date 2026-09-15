#!/usr/bin/env python3
"""Exercise weapon pre-equipping while hunting through a solid tunnel wall.

The chicken begins beyond melee reach; the bot has to mine with a pickaxe before
it can equip its sword and kill it. Records frequent raw hand/engine samples and
video. The open variant verifies that weapon pre-equipping remains available.
"""
import argparse,json,subprocess,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser();ap.add_argument('--tag',required=True);ap.add_argument('--output-dir',type=Path,required=True);ap.add_argument('--open',action='store_true');ap.add_argument('--expect-bug',action='store_true');args=ap.parse_args()
root=args.output_dir.resolve();root.mkdir(parents=True,exist_ok=True)
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server');p.call('ConnectToServer','test-server')
for _ in range(45):
 time.sleep(1)
 if p.call('getGameState').get('self'):break
p.call('stopPathing');recording=False
try:
 commands=['gamemode spectator tester1','forceload add 1838 458 1856 462','kill @e[tag=hunt_tunnel]',
 'kill @e[type=item,x=1838,y=-62,z=458,dx=18,dy=5,dz=4]',
 'fill 1838 -62 458 1856 -58 462 bedrock','fill 1840 -60 460 1854 -59 460 air',
 'clear tester1','tp tester1 1842.5 -60 460.5 -90 0','gamemode survival tester1',
 'give tester1 iron_pickaxe','give tester1 stone_sword','effect give tester1 instant_health 1 5 true','effect give tester1 saturation 1 5 true',
 'summon chicken 1852.5 -60 460.5 {NoAI:1b,Tags:["hunt_tunnel"]}']
 if not args.open:commands.insert(6,'fill 1844 -60 460 1846 -59 460 stone')
 for command in commands:r.cmd(command)
 time.sleep(2);assert p.call('selectHotbar',0)
 before=p.call('getGameState')['self'];assert before['held']=='minecraft:iron_pickaxe' and before['onGround'] and abs(float(before['pos'].split(',')[0])-1842.5)<.1,before
 rec_start(65);recording=True
 code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters
import time,json
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;a=j.adris.altoclef.AltoClef.getInstance();api=g.entry_point
cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('net.minecraft.class_1428');t=j.adris.altoclef.tasks.entity.KillEntitiesTask(cs)
cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task');m=a.getClass().getMethod('runUserTask',cs);h=j.java.lang.invoke.MethodHandles.publicLookup().unreflect(m).bindTo(a).bindTo(t);run=j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable'),h);j.net.minecraft.class_310.method_1551().execute(run)
rows=[];start=time.monotonic();e=j.kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR
while time.monotonic()-start<40:
 p=a.getPlayer();rows.append({'t':round(time.monotonic()-start,3),'breaking':e.isBreakingNow(),'placing':e.isPlacingNow(),'queue':j.kaptainwutax.tungsten.path.movements.MovementQueue.remainingNeedsBlockWork(a.getWorld()),'hand':str(p.method_6047().method_7909()),'x':p.method_23317(),'hp':p.method_6032(),'fps':api.getPerfStats().get('fps')});time.sleep(.08)
print(json.dumps(rows))
'''
 q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,timeout=55)
 if q.returncode:raise RuntimeError(q.stderr)
 rows=json.loads(q.stdout);alive='passed' in r.cmd('execute if entity @e[tag=hunt_tunnel]').lower()
 collisions=sum(v['breaking'] and not v['queue'] and v['hand']=='minecraft:stone_sword' for v in rows)
 # The executor also holds a queue during its post-dig settling interval.
 # Count interrupted pickaxe episodes separately from an inherited sword at startup.
 switches=sum(prev['breaking'] and cur['breaking'] and prev['hand']=='minecraft:iron_pickaxe' and cur['hand']=='minecraft:stone_sword' for prev,cur in zip(rows,rows[1:]))
 mined=sum(v['breaking'] for v in rows);advanced=max(v['x'] for v in rows)>1847
 inventory=p.call('getInventoryFull');cobble=sum(v.get('count',0) for v in inventory.get('slots',[]) if v.get('item')=='minecraft:cobblestone')
 passed=(collisions>5 and mined>10) if args.expect_bug else (not alive and min(v['hp'] for v in rows)==20 and ((args.open and any(v['hand']=='minecraft:stone_sword' for v in rows)) or (mined>0 and advanced and switches==0 and cobble==6)))
 result={'before':before,'samples':rows,'alive':alive,'mining_samples':mined,'sword_during_executor_mining':collisions,'pick_to_sword_during_continuous_queue':switches,'advanced':advanced,'pass':passed,'expected_bug':args.expect_bug,'open':args.open,'final':p.call('getGameState'),'inventory':inventory,'cobblestone':cobble}
 (root/f'{args.tag}.json').write_text(json.dumps(result,indent=2));p.screenshot(str(root/f'{args.tag}.png'));print({k:v for k,v in result.items() if k not in ('samples','inventory')},flush=True)
 if not passed:raise RuntimeError('hunt tunnel gate failed')
finally:
 p.call('stopPathing')
 if recording:rec_stop(str(root/f'{args.tag}.mp4'))
 r.cmd('kill @e[tag=hunt_tunnel]');r.cmd('forceload remove 1838 458 1856 462')
