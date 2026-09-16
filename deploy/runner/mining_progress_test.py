"""Exercise the live checker with controlled damage snapshots, without mining.

Requires the fed, idle flat client near the food_pursuit_test arena (4200,2200).
The controller fields and two initially empty fixture cells are restored afterwards.
"""
import argparse,json,subprocess,sys
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,required=True);ap.add_argument('--expect-bug',action='store_true');args=ap.parse_args();p=Py4jClient('uctest-mc-tester1');r=Rcon();p.call('stopPathing')
assert 'tester1' in r.cmd('list'), 'Run on the disposable flat server'
state=p.call('getGameState')['self'];x,y,z=map(float,state['pos'].split(','));assert abs(x-4200)<48 and abs(z-2200)<48, 'Run food_pursuit_test first so the fixture chunks are loaded'
positions=[(4203,-8,2203),(4204,-8,2203)]
changed=[]
try:
 for x,y,z in positions:
  assert 'passed' in r.cmd(f'execute if block {x} {y} {z} air').lower()
  changed.append((x,y,z))
  r.cmd(f'setblock {x} {y} {z} stone')
 code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters
import time,json
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;mod=j.adris.altoclef.AltoClef.getInstance();c=j.net.minecraft.class_310.method_1551();lk=j.java.lang.invoke.MethodHandles.lookup();extras=mod.getControllerExtras()
def call(h):
 f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));c.execute(f);return f.get()
def setter(name):
 f=extras.getClass().getDeclaredField(name);f.setAccessible(True);return lk.unreflectSetter(f).bindTo(extras)
def setvalue(h,value):
 a=g.new_array(j.java.lang.Object,1);a[0]=value
 bound=j.java.lang.invoke.MethodHandles.insertArguments(h,0,a);f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable'),bound),None);c.execute(f);f.get()
posset=setter('blockBreakPos');progset=setter('blockBreakProgress');oldpos=extras.getBreakingBlockPos();oldprogress=extras.getBreakingBlockProgress();assert oldpos is None
positions=[j.net.minecraft.class_2338(4203,-8,2203),j.net.minecraft.class_2338(4204,-8,2203)];results={}
try:
 for name in ['productive_switch','zero_switch','same_block_work','same_block_stall']:
  checker=j.adris.altoclef.util.progresscheck.MovementProgressChecker();m=next(m for m in checker.getClass().getMethods() if m.getName()=='check');check=lk.unreflect(m).bindTo(checker).bindTo(mod);reset=lk.unreflect(next(m for m in checker.getClass().getMethods() if m.getName()=='reset' and m.getParameterCount()==0)).bindTo(checker);firstcheck=j.java.lang.invoke.MethodHandles.foldArguments(check,reset);rows=[];started=time.monotonic()
  for i in range(18):
   target=(i//5)%2 if name=='productive_switch' else (i%2 if name=='zero_switch' else 0)
   progress=.2+(i%5)*.15 if name=='productive_switch' else (i*.04 if name=='same_block_work' else 0.0)
   setvalue(posset,positions[target]);setvalue(progset,j.java.lang.Double(str(progress)));ok=bool(call(firstcheck if i==0 else check));rows.append({'t':round(time.monotonic()-started,3),'i':i,'target':target,'progress':progress,'ok':ok});time.sleep(.12)
  results[name]={'all_ok':all(r['ok'] for r in rows),'any_failed':any(not r['ok'] for r in rows),'samples':rows}
finally:
 setvalue(posset,oldpos);setvalue(progset,j.java.lang.Double(str(oldprogress)))
print(json.dumps(results))
'''
 q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,timeout=60)
 if q.returncode:raise RuntimeError(q.stderr)
 d=json.loads(q.stdout);d['pass']=(d['productive_switch']['any_failed'] if args.expect_bug else d['productive_switch']['all_ok']) and d['zero_switch']['any_failed'] and d['same_block_work']['all_ok'] and d['same_block_stall']['any_failed'];args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(d,indent=2));print({k:{a:b for a,b in v.items() if a!='samples'} if isinstance(v,dict) else v for k,v in d.items()});assert d['pass']
finally:
 for x,y,z in changed:r.cmd(f'setblock {x} {y} {z} air')
