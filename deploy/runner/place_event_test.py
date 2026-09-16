"""A server block change is not a failed player placement."""
import argparse,json,subprocess,sys,time,statistics
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser();ap.add_argument('--tag',required=True);ap.add_argument('--expect-bug',action='store_true');ap.add_argument('--output-dir',type=Path,required=True);a=ap.parse_args()
root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True)
p=Py4jClient('uctest-mc-tester1');r=Rcon();old={};recording=False
try:
 if 'tester1' not in r.cmd('list') or not p.call('getGameState').get('self'):p.call('ConnectToServer','test-server')
 for _ in range(45):
  if 'tester1' in r.cmd('list') and p.call('getGameState').get('self'):break
  time.sleep(1)
 else:raise RuntimeError('flat login failed')
 p.call('stopPathing');p.call('closeOpenScreen')
 for k in ['allowPlace','botFpsNoIdleThrottle']:
  old[k]=p.call('tungstenSetting',k,'').split('=',1)[1];p.call('tungstenSetting',k,'true')
 for cmd in ['gamemode spectator tester1','forceload add 3796 1796 3804 1804','fill 3796 -61 1796 3804 -61 1804 bedrock','fill 3796 -60 1796 3804 -54 1804 air','clear tester1','give tester1 cobblestone 32','tp tester1 3800.5 -60 1800.5 0 0','gamemode survival tester1','effect give tester1 instant_health 1 5 true']:
  r.cmd(cmd)
 code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;a=j.adris.altoclef.AltoClef.getInstance();task=j.adris.altoclef.tasks.movement.IdleTask()
cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task');m=a.getClass().getMethod('runUserTask',cs);h=j.java.lang.invoke.MethodHandles.lookup().unreflect(m).bindTo(a).bindTo(task);f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));j.net.minecraft.class_310.method_1551().execute(f);f.get()
'''
 subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],check=True,capture_output=True,text=True)
 time.sleep(1)
 before=p.call('canPlaceBlock',3800,-60,1800);assert before['policyAllows'],before
 rec_start(25);recording=True;time.sleep(3)
 # Actual world updates, with no placement action by the bot.
 r.cmd('setblock 3802 -60 1800 stone');time.sleep(.1);r.cmd('setblock 3802 -60 1800 air');time.sleep(1.5)
 after=p.call('canPlaceBlock',3800,-60,1800)
 protected=None
 if not a.expect_bug:
  # Scope the negative control to a fixture-owned zone only.
  check="from py4j.java_gateway import JavaGateway,GatewayParameters,get_field;g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));c=g.jvm.kaptainwutax.tungsten.TungstenConfig.get();assert get_field(c,'placeDenyZones').isEmpty() and get_field(c,'breakDenyZones').isEmpty()"
  subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',check],check=True,capture_output=True,text=True)
  try:
   p.call('markProtectedArea',3804,-60,1800,0)
   protected=not p.call('canPlaceBlock',3804,-60,1800)['policyAllows'] and p.call('canPlaceBlock',3800,-60,1800)['policyAllows']
  finally:p.call('clearProtectedAreas')
 p.call('selectHotbar',0);p.call('pillarTo',-59)
 rows=[];start=time.monotonic()
 while time.monotonic()-start<7:
  s=p.call('getGameState')['self'];rows.append({'t':round(time.monotonic()-start,2),'state':s,'fps':p.call('getPerfStats').get('fps',0)})
  if s['onGround'] and float(s['pos'].split(',')[1])>=-59.05:break
  time.sleep(.2)
 placed='passed' in r.cmd('execute if block 3800 -60 1800 cobblestone').lower()
 arrived=rows[-1]['state']['onGround'] and float(rows[-1]['state']['pos'].split(',')[1])>=-59.05
 healthy=all(v['state']['hp']==20 for v in rows)
 d={'before':before,'after':after,'placed':placed,'arrived':arrived,'healthy':healthy,'explicit_protection':protected,'samples':rows,'median_fps':statistics.median(v['fps'] for v in rows)}
 d['pass']=d['median_fps']>=14 and ((not after['policyAllows'] and not placed) if a.expect_bug else after['policyAllows'] and placed and arrived and healthy and protected)
 (root/(a.tag+'.json')).write_text(json.dumps(d,indent=2));p.screenshot(str(root/(a.tag+'.png')));print({k:v for k,v in d.items() if k!='samples'},flush=True)
 if not d['pass']:raise RuntimeError('world change placement gate failed')
finally:
 p.call('stopPathing')
 if recording:rec_stop(str(root/(a.tag+'.mp4')))
 r.cmd('forceload remove 3796 1796 3804 1804')
 for k,v in old.items():p.call('tungstenSetting',k,v)
