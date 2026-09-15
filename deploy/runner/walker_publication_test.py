#!/usr/bin/env python3
"""A completed background plan must wait for the client before changing walker state.

The client barrier times out after ten seconds even if the probe fails. The enclosed
corridor forces a short partial plan and the actual walker-to-physics handoff. The
retarget control queues an old result, changes the goal, and requires its rejection.
"""
import argparse
import json
import subprocess
import time
from pathlib import Path
from uctest.harness import Py4jClient, Rcon
from gamer_smoke import rec_start, rec_stop

ap = argparse.ArgumentParser(description=__doc__)
ap.add_argument('--tag', required=True)
ap.add_argument('--output-dir', type=Path, required=True)
ap.add_argument('--retarget', action='store_true')
ap.add_argument('--one-cell', action='store_true', help='Force an incomplete one-waypoint plan')
ap.add_argument('--goal-here', action='store_true', help='Control: the one-waypoint plan is complete')
ap.add_argument('--expect-bug', action='store_true')
args = ap.parse_args()
if args.goal_here and (args.retarget or args.expect_bug):
    ap.error('--goal-here is a successful completion control without retargeting')
p = Py4jClient('uctest-mc-tester1')
r = Rcon()
root = args.output_dir.resolve()
root.mkdir(parents=True, exist_ok=True)
recording = False
old_idle = p.call('tungstenSetting', 'botFpsNoIdleThrottle', '').split('=', 1)[1]
try:
    p.call('ConnectToServer', 'test-server')
    for _ in range(45):
        time.sleep(1)
        if 'tester1' in r.cmd('list'):
            break
    else:
        raise RuntimeError('flat server login not confirmed')
    p.call('stopPathing')
    time.sleep(.3)
    p.call('closeOpenScreen')
    p.call('tungstenSetting', 'botFpsNoIdleThrottle', 'true')
    for command in [
        'gamemode spectator tester1', 'forceload add 2498 948 2508 954',
        'fill 2498 -61 948 2508 -56 954 bedrock',
        f'fill 2501 -60 951 {2501 if args.one_cell else 2504} -59 951 air', 'clear tester1',
        'tp tester1 2501.5 -60 951.5 -90 0', 'gamemode survival tester1',
        'effect give tester1 instant_health 1 5 true',
    ]:
        r.cmd(command)
    time.sleep(1)
    rec_start(30)
    recording = True
    code = r'''
import json,time
from py4j.java_gateway import JavaGateway,GatewayParameters
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;k=j.kaptainwutax.tungsten;c=j.net.minecraft.class_310.method_1551()
lookup=j.java.lang.invoke.MethodHandles.publicLookup()
def mh(obj,name):
 cs=g.new_array(j.java.lang.Class,0);return lookup.unreflect(obj.getClass().getMethod(name,cs)).bindTo(obj)
def on_client(handle):
 f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),handle));c.execute(f);return f.get()
cs=g.new_array(j.java.lang.Class,0);on_client(lookup.unreflect(j.java.lang.Class.forName('kaptainwutax.tungsten.TungstenMod').getMethod('resetAllState',cs)))
def counter(name):
 try:return j.java.lang.Class.forName('kaptainwutax.tungsten.task.FastNavigator').getField(name).getInt(None)
 except Exception:return None
def snapshot():
 return {'walker':k.task.BlockPathWalker.isRunning(),'handoffs':k.task.FastNavigator.navDeadEnd,'applied':counter('navPlansApplied'),'discarded':counter('navPlansDiscarded')}
def wait_worker():
 for _ in range(100):
  if not any(t.isAlive() and str(t.getName())=='FastNavigator-plan' for t in j.java.lang.Thread.getAllStackTraces().keySet()):return
  time.sleep(.02)
 raise RuntimeError('calculation did not finish inside barrier budget')
release=j.java.util.concurrent.CountDownLatch(1);entered=j.java.util.concurrent.CountDownLatch(1)
cs=g.new_array(j.java.lang.Class,2);cs[0]=j.java.lang.Long.TYPE;cs[1]=j.java.lang.Class.forName('java.util.concurrent.TimeUnit')
h=lookup.unreflect(release.getClass().getMethod('await',cs)).bindTo(release)
values=g.new_array(j.java.lang.Object,2);values[0]=j.java.lang.Long('10000');values[1]=j.java.util.concurrent.TimeUnit.MILLISECONDS
h=j.java.lang.invoke.MethodHandles.insertArguments(h,0,values)
h=j.java.lang.invoke.MethodHandles.foldArguments(h,mh(entered,'countDown'))
c.execute(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable'),h))
try:
 for _ in range(100):
  if entered.getCount()==0:break
  time.sleep(.01)
 else:raise RuntimeError('client did not enter barrier')
 path=j.java.util.ArrayList();path.add(j.net.minecraft.class_2338(2501,-60,951));path.add(j.net.minecraft.class_2338(2502,-60,951))
 # The paused client makes these setup mutations sequential; only the real planner runs.
 k.task.BlockPathWalker.startBFS(path,True);before=snapshot()
 k.task.FastNavigator.start(j.net.minecraft.class_243(2501.5 if GOAL_HERE else 2560.5,-60.0,951.5));wait_worker()
 first=snapshot()
 if RETARGET:
  k.task.FastNavigator.start(j.net.minecraft.class_243(2570.5,-60.0,951.5));wait_worker()
 blocked=snapshot()
finally:release.countDown()
# A queued marker waits behind both result publications before reading their counters.
on_client(mh(j.java.util.concurrent.CountDownLatch(1),'getCount'))
after=snapshot()
print(json.dumps({'before':before,'first':first,'blocked':blocked,'after':after}))
'''.replace('RETARGET', repr(args.retarget)).replace('GOAL_HERE', repr(args.goal_here))
    q = subprocess.run(['docker', 'exec', 'uctest-mc-tester1', 'python3', '-c', code],
                       capture_output=True, text=True, timeout=20)
    if q.returncode:
        raise RuntimeError(q.stderr)
    data = json.loads(q.stdout)
    before, blocked, after = (data[key] for key in ('before', 'blocked', 'after'))
    if args.goal_here:
        passed = (blocked['walker'] and blocked['handoffs'] == before['handoffs']
                  and blocked['applied'] == before['applied']
                  and after['handoffs'] == before['handoffs']
                  and after['applied'] > before['applied'])
    elif args.expect_bug:
        if args.one_cell:
            passed = (after['applied'] > before['applied']
                      and after['handoffs'] == before['handoffs'])
        else:
            passed = not blocked['walker'] and blocked['handoffs'] > before['handoffs']
    else:
        passed = (blocked['walker'] and blocked['handoffs'] == before['handoffs']
                  and blocked['applied'] == before['applied']
                  and after['handoffs'] > before['handoffs']
                  and after['applied'] > before['applied']
                  and (not args.retarget or after['discarded'] > before['discarded']))
    data.update({'pass': passed, 'retarget': args.retarget, 'one_cell': args.one_cell, 'goal_here': args.goal_here,
                 'expected_bug': args.expect_bug})
    (root / f'{args.tag}.json').write_text(json.dumps(data, indent=2))
    print(data, flush=True)
    p.screenshot(str(root / f'{args.tag}.png'))
    if not passed:
        raise RuntimeError('walker publication gate failed')
finally:
    try:
        p.call('stopPathing')
    finally:
        try:
            if recording:
                rec_stop(str(root / f'{args.tag}.mp4'))
        finally:
            p.call('tungstenSetting', 'botFpsNoIdleThrottle', old_idle)
            r.cmd('gamemode survival tester1')
            r.cmd('forceload remove 2498 948 2508 954')
