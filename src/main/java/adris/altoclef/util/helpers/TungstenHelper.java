package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.path.PathExecutor;
import kaptainwutax.tungsten.path.PathFinder;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * Tungsten pathfinder integration.
 *
 * When activated, Tungsten LOCKS control for LOCK_DURATION_MS so progress checkers
 * and shredder restarts don't immediately interrupt it.
 *
 * <p>HISTORY (2026-07-27): this class used to reach into tungsten through reflection,
 * "so it works even if tungsten is not installed". That was never true — the same class
 * already referenced {@code kaptainwutax.tungsten.task.*} directly in
 * {@link #isCombatActive()}, so tungsten was always a hard compile-time dependency and
 * the reflection bought nothing. Worse, it silently BROKE: {@code initReflection()} looked
 * up {@code PathFinder.searchTimeoutMs}, a field that had been moved to
 * {@link TungstenConfig}. The resulting {@code NoSuchFieldException} left
 * {@code reflectionReady = false} permanently, so {@code isTungstenLoaded()} always
 * returned false and every guarded method here — {@code tryPathTo}, {@code tryPathToEntity},
 * {@code stop}, {@code isActive}, {@code isLocked} — was a permanent no-op. The whole
 * "tungsten as a fallback when the primary pathfinder fails" layer had never executed.
 * It is now direct, typed calls: a signature change becomes a compile error instead of a
 * feature that silently disappears.
 */
public class TungstenHelper {

    private static boolean active = false;
    private static int failCount = 0;
    private static long lastStartTime = 0;

    // Drop-in swap (TODO 13): when primary, altoclef goals route straight to
    // tungsten instead of waiting for the fallback path to fail.
    //
    // ON BY DEFAULT (G-0). The goal of this repo is that the bot navigates on tungsten; while this
    // shipped false, it did not -- out of the box every altoclef goal went to shredder, and
    // tungsten only drove after a harness or an agent called setTungstenPathing(true). Everything
    // the bench measures about altoclef navigation, it measures with this ON: the @gamer survival
    // sweep flips it before it starts, so the configuration under test was never the configuration
    // shipped. Now they are the same one.
    private static volatile boolean primary = true;
    public static void setPrimary(boolean p) { primary = p; }
    public static boolean isPrimary() { return primary; }

    private static final int MAX_FAIL_COUNT = 5;
    private static final long COOLDOWN_MS = 1000;
    private static final long LOCK_DURATION_MS = 30_000; // 30 sec exclusive control
    private static final long RETARGET_INTERVAL_MS = 3000; // re-send target every 3 sec

    /** Search tuning used for the short, frequently-retargeted fallback legs. */
    private static final long FALLBACK_SEARCH_TIMEOUT_MS = 2000L;
    private static final int FALLBACK_MIN_PATH_SIZE = 2;
    private static final double FALLBACK_MIN_DIST_PATH = 0.3;

    private static long lockUntil = 0;       // Tungsten has exclusive control until this time
    private static long lastRetargetTime = 0;
    private static Entity lockedEntity = null; // entity we're chasing during lock

    /**
     * Tungsten is compiled into this mod, so the only real question is whether the
     * client-side singletons have been created yet ({@code EXECUTOR} is assigned in
     * {@code TungstenMod.onInitializeClient}, so it is null very early in startup).
     */
    public static boolean isTungstenLoaded() {
        return TungstenModDataContainer.PATHFINDER != null
                && TungstenModDataContainer.EXECUTOR != null;
    }

    /** Apply the short-leg search tuning used by the fallback driver. */
    private static void applyFallbackTuning(PathFinder pf) {
        // NOTE: searchTimeoutMs lives in TungstenConfig, not on PathFinder. Writing it
        // here mutates GLOBAL persisted config — tracked as C7.5 in TODOS.md; the proper
        // fix is per-call search parameters, which is a PathFinder API change.
        TungstenConfig.get().searchTimeoutMs = FALLBACK_SEARCH_TIMEOUT_MS;
        pf.minPathSizeForTimeout = FALLBACK_MIN_PATH_SIZE;
        pf.minDistPath = FALLBACK_MIN_DIST_PATH;
    }

    /**
     * Try Tungsten pathfinding to a position. Returns true if Tungsten was started.
     * Acquires a 30-second lock — the primary pathfinder should not interfere meanwhile.
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

            PathFinder pf = TungstenModDataContainer.PATHFINDER;
            boolean tungstenBusy = pf.active.get() || TungstenModDataContainer.isExecutorRunning();

            // If locked and Tungsten is still working, just retarget periodically
            if (isLocked() && tungstenBusy) {
                if (now - lastRetargetTime > RETARGET_INTERVAL_MS) {
                    applyFallbackTuning(pf);
                    pf.find(world, target, player);
                    lastRetargetTime = now;
                    Debug.logInternal("[TungstenHelper] Retargeted to " + formatVec(target));
                }
                return true;
            }

            // If locked but Tungsten finished a segment, restart it
            if (isLocked()) {
                applyFallbackTuning(pf);
                pf.find(world, target, player);
                lastStartTime = now;
                lastRetargetTime = now;
                Debug.logInternal("[TungstenHelper] Lock active, restarting path to " + formatVec(target));
                return true;
            }

            // Fresh start — acquire lock
            applyFallbackTuning(pf);
            pf.find(world, target, player);
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
            TungstenModDataContainer.PATHFINDER.stop.set(true);
            PathExecutor exec = TungstenModDataContainer.EXECUTOR;
            if (exec != null) exec.stop = true;
            active = false;
            lockUntil = 0;
            lockedEntity = null;
            Debug.logInternal("[TungstenHelper] Stopped (lock cleared)");
        } catch (Exception e) {
            Debug.logWarning("[TungstenHelper] Failed to stop: " + e.getMessage());
        }
    }

    /**
     * Is a tungsten COMBAT/movement task driving the client right now (punk, follow,
     * flee, the block-path walker, bridge/pillar)? These own the movement keys and
     * legitimately hold a small area (circle-strafe, kite, place-and-step), so
     * altoclef's stuck detection must never shimmy through them.
     */
    public static boolean isCombatActive() {
        try {
            return kaptainwutax.tungsten.task.PunkPlayerTask.isActive()
                    || kaptainwutax.tungsten.task.FollowEntityTask.isActive()
                    || kaptainwutax.tungsten.task.FollowPlayerTask.isActive()
                    || kaptainwutax.tungsten.task.RunAwayTask.isActive()
                    || kaptainwutax.tungsten.task.BowShooter.isActive()
                    || kaptainwutax.tungsten.task.BridgeTask.isActive()
                    || kaptainwutax.tungsten.task.PillarTask.isActive()
                    || kaptainwutax.tungsten.task.BlockPathWalker.isRunning();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Is Tungsten currently pathfinding or executing? */
    public static boolean isActive() {
        if (!isTungstenLoaded()) return false;
        // If locked, we're "active" even between path segments
        if (isLocked()) return true;
        if (!active) return false;
        boolean busy = TungstenModDataContainer.PATHFINDER.active.get()
                || TungstenModDataContainer.isExecutorRunning();
        if (!busy) active = false;
        return busy;
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
