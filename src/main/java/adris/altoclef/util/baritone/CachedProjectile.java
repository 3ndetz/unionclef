package adris.altoclef.util.baritone;

import adris.altoclef.util.time.TimerGame;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Type;

public class CachedProjectile {
    private final TimerGame lastCache = new TimerGame(2);
    public Vec3d velocity;
    public Vec3d position;
    public double gravity;
    public Type projectileType;
    private Vec3d cachedHit;
    private boolean cacheHeld = false;

    public Vec3d getCachedHit() {
        return cachedHit;
    }

    public void setCacheHit(Vec3d cache) {
        cachedHit = cache;
        cacheHeld = true;
        lastCache.reset();
    }

    public boolean needsToRecache() {
        return !cacheHeld || lastCache.elapsed();
    }
}
