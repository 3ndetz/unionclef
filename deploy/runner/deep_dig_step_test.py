#!/usr/bin/env python3
"""Descend beside a ledge before mining a lower floor, preserving the launch support."""
import argparse,json,subprocess,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--tag',required=True);ap.add_argument('--output-dir',type=Path,required=True);ap.add_argument('--connect',action='store_true');ap.add_argument('--expect-bug',action='store_true');ap.add_argument('--offset',type=float,default=-.2);a=ap.parse_args()
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server');root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True);recording=False
if a.connect:
 p.call('ConnectToServer','test-server')
 for _ in range(45):
  time.sleep(1)
  if 'tester1' in r.cmd('list'):break
 else:raise RuntimeError('join failed')
old={k:p.call('tungstenSetting',k,'').split('=',1)[1] for k in ['allowBreak','allowPlace']}
try:
 p.call('stopPathing');p.call('tungstenSetting','allowBreak','true');p.call('tungstenSetting','allowPlace','false')
 for cmd in ['gamemode spectator tester1','forceload add 2020 698 2026 702','fill 2020 -63 698 2026 -54 702 bedrock',
 'fill 2021 -57 700 2022 -55 700 air','fill 2023 -60 700 2023 -55 700 air','fill 2023 -60 700 2023 -56 700 stone','setblock 2020 -56 700 glowstone',
 'kill @e[type=item,x=2023,y=-58,z=700,distance=..8]','clear tester1','give tester1 iron_pickaxe',f'tp tester1 {2022.5+a.offset} -57 700.5 -90 0','gamemode survival tester1','effect give tester1 instant_health 1 5 true','effect give tester1 saturation 1 5 true']:r.cmd(cmd)
 time.sleep(1);p.call('selectHotbar',0);before=p.call('getGameState')['self'];assert before['onGround'] and before['hp']==20 and abs(float(before['pos'].split(',')[0])-(2022.5+a.offset))<.05,before
 rec_start(40);recording=True
 code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters,get_field
import json,time
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;k=j.kaptainwutax.tungsten;a=j.adris.altoclef.AltoClef.getInstance();goal=j.net.minecraft.class_2338(2023,-60,700)
res=k.path.fast.FastPlanner.plan(a.getWorld(),a.getPlayer().method_24515(),goal,500,None,True);plan=[]
for wp in get_field(res,'path'):
 plan.append({'pos':str(get_field(wp,'pos')),'break':[str(b) for b in get_field(wp,'toBreak')] if get_field(wp,'toBreak') else []})
initial=k.path.PathExecutor.breakOccludedUnclearable
k.task.FastNavigator.startExact(goal);rows=[];start=time.monotonic()
while time.monotonic()-start<25:
 p=a.getPlayer();rows.append({'t':round(time.monotonic()-start,3),'x':p.method_23317(),'y':p.method_23318(),'hp':p.method_6032(),'occluded':k.path.PathExecutor.breakOccludedUnclearable-initial})
 if rows[-1]['y'] < -59.9 or rows[-1]['hp']<=0:break
 time.sleep(.15)
print(json.dumps({'plan':plan,'samples':rows}))
'''
 q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,timeout=35)
 if q.returncode:raise RuntimeError(q.stderr)
 d=json.loads(q.stdout);rows=d['samples'];opened='passed' in r.cmd('execute if block 2023 -60 700 air').lower();floor='passed' in r.cmd('execute if block 2022 -58 700 bedrock').lower()
 passed=(rows[-1]['occluded']>0 and not opened) if a.expect_bug else (opened and floor and rows[-1]['y'] < -59.9 and min(x['hp'] for x in rows)==20 and rows[-1]['occluded']==0)
 d.update({'pass':passed,'expected_bug':a.expect_bug,'opened':opened,'launch_support_preserved':floor,'before':before,'offset':a.offset});(root/f'{a.tag}.json').write_text(json.dumps(d,indent=2));p.screenshot(str(root/f'{a.tag}.png'));print({k:v for k,v in d.items() if k!='samples'},rows[-1],flush=True)
 if not passed:raise RuntimeError('deep dig step failed')
finally:
 p.call('stopPathing')
 if recording:rec_stop(str(root/f'{a.tag}.mp4'))
 for k,v in old.items():p.call('tungstenSetting',k,v)
 r.cmd('forceload remove 2020 698 2026 702')
