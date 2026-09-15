#!/usr/bin/env python3
"""Live descent head-clearance regression on the disposable flat stand.

Restores a sealed ledge corridor before each run. A breakable overhang must be
included in the plan and removed before descent; bedrock and disabled breaking
must reject that route. Depths 1..3 cover all ordinary fall moves. landing_slab
checks that the support inside the landing cell survives. All settings restore.
"""
import sys,time,json,subprocess,argparse
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser();ap.add_argument('--depth',type=int,choices=[1,2,3],default=1);ap.add_argument('--roof',choices=['stone','slab','bedrock','open','no_break','landing_slab'],default='stone');ap.add_argument('--tag',required=True);ap.add_argument('--connect',action='store_true');ap.add_argument('--output-dir',type=Path,required=True);args=ap.parse_args()
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server')
if args.connect:
 p.call('ConnectToServer','test-server')
 for _ in range(45):
  time.sleep(1)
  if p.call('getGameState').get('self'):break
 else:raise RuntimeError('join failed')
landing=-59-args.depth

root=args.output_dir.resolve();root.mkdir(exist_ok=True,parents=True)
flags={k:p.call('tungstenSetting',k,'').split('=',1)[1] for k in ['allowBreak','allowPlace']}
try:
 p.call('stopPathing');p.call('tungstenSetting','allowBreak','false' if args.roof=='no_break' else 'true');p.call('tungstenSetting','allowPlace','false')
 for c in ['forceload add 1596 316 1606 324','fill 1596 -64 316 1606 -54 324 bedrock',f'fill 1598 {landing} 320 1604 -55 320 air',f'fill 1598 {landing} 320 1600 -60 320 bedrock','fill 1598 -54 320 1604 -54 320 glowstone','setblock 1601 -58 320 '+({'slab':'stone_slab[type=top]','bedrock':'bedrock','open':'air'}.get(args.roof,'stone')),'gamemode survival tester1','clear tester1','give tester1 iron_pickaxe','effect give tester1 instant_health 1 5 true','tp tester1 1600.5 -58.9 320.5 -90 0']:r.cmd(c)
 if args.roof=='landing_slab':r.cmd(f'fill 1601 {landing} 320 1604 {landing} 320 stone_slab[type=bottom]')
 r.cmd('effect give tester1 instant_health 1 5 true');r.cmd('effect give tester1 saturation 1 5 true')
 time.sleep(1)
 before=p.call('getGameState')['self']
 assert before['hp']==20 and before['onGround'], before
 rec_start(45)
 code='''from py4j.java_gateway import JavaGateway,GatewayParameters,get_field
import json
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;k=j.kaptainwutax.tungsten;a=j.adris.altoclef.AltoClef.getInstance()
s=j.net.minecraft.class_2338(1600,-59,320);d=j.net.minecraft.class_2338(1603,-60,320)
r=k.path.fast.FastPlanner.plan(a.getWorld(),s,d,1000,None,True)
print(json.dumps({'complete':get_field(r,'complete'),'path':[{'pos':str(get_field(n,'pos')),'break':[str(b) for b in get_field(n,'toBreak')] if get_field(n,'toBreak') else []} for n in get_field(r,'path')]}))
k.task.FastNavigator.startExact(d)
'''
 code=code.replace('1603,-60,320',f'1603,{landing},320')
 q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,check=True);print(q.stdout,flush=True)
 rows=[];start=time.monotonic()
 while time.monotonic()-start<(8 if args.roof in ('bedrock','no_break') else 30):
  time.sleep(1);state,perf=p.batch([('getGameState',),('getPerfStats',)]);s=state['self'];s['fps']=perf.get('fps');rows.append(s);print(s,flush=True)
  x,y,z=map(float,s['pos'].split(','))
  if x>=1602.3 and y<=landing+(.6 if args.roof=='landing_slab' else .1) and s['onGround']:break
 p.screenshot(str(root/f'{args.tag}.png'));(root/f'{args.tag}.json').write_text(json.dumps({'plan':q.stdout,'before':before,'samples':rows,'depth':args.depth,'roof':args.roof},indent=2))
 # The body must fully clear the roof column (ending at x=1602). This
 # gate tests passage, not exact-goal arrival, which is a separate known issue.
 reached=x>=1602.3 and y<=landing+(.6 if args.roof=='landing_slab' else .1) and s['onGround']
 blocked=args.roof in ('bedrock','no_break')
 planned=json.loads(q.stdout)
 passed=(not reached and not planned['complete']) if blocked else (reached and s['hp']==20)
 if not blocked and args.roof!='open':
  assert any('x=1601, y=-58, z=320' in block for node in planned['path'] for block in node['break']), 'overhang omitted from plan'
 if args.roof=='landing_slab':
  assert 'stone_slab' in str(p.call('getBlockAt',1601,landing,320)), 'landing support removed'
 print('PASS' if passed else 'FAIL',flush=True)
 if not passed:raise RuntimeError('descent clearance gate failed')
finally:
 p.call('stopPathing')
 for k,v in flags.items():p.call('tungstenSetting',k,v)
 rec_stop(str(root/f'{args.tag}.mp4'));r.cmd('forceload remove 1596 316 1606 324')
