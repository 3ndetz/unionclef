#!/usr/bin/env python3
"""Pillaring must place above a usable block without opening its interface.

The terrain and inventory are rebuilt for each trial. Success requires actual
server-side cobblestone at every requested rung, grounded arrival and no opened GUI.
Use --cave-vines --navigator to test planned interaction clearance, and --no-break
for its policy refusal control. --vine-tip-offset selects head or jump-eye height.
"""
import argparse
import json
import subprocess
import statistics
import time
from pathlib import Path
from uctest.harness import Py4jClient, Rcon
from gamer_smoke import rec_start, rec_stop

ap = argparse.ArgumentParser(description=__doc__)
ap.add_argument('--support', choices=['stone', 'smoker', 'furnace', 'crafting_table'], required=True)
ap.add_argument('--tag', required=True)
ap.add_argument('--output-dir', type=Path, required=True)
ap.add_argument('--expect-bug', action='store_true')
ap.add_argument('--off-center', action='store_true')
terrain = ap.add_mutually_exclusive_group()
terrain.add_argument('--vine', action='store_true')
terrain.add_argument('--cave-vines', action='store_true')
ap.add_argument('--navigator', action='store_true')
ap.add_argument('--no-break', action='store_true')
ap.add_argument('--vine-tip-offset', type=int, choices=[1,2], default=2)
terrain.add_argument('--ceiling', action='store_true')
terrain.add_argument('--low-roof', action='store_true', help='One valid rung below a three-block-high roof')
args = ap.parse_args()
if args.no_break and not (args.cave_vines and args.navigator):
    ap.error('--no-break requires --cave-vines --navigator')
if args.ceiling and args.navigator:
    ap.error('--ceiling checks primitive refusal; omit --navigator')
root = args.output_dir.resolve()
root.mkdir(parents=True, exist_ok=True)
p = Py4jClient('uctest-mc-tester1')
r = Rcon()
old_idle = p.call('tungstenSetting', 'botFpsNoIdleThrottle', '').split('=', 1)[1]
old_break = p.call('tungstenSetting', 'allowBreak', '').split('=', 1)[1]
recording = False
target_y = -59 if args.low_roof else -57
rungs = (-60,) if args.low_roof else (-60, -59, -58)
start_z = 980.8 if args.off_center else 980.5
try:
    # Reconnecting to the same server can leave RCON seeing the old login
    # while the client has already discarded its world.
    if 'tester1' not in r.cmd('list') or not p.call('getGameState').get('self'):
        p.call('ConnectToServer', 'test-server')
    for _ in range(45):
        if 'tester1' in r.cmd('list') and p.call('getGameState').get('self'):
            break
        time.sleep(1)
    else:
        raise RuntimeError('flat server login not confirmed')
    p.call('stopPathing')
    p.call('closeOpenScreen')
    p.call('tungstenSetting', 'botFpsNoIdleThrottle', 'true')
    p.call('tungstenSetting', 'allowBreak', 'false' if args.no_break else 'true')
    for command in [
        'gamemode spectator tester1', 'forceload add 2697 977 2703 983',
        'fill 2697 -60 977 2703 -53 983 air',
        'fill 2697 -62 977 2703 -61 983 bedrock',
        f'setblock 2700 -61 980 {args.support}', 'clear tester1',
        'give tester1 cobblestone 32', f'tp tester1 2700.5 -60 {start_z} 0 0',
        'gamemode survival tester1', 'effect give tester1 saturation 1 20 true', 'effect give tester1 instant_health 1 5 true',
    ]:
        r.cmd(command)
    if args.low_roof:
        r.cmd('setblock 2700 -57 980 stone')
    if args.vine:
        r.cmd('fill 2701 -60 980 2701 -56 980 stone')
        r.cmd('fill 2700 -60 980 2700 -58 980 vine[east=true]')
    if args.cave_vines:
        r.cmd('setblock 2700 -55 980 stone')
        r.cmd(f'fill 2700 {-59 + args.vine_tip_offset} 980 2700 -56 980 cave_vines_plant')
        r.cmd(f'setblock 2700 {-60 + args.vine_tip_offset} 980 cave_vines')
    if args.ceiling:
        r.cmd('setblock 2700 -58 980 stone')
    assert 'passed' in r.cmd(f'execute if block 2700 -61 980 {args.support}').lower()
    for _ in range(15):
        state = p.call('getGameState').get('self')
        if state and state['onGround'] and state['pos'].startswith(f'2700.5,-60.0,{start_z}'):
            break
        time.sleep(1)
    else:
        raise RuntimeError('grounded fixture position not confirmed')
    rec_start(25)
    recording = True
    time.sleep(3)  # Exclude recorder startup from the short movement sample.
    code = r'''
import json,time
from py4j.java_gateway import JavaGateway,GatewayParameters,get_field
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;c=j.net.minecraft.class_310.method_1551();lookup=j.java.lang.invoke.MethodHandles.lookup()
def on_client(h):
 f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));c.execute(f);return f.get()
empty=g.new_array(j.java.lang.Class,0)
cls=j.java.lang.Class.forName('kaptainwutax.tungsten.TungstenMod');on_client(lookup.unreflect(cls.getMethod('resetAllState',empty)))
f=c.getClass().getDeclaredField('field_1755');f.setAccessible(True);screen=lookup.unreflectGetter(f).bindTo(c)
cls=j.java.lang.Class.forName('kaptainwutax.tungsten.task.PillarTask');active=lookup.unreflect(cls.getMethod('isActive',empty))
g.entry_point.selectHotbar(0)
before=dict(g.entry_point.getGameState()['self'])
assert before['onGround'] and before['held']=='minecraft:cobblestone',before
plan = None
assert g.entry_point.pillarTo(TARGET_Y)
rows=[];started=time.monotonic()
while time.monotonic()-started<15:
 # Read completion first so the final position cannot predate completion.
 running=bool(on_client(active));state=dict(g.entry_point.getGameState()['self']);gui=on_client(screen)
 rows.append({'t':round(time.monotonic()-started,2),'state':state,'screen':None if gui is None else str(gui.getClass().getName()),'active':running,'fps':g.entry_point.getPerfStats().get('fps',0)})
 if not running and time.monotonic()-started>.7:break
 time.sleep(.1)
print(json.dumps({'before':before,'plan':plan,'samples':rows}))
'''
    if args.navigator:
        code=code.replace("cls=j.java.lang.Class.forName('kaptainwutax.tungsten.task.PillarTask')","cls=j.java.lang.Class.forName('kaptainwutax.tungsten.task.FastNavigator')")
        code=code.replace("assert g.entry_point.pillarTo(TARGET_Y)",r"""
f=c.getClass().getDeclaredField('field_1687');f.setAccessible(True);world=f.get(c)
planner=j.java.lang.Class.forName('kaptainwutax.tungsten.path.fast.FastPlanner')
m=next(m for m in planner.getMethods() if m.getName()=='plan' and m.getParameterCount()==6)
values=[world,j.net.minecraft.class_2338(2700,-60,980),j.net.minecraft.class_2338(2700,TARGET_Y,980),j.java.lang.Long('1000'),None,j.java.lang.Boolean(True)]
args=g.new_array(j.java.lang.Object,len(values))
for i,v in enumerate(values):args[i]=v
result=on_client(j.java.lang.invoke.MethodHandles.insertArguments(lookup.unreflect(m),0,args))
plan={'complete':get_field(result,'complete'),'path':[{'pos':str(get_field(n,'pos')),'break':[str(b) for b in get_field(n,'toBreak')] if get_field(n,'toBreak') else []} for n in get_field(result,'path')]}
# Keep the synchronous planning probe outside the movement/FPS sample.
time.sleep(3)
m=next(m for m in cls.getMethods() if m.getName()=='startExact' and m.getParameterCount()==1);on_client(lookup.unreflect(m).bindTo(j.net.minecraft.class_2338(2700,TARGET_Y,980)))
""")
    code = code.replace('TARGET_Y', str(target_y))
    q = subprocess.run(['docker', 'exec', 'uctest-mc-tester1', 'python3', '-c', code],
                       capture_output=True, text=True, timeout=30)
    if q.returncode:
        raise RuntimeError(q.stderr)
    data = json.loads(q.stdout)
    data.update({'support': args.support, 'vine': args.vine, 'ceiling': args.ceiling, 'off_center': args.off_center, 'low_roof': args.low_roof, 'cave_vines': args.cave_vines, 'navigator': args.navigator})
    data['placed'] = sum('passed' in r.cmd(f'execute if block 2700 {y} 980 cobblestone').lower()
                         for y in rungs)
    data['opened_gui'] = any(row['screen'] is not None for row in data['samples'])
    final = data['samples'][-1]['state']
    data['arrived'] = final['onGround'] and float(final['pos'].split(',')[1]) >= target_y - .05
    data['pass'] = ((not data['opened_gui'] if args.low_roof or args.cave_vines else data['opened_gui']) and not data['arrived'] and data['placed'] == 0) if args.expect_bug else (
        data['arrived'] and data['placed'] == len(rungs) and not data['opened_gui']
        and all(row['state']['hp'] == 20 for row in data['samples']))
    if args.ceiling:
        data['pass'] = not data['arrived'] and data['placed'] == 0 and not data['opened_gui']
    if args.no_break:
        data['protected_intact'] = 'passed' in r.cmd(f'execute if block 2700 {-60 + args.vine_tip_offset} 980 cave_vines').lower()
        data['pass'] = data['protected_intact'] and not data['arrived'] and not data['opened_gui'] and all(row['state']['hp'] == 20 for row in data['samples'])
    data['vine_tip_offset'] = args.vine_tip_offset
    data['no_break'] = args.no_break
    data['median_fps'] = statistics.median(row['fps'] for row in data['samples'])
    data['pass'] = data['pass'] and data['median_fps'] >= 14
    data['expected_bug'] = args.expect_bug
    (root / f'{args.tag}.json').write_text(json.dumps(data, indent=2))
    p.screenshot(str(root / f'{args.tag}.png'))
    print({key: value for key, value in data.items() if key != 'samples'}, flush=True)
    if not data['pass']:
        raise RuntimeError('pillar interaction gate failed')
finally:
    try:
        p.call('stopPathing')
        p.call('closeOpenScreen')
    finally:
        try:
            if recording:
                rec_stop(str(root / f'{args.tag}.mp4'))
        finally:
            p.call('tungstenSetting', 'allowBreak', old_break)
            p.call('tungstenSetting', 'botFpsNoIdleThrottle', old_idle)
            r.cmd('gamemode survival tester1')
            r.cmd('forceload remove 2697 977 2703 983')
