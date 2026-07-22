package baritone.api.noop;

import baritone.api.IBaritoneProvider;

import java.lang.reflect.Proxy;
import java.util.Collections;

/**
 * No-op stub when Baritone can't initialize (incompatible MC version).
 * Uses dynamic proxy — all methods return safe defaults.
 */
public class NoopBaritoneProvider {

    @SuppressWarnings("unchecked")
    public static IBaritoneProvider create() {
        return (IBaritoneProvider) Proxy.newProxyInstance(
            IBaritoneProvider.class.getClassLoader(),
            new Class[]{IBaritoneProvider.class},
            (proxy, method, args) -> {
                Class<?> ret = method.getReturnType();
                if (ret == boolean.class) return false;
                if (ret == int.class) return 0;
                if (ret == void.class) return null;
                if (ret == java.util.List.class) return Collections.emptyList();
                if (ret.isInterface()) {
                    return NoopBaritone.create();
                }
                return null;
            }
        );
    }
}
