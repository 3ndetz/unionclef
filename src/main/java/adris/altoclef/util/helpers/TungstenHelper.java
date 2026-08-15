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

    /**
     * Consecutive BARREN locks tolerated before navigation stops claiming it has this target.
     *
     * <h2>Why this is not MAX_FAIL_COUNT</h2>
     *
     * They guard different failures with different costs. {@code MAX_FAIL_COUNT} counts EXCEPTIONS
     * -- something threw -- and five of those is a reasonable patience because each is instant. A
     * barren lock costs THIRTY SECONDS of a bot standing still, so the same five means
     * <b>150 seconds</b> of doing nothing before anything reconsiders.
     *
     * <p>That is too lax twice over. As behaviour, two and a half minutes of no progress is not
     * patience, it is a hang. And as a TESTABLE guard it is worse: the craft courses run 90, 120,
     * 150, 180, 240 and 300 seconds, so at 150 s the guard cannot fire at all on the first four --
     * including mine_stone, the course this was found on. A guard that no course can exercise is
     * one this repo has now shipped three times, and it is the reason for two of the day's refuted
     * series.
     *
     * <p>Two barren locks is sixty seconds of getting nowhere toward one target, which is already
     * generous, and it is inside every course on the bench.
     */
    private static final int MAX_BARREN_LOCKS = 2;

    /** Consecutive barren locks for the CURRENT target; reset by any lock that closed ground. */
    private static int barrenStreak = 0;
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
        // Refuse to take a fresh 30-second window when the last two got nowhere. Without this the
        // window renews for ever and the caller is told "tungsten has it" while nothing moves.
        if (kaptainwutax.tungsten.TungstenConfig.get().barrenLockCountsAsFailure
                && barrenStreak >= MAX_BARREN_LOCKS && !isLocked()) {
            return false;
        }

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
            // ⛔ find() CAN REFUSE, AND THIS TOLD THE CALLER IT HAD SUCCEEDED ANYWAY.
            //
            // PathFinder.find opens with `if (active.get() || thread != null) return false;` -- it
            // will not start while a previous search thread is still alive, and TungstenHelper.stop()
            // sets the stop flag without joining that thread. So there is a window, after every
            // stop and every finished search, in which find() simply declines.
            //
            // The return value was discarded. This method then took a THIRTY-SECOND exclusive lock
            // and returned true, so GetToEntityTask was told "tungsten has it", returned null, and
            // drove nothing -- for a search that never started. Both of its walk branches are
            // guarded on this method returning true, so a false yes stops the bot dead.
            //
            // Traced on mine_coal: the bot parked 1.17 blocks from a drop it could see (the tracker
            // reported it 2393 times) and never closed, with no ban, no barren lock and nothing
            // wrong with the search itself.
            //
            // Same shape as the defects this repo has already paid for -- a gate whose awake half
            // could never fail, a dodge whose keys never reached the game, a stop that did not hold.
            // A caller acting on a success the callee never reported.
            boolean started = pf.find(world, target, player);
            if (kaptainwutax.tungsten.TungstenConfig.get().pathStartMustSucceed && !started) {
                findRefused++;
                return false;
            }
            lastStartTime = now;
            lastRetargetTime = now;
            lockUntil = now + LOCK_DURATION_MS;
            // Latch how far away the target is, so the lock can be JUDGED when it expires rather
            // than silently renewed. See isLocked().
            try {
                lockStartDist = lockedEntity != null && !lockedEntity.isRemoved()
                        ? player.getPos().distanceTo(lockedEntity.getPos()) : -1;
            } catch (Exception ignored) {
                lockStartDist = -1;
            }
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
        // A NEW TARGET IS A NEW PROBLEM. The streak is earned against ONE thing we could not get
        // to; carrying it to the next drop would refuse navigation to a target we have never tried.
        // Same reasoning as PickupDroppedItemTask spending its wander escalation when the drop
        // changes, and the same bug if it is omitted.
        if (!entity.equals(lockedEntity)) {
            barrenStreak = 0;
        }
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

    /** Distance to the target when the current lock was taken, and counters for the A/B. */
    private static double lockStartDist = -1;
    /** Locks that expired without the bot getting closer, and locks that made progress. */
    public static volatile int lockBarren, lockProductive;

    /** Times find() declined to start and this method reported the refusal. Read as lock's 3rd. */
    public static volatile int findRefused;

    /** How much closer the bot must get during a 30s lock for that lock to count as working. */
    private static final double LOCK_PROGRESS_BLOCKS = 0.5;

    /**
     * Is Tungsten currently in its exclusive 30s window?
     *
     * <h2>The window renews itself forever, and the guard against that cannot fire</h2>
     *
     * When the lock expires this returns false, and on the very next tick {@code GetToEntityTask}
     * sees no lock and no active search, calls {@code tryPathToEntity} again, and takes a FRESH
     * thirty seconds. Nothing in between asks whether the last thirty accomplished anything. While
     * locked the task returns null every tick after resetting its progress checker, so it drives
     * nothing and cannot give up either.
     *
     * <p>That is the countdown in every stall this project has traced -- {@code Tungsten
     * pathfinding (29s left)} ticking down and starting over. On mine_stone it held the bot on one
     * spot for 90 seconds of a 120-second run; on the @gamer playthrough, 160 seconds of daylight on
     * {@code Mine And Collect: [[coal]]} with every drive counter at zero.
     *
     * <p>{@code MAX_FAIL_COUNT} exists for exactly this and is unreachable: {@code failCount++}
     * appears only in {@code tryPathTo}'s catch block, so it counts EXCEPTIONS. A search that
     * honestly finds no path is not an exception, so the limit never sees the failure it was
     * written for -- the same shape as a gate whose awake half could never fail, which this repo has
     * now paid for three times.
     *
     * <p>So a lock that expires without the bot getting closer is counted as a failure here, which
     * is what makes the existing limit live. After {@code MAX_FAIL_COUNT} barren locks
     * {@code tryPathTo} returns false, the caller stops being told "tungsten has it", and the
     * give-up path it already owns -- progress checker, wander, blacklist -- can finally run.
     */
    public static boolean isLocked() {
        if (lockUntil == 0) return false;
        if (System.currentTimeMillis() > lockUntil) {
            lockUntil = 0;
            // COUNT ALWAYS, ACT ONLY WHEN FLAGGED. Gating the COUNTER on the flag would make it
            // unreadable in the control arm -- "barren locks with the fix off" is the number the
            // whole premise rests on, and it would always be 0 by construction. That is exactly how
            // a mechanism gate was declared for the stranded series and never exposed, voiding
            // forty launches by its own rule, and how navSearchOnly read 0 today whether or not the
            // bug was present. The counter is an observation; the flag decides the BEHAVIOUR.
            scoreExpiredLock();
            return false;
        }
        return true;
    }

    /** Did the lock that just expired get us anywhere? Never throws; an instrument must not. */
    private static void scoreExpiredLock() {
        try {
            if (lockStartDist < 0 || lockedEntity == null || lockedEntity.isRemoved()) {
                return;
            }
            var player = AltoClef.getInstance().getPlayer();
            if (player == null) return;
            double now = player.getPos().distanceTo(lockedEntity.getPos());
            if (lockStartDist - now < LOCK_PROGRESS_BLOCKS) {
                lockBarren++;
                barrenStreak++;
            } else {
                // Real progress spends the escalation, the same rule PickupDroppedItemTask applies
                // to its wander radius: being stuck on THIS target is what should accumulate, and a
                // lock that closed ground is not stuck.
                lockProductive++;
                barrenStreak = 0;
            }
        } catch (Exception ignored) {
            // never let the accounting be the thing that breaks navigation
        } finally {
            lockStartDist = -1;
        }
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
            lockStartDist = -1;
            barrenStreak = 0;
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
