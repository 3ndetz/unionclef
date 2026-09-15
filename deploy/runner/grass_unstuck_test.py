#!/usr/bin/env python3
"""Fleeing must resume after a temporary obstruction opens beside passable grass."""
import argparse,json,subprocess,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--tag',required=True);ap.add_argument('--output-dir',type=Path,required=True);ap.add_argument('--expect-bug',action='store_true');ap.add_argument('--plant',choices=['tall_grass','dandelion','air'],default='tall_grass');a=ap.parse_args()
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server');root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True);recording=False
p.call('ConnectToServer','test-server')
for _ in range(45):
 time.sleep(1)
 if 'tester1' in r.cmd('list'):break
else:raise RuntimeError('connection failed')
old=p.call('tungstenSetting','allowPlace','').split('=',1)[1]
try:
 p.call('stopPathing');p.call('tungstenSetting','allowPlace','false')
 for c in ['gamemode spectator tester1','forceload add 2160 818 2176 822','fill 2160 -62 818 2176 -56 822 bedrock','fill 2162 -60 820 2174 -58 820 air','fill 2163 -60 820 2174 -60 820 bedrock','fill 2163 -59 820 2163 -58 820 bedrock','setblock 2162 -60 820 moss_block','setblock 2162 -61 820 moss_block','setblock 2162 -60 820 tall_grass[half=lower]','setblock 2162 -59 820 tall_grass[half=upper]','clear tester1','tp tester1 2162.5 -60 820.5 -90 0','gamemode survival tester1','effect give tester1 instant_health 1 5 true','effect give tester1 saturation 1 5 true']:r.cmd(c)
 if a.plant!='tall_grass':
  r.cmd('setblock 2162 -59 820 air');r.cmd('setblock 2162 -60 820 '+a.plant)
 time.sleep(1);rec_start(60);recording=True
 code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters
j=(g:=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True))).jvm;a=j.adris.altoclef.AltoClef.getInstance()
ps=g.new_array(j.net.minecraft.class_2338,1);ps[0]=j.net.minecraft.class_2338(2160,-60,820);t=j.adris.altoclef.tasks.movement.RunAwayFromPositionTask(8.0,j.java.lang.Integer(-59),ps)
cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task');m=a.getClass().getMethod('runUserTask',cs);h=j.java.lang.invoke.MethodHandles.publicLookup().unreflect(m).bindTo(a).bindTo(t);j.net.minecraft.class_310.method_1551().execute(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable'),h))
'''
 subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],check=True)
 rows=[];start=time.monotonic();opened=False
 while time.monotonic()-start<40:
  elapsed=time.monotonic()-start
  if elapsed>=15 and not opened:r.cmd('fill 2163 -59 820 2163 -58 820 air');opened=True
  s=p.call('getGameState')['self'];chain=p.call('getTaskChainString');rows.append({'t':round(elapsed,2),'state':s,'chain':chain,'opened':opened})
  if opened and float(s['pos'].split(',')[0])>=2168:break
  time.sleep(.5)
 moved=float(rows[-1]['state']['pos'].split(',')[0])>=2168
 shimmy=any('Shimmying' in x['chain'] for x in rows)
 passed=(shimmy and not moved) if a.expect_bug else (moved and not shimmy and min(x['state']['hp'] for x in rows)==20)
 (root/f'{a.tag}.json').write_text(json.dumps({'pass':passed,'expected_bug':a.expect_bug,'shimmy':shimmy,'samples':rows},indent=2));p.screenshot(str(root/f'{a.tag}.png'));print({'pass':passed,'shimmy':shimmy,'last':rows[-1]},flush=True)
 if not passed:raise RuntimeError('grass recovery gate failed')
finally:
 p.call('stopPathing');p.call('tungstenSetting','allowPlace',old)
 if recording:rec_stop(str(root/f'{a.tag}.mp4'))
 r.cmd('forceload remove 2160 818 2176 822')
