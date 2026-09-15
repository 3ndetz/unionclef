#!/usr/bin/env python3
"""An existing retreat must retain live occluded threats until safe distance is reached."""
import argparse
import json
import math
import subprocess
import time
from pathlib import Path

from gamer_smoke import rec_start, rec_stop
from uctest.harness import Py4jClient, Rcon

PROBE = r'''
from py4j.java_gateway import JavaGateway, GatewayParameters
import json
j = (g := JavaGateway(gateway_parameters=GatewayParameters(port=25333, auto_convert=True))).jvm
a = j.adris.altoclef.AltoClef.getInstance()
mc = j.net.minecraft.class_310.method_1551()
def call(obj, name):
    cs = g.new_array(j.java.lang.Class, 0)
    method = obj.getClass().getMethod(name, cs)
    handle = j.java.lang.invoke.MethodHandles.publicLookup().unreflect(method).bindTo(obj)
    proxy = j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(
        j.java.lang.Class.forName('java.util.concurrent.Callable'), handle)
    future = j.java.util.concurrent.FutureTask(proxy)
    mc.execute(future)
    return future.get()
# No active task loop in predicate controls: refresh on the client thread explicitly.
call(a.getEntityTracker(), 'setDirty')
t = j.adris.altoclef.tasks.movement.RunAwayFromHostilesTask(FLEE_DISTANCE, INCLUDE_SKELETONS)
print(json.dumps(dict(finished=call(t, 'isFinished'),
    visible=call(a.getEntityTracker(), 'getHostiles').size(),
    tracked=call(a.getEntityTracker(), 'getTrackedHostiles').size())))
'''
START = r'''
from py4j.java_gateway import JavaGateway, GatewayParameters
j = (g := JavaGateway(gateway_parameters=GatewayParameters(port=25333, auto_convert=True))).jvm
a = j.adris.altoclef.AltoClef.getInstance()
t = j.adris.altoclef.tasks.movement.RunAwayFromHostilesTask(FLEE_DISTANCE, True)
cs = g.new_array(j.java.lang.Class, 1)
cs[0] = j.java.lang.Class.forName('adris.altoclef.tasksystem.Task')
m = a.getClass().getMethod('runUserTask', cs)
h = j.java.lang.invoke.MethodHandles.publicLookup().unreflect(m).bindTo(a).bindTo(t)
r = j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable'), h)
j.net.minecraft.class_310.method_1551().execute(r)
'''


def client(code):
    result = subprocess.run(['docker', 'exec', 'uctest-mc-tester1', 'python3', '-c', code],
                            capture_output=True, text=True, check=True, timeout=25)
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--distance', type=float, default=12)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    root = args.output_dir.resolve()
    root.mkdir(parents=True, exist_ok=True)
    p, r = Py4jClient('uctest-mc-tester1'), Rcon('uctest-server')
    p.call('ConnectToServer', 'test-server')
    for _ in range(45):
        time.sleep(1)
        if 'tester1' in r.cmd('list'):
            break
    else:
        raise RuntimeError('connection failed')
    recording = False
    controls = []
    try:
        p.call('stopPathing')
        for command in [
            'gamemode spectator tester1', 'kill @e[tag=flee_visibility]',
            'forceload add 2260 900 2340 910',
            'fill 2260 -61 900 2340 -61 910 bedrock',
            'fill 2260 -60 900 2340 -55 910 air',
            'fill 2302 -60 900 2302 -55 910 bedrock',
            'clear tester1', 'tp tester1 2300.5 -60 905.5 -90 0',
            'gamemode survival tester1', 'effect give tester1 instant_health 1 5 true',
            'effect give tester1 saturation 1 5 true',
        ]:
            r.cmd(command)
        # Predicate controls do not run defence chains or hand over movement.
        for name, mob, x, visible, include, distance, expected_tracked, finished in [
            ('occluded_zombie', 'zombie', 2304.5, False, True, 12, 1, False),
            ('visible_zombie', 'zombie', 2296.5, True, True, 12, 1, False),
            ('distant_zombie', 'zombie', 2334.5, False, True, 12, 1, True),
            ('long_retreat_threat', 'zombie', 2328.5, False, True, 30, 1, False),
            ('long_retreat_safe', 'zombie', 2334.5, False, True, 30, 1, True),
            ('calm_enderman', 'enderman', 2304.5, False, True, 12, 0, True),
            ('included_skeleton', 'skeleton', 2304.5, False, True, 12, 1, False),
            ('excluded_skeleton', 'skeleton', 2304.5, False, False, 12, 1, True),
        ]:
            r.cmd('kill @e[tag=flee_visibility]')
            r.cmd(f'summon {mob} {x} -60 905.5 '
                  '{NoAI:1b,PersistenceRequired:1b,Tags:["flee_visibility"]}')
            time.sleep(.7)
            row = json.loads(client(PROBE.replace('INCLUDE_SKELETONS', repr(include)).replace('FLEE_DISTANCE', str(float(distance)))))
            row['case'] = name
            controls.append(row)
            assert row['finished'] == finished and row['tracked'] == expected_tracked and row['visible'] == int(visible), row
        r.cmd('kill @e[tag=flee_visibility]')
        time.sleep(.7)
        row = json.loads(client(PROBE.replace('INCLUDE_SKELETONS', 'True').replace('FLEE_DISTANCE', '12.0')))
        row['case'] = 'removed_mob'
        controls.append(row)
        assert row['finished'] and row['tracked'] == 0 and row['visible'] == 0, row
        r.cmd('summon zombie 2304.5 -60 905.5 '
              '{NoAI:1b,PersistenceRequired:1b,Tags:["flee_visibility"]}')
        time.sleep(.7)
        rec_start(40)
        recording = True
        client(START.replace('FLEE_DISTANCE', str(args.distance)))
        rows = []
        start = time.monotonic()
        while time.monotonic() - start < 25:
            state = p.call('getGameState')['self']
            rows.append(dict(t=round(time.monotonic()-start, 2), state=state,
                             chain=p.call('getTaskChainString')))
            x = float(state['pos'].split(',')[0])
            if math.floor(x) <= math.floor(2304.5 - args.distance):
                break
            time.sleep(.4)
        passed = math.floor(x) <= math.floor(2304.5 - args.distance) and min(row['state']['hp'] for row in rows) == 20
        result = dict(passed=passed, controls=controls, samples=rows)
        (root / f'{args.tag}.json').write_text(json.dumps(result, indent=2))
        p.screenshot(str(root / f'{args.tag}.png'))
        print(dict(passed=passed, controls=controls, last=rows[-1]), flush=True)
        assert passed, 'retreat did not reach safe distance'
    finally:
        p.call('stopPathing')
        if recording:
            rec_stop(str(root / f'{args.tag}.mp4'))
        r.cmd('kill @e[tag=flee_visibility]')
        r.cmd('forceload remove 2260 900 2340 910')


if __name__ == '__main__':
    main()
