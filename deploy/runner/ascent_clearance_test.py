#!/usr/bin/env python3
"""Live low-ceiling regression on the disposable flat stand.

Builds a sealed corridor at (1104,-60,304). Tests a take-off ceiling (slab/full),
a one-high passage requiring head-block removal, or an open ascent. Each trial
restores geometry and requires a grounded start; settings are restored on exit.
Use --flag false to reproduce the slab clearance defect; --walker false on
one_high reproduces premature final-waypoint completion. Those controls return
nonzero when the bot fails to finish. --connected assumes the client is already
on test-server. Other runs connect explicitly.
"""
import argparse,json,subprocess,sys,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--output-dir',type=Path,required=True);ap.add_argument('--flag',choices=['true','false'],required=True);ap.add_argument('--walker',choices=['true','false'],default='true');ap.add_argument('--case',choices=['slab','full','one_high','open'],default='slab');ap.add_argument('--repeat',type=int,default=3);ap.add_argument('--record',action='store_true');ap.add_argument('--connected',action='store_true');a=ap.parse_args()
root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True);tag=f'ceiling-{a.case}-{a.flag}-arrival-{a.walker}'
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server')
if not a.connected:p.call('ConnectToServer','test-server');time.sleep(3)
for i in range(40):
 if p.call('inGame'):break
 time.sleep(1)
else:raise RuntimeError('connect failed')
flags={'walkerFinalStandArrival':a.walker,'planAscentClearance':a.flag,'allowBreak':'true','allowPlace':'false'}
old={k:p.call('tungstenSetting',k,'').split('=',1)[1] for k in flags}
for k,v in flags.items():print(p.call('tungstenSetting',k,v),flush=True)
snip=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters,get_field
import json
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;k=j.kaptainwutax.tungsten;a=j.adris.altoclef.AltoClef.getInstance()
s=j.net.minecraft.class_2338(1104,-60,304);d=j.net.minecraft.class_2338(1107,-59,304)
# Schedule world access and route mutation on the client, like the live driver.
c=j.net.minecraft.class_310.method_1551();lookup=j.java.lang.invoke.MethodHandles.lookup()
def on_client(handle):
 f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),handle));c.execute(f);return f.get()
def invoke_static(class_name,name,values):
 cls=j.java.lang.Class.forName(class_name)
 method=next(m for m in cls.getMethods() if m.getName()==name and m.getParameterCount()==len(values))
 args=g.new_array(j.java.lang.Object,len(values))
 for i,value in enumerate(values):args[i]=value
 return on_client(j.java.lang.invoke.MethodHandles.insertArguments(lookup.unreflect(method),0,args))
empty=g.new_array(j.java.lang.Class,0)
world=on_client(lookup.unreflect(a.getClass().getMethod('getWorld',empty)).bindTo(a))
r=invoke_static('kaptainwutax.tungsten.path.fast.FastPlanner','plan',[world,s,d,j.java.lang.Long('1000'),None,j.java.lang.Boolean(True)])
print(json.dumps({'complete':get_field(r,'complete'),'path':[{'pos':str(get_field(n,'pos')),'break':[str(b) for b in get_field(n,'toBreak')] if get_field(n,'toBreak') else []} for n in get_field(r,'path')]}))
invoke_static('kaptainwutax.tungsten.task.FastNavigator','startExact',[d])
g.close()
'''
rows=[]
try:
 for i in range(a.repeat):
  p.call('stopPathing');time.sleep(.5)
  r.cmd('forceload add 1100 300 1110 310')
  r.cmd('fill 1100 -61 300 1110 -53 310 bedrock')
  r.cmd('fill 1102 -60 304 1108 -55 304 air')
  r.cmd('fill 1105 -60 304 1108 -60 304 bedrock')
  r.cmd('fill 1102 -54 304 1108 -54 304 glowstone')
  if a.case in ('slab','full'):r.cmd('fill 1102 -58 304 1104 -58 304 '+('stone_slab[type=top]' if a.case=='slab' else 'stone'))
  if a.case=='one_high':r.cmd('fill 1105 -58 304 1108 -58 304 stone')
  r.cmd('gamemode survival tester1');r.cmd('clear tester1')
  if a.case=='one_high':r.cmd('give tester1 iron_pickaxe')
  # Previous food tests can leave zero hunger; clearance must not measure starvation.
  r.cmd('effect clear tester1 hunger');r.cmd('effect give tester1 saturation 1 20 true')
  r.cmd('effect give tester1 instant_health 1 5 true');r.cmd('tp tester1 1104.5 -59.9 304.5 -90 0');time.sleep(1)
  before=p.call('getGameState')['self']
  if not before['onGround']:raise RuntimeError('fixture start is not grounded: '+str(before))
  if a.record and i==0:rec_start(30)
  q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',snip],check=True,capture_output=True,text=True,timeout=30)
  plan=json.loads(q.stdout.strip());print('PLAN',plan,flush=True)
  t0=time.monotonic();samples=[]
  try:
   while time.monotonic()-t0<22:
    time.sleep(.6);s=p.call('getGameState')['self'];samples.append(s)
    x,y,z=map(float,s['pos'].split(','))
    if x>=1107 and y>=-59.05 and s['onGround']:break
   passed=x>=1107 and y>=-59.05 and s['onGround'] and s['hp']==20
   row={'trial':i+1,'case':a.case,'flag':a.flag,'walker':a.walker,'before':before,'plan':plan,'pass':passed,'seconds':round(time.monotonic()-t0,1),'state':s,'samples':samples};rows.append(row);print(json.dumps({k:v for k,v in row.items() if k!='samples'}),flush=True)
   if i==0:p.screenshot(str(root/f'{tag}.png'))
  finally:
   p.call('stopPathing')
   if a.record and i==0:rec_stop(str(root/f'{tag}.mp4'))
finally:
 p.call('stopPathing')
 for k,v in old.items():p.call('tungstenSetting',k,v)
 (root/f'{tag}.json').write_text(json.dumps(rows,indent=2))
 r.cmd('forceload remove 1100 300 1110 310')
raise SystemExit(0 if rows and all(r['pass'] for r in rows) else 1)
