#!/usr/bin/env python3
"""Check gamer resource decisions across interruption and inventory-losing respawn.

Uses only a disposable flat-server fixture. Starts cooking with tools and raw food,
interrupts the existing root on the client thread, then kills the player and removes
local drops to represent equipment lost far from spawn. The resumed task must gather
wood for tools instead of hunting the old raw-food target. No inventory tracker cache
is polled from a background thread.
"""
import argparse
import json
import subprocess
import time
from pathlib import Path
from uctest.harness import Py4jClient, Rcon
from gamer_smoke import rec_start, rec_stop

INTERRUPT = r'''
import time,json
from py4j.java_gateway import JavaGateway,GatewayParameters
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;a=j.adris.altoclef.AltoClef.getInstance()
t=a.getTaskRunner().getCurrentTaskChain().getTasks()[0]
f=t.getClass().getDeclaredField('lastTask');f.setAccessible(True)
before=j.java.lang.System.identityHashCode(f.get(t));player=j.java.lang.System.identityHashCode(a.getPlayer())
classes=g.new_array(j.java.lang.Class,1);classes[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task')
m=t.getClass().getMethod('interrupt',classes)
h=j.java.lang.invoke.MethodHandles.publicLookup().unreflect(m).bindTo(t).bindTo(None)
r=j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable'),h)
j.net.minecraft.class_310.method_1551().execute(r);time.sleep(2)
print(json.dumps({'same_task':before==j.java.lang.System.identityHashCode(f.get(t)), 'same_player':player==j.java.lang.System.identityHashCode(a.getPlayer())}))
'''

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--output-dir',type=Path,required=True)
    ap.add_argument('--repeat',type=int,default=3)
    ap.add_argument('--record',action='store_true')
    args=ap.parse_args()
    if not 1<=args.repeat<=6:ap.error('repeat must be 1..6')
    root=args.output_dir.resolve();root.mkdir(parents=True,exist_ok=True)
    p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server')
    p.call('ConnectToServer','test-server')
    for _ in range(45):
        time.sleep(1)
        if p.call('getGameState').get('self'):break
    else:raise RuntimeError('client did not join')
    rows=[]
    if args.record:rec_start(args.repeat*90+30)
    try:
        for trial in range(args.repeat):
            p.call('stopPathing');time.sleep(.5)
            for c in ['forceload add 1496 316 1512 324',
                      'fill 1496 -61 316 1512 -55 324 bedrock',
                      'fill 1497 -60 317 1511 -56 323 air',
                      'setblock 1500 -55 320 glowstone','clear tester1',
                      'gamemode survival tester1','gamerule keep_inventory false',
                      'spawnpoint tester1 1500 -60 320','tp tester1 1500.5 -60 320.5',
                      'effect give tester1 instant_health 1 5 true',
                      'effect give tester1 saturation 1 5 true']:
                r.cmd(c)
            for item,count in [('stone_pickaxe',1),('stone_axe',1),('stone_sword',1),
                               ('stone_shovel',1),('oak_log',16),('cobblestone',16),
                               ('crafting_table',1),('smoker',1),('porkchop',8)]:
                r.cmd(f'give tester1 {item} {count}')
            p.call('setTungstenPathing',True);p.call('ExecuteCommand','@gamer')
            for _ in range(20):
                time.sleep(1);chain=p.call('getTaskChainString')
                if 'Collect Fuel' in chain:break
            else:raise RuntimeError('fixture did not reach cooking fuel stage')
            q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',INTERRUPT],capture_output=True,text=True,check=True,timeout=15)
            control=json.loads(q.stdout.strip().splitlines()[-1])
            assert control['same_task'] and control['same_player'],control
            r.cmd('kill tester1')
            r.cmd('kill @e[type=item,x=1500,y=-60,z=320,distance=..30]')
            timeline=[];passed=False
            for step in range(15):
                time.sleep(1)
                state,chain,perf=p.batch([('getGameState',),('getTaskChainString',),('getPerfStats',)])
                row={'step':step,'state':state,'chain':chain,'fps':perf.get('fps')};timeline.append(row)
                alive=state.get('self',{}).get('hp',0)>0
                # A new basic-tool resource tree is the decision under test. Geometry
                # intentionally has no resources; mining/crafting are separate gates.
                if alive and ('wooden_pickaxe' in chain or 'wooden_axe' in chain) and 'cooked_porkchop' not in chain:
                    passed=True;break
            p.screenshot(str(root/f'respawn-{trial+1}.png'))
            rows.append({'trial':trial+1,'interruption':control,'passed':passed,'timeline':timeline})
            (root/'results.json').write_text(json.dumps(rows,indent=2))
            print(json.dumps({'trial':trial+1,'passed':passed,'interruption':control,'last':timeline[-1]}),flush=True)
            if not passed:raise RuntimeError('stale cooking task survived respawn')
    finally:
        p.call('stopPathing');r.cmd('forceload remove 1496 316 1512 324')
        if args.record:rec_stop(str(root/'respawn-resources.mp4'))

if __name__=='__main__':main()
