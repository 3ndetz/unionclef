#!/usr/bin/env python3
"""A reach goal must not cancel a bridge while the player stands over its missing floor."""
import argparse
import json
import math
import subprocess
import time
from pathlib import Path
from uctest.harness import Py4jClient, Rcon
from gamer_smoke import rec_start, rec_stop

ap = argparse.ArgumentParser(description=__doc__)
ap.add_argument('--tag', required=True)
ap.add_argument('--output-dir', type=Path, required=True)
ap.add_argument('--expect-bug', action='store_true')
ap.add_argument('--arrival-guard', choices=['true', 'false'], default='true')
ap.add_argument('--open', action='store_true', help='Allow jumping instead of forcing a bridge')
args = ap.parse_args()
root = args.output_dir.resolve()
root.mkdir(parents=True, exist_ok=True)
p = Py4jClient('uctest-mc-tester1')
r = Rcon()
recording = False
flags = {'botFpsNoIdleThrottle': 'true', 'allowPlace': 'true', 'allowBreak': 'false', 'arrivalNeedsSettledBody': args.arrival_guard}
old = {k: p.call('tungstenSetting', k, '').split('=', 1)[1] for k in flags}
try:
    if 'tester1' not in r.cmd('list') or not p.call('getGameState').get('self'):
        p.call('ConnectToServer', 'test-server')
    for _ in range(45):
        time.sleep(1)
        if 'tester1' in r.cmd('list') and p.call('getGameState').get('self'):
            break
    else:
        raise RuntimeError('flat login not confirmed')
    p.call('stopPathing')
    p.call('closeOpenScreen')
    for k, v in flags.items():
        p.call('tungstenSetting', k, v)
    for cmd in [
        'gamemode spectator tester1', 'forceload add 3097 1037 3108 1043',
        'fill 3097 -60 1037 3108 -25 1043 air',
        'fill 3099 -30 1039 3101 -30 1041 bedrock',
        'setblock 3105 -30 1040 bedrock', 'setblock 3105 -29 1040 stone',
        'clear tester1', 'give tester1 cobblestone 32',
        'tp tester1 3100.5 -29 1040.5 -90 0', 'gamemode survival tester1',
        'effect give tester1 instant_health 1 5 true',
    ]:
        r.cmd(cmd)
    if not args.open:
        r.cmd('fill 3099 -27 1039 3105 -27 1041 bedrock')
    time.sleep(1)
    rec_start(25)
    recording = True
    code = r'''
import json,time
from py4j.java_gateway import JavaGateway,GatewayParameters,get_field
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;c=j.net.minecraft.class_310.method_1551();lookup=j.java.lang.invoke.MethodHandles.lookup()
def on_client(h):
 f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));c.execute(f);return f.get()
def method(cls,name,n):
 return next(m for m in j.java.lang.Class.forName(cls).getMethods() if m.getName()==name and m.getParameterCount()==n and (name!='start' or str(m.getParameterTypes()[1].getName())=='net.minecraft.class_2338'))
cls='kaptainwutax.tungsten.task.FastNavigator'
on_client(lookup.unreflect(method('kaptainwutax.tungsten.TungstenMod','resetAllState',0)))
active=lookup.unreflect(method(cls,'isActive',0))
g.entry_point.selectHotbar(0)
before=dict(g.entry_point.getGameState()['self']);assert before['onGround'] and before['pos']=='3100.5,-29.0,1040.5',before
a=j.adris.altoclef.AltoClef.getInstance();empty=g.new_array(j.java.lang.Class,0)
world=on_client(lookup.unreflect(a.getClass().getMethod('getWorld',empty)).bindTo(a))
pa=g.new_array(j.java.lang.Object,6);pa[0]=world;pa[1]=j.net.minecraft.class_2338(3100,-29,1040);pa[2]=j.net.minecraft.class_2338(3105,-29,1040);pa[3]=j.java.lang.Long('1000');pa[4]=pa[2];pa[5]=j.java.lang.Boolean(False)
result=on_client(j.java.lang.invoke.MethodHandles.insertArguments(lookup.unreflect(method('kaptainwutax.tungsten.path.fast.FastPlanner','plan',6)),0,pa))
plan=[{'pos':str(get_field(w,'pos')),'physics':bool(get_field(w,'needsPhysics')),'place':str(get_field(w,'toPlace')),'break':str(get_field(w,'toBreak'))} for w in get_field(result,'path')]
values=g.new_array(j.java.lang.Object,2);values[0]=j.net.minecraft.class_243(3105.5,-29.0,1040.5);values[1]=j.net.minecraft.class_2338(3105,-29,1040)
on_client(j.java.lang.invoke.MethodHandles.insertArguments(lookup.unreflect(method(cls,'start',2)),0,values))
rows=[];started=time.monotonic();ended=None
while time.monotonic()-started<15:
 running=bool(on_client(active));state=dict(g.entry_point.getGameState()['self']);t=time.monotonic()-started
 rows.append({'t':round(t,2),'active':running,'state':state})
 if not running and ended is None:ended=t
 if ended is not None and t-ended>1.5:break
 if float(state['pos'].split(',')[1]) < -34:break
 time.sleep(.05)
print(json.dumps({'before':before,'plan':plan,'samples':rows}))
'''
    q = subprocess.run(['docker', 'exec', 'uctest-mc-tester1', 'python3', '-c', code],
                       capture_output=True, text=True, timeout=30)
    if q.returncode:
        raise RuntimeError(q.stderr)
    data = json.loads(q.stdout)
    data['placed'] = sum('passed' in r.cmd(f'execute if block {x} -30 1040 cobblestone').lower()
                         for x in (3102, 3103, 3104))
    final = data['samples'][-1]
    data['fell'] = any(float(s['state']['pos'].split(',')[1]) < -29.1 for s in data['samples'])
    x, y, z = map(float, final['state']['pos'].split(','))
    eye_distance = math.dist((x, y + 1.62, z), (3105.5, -28.5, 1040.5))
    data['arrived'] = (not final['active'] and final['state']['onGround'] and not data['fell']
                       and eye_distance <= 4.5
                       and all(row['state']['hp'] == 20 for row in data['samples']))
    data['pass'] = data['fell'] if args.expect_bug else data['arrived'] and (args.open or data['placed'] > 0)
    data['expected_bug'] = args.expect_bug
    data['open'] = args.open
    data['arrival_guard'] = args.arrival_guard
    (root / f'{args.tag}.json').write_text(json.dumps(data, indent=2))
    p.screenshot(str(root / f'{args.tag}.png'))
    print({k: v for k, v in data.items() if k != 'samples'}, flush=True)
    if not data['pass']:
        raise RuntimeError('reach bridge gate failed')
finally:
    try:
        p.call('stopPathing')
    finally:
        try:
            if recording:
                rec_stop(str(root / f'{args.tag}.mp4'))
        finally:
            for k, v in old.items():
                p.call('tungstenSetting', k, v)
            r.cmd('gamemode survival tester1')
            r.cmd('forceload remove 3097 1037 3108 1043')
