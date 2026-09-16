"""Read or scope food reservations on the client thread for live tests."""
import json,subprocess
CODE=r'''
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
q=json.loads(sys.argv[1]);g=JavaGateway(gateway_parameters=GatewayParameters(port=25333,auto_convert=True));j=g.jvm;a=j.adris.altoclef.AltoClef.getInstance();b=a.getBehaviour();settings=a.getModSettings();c=j.net.minecraft.class_310.method_1551();lookup=j.java.lang.invoke.MethodHandles.lookup()
def run(h,void=False):
 proxy=j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(j.java.lang.Class.forName('java.lang.Runnable' if void else 'java.util.concurrent.Callable'),h)
 f=j.java.util.concurrent.FutureTask(proxy,None) if void else j.java.util.concurrent.FutureTask(proxy)
 c.execute(f);return f.get()
def invoke(obj,name,args=(),static=False):
 cls=obj if static else obj.getClass();m=next(m for m in cls.getMethods() if m.getName()==name and m.getParameterCount()==len(args));h=lookup.unreflect(m)
 if not static:h=h.bindTo(obj)
 if args:
  v=g.new_array(j.java.lang.Object,len(args))
  for i,x in enumerate(args):v[i]=x
  h=j.java.lang.invoke.MethodHandles.insertArguments(h,0,v)
 return run(h,str(m.getReturnType().getName())=='void')
def set_unused(value):
 f=settings.getClass().getDeclaredField('throwAwayUnusedItems');f.setAccessible(True);h=lookup.unreflectSetter(f).bindTo(settings);v=g.new_array(j.java.lang.Object,1);v[0]=j.java.lang.Boolean(value);run(j.java.lang.invoke.MethodHandles.insertArguments(h,0,v),True)
def depth():
 f=b.getClass().getDeclaredField('states');f.setAccessible(True);return f.get(b).size()
identifier=j.net.minecraft.class_2960.method_60654('minecraft:'+q.get('item','bread'))
registry=j.java.lang.Class.forName('net.minecraft.class_7923').getField('field_41178').get(None)
item=invoke(registry,'method_63535',[identifier]);assert item is not None
items=g.new_array(j.net.minecraft.class_1792,1);items[0]=item
if q['mode']=='enter':
 out={'depth':depth(),'throwaway_unused':settings.shouldThrowawayUnusedItems()};invoke(b,'push')
 try:
  if q.get('reserve'):invoke(b,'addProtectedItems',[items])
  if q.get('keep_unused'):set_unused(False)
 except Exception:
  invoke(b,'pop');set_unused(out['throwaway_unused']);raise
elif q['mode']=='exit':
 invoke(b,'pop');set_unused(q['old']['throwaway_unused']);out={'depth':depth()};assert out['depth']==q['old']['depth'],out
else:
 stack=j.net.minecraft.class_1799(items[0],1);cls=j.java.lang.Class.forName('adris.altoclef.util.helpers.ItemHelper')
 consume=bool(invoke(cls,'canConsumeFood',[a,stack],True)) if any(m.getName()=='canConsumeFood' for m in cls.getMethods()) else None
 discard_protected=bool(invoke(b,'isDiscardProtected',[items[0]])) if any(m.getName()=='isDiscardProtected' for m in b.getClass().getMethods()) else None
 out={'consume':consume,'discard':bool(invoke(cls,'canThrowAwayStack',[a,stack],True)),'reserved':bool(invoke(b,'isProtected',[items[0]])),'discard_protected':discard_protected}
print(json.dumps(out))
'''
def policy_probe(**query):
 r=subprocess.run(['docker','exec','-i','uctest-mc-tester1','python3','-c',CODE,json.dumps(query)],capture_output=True,text=True,timeout=20)
 if r.returncode:raise RuntimeError(r.stderr.strip()[-2500:])
 return json.loads(r.stdout.strip())
