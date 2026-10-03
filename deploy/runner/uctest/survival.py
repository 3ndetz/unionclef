"""End an owned survival observation before stopping its defence.

The world keeps ticking during diagnostics and checkpoint copying. Disconnect
first, confirm it, then stop tasks; a failed disconnect must leave defence live.
No container, server, inventory, health or game rules are changed.
"""
import inspect
import json

from . import process


def disconnect_before_stop(in_game, disconnect, stop, *, timeout=5.0,
                           clock=None, sleep=None):
    """Confirm world exit before calling stop; raise without stop on timeout."""
    import time
    clock = clock or time.monotonic
    sleep = sleep or time.sleep
    # An offline menu can still own a pending reconnect. Cancel that intent
    # even when the world is already absent before confirming the boundary.
    disconnect()
    deadline = clock() + timeout
    while in_game():
        if clock() >= deadline:
            raise RuntimeError('Disconnect not confirmed; keeping survival active')
        sleep(0.1)
    result = stop()
    if in_game():
        raise RuntimeError('Client rejoined during survival teardown')
    return result


def disconnect_gateway(gateway):
    """Cancel menu reconnects through the public logout primitive, then stop."""
    import time
    mc, j = gateway.entry_point, gateway.jvm
    client = j.net.minecraft.class_310.method_1551()

    def disconnect():
        if not mc.disconnectFromServer():
            raise RuntimeError('Deliberate logout failed; keeping survival active')

    def stop():
        # stopPathing queues @stop even after logout. Its return is only an
        # enqueue acknowledgement; the following client-thread barrier is proof.
        mc.stopPathing()
        method = next(m for m in mc.getClass().getMethods()
                      if m.getName() == 'getRunnerStatus' and m.getParameterCount() == 0)
        handle = j.java.lang.invoke.MethodHandles.lookup().unreflect(method).bindTo(mc)
        handle = handle.asType(j.java.lang.invoke.MethodType.methodType(
            j.java.lang.Class.forName('java.lang.Object')))
        work = j.java.lang.invoke.MethodHandleProxies.asInterfaceInstance(
            j.java.lang.Class.forName('java.util.concurrent.Callable'), handle)
        future = j.java.util.concurrent.FutureTask(work)
        client.execute(future)
        runner = str(future.get(20, j.java.util.concurrent.TimeUnit.SECONDS))
        if not runner.startswith('active=false'):
            raise RuntimeError('Gamer stop not confirmed: ' + runner)
        return {'runner': runner, 'inGame': False, 'epochMs': int(time.time() * 1000)}

    return disconnect_before_stop(mc.inGame, disconnect, stop)


# Containers have py4j, not the host runner package. Ship these exact functions
# rather than maintaining a second copy of the safety sequence in each wrapper.
GATEWAY_SOURCE = (inspect.getsource(disconnect_before_stop) + '\n' +
                  inspect.getsource(disconnect_gateway))


def disconnect_and_stop(container, port=25333):
    """Disconnect one owned client and return its confirmed inactive status."""
    code = GATEWAY_SOURCE + """
import json, sys
from py4j.java_gateway import JavaGateway, GatewayParameters
gateway = JavaGateway(gateway_parameters=GatewayParameters(
    port=int(sys.argv[1]), auto_convert=True))
try:
    print(json.dumps(disconnect_gateway(gateway)))
finally:
    gateway.close()
"""
    result = process.run(['docker', 'exec', container, 'python3', '-c', code, str(port)],
                         capture_output=True, text=True, check=True, timeout=35)
    return json.loads(result.stdout.strip().splitlines()[-1])
