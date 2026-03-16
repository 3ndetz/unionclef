package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tungsten pathfinder integration via reflection.
 * Fallback when Baritone fails (NaN, stuck, no path).
 * When activated, Tungsten LOCKS control for LOCK_DURATION_MS to avoid being
 * immediately interrupted by progress checkers or Baritone restarts.
 * All methods are safe to call even if Tungsten is not installed — they no-op.
 */
public class TungstenHelper {

    private static Boolean loaded = null;
    private static boolean active = false;
    private static int failCount = 0;
    private static long lastStartTime = 0;

    private static final int MAX_FAIL_COUNT = 5;
    private static final long COOLDOWN_MS = 1000;
    private static final long LOCK_DURATION_MS = 30_000; // 30 sec exclusive control
    private static final long RETARGET_INTERVAL_MS = 3000; // re-send target every 3 sec

    private static long lockUntil = 0;       // Tungsten has exclusive control until this time
    private static long lastRetargetTime = 0;
    private static Entity lockedEntity = null; // entity we're chasing during lock

    // Cached reflection handles
    private static boolean reflectionReady = false;
    private static Object pathfinderInstance;    // TungstenModDataContainer.PATHFINDER
    private static Object executorInstance;      // TungstenModDataContainer.EXECUTOR
    private static AtomicBoolean pathfinderActive;
    private static AtomicBoolean pathfinderStop;
    private static Field executorStopField;
    private static Method findMethod;            // PathFinder.find(WorldView, Vec3d, PlayerEntity)
    private static Method executorIsRunning;
    private static Field searchTimeoutField;
    private static Field minPathSizeField;
    private static Field minDistPathField;

    public static boolean isTungstenLoaded() {
        if (loaded == null) {
            loaded = FabricLoader.getInstance().isModLoaded("tungsten");
            if (loaded) initReflection();
        }
        return loaded && reflectionReady;
    }

    private static void initReflection() {
        try {
            Class<?> containerClass = Class.forName("kaptainwutax.tungsten.TungstenModDataContainer");
            pathfinderInstance = containerClass.getField("PATHFINDER").get(null);
            executorInstance = containerClass.getField("EXECUTOR").get(null);

            Class<?> pfClass = pathfinderInstance.getClass();
            pathfinderActive = (AtomicBoolean) pfClass.getField("active").get(pathfinderInstance);
            pathfinderStop = (AtomicBoolean) pfClass.getField("stop").get(pathfinderInstance);

            searchTimeoutField = pfClass.getField("searchTimeoutMs");
            minPathSizeField = pfClass.getField("minPathSizeForTimeout");
            minDistPathField = pfClass.getField("minDistPath");

            findMethod = pfClass.getMethod("find",
                    Class.forName("net.minecraft.world.WorldView"),
                    Vec3d.class,
                    Class.forName("net.minecraft.entity.player.PlayerEntity"));

            Class<?> execClass = executorInstance.getClass();
            executorIsRunning = execClass.getMethod("isRunning");
            executorStopField = execClass.getField("stop");

            reflectionReady = true;
            Debug.logInternal("[TungstenHelper] Reflection init OK");
        } catch (Exception e) {
            reflectionReady = false;
            Debug.logWarning("[TungstenHelper] Reflection init failed: " + e.getMessage());
        }
    }

    /**
     * Try Tungsten pathfinding to a position. Returns true if Tungsten was started.
     * Acquires a 30-second lock — Baritone should not interfere during this time.
     */
    public static boolean tryPathTo(Vec3d target) {
        if (!isTungstenLoaded()) return false;
        if (failCount >= MAX_FAIL_COUNT) return false;

        long now = System.currentTimeMillis();
        if (now - lastStartTime < COOLDOWN_MS && !isLocked()) return false;

        try {
            var player = AltoClef.getInstance().getPlayer();
            var world = AltoClef.getInstance().getWorld();
            if (player == null || world == null) return false;

            boolean tungstenBusy = pathfinderActive.get()
                    || (boolean) executorIsRunning.invoke(executorInstance);

            // If locked and Tungsten is still working, just retarget periodically
            if (isLocked() && tungstenBusy) {
                if (now - lastRetargetTime > RETARGET_INTERVAL_MS) {
                    searchTimeoutField.set(pathfinderInstance, 2000L);
                    minPathSizeField.set(pathfinderInstance, 2);
                    minDistPathField.set(pathfinderInstance, 0.3);
                    findMethod.invoke(pathfinderInstance, world, target, player);
                    lastRetargetTime = now;
                    Debug.logInternal("[TungstenHelper] Retargeted to " + formatVec(target));
                }
                return true;
            }

            // If locked but Tungsten finished a segment, restart it
            if (isLocked() && !tungstenBusy) {
                searchTimeoutField.set(pathfinderInstance, 2000L);
                minPathSizeField.set(pathfinderInstance, 2);
                minDistPathField.set(pathfinderInstance, 0.3);
                findMethod.invoke(pathfinderInstance, world, target, player);
                lastStartTime = now;
                lastRetargetTime = now;
                Debug.logInternal("[TungstenHelper] Lock active, restarting path to " + formatVec(target));
                return true;
            }

            // Fresh start — acquire lock
            searchTimeoutField.set(pathfinderInstance, 2000L);
            minPathSizeField.set(pathfinderInstance, 2);
            minDistPathField.set(pathfinderInstance, 0.3);

            findMethod.invoke(pathfinderInstance, world, target, player);
            lastStartTime = now;
            lastRetargetTime = now;
            lockUntil = now + LOCK_DURATION_MS;
            active = true;

            Debug.logInternal("[TungstenHelper] LOCKED for 30s, pathfinding to " + formatVec(target));
            return true;
        } catch (Exception e) {
            Debug.logWarning("[TungstenHelper] Failed to start: " + e.getMessage());
            failCount++;
            return false;
        }
    }

    /** Try Tungsten pathfinding to an entity (with lock + retargeting). */
    public static boolean tryPathToEntity(Entity entity) {
        if (entity == null || entity.isRemoved()) return false;
        lockedEntity = entity;
        return tryPathTo(entity.getPos());
    }

    /**
     * Call every tick during lock to keep Tungsten chasing the entity.
     * Returns true if Tungsten is locked and working.
     */
    public static boolean tickLock() {
        if (!isLocked()) return false;
        if (lockedEntity != null && !lockedEntity.isRemoved()) {
            tryPathTo(lockedEntity.getPos());
        }
        return true;
    }

    /** Returns the timestamp when lock expires (for debug display). */
    public static long lockUntilMs() {
        return lockUntil;
    }

    /** Is Tungsten currently in its exclusive 30s window? */
    public static boolean isLocked() {
        if (lockUntil == 0) return false;
        if (System.currentTimeMillis() > lockUntil) {
            lockUntil = 0;
            return false;
        }
        return true;
    }

    /** Stop Tungsten pathfinding if it's running. Also clears the lock. */
    public static void stop() {
        if (!isTungstenLoaded()) return;
        try {
            pathfinderStop.set(true);
            executorStopField.set(executorInstance, true);
            active = false;
            lockUntil = 0;
            lockedEntity = null;
            Debug.logInternal("[TungstenHelper] Stopped (lock cleared)");
        } catch (Exception e) {
            Debug.logWarning("[TungstenHelper] Failed to stop: " + e.getMessage());
        }
    }

    /** Is Tungsten currently pathfinding or executing? */
    public static boolean isActive() {
        if (!isTungstenLoaded()) return false;
        // If locked, we're "active" even between path segments
        if (isLocked()) return true;
        if (!active) return false;
        try {
            boolean busy = pathfinderActive.get()
                    || (boolean) executorIsRunning.invoke(executorInstance);
            if (!busy) active = false;
            return busy;
        } catch (Exception e) {
            return false;
        }
    }

    /** Reset fail counter — call when task restarts or target changes. */
    public static void reset() {
        failCount = 0;
        active = false;
        lastStartTime = 0;
        lockUntil = 0;
        lockedEntity = null;
        lastRetargetTime = 0;
    }

    private static String formatVec(Vec3d v) {
        return String.format("(%.0f, %.0f, %.0f)", v.x, v.y, v.z);
    }
}
