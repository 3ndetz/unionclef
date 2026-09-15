#!/usr/bin/env python3
"""Open a capped crafting table from a diagonally elevated starting cell.

The fixture reproduces a false adjacency arrival above a buried container.
--open removes the cap as a control. Geometry/settings reset for each run.
"""
import argparse,json,subprocess,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
from gamer_smoke import rec_start,rec_stop
ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--tag',required=True);ap.add_argument('--output-dir',type=Path,required=True);ap.add_argument('--connect',action='store_true');ap.add_argument('--expect-bug',action='store_true');ap.add_argument('--open',action='store_true');a=ap.parse_args()
root=a.output_dir.resolve();root.mkdir(parents=True,exist_ok=True);p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server');recording=False
if a.connect:
 p.call('ConnectToServer','test-server')
 for _ in range(45):
  time.sleep(1)
  if 'tester1' in r.cmd('list'):break
 else:raise RuntimeError('flat server login failed')
old={k:p.call('tungstenSetting',k,'').split('=',1)[1] for k in ['allowBreak','allowPlace']}
try:
 p.call('stopPathing');p.call('closeOpenScreen');p.call('tungstenSetting','allowBreak','true');p.call('tungstenSetting','allowPlace','false')
 for cmd in ['gamemode spectator tester1','forceload add 1980 598 1986 602','fill 1980 -62 598 1986 -56 602 bedrock',
 'fill 1981 -60 600 1984 -57 600 air','fill 1981 -60 600 1984 -60 600 dirt',
 'setblock 1983 -60 600 crafting_table','fill 1983 -59 600 1983 -58 600 '+('air' if a.open else 'dirt'),
 'setblock 1980 -58 600 glowstone','clear tester1','give tester1 iron_pickaxe','give tester1 iron_shovel',
 'tp tester1 1982.5 -59 600.5 -90 0','gamemode survival tester1','effect give tester1 instant_health 1 5 true','effect give tester1 saturation 1 5 true']:r.cmd(cmd)
 time.sleep(1);before=p.call('getGameState')['self'];assert before['onGround'] and before['hp']==20,before
 rec_start(45);recording=True
 code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters,get_field
import time,json
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;a=j.adris.altoclef.AltoClef.getInstance();target=j.net.minecraft.class_2338(1983,-60,600);t=j.adris.altoclef.tasks.InteractWithBlockTask(target)
c=j.net.minecraft.class_310.method_1551();lookup=j.java.lang.invoke.MethodHandles.lookup()
def on_client(h):
 f=j.java.util.concurrent.FutureTask(j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h));c.execute(f);return f.get()
empty=g.new_array(j.java.lang.Class,0)
player=on_client(lookup.unreflect(a.getClass().getMethod('getPlayer',empty)).bindTo(a))
handler=lookup.unreflectGetter(player.getClass().getField('field_7512')).bindTo(player)
cs=g.new_array(j.java.lang.Class,1);cs[0]=j.java.lang.Class.forName('net.minecraft.class_2338')
reach=lookup.unreflect(j.java.lang.Class.forName('adris.altoclef.util.helpers.LookHelper').getMethod('getReach',cs)).bindTo(target)
initial={'adjacent':j.kaptainwutax.tungsten.path.fast.FastPlanner.adjacentToBlock(j.net.minecraft.class_2338(1982,-59,600),target),'reach':on_client(reach).isPresent()}
cs[0]=j.java.lang.Class.forName('adris.altoclef.tasksystem.Task');on_client(lookup.unreflect(a.getClass().getMethod('runUserTask',cs)).bindTo(a).bindTo(t))
rows=[];start=time.monotonic()
while time.monotonic()-start<25:
 state=dict(g.entry_point.getGameState()['self']);x,y,z=map(float,state['pos'].split(','));screen=str(on_client(handler).getClass().getName());rows.append({'t':round(time.monotonic()-start,3),'x':x,'y':y,'hp':state['hp'],'handler':screen,'reach':on_client(reach).isPresent()})
 if screen=='net.minecraft.class_1714':break
 time.sleep(.15)
print(json.dumps({'initial':initial,'samples':rows}))
'''
 q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],capture_output=True,text=True,timeout=35)
 if q.returncode:raise RuntimeError(q.stderr)
 d=json.loads(q.stdout);rows=d['samples'];opened=rows[-1]['handler']=='net.minecraft.class_1714'
 preserved='passed' in r.cmd('execute if block 1983 -60 600 crafting_table').lower()
 passed=(not opened and d['initial']['adjacent'] and not d['initial']['reach']) if a.expect_bug else (opened and preserved and min(v['hp'] for v in rows)==20)
 d.update({'pass':passed,'expected_bug':a.expect_bug,'open':a.open,'table_preserved':preserved,'before':before});(root/f'{a.tag}.json').write_text(json.dumps(d,indent=2));p.screenshot(str(root/f'{a.tag}.png'));print({k:v for k,v in d.items() if k!='samples'},rows[-1],flush=True)
 if not passed:raise RuntimeError('buried container gate failed')
finally:
 p.call('stopPathing');p.call('closeOpenScreen')
 if recording:rec_stop(str(root/f'{a.tag}.mp4'))
 for k,v in old.items():p.call('tungstenSetting',k,v)
 r.cmd('forceload remove 1980 598 1986 602')
