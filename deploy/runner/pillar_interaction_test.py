#!/usr/bin/env python3
"""Pillaring must place above a usable block without opening its interface.

The terrain and inventory are rebuilt for each trial. Success requires actual
server-side cobblestone at all three rungs, grounded arrival and no opened GUI.
"""
import argparse
import json
import subprocess
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
terrain.add_argument('--ceiling', action='store_true')
args = ap.parse_args()
root = args.output_dir.resolve()
root.mkdir(parents=True, exist_ok=True)
p = Py4jClient('uctest-mc-tester1')
r = Rcon()
old_idle = p.call('tungstenSetting', 'botFpsNoIdleThrottle', '').split('=', 1)[1]
recording = False
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
    for command in [
        'gamemode spectator tester1', 'forceload add 2697 977 2703 983',
        'fill 2697 -60 977 2703 -53 983 air',
        'fill 2697 -62 977 2703 -61 983 bedrock',
        f'setblock 2700 -61 980 {args.support}', 'clear tester1',
        'give tester1 cobblestone 32', f'tp tester1 2700.5 -60 {start_z} 0 0',
        'gamemode survival tester1', 'effect give tester1 instant_health 1 5 true',
    ]:
        r.cmd(command)
    if args.vine:
        r.cmd('fill 2701 -60 980 2701 -56 980 stone')
        r.cmd('fill 2700 -60 980 2700 -58 980 vine[east=true]')
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
    code = r'''
import json,time
from py4j.java_gateway import JavaGateway,GatewayParameters
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
assert g.entry_point.pillarTo(-57)
rows=[];started=time.monotonic()
while time.monotonic()-started<15:
 # Read completion first so the final position cannot predate completion.
 running=bool(on_client(active));state=dict(g.entry_point.getGameState()['self']);gui=on_client(screen)
 rows.append({'t':round(time.monotonic()-started,2),'state':state,'screen':None if gui is None else str(gui.getClass().getName()),'active':running})
 if not running and time.monotonic()-started>.7:break
 time.sleep(.1)
print(json.dumps({'before':before,'samples':rows}))
'''
    q = subprocess.run(['docker', 'exec', 'uctest-mc-tester1', 'python3', '-c', code],
                       capture_output=True, text=True, timeout=30)
    if q.returncode:
        raise RuntimeError(q.stderr)
    data = json.loads(q.stdout)
    data.update({'support': args.support, 'vine': args.vine, 'ceiling': args.ceiling, 'off_center': args.off_center})
    data['placed'] = sum('passed' in r.cmd(f'execute if block 2700 {y} 980 cobblestone').lower()
                         for y in (-60, -59, -58))
    data['opened_gui'] = any(row['screen'] is not None for row in data['samples'])
    final = data['samples'][-1]['state']
    data['arrived'] = final['onGround'] and float(final['pos'].split(',')[1]) >= -57.05
    data['pass'] = (data['opened_gui'] and not data['arrived'] and data['placed'] == 0) if args.expect_bug else (
        data['arrived'] and data['placed'] == 3 and not data['opened_gui']
        and all(row['state']['hp'] == 20 for row in data['samples']))
    if args.ceiling:
        data['pass'] = not data['arrived'] and data['placed'] == 0 and not data['opened_gui']
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
            p.call('tungstenSetting', 'botFpsNoIdleThrottle', old_idle)
            r.cmd('gamemode survival tester1')
            r.cmd('forceload remove 2697 977 2703 983')
