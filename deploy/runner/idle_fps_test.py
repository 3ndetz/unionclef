#!/usr/bin/env python3
"""Exercise the real client's inactivity limiter with controlled timer ages.

Timer/window-state fixtures and configuration are restored in finally. The window
minimization branch is injected as state, not an OS window-manager test. Finishes
disconnected after testing the actual menu boundary.
"""
import argparse
import json
import subprocess
import time
from pathlib import Path

from gamer_smoke import rec_start, rec_stop
from uctest.harness import Py4jClient, Rcon

CODE = r'''
from py4j.java_gateway import JavaGateway, GatewayParameters, get_field
import json,time
j=(g:=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True))).jvm
c=j.net.minecraft.class_310.method_1551();lim=c.method_61964();window=c.method_22683()
cfg=j.kaptainwutax.tungsten.TungstenConfig.get();fpsOption=get_field(c,'field_1690').method_42524()
def handle(obj,name,types=(),args=()):
    cs=g.new_array(j.java.lang.Class,len(types))
    for i,t in enumerate(types):cs[i]=t
    m=obj.getClass().getMethod(name,cs)
    h=j.java.lang.invoke.MethodHandles.lookup().unreflect(m).bindTo(obj)
    if args:
        values=g.new_array(j.java.lang.Object,len(args))
        for i,v in enumerate(args):values[i]=v
        h=j.java.lang.invoke.MethodHandles.insertArguments(h,0,values)
    return h
def invoke(h):
    proxy=j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.util.concurrent.Callable'),h)
    f=j.java.util.concurrent.FutureTask(proxy);c.execute(f);return f.get()
def call(obj,name,types=(),args=()):return invoke(handle(obj,name,types,args))
def field(obj,name):
    f=obj.getClass().getDeclaredField(name);f.setAccessible(True);return f
objclass=j.java.lang.Class.forName('java.lang.Object')
longtype=j.java.lang.Long.TYPE;booltype=j.java.lang.Boolean.TYPE;inttype=j.java.lang.Integer.TYPE
timer=field(lim,'field_52733');minimized=field(window,'field_52735');enabled=field(cfg,'botFpsNoIdleThrottle')
old=(timer.getLong(lim),int(call(fpsOption,'method_41753')),minimized.getBoolean(window),enabled.getBoolean(cfg))
def flag(v):call(enabled,'setBoolean',(objclass,booltype),(cfg,j.java.lang.Boolean(v)))
def age(ms):call(timer,'setLong',(objclass,longtype),(lim,j.java.lang.Long(j.net.minecraft.class_156.method_658()-ms)))
def cap(n):call(fpsOption,'method_41748',(objclass,),(j.java.lang.Integer(n),))
def mini(v):call(minimized,'setBoolean',(objclass,booltype),(window,j.java.lang.Boolean(v)))
rows=[]
def check(name,on,ms,maximum,reason,limit,iconified=False,check_fps=False):
    flag(on);cap(maximum);mini(iconified);age(ms)
    if iconified:
        # Inject minimization and query it in one client-thread operation.
        # This exercises limiter behavior, not OS window management.
        setter=handle(minimized,'setBoolean',(objclass,booltype),(window,j.java.lang.Boolean(True)))
        actual_reason=str(invoke(j.java.lang.invoke.MethodHandles.foldArguments(handle(lim,'method_66514'),setter)))
        actual_limit=int(invoke(j.java.lang.invoke.MethodHandles.foldArguments(handle(lim,'method_61937'),setter)))
        mini(False)
    else:
        actual_reason=str(call(lim,'method_66514'));actual_limit=int(call(lim,'method_61937'))
    time.sleep(2)
    fps=g.entry_point.getPerfStats().get('fps')
    fps_samples=[fps]
    if check_fps:
        stable=0
        for _ in range(8):
            stable=stable+1 if fps>=14 else 0
            if stable>=2:break
            time.sleep(1);fps=g.entry_point.getPerfStats().get('fps');fps_samples.append(fps)
    row=dict(case=name,enabled=on,age_ms=ms,max_fps=maximum,reason=actual_reason,limit=actual_limit,fps=fps,fps_samples=fps_samples)
    rows.append(row)
    assert actual_reason==reason and actual_limit==limit,row
    if check_fps:assert stable>=2,row
try:
    for i in range(3):
        check('long-off-'+str(i),False,660000,30,'LONG_AFK',10)
        check('long-on-'+str(i),True,660000,30,'LONG_AFK',30,check_fps=True)
    check('short-off',False,120000,60,'SHORT_AFK',30)
    check('short-on',True,120000,60,'SHORT_AFK',60)
    check('configured-cap',True,660000,20,'LONG_AFK',20)
    check('recent-off',False,0,30,'NONE',30)
    check('minimized-on',True,660000,30,'WINDOW_ICONIFIED',30,iconified=True)
    mini(False)
    a=j.adris.altoclef.AltoClef.getInstance();menu=get_field(a.getTaskRunner(),'gameMenuTaskChain');c.execute(menu._innerDisconnect(c));time.sleep(2)
    assert not g.entry_point.inGame()
    check('menu-on',True,660000,30,'LONG_AFK',30)
finally:
    mini(old[2]);cap(old[1]);flag(old[3]);call(timer,'setLong',(objclass,longtype),(lim,j.java.lang.Long(old[0])))
print(json.dumps(dict(passed=True,controls=rows)))
'''


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--output-dir', type=Path, required=True)
    ap.add_argument('--tag', required=True)
    args = ap.parse_args()
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
    p.call('stopPathing')
    rec_start(85)
    try:
        result = subprocess.run(['docker', 'exec', 'uctest-mc-tester1', 'python3', '-c', CODE],
                                capture_output=True, text=True, timeout=100, check=True)
        data = json.loads(result.stdout)
        (root / f'{args.tag}.json').write_text(json.dumps(data, indent=2))
        print(data, flush=True)
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(exc.stderr) from None
    finally:
        p.call('stopPathing')
        rec_stop(str(root / f'{args.tag}.mp4'))


if __name__ == '__main__':
    main()
