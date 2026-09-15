#!/usr/bin/env python3
"""Check recovery overlap against live collision geometry, including an embedded fence."""
import argparse,json,subprocess,time
from pathlib import Path
from uctest.harness import Py4jClient,Rcon
ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--output',type=Path,required=True);a=ap.parse_args()
p=Py4jClient('uctest-mc-tester1');r=Rcon('uctest-server');rows=[]
code=r'''
from py4j.java_gateway import JavaGateway,GatewayParameters
import json
g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;a=j.adris.altoclef.AltoClef.getInstance();p=a.getPlayer();helper=j.adris.altoclef.util.helpers.WorldHelper
print(json.dumps({'x':p.method_23317(),'y':p.method_23318(),'overlap':helper.intersectsPlayerCollision(a,j.net.minecraft.class_2338(2182,-60,840)),'adjacent':helper.intersectsPlayerCollision(a,j.net.minecraft.class_2338(2183,-60,840))}))
'''
try:
 p.call('stopPathing')
 for c in ['gamemode spectator tester1','forceload add 2180 838 2185 842','fill 2180 -61 838 2185 -61 842 bedrock','fill 2180 -60 838 2185 -57 842 air','setblock 2183 -60 840 oak_fence','tp tester1 2182.5 -60 840.5','gamemode survival tester1']:r.cmd(c)
 for block,expected in [('air',False),('short_grass',False),('dandelion',False),('oak_fence',True),('oak_fence_gate[facing=east,open=false]',True),('oak_fence_gate[facing=east,open=true]',False)]:
  r.cmd('setblock 2182 -60 840 '+block);r.cmd('tp tester1 2182.5 -60 840.5');time.sleep(.25)
  q=subprocess.run(['docker','exec','uctest-mc-tester1','python3','-c',code],check=True,capture_output=True,text=True);d=json.loads(q.stdout);d.update({'block':block,'expected':expected});rows.append(d)
  assert abs(d['x']-2182.5)<.01 and abs(d['y']+60)<.01,d
  assert d['overlap']==expected and not d['adjacent'],d
 a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(rows,indent=2));print(json.dumps(rows))
finally:
 r.cmd('fill 2182 -60 840 2183 -59 840 air');r.cmd('forceload remove 2180 838 2185 842')
