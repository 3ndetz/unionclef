#!/usr/bin/env python3
"""Live approach-radius and liquid-collection regressions on the flat stand.

The near case approaches a block twenty cells away; at_goal checks radius zero.
Water/lava cases begin with only an empty bucket and require the filled item.
--expect-bug reproduces the historical MAX_VALUE-radius freeze on the near case.
The fixture waits for teleport and grounding before task startup. Videos, raw
inventory snapshots, health and FPS are recorded; no lazy inventory counts are
queried on the sampling thread. These are functional gates, not speed estimates.
"""
import argparse,json,subprocess,sys,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser();ap.add_argument('--case',choices=['near','at_goal','water','lava'],default='near');ap.add_argument('--tag',required=True);ap.add_argument('--reuse',action='store_true');ap.add_argument('--expect-bug',action='store_true');ap.add_argument('--output-dir',type=Path,required=True);a=ap.parse_args()
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server');root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True)
if not a.reuse:p.call('ConnectToServer','test-server')
for _ in range(45):
 time.sleep(1)
 if p.call('getGameState').get('self'):break
else:raise RuntimeError('not connected')
p.call('stopPathing');recording=False
try:
 for c in ['gamemode spectator tester1','forceload add 1800 356 1830 364','fill 1800 -62 356 1830 -55 364 air','fill 1800 -62 356 1830 -61 364 bedrock','clear tester1',('tp tester1 1822.5 -60 360.5 -90 0' if a.case=='at_goal' else 'tp tester1 1802.5 -60 360.5 -90 0'),'gamemode survival tester1','effect give tester1 instant_health 1 5 true','effect give tester1 saturation 1 5 true']:r.cmd(c)
 if a.case not in ('near','at_goal'):
  r.cmd('setblock 1822 -61 360 '+a.case);r.cmd('give tester1 bucket')
 for _ in range(15):
  time.sleep(.5);before=p.call('getGameState')['self'];bx,by,bz=map(float,before['pos'].split(','))
  if before['onGround'] and abs(bx-(1822.5 if a.case=='at_goal' else 1802.5))<.1 and abs(by+60)<.1:break
 else:raise RuntimeError('fixture start not settled: '+str(before))
 r.cmd('effect give tester1 instant_health 1 5 true');time.sleep(.5)
 before=p.call('getGameState')['self'];assert before['hp']==20 and before['onGround'],before
 rec_start(65);recording=True
 code='''from py4j.java_gateway import JavaGateway,GatewayParameters
import time,json
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;a=j.adris.altoclef.AltoClef.getInstance()
TASK
classes=g.new_array(j.java.lang.Class,1);classes[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task')
m=a.getClass().getMethod('runUserTask',classes);h=j.java.lang.invoke.MethodHandles.publicLookup().unreflect(m).bindTo(a).bindTo(t)
r=j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable'),h);j.net.minecraft.class_310.method_1551().execute(r)
time.sleep(1)
print('started')
'''
 task="t=j.adris.altoclef.tasks.movement.GetCloseToBlockTask(j.net.minecraft.class_2338(1822,-60,360))" if a.case in ('near','at_goal') else "t=j.adris.altoclef.TaskCatalogue.getItemTask('"+a.case+"_bucket',1)"
 q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code.replace('TASK',task)],capture_output=True,text=True,timeout=20)
 if q.returncode:raise RuntimeError(q.stderr)
 rows=[];start=time.monotonic();passed=False
 while time.monotonic()-start<(9 if a.expect_bug else 45):
  time.sleep(1);state,chain,inv,perf=p.batch([('getGameState',),('getTaskChainString',),('getInventoryFull',),('getPerfStats',)])
  s=state['self'];row={'t':time.monotonic()-start,'self':s,'chain':chain,'inventory':inv,'fps':perf.get('fps')};rows.append(row)
  print(round(row['t'],1),s['pos'],s['hp'],str(chain)[-160:],flush=True)
  x,y,z=map(float,s['pos'].split(','))
  if a.case in ('near','at_goal'):passed=abs(x-1822.5)<1 and abs(z-360.5)<1 and s['hp']==20
  else:passed=any(v.get('item')=='minecraft:'+a.case+'_bucket' and v.get('count',0)>=1 for v in inv.get('slots',[])) and s['hp']==20
  if passed and not a.expect_bug and (a.case!='at_goal' or row['t']>=4):break
 if a.expect_bug:passed=not passed and all('2147483647' in str(v['chain']) for v in rows) and all(v['self']['pos']==before['pos'] for v in rows)
 if a.case=='at_goal':passed=passed and all('within 0 blocks' in str(v['chain']) for v in rows)
 result={'case':a.case,'before':before,'samples':rows,'pass':passed,'expected_bug':a.expect_bug};(root/f'{a.tag}.json').write_text(json.dumps(result,indent=2));p.screenshot(str((root/f'{a.tag}.png').resolve()));print('RESULT',passed,flush=True)
 if not passed:raise RuntimeError('approach gate failed')
finally:
 p.call('stopPathing')
 if recording:rec_stop(str((root/f'{a.tag}.mp4').resolve()))
 r.cmd('forceload remove 1800 356 1830 364')
