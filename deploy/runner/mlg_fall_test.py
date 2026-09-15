#!/usr/bin/env python3
"""Remove a platform under an active idle task and measure real water-bucket rescue.

Server death and bucket-use statistics survive fast respawns and immediate water
pickup, which frame polling can miss. Require an in-arena healthy landing;
--require-refill additionally verifies that the rescue water was collected again.
Partial surfaces and an optional full-block bank exercise landing selection.
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path
from uctest.harness import Py4jClient, Rcon
from gamer_smoke import rec_start, rec_stop

ap = argparse.ArgumentParser(description=__doc__)
ap.add_argument('--tag', required=True)
ap.add_argument('--output-dir', type=Path, required=True)
ap.add_argument('--height', type=int, default=30, choices=range(4, 61))
ap.add_argument('--inventory', action='store_true', help='Put water in the pack instead of hotbar')
ap.add_argument('--surface', choices=['bedrock','deepslate_tile_slab','deepslate_tile_stairs','deepslate_tile_wall'], default='bedrock')
ap.add_argument('--require-refill', action='store_true', help='Also require the rescue water back in the bucket')
ap.add_argument('--safe-bank', choices=['west','east'], help='Put a full-block landing bank beside partial supports')
ap.add_argument('--no-bucket', action='store_true', help='Damage control without a rescue item')
ap.add_argument('--shield', action='store_true')
ap.add_argument('--hotbar-slot', type=int, choices=range(1,9), default=2)
ap.add_argument('--offset', type=float, default=0.5)
ap.add_argument('--expect-bug', action='store_true')
args = ap.parse_args()
p = Py4jClient('uctest-mc-tester1')
r = Rcon('uctest-server')
root = args.output_dir.resolve()
root.mkdir(parents=True, exist_ok=True)
recording = False
worker = None
old = p.call('tungstenSetting', 'botFpsNoIdleThrottle', '').split('=', 1)[1]
try:
    assert 'tester1' in r.cmd('list'), 'flat server login required'
    assert 'true' in r.cmd('gamerule fall_damage'), 'fall damage must be enabled'
    p.call('stopPathing')
    p.call('closeOpenScreen')
    p.call('tungstenSetting', 'botFpsNoIdleThrottle', 'true')
    y = -60 + args.height
    for cmd in [
        'gamemode spectator tester1', 'forceload add 3195 1095 3205 1105',
        'fill 3195 -62 1095 3205 -62 1105 bedrock',
        f'fill 3195 -61 1095 3205 -61 1105 {args.surface}',
        'fill 3195 -60 1095 3205 2 1105 air',
        f'setblock 3200 {y-1} 1100 stone', 'clear tester1',
        'item replace entity tester1 hotbar.0 with diamond_sword',
        'item replace entity tester1 '+('inventory.0' if args.inventory else f'hotbar.{args.hotbar_slot}')+' with water_bucket',
        f'tp tester1 {3200+args.offset} {y} 1100.5 0 0',
        'gamemode survival tester1', 'effect clear tester1', 'effect give tester1 instant_health 1 5 true',
        'effect give tester1 saturation 1 5 true',
    ]:
        r.cmd(cmd)
    if args.safe_bank:
        lo, hi = (3195, 3199) if args.safe_bank == 'west' else (3201, 3205)
        r.cmd(f'fill {lo} -61 1095 {hi} -61 1105 bedrock')
    if args.no_bucket:
        r.cmd('clear tester1 water_bucket')
    if args.shield:
        r.cmd('item replace entity tester1 weapon.offhand with shield')
    r.cmd('scoreboard objectives add mlg_probe_deaths deathCount', allow_reject=True)
    r.cmd('scoreboard players set tester1 mlg_probe_deaths 0')
    r.cmd('scoreboard objectives add mlg_probe_use minecraft.used:minecraft.water_bucket', allow_reject=True)
    r.cmd('scoreboard players set tester1 mlg_probe_use 0')
    p.call('selectHotbar', 0)
    time.sleep(5)
    before = p.call('getGameState')['self']
    assert before['onGround'] and before['hp'] == 20 and before['held'] == 'minecraft:diamond_sword', before
    bx, by, bz = map(float, before['pos'].split(','))
    assert abs(bx-(3200+args.offset))<.1 and abs(by-y)<.1 and abs(bz-1100.5)<.1, before
    rec_start(20)
    recording = True
    code = r'''
import json,time
from py4j.java_gateway import JavaGateway,GatewayParameters
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm
c=j.net.minecraft.class_310.method_1551();a=j.adris.altoclef.AltoClef.getInstance();lookup=j.java.lang.invoke.MethodHandles.lookup()
def on_client(h):
 f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));c.execute(f);return f.get()
empty=g.new_array(j.java.lang.Class,0)
on_client(lookup.unreflect(j.java.lang.Class.forName('kaptainwutax.tungsten.TungstenMod').getMethod('resetAllState',empty)))
cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task')
on_client(lookup.unreflect(a.getClass().getMethod('runUserTask',cs)).bindTo(a).bindTo(j.adris.altoclef.tasks.movement.IdleTask()))
print('READY',flush=True)
rows=[];start=time.monotonic()
while time.monotonic()-start<8:
 state=dict(g.entry_point.getGameState()['self'])
 rows.append({'t':round(time.monotonic()-start,3),'state':state,'chain':g.entry_point.getTaskChainString(),'fps':g.entry_point.getPerfStats().get('fps',0)})
 time.sleep(.02)
chain=a.getMLGBucketChain()
current=on_client(lookup.unreflect(chain.getClass().getMethod('getCurrentTask',empty)).bindTo(chain))
placed=None
if current is not None and str(current.getClass().getName())=='adris.altoclef.tasks.movement.MLGBucketTask':
 placed=on_client(lookup.unreflect(current.getClass().getMethod('getWaterPlacedPos',empty)).bindTo(current))
done=on_client(lookup.unreflect(chain.getClass().getMethod('doneMLG',empty)).bindTo(chain))
print(json.dumps({'samples':rows,'attempted_source':str(placed) if placed else None,'recovery_released':bool(done)}))
'''
    worker = subprocess.Popen(['docker', 'exec', 'uctest-mc-tester1', 'python3', '-u', '-c', code],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    import select
    if not select.select([worker.stdout], [], [], 15)[0]:
        raise RuntimeError('collector did not start')
    marker = worker.stdout.readline().strip()
    if marker != 'READY':
        raise RuntimeError(marker + worker.stderr.read())
    r.cmd(f'setblock 3200 {y-1} 1100 air')
    out, err = worker.communicate(timeout=15)
    if worker.returncode:
        raise RuntimeError(err)
    data = json.loads(out)
    states = [v['state'] for v in data['samples']]
    import statistics
    median_fps = statistics.median(v['fps'] for v in data['samples'])
    active = any('MLG' in v['chain'] for v in data['samples'])
    used = any(s['held'] == 'minecraft:bucket' for s in states)
    final = states[-1]
    fx, fy, fz = map(float, final['pos'].split(','))
    landed = 3195 <= fx <= 3206 and 1095 <= fz <= 1106 and abs(fy+60)<1.2
    death_reply = r.cmd('scoreboard players get tester1 mlg_probe_deaths')
    deaths = int(re.search(r'has (-?\d+)', death_reply).group(1))
    healthy = deaths == 0 and all(s['hp'] == 20 for s in states)
    use_reply = r.cmd('scoreboard players get tester1 mlg_probe_use')
    bucket_uses = int(re.search(r'has (-?\d+)', use_reply).group(1))
    inventory = p.call('getInventoryFull')
    water_buckets = sum(v.get('count',0) for v in inventory['slots'] if v.get('item') == 'minecraft:water_bucket')
    rescued = active and bucket_uses > 0 and landed and healthy
    complete = rescued and data['recovery_released'] and (not args.require_refill or water_buckets > 0)
    data.update({'before': before, 'height': args.height, 'inventory': args.inventory, 'surface': args.surface, 'shield': args.shield, 'safe_bank': args.safe_bank, 'hotbar_slot': args.hotbar_slot,
                 'water_buckets_after': water_buckets, 'require_refill': args.require_refill, 'median_fps': median_fps, 'deaths': deaths, 'bucket_uses': bucket_uses, 'active': active, 'used_bucket': used, 'landed': landed, 'healthy': healthy,
                 'rescued': rescued, 'pass': (active and not healthy) if args.expect_bug or args.no_bucket else complete})
    data['valid'] = median_fps >= 14
    data['pass'] = data['pass'] and data['valid']
    (root / f'{args.tag}.json').write_text(json.dumps(data, indent=2))
    p.screenshot(str(root / f'{args.tag}.png'))
    print({k: v for k, v in data.items() if k != 'samples'}, flush=True)
    if median_fps < 14:
        raise RuntimeError('stand FPS below acceptance floor')
    if not data['pass']:
        raise RuntimeError('MLG fall gate failed')
finally:
    if worker is not None and worker.poll() is None:
        worker.kill()
    p.call('stopPathing')
    p.call('closeOpenScreen')
    if recording:
        rec_stop(str(root / f'{args.tag}.mp4'))
    p.call('tungstenSetting', 'botFpsNoIdleThrottle', old)
    r.cmd('gamemode survival tester1')
    r.cmd('scoreboard objectives remove mlg_probe_deaths')
    r.cmd('scoreboard objectives remove mlg_probe_use')
    r.cmd('forceload remove 3195 1095 3205 1105')
