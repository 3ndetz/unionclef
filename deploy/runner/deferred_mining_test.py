#!/usr/bin/env python3
"""Exercise a physics approach followed by queued mining on the flat stand.

The far start must replay its approach before mining; --immediate covers the
already-at-wall shortcut. --expect-bug requires the old out-of-reach abort.
The raw physics entry needs resumeUsesSearchTarget to continue beyond its dig;
this fixture enables and restores it. Each run records executor state and video.
"""
import argparse,json,subprocess,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser(description=__doc__)
ap.add_argument('--tag',required=True);ap.add_argument('--output-dir',type=Path,required=True)
ap.add_argument('--slow',action='store_true',help='Two obsidian blocks with a diamond pickaxe');ap.add_argument('--connect',action='store_true');ap.add_argument('--immediate',action='store_true');ap.add_argument('--expect-bug',action='store_true')
a=ap.parse_args();root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True)
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server');recording=False
if a.connect:
 p.call('ConnectToServer','test-server')
 for _ in range(45):
  time.sleep(1)
  if p.call('getGameState').get('self'):break
 else:raise RuntimeError('join failed')
old={k:p.call('tungstenSetting',k,'').split('=',1)[1] for k in ['allowBreak','allowPlace','resumeUsesSearchTarget']}
try:
 p.call('stopPathing');p.call('tungstenSetting','allowBreak','true');p.call('tungstenSetting','allowPlace','false');p.call('tungstenSetting','resumeUsesSearchTarget','true')
 x=1908.5 if a.immediate else 1902.5
 for cmd in ['gamemode spectator tester1','forceload add 1900 498 1916 502',
  'fill 1900 -62 498 1916 -58 502 bedrock','fill 1901 -60 500 1915 -59 500 air',
  'fill 1909 -60 500 1909 -59 500 '+('obsidian' if a.slow else 'stone'),'clear tester1','give tester1 '+('diamond_pickaxe' if a.slow else 'iron_pickaxe'),
  f'tp tester1 {x} -60 500.5 -90 0','gamemode survival tester1',
  'effect give tester1 instant_health 1 5 true','effect give tester1 saturation 1 5 true']:r.cmd(cmd)
 time.sleep(1);p.call('selectHotbar',0)
 before=p.call('getGameState')['self'];assert before['onGround'] and before['hp']==20 and abs(float(before['pos'].split(',')[0])-x)<.1,before
 rec_start(55);recording=True
 code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters
import json,time
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;k=j.kaptainwutax.tungsten;a=j.adris.altoclef.AltoClef.getInstance();e=k.TungstenModDataContainer.EXECUTOR
initial=k.path.PathExecutor.breakAbortReach
timeouts=k.path.PathExecutor.breakAbortTimeout
try: sizedField=j.java.lang.Class.forName('kaptainwutax.tungsten.path.PathExecutor').getField('breakBudgetSized')
except Exception:sizedField=None
sized=sizedField.getInt(None) if sizedField is not None else 0
assert k.TungstenModDataContainer.PATHFINDER.find(a.getWorld(),j.net.minecraft.class_243(1913.5,-60.0,500.5),a.getPlayer())
rows=[];start=time.monotonic()
while time.monotonic()-start<35:
 p=a.getPlayer();rows.append({'t':round(time.monotonic()-start,3),'x':p.method_23317(),'y':p.method_23318(),'hp':p.method_6032(),'queued':e.isBreakingNow(),'mining':e.isMiningNow(),'path':e.pathSizeNow(),'tick':e.tickIndexNow(),'reachAborts':k.path.PathExecutor.breakAbortReach-initial,'timeouts':k.path.PathExecutor.breakAbortTimeout-timeouts,'budgetCells':sizedField.getInt(None)-sized if sizedField is not None else 0})
 if rows[-1]['x']>1912 and rows[-1]['hp']==20 and not rows[-1]['queued']:break
 if rows[-1]['hp']<=0 or rows[-1]['reachAborts']>0:break
 time.sleep(.1)
print(json.dumps(rows))
'''
 q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,timeout=45)
 if q.returncode:raise RuntimeError(q.stderr)
 rows=json.loads(q.stdout);opened='passed' in r.cmd('execute if block 1909 -60 500 air if block 1909 -59 500 air').lower()
 passed=rows[-1]['reachAborts']>0 if a.expect_bug else (opened and max(v['x'] for v in rows)>1912 and min(v['hp'] for v in rows)==20 and rows[-1]['reachAborts']==0 and rows[-1]['timeouts']==0 and rows[-1]['budgetCells']>=2)
 result={'pass':passed,'immediate':a.immediate,'slow':a.slow,'expected_bug':a.expect_bug,'before':before,'opened':opened,'samples':rows}
 (root/f'{a.tag}.json').write_text(json.dumps(result,indent=2));p.screenshot(str(root/f'{a.tag}.png'))
 print({k:v for k,v in result.items() if k!='samples'},rows[-1],flush=True)
 if not passed:raise RuntimeError('deferred mining gate failed')
finally:
 p.call('stopPathing')
 if recording:rec_stop(str(root/f'{a.tag}.mp4'))
 for k,v in old.items():p.call('tungstenSetting',k,v)
 r.cmd('forceload remove 1900 498 1916 502')
