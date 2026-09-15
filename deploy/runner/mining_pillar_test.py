#!/usr/bin/env python3
"""Mine an elevated ore while the approach must pillar inside a narrow shaft."""
import argparse,json,subprocess,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--tag',required=True);ap.add_argument('--output-dir',type=Path,required=True);ap.add_argument('--connect',action='store_true');ap.add_argument('--expect-bug',action='store_true');a=ap.parse_args()
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server');root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True);recording=False
if a.connect:
 p.call('ConnectToServer','test-server')
 for _ in range(45):
  time.sleep(1)
  if 'tester1' in r.cmd('list'):break
 else:raise RuntimeError('join failed')
old={k:p.call('tungstenSetting',k,'').split('=',1)[1] for k in ['allowBreak','allowPlace']}
try:
 p.call('stopPathing');p.call('tungstenSetting','allowBreak','true');p.call('tungstenSetting','allowPlace','true')
 for c in ['gamemode spectator tester1','forceload add 2060 738 2067 742','fill 2060 -62 738 2067 -51 742 bedrock',
 'fill 2063 -60 740 2063 -52 740 air','fill 2064 -57 740 2064 -54 740 coal_ore','setblock 2065 -56 740 iron_ore',
 'setblock 2062 -55 740 glowstone','kill @e[type=item,x=2063,y=-58,z=740,distance=..8]','clear tester1','give tester1 iron_pickaxe','give tester1 dirt 32',
 'tp tester1 2063.5 -60 740.3 -90 0','gamemode survival tester1','effect give tester1 instant_health 1 5 true','effect give tester1 saturation 1 5 true']:r.cmd(c)
 time.sleep(1);p.call('selectHotbar',0);rec_start(55);recording=True
 code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters,get_field
import time,json
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;a=j.adris.altoclef.AltoClef.getInstance();k=j.kaptainwutax.tungsten;target=j.net.minecraft.class_2338(2065,-56,740);t=j.adris.altoclef.tasks.construction.DestroyBlockTask(target)
# Fixture restoration invalidates pending confirmations from the previous arena.
# The task runner is stopped here, before the new task is scheduled.
a.getBehaviour().resetAvoidBlockBreakingExtra();a.getBehaviour().resetAvoidBlockPlacingExtra()
f=a.getTaskRunner().getClass().getDeclaredField('chains');f.setAccessible(True)
for chain in f.get(a.getTaskRunner()):
 if str(chain.getClass().getName())=='adris.altoclef.chains.WorldSurvivalChain':
  for name in ['_lastPlacedBlock','_lastBrokenBlock','_isAvoidingBlockPlace']:
   field=chain.getClass().getDeclaredField(name);field.setAccessible(True);field.setBoolean(chain,False)
cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task');m=a.getClass().getMethod('runUserTask',cs);h=j.java.lang.invoke.MethodHandles.publicLookup().unreflect(m).bindTo(a).bindTo(t);run=j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable'),h);j.net.minecraft.class_310.method_1551().execute(run)
try:
 yf=j.java.lang.Class.forName('adris.altoclef.tasks.construction.DestroyBlockTask').getField('dbBuilderYield');y0=yf.getInt(None)
except Exception:yf=None;y0=0
initial=j.adris.altoclef.chains.PlayerInteractionFixChain.fixToolSwaps;rows=[];start=time.monotonic()
while time.monotonic()-start<35:
 p=a.getPlayer();rows.append({'t':round(time.monotonic()-start,3),'y':p.method_23318(),'hp':p.method_6032(),'pillar':k.task.PillarTask.isActive(),'hand':str(p.method_6047().method_7909()),'swaps':j.adris.altoclef.chains.PlayerInteractionFixChain.fixToolSwaps-initial,'yielded':yf.getInt(None)-y0 if yf is not None else 0,'pitch':p.method_36455(),'target':str(a.getWorld().method_8320(target))})
 if rows[-1]['target']=='Block{minecraft:air}' or rows[-1]['hp']<=0:break
 time.sleep(.1)
print(json.dumps(rows))
'''
 q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,timeout=45)
 if q.returncode:raise RuntimeError(q.stderr)
 rows=json.loads(q.stdout);done=rows[-1]['target']=='Block{minecraft:air}';pillar=any(s['pillar'] for s in rows)
 passed=(not done and pillar and rows[-1]['swaps']>10) if a.expect_bug else (done and pillar and min(s['hp'] for s in rows)==20 and rows[-1]['swaps']==0 and rows[-1]['yielded']>0)
 d={'pass':passed,'expected_bug':a.expect_bug,'pillar_observed':pillar,'target_mined':done,'samples':rows};(root/f'{a.tag}.json').write_text(json.dumps(d,indent=2));p.screenshot(str(root/f'{a.tag}.png'));print({k:v for k,v in d.items() if k!='samples'},rows[-1],flush=True)
 if not passed:raise RuntimeError('mining pillar gate failed')
finally:
 p.call('stopPathing')
 if recording:rec_stop(str(root/f'{a.tag}.mp4'))
 for k,v in old.items():p.call('tungstenSetting',k,v)
 r.cmd('forceload remove 2060 738 2067 742')
