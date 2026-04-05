package baritone.api.noop;

import baritone.api.IBaritone;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;

/**
 * Dynamic proxy that returns safe defaults for all IBaritone methods.
 * Used when Baritone can't initialize (incompatible MC version).
 */
public class NoopBaritone {

    @SuppressWarnings("unchecked")
    static IBaritone create() {
        return (IBaritone) Proxy.newProxyInstance(
            IBaritone.class.getClassLoader(),
            new Class[]{IBaritone.class},
            new NoopHandler()
        );
    }

    private static class NoopHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            Class<?> ret = method.getReturnType();
            if (ret == boolean.class) return false;
            if (ret == int.class) return 0;
            if (ret == long.class) return 0L;
            if (ret == float.class) return 0f;
            if (ret == double.class) return 0d;
            if (ret == void.class) return null;
            if (ret == java.util.List.class) return Collections.emptyList();
            // For interface return types, create nested proxy
            if (ret.isInterface()) {
                return Proxy.newProxyInstance(ret.getClassLoader(), new Class[]{ret}, this);
            }
            return null;
        }
    }
}
