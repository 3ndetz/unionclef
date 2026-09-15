#!/usr/bin/env python3
"""Live standing-body water clearance regression on the disposable flat stand.

Checks full-block/slab bank exits, entry with/without a low ceiling, and an
unsupported exit above a waterfall. Both route geometry and physical motion
must pass. Water breathing isolates collision/fall checks from idle drowning:
this is not a survival-policy test. The effect and movement settings restore
in finally. Slab goals use the planner's upper-cell surface representation;
exact arrival to the slab's containing cell is not covered.
"""
import argparse,json,subprocess,sys,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser();ap.add_argument('--case',choices=['gap','bank','roof','bank_slab','entry_open','entry_roof'],required=True);ap.add_argument('--tag',required=True);ap.add_argument('--reuse',action='store_true',help='Reuse an already connected disposable test-server client');ap.add_argument('--output-dir',type=Path,required=True);a=ap.parse_args()
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server');root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True)
if not a.reuse:p.call('ConnectToServer','test-server')
for _ in range(45):
 time.sleep(1)
 if p.call('getGameState').get('self'):break
else:raise RuntimeError('join failed')
p.call('stopPathing')
old={k:p.call('tungstenSetting',k,'').split('=',1)[1] for k in ['allowBreak','allowPlace']}
recording=False
try:
 for k in old:p.call('tungstenSetting',k,'false')
 for c in ['gamemode spectator tester1','forceload add 1696 336 1710 344','fill 1696 -62 336 1710 -45 344 air','fill 1696 -62 336 1710 -62 344 bedrock','fill 1698 -51 339 1702 -51 341 bedrock','fill 1698 -50 339 1702 -47 339 bedrock','fill 1698 -50 341 1702 -47 341 bedrock','fill 1698 -50 340 1698 -47 340 bedrock','fill 1699 -50 340 1702 -49 340 water']:r.cmd(c)
 if a.case=='gap':r.cmd('fill 1706 -50 339 1708 -50 341 bedrock')
 elif a.case=='bank_slab':r.cmd('fill 1703 -50 339 1708 -50 341 stone_slab[type=bottom]')
 else:r.cmd('fill 1703 -49 339 1708 -49 341 bedrock')
 if a.case in ('roof','entry_roof'):r.cmd('fill 1699 -47 340 1702 -47 340 bedrock')
 r.cmd('effect give tester1 water_breathing 90 0 true');r.cmd('clear tester1');r.cmd('tp tester1 1706.5 -48 340.5 90 0' if a.case.startswith('entry') else 'tp tester1 1700.5 -49 340.5 -90 0');r.cmd('gamemode survival tester1');r.cmd('effect give tester1 instant_health 1 5 true');r.cmd('effect give tester1 saturation 1 5 true');time.sleep(2)
 assert p.call('getGameState')['self']['hp']==20
 rec_start(45);recording=True
 goal_y=-49 if a.case in ('gap','bank_slab') or a.case.startswith('entry') else -48
 goal_feet=-49.5 if a.case=='bank_slab' else -48
 code='''from py4j.java_gateway import JavaGateway,GatewayParameters,get_field
import json,re
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;k=j.kaptainwutax.tungsten;a=j.adris.altoclef.AltoClef.getInstance();w=a.getWorld()
s=j.net.minecraft.class_2338(START_X,START_Y,340);d=j.net.minecraft.class_2338(GOAL_X,GOAL_Y,340)
r=k.path.fast.FastPlanner.plan(w,s,d,1500,None,True)
rows=[]
for n in get_field(r,'path'):
 p=get_field(n,'pos');coords=list(map(int,re.search(r'x=(-?\d+), y=(-?\d+), z=(-?\d+)',str(p)).groups()));rows.append({'pos':str(p),'xyz':coords,'block':str(w.method_8320(p)),'below':str(w.method_8320(p.method_10074())),'standable':k.helpers.PlayerFit.standable(w,p),'body_fits':k.helpers.PlayerFit.bodyFits(w,coords[0]+.5,float(coords[1]),coords[2]+.5)})
print(json.dumps({'complete':get_field(r,'complete'),'path':rows}))
k.task.FastNavigator.startExact(d)
'''.replace('GOAL_Y',str(goal_y)).replace('START_X','1706' if a.case.startswith('entry') else '1700').replace('START_Y','-48' if a.case.startswith('entry') else '-49').replace('GOAL_X','1700' if a.case.startswith('entry') else '1706')
 q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,timeout=20)
 if q.returncode:raise RuntimeError(q.stderr)
 plan=json.loads(q.stdout);print('PLAN',plan,flush=True)
 rows=[];start=time.monotonic()
 while time.monotonic()-start<28:
  time.sleep(1);state,perf=p.batch([('getGameState',),('getPerfStats',)]);s=state['self'];s['fps']=perf.get('fps');rows.append(s);print(s,flush=True)
  x,y,z=map(float,s['pos'].split(','))
  if a.case in ('bank','bank_slab') and x>=1705 and y>=goal_feet-.05 and s['onGround']:break
  if a.case=='entry_open' and x<=1701.5 and y<-48.1:break
 invalid=[]
 for before,after in zip(plan['path'],plan['path'][1:]):
  if 'minecraft:water' in before['block'] and 'minecraft:water' not in after['block'] and not after['standable'] and 'minecraft:water' not in after['below']:invalid.append(after)
 invalid_water=[n for n in plan['path'] if 'minecraft:water' in n['block'] and not n['body_fits']]
 stayed_upper=min(float(s['pos'].split(',')[1]) for s in rows)>=-50.1
 passed=not invalid and not invalid_water and (plan['complete'] and x>=1705 and y>=goal_feet-.05 and s['onGround'] and s['hp']==20 if a.case in ('bank','bank_slab') else (plan['complete'] and x<=1701.5 and y<-48.1 and s['hp']==20 if a.case=='entry_open' else not plan['complete'] and stayed_upper and all(q['hp']==20 for q in rows)))
 result={'case':a.case,'plan':plan,'samples':rows,'invalid_exits':invalid,'invalid_water':invalid_water,'stayed_upper':stayed_upper,'pass':passed,'min_y':min(float(s['pos'].split(',')[1]) for s in rows)}
 (root/f'{a.tag}.json').write_text(json.dumps(result,indent=2));p.screenshot(str((root/f'{a.tag}.png').resolve()));print('RESULT',passed,result['min_y'],flush=True)
 if not passed:raise RuntimeError('water exit gate failed')
finally:
 p.call('stopPathing')
 for k,v in old.items():p.call('tungstenSetting',k,v)
 if recording:rec_stop(str((root/f'{a.tag}.mp4').resolve()))
 r.cmd('effect clear tester1 water_breathing');r.cmd('forceload remove 1696 336 1710 344')
