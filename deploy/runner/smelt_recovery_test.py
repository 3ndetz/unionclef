#!/usr/bin/env python3
"""Check furnace output recovery and remaining ingredient accounting on the flat stand.

--ready starts with six ingots and the seventh in a closed furnace.
--mine-ore starts with six ingots and one reachable ore block; no raw iron is given.
--supply-drop substitutes one dropped raw iron for the ore, isolating acquisition
from mining. Either acquisition mode with --loaded also starts with one raw iron inside the furnace and only
five ingots in inventory, checking that furnace input is counted exactly once.
The default starts with six ingots, one raw iron and fuel. --batch 7 --fuel-count 4
reproduces a full seven-ingot batch with surplus fuel, as in the survival save. Each trial requires
seven ingots in inventory, an empty cursor and normal task completion.
The disposable fixture is restored every trial; the natural save is untouched.
"""
import argparse,json,subprocess,sys,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
ap=argparse.ArgumentParser();ap.add_argument('--output-dir',type=Path,required=True);ap.add_argument('--record',action='store_true');ap.add_argument('--ready',action='store_true');ap.add_argument('--mine-ore',action='store_true');ap.add_argument('--supply-drop',action='store_true');ap.add_argument('--loaded',action='store_true');ap.add_argument('--batch',type=int,default=1);ap.add_argument('--fuel-count',type=int,default=1);ap.add_argument('--repeat',type=int,default=1);ap.add_argument('--tag',required=True);ap.add_argument('--connected',action='store_true');a=ap.parse_args()
if not 1 <= a.batch <= 7 or a.fuel_count < 1:ap.error('batch must be 1..7 and fuel-count positive')
if a.repeat < 1 or a.repeat > 16:ap.error('--repeat must be between 1 and 16')
if a.loaded and not (a.mine_ore or a.supply_drop):ap.error('--loaded requires --mine-ore or --supply-drop')
if sum([a.ready,a.mine_ore,a.supply_drop])>1:ap.error('--ready, --mine-ore and --supply-drop are mutually exclusive')
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server');root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True)
if not a.connected:
 p.call('ConnectToServer','test-server');time.sleep(3)
 for _ in range(40):
  if p.call('getGameState').get('self'):break
  time.sleep(1)
 else:raise RuntimeError('join failed')
code=r'''
import json,time
from py4j.java_gateway import JavaGateway,GatewayParameters
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;a=j.adris.altoclef.AltoClef.getInstance()
a.getBehaviour().resetAvoidBlockBreakingExtra();a.getBehaviour().resetAvoidBlockPlacingExtra()
o=j.adris.altoclef.util.ItemTarget('iron_ingot',7);m=j.adris.altoclef.util.ItemTarget('raw_iron',7)
t=j.adris.altoclef.tasks.container.SmeltInFurnaceTask(j.adris.altoclef.util.SmeltTarget(o,m,g.new_array(j.net.minecraft.class_1792,0)))
f=t.getClass().getDeclaredField('_doTask');f.setAccessible(True);d=f.get(t)
f=d.getClass().getDeclaredField('furnaceCache');f.setAccessible(True);c=f.get(d)
# Observe raw inventory stacks: lazy tracker getters mutate caches and must not
# be called concurrently from the Py4J sampling thread.
# Run task lifecycle mutations on the Minecraft thread, like in-game commands.
classes=g.new_array(j.java.lang.Class,1);classes[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task')
method=a.getClass().getMethod('runUserTask',classes)
handle=j.java.lang.invoke.MethodHandles.publicLookup().unreflect(method).bindTo(a).bindTo(t)
runnable=j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable'),handle)
assert sum(stack.method_7947() for stack in a.getItemStorage().getItemStacksPlayerInventory(False) if o.matches(stack.method_7909())) < 7, 'fixture already meets the target'
j.net.minecraft.class_310.method_1551().execute(runnable)
start=time.monotonic();last=None
while time.monotonic()-start<RUN_SECONDS:
 time.sleep(.1);chain=a.getTaskRunner().getCurrentTaskChain();row={'inventory':sum(stack.method_7947() for stack in a.getItemStorage().getItemStacksPlayerInventory(False) if o.matches(stack.method_7909())),'fps':g.entry_point.getPerfStats().get('fps'),'task':str(d),'chain':'; '.join(str(task) for task in chain.getTasks()) if chain is not None else ''}
 for f in c.getClass().getDeclaredFields():f.setAccessible(True);row[f.getName()]=str(f.get(c))
 if row!=last:print(json.dumps({'time':round(time.monotonic()-start,2),**row}),flush=True);last=row
 if row['inventory']>=7 and j.adris.altoclef.util.helpers.StorageHelper.getItemStackInCursorSlot().method_7960() and t.stopped():print('PASS',flush=True);break
else:print('FAIL',flush=True)
'''
duration=max(35,a.batch*12+10)
code=code.replace('RUN_SECONDS',str(duration))
from gamer_smoke import rec_start,rec_stop
if a.record:rec_start(a.repeat*(duration+15)+20)
try:
 for i in range(a.repeat):
  x=1280+16*i
  p.call('stopPathing');time.sleep(.5)
  r.cmd(f'forceload add {x-2} 298 {x+5} 302')
  r.cmd(f'fill {x-2} -61 298 {x+5} -56 302 bedrock')
  r.cmd(f'fill {x-1} -60 299 {x+4} -58 301 air')
  r.cmd(f'setblock {x+1} -57 300 glowstone')
  r.cmd(f'setblock {x+2} -60 300 furnace[facing=west]'+('{Items:[{Slot:2b,id:"minecraft:iron_ingot",count:1}]}' if a.ready else ''))
  r.cmd('clear tester1');r.cmd('gamemode survival tester1')
  r.cmd('effect give tester1 instant_health 1 5 true');r.cmd('effect give tester1 saturation 1 5 true')
  ingots=5 if a.loaded else (6 if a.ready or a.mine_ore or a.supply_drop else 7-a.batch)
  if ingots:r.cmd(f'give tester1 iron_ingot {ingots}')
  if a.loaded:r.cmd(f'data merge block {x+2} -60 300 '+ '{cooking_total_time:200s,Items:[{Slot:0b,id:"minecraft:raw_iron",count:1},{Slot:1b,id:"minecraft:coal",count:1}]}')
  if not a.ready:
   r.cmd(f'give tester1 coal {a.fuel_count}')
   if a.supply_drop:
    r.cmd(f'summon item {x-0.5} -60 300.5 '+ '{Item:{id:"minecraft:raw_iron",count:1},PickupDelay:60s}')
   elif a.mine_ore:
    r.cmd(f'setblock {x} -60 299 iron_ore');r.cmd('give tester1 iron_pickaxe')
   else:r.cmd(f'give tester1 raw_iron {a.batch}')
  r.cmd(f'tp tester1 {x+0.5} -60 300.5 -90 0');time.sleep(1)
  q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,timeout=duration+15,check=True);print(i+1,q.stdout,q.stderr,flush=True)
  (root/f'{a.tag}-{i+1}.log').write_text(q.stdout+q.stderr)
  p.call('stopPathing')
  if 'PASS' not in q.stdout:raise RuntimeError('smelting trial failed')
  if a.supply_drop and 'Getting Materials' not in q.stdout:raise RuntimeError('acquisition was not exercised')
finally:
 p.call('stopPathing')
 for i in range(a.repeat):r.cmd(f'forceload remove {1278+16*i} 298 {1285+16*i} 302')
 if a.record:rec_stop(str(root/f'{a.tag}.mp4'))
