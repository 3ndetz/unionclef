#!/usr/bin/env python3
"""Check that public task commands cannot mutate the runner across a paused client."""
import argparse,json,subprocess
from pathlib import Path
ap=argparse.ArgumentParser();ap.add_argument('--tag',required=True);ap.add_argument('--output-dir',type=Path,required=True);ap.add_argument('--expect-bug',action='store_true');args=ap.parse_args()
from uctest.harness import Rcon
assert 'tester1' in Rcon().cmd('list'),'tester1 must be connected to the flat test server'
code=r'''
import json,time
from py4j.java_gateway import JavaGateway,GatewayParameters
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;c=j.net.minecraft.class_310.method_1551();a=j.adris.altoclef.AltoClef.getInstance();api=g.entry_point;lookup=j.java.lang.invoke.MethodHandles.publicLookup()
def mh(obj,name):
 cs=g.new_array(j.java.lang.Class,0);return lookup.unreflect(obj.getClass().getMethod(name,cs)).bindTo(obj)
def on_client(h):
 f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));c.execute(f);return f.get()
def fence():on_client(mh(j.java.util.concurrent.CountDownLatch(1),'getCount'))
def paused(action):
 release=j.java.util.concurrent.CountDownLatch(1);entered=j.java.util.concurrent.CountDownLatch(1)
 cs=g.new_array(j.java.lang.Class,2);cs[0]=j.java.lang.Long.TYPE;cs[1]=j.java.lang.Class.forName('java.util.concurrent.TimeUnit');h=lookup.unreflect(release.getClass().getMethod('await',cs)).bindTo(release)
 v=g.new_array(j.java.lang.Object,2);v[0]=j.java.lang.Long('10000');v[1]=j.java.util.concurrent.TimeUnit.MILLISECONDS;h=j.java.lang.invoke.MethodHandles.insertArguments(h,0,v);h=j.java.lang.invoke.MethodHandles.foldArguments(h,mh(entered,'countDown'));c.execute(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable'),h))
 try:
  for _ in range(100):
   if entered.getCount()==0:break
   time.sleep(.01)
  else:raise RuntimeError('client barrier did not start')
  before=a.getTaskRunner().isActive();action();time.sleep(1);during=a.getTaskRunner().isActive()
 finally:release.countDown()
 fence();time.sleep(.15);after=a.getTaskRunner().isActive();return {'before':before,'during':during,'after':after}
rows=[]
api.stopPathing();time.sleep(.5);fence()
try:
 for _ in range(6):
  started=paused(lambda:api.ExecuteCommand('@gamer'))
  stopped=paused(lambda:api.stopPathing())
  rows.append({'start':started,'stop':stopped})
finally:api.stopPathing();time.sleep(.5);fence()
print(json.dumps(rows))
'''
r=subprocess.run(['docker','exec','-i','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,check=True,timeout=90);rows=json.loads(r.stdout.strip());good=all(not v['start']['before'] and not v['start']['during'] and v['start']['after'] and v['stop']['before'] and v['stop']['during'] and not v['stop']['after'] for v in rows);bug=all(not v['start']['before'] and v['start']['during'] and v['start']['after'] and v['stop']['before'] and not v['stop']['during'] and not v['stop']['after'] for v in rows)
d={'samples':rows,'pass':bug if args.expect_bug else good,'expected_bug':args.expect_bug};root=args.output_dir.resolve();root.mkdir(parents=True,exist_ok=True);(root/(args.tag+'.json')).write_text(json.dumps(d,indent=2));print(d);assert d['pass']
