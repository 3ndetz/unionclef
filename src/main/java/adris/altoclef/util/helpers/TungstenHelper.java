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
     * Where the TARGET was when the lock was taken -- the field the barren geometry has
     * always been missing.
     *
     * <p>Body displacement alone cannot tell the two live-target failures apart. A rabbit
     * that walks off and a rabbit standing three blocks away behind a ledge both read m0.0,
     * and they need opposite fixes: chase harder, or stop chasing and solve the terrain.
     * Nine minutes of a 30-minute run went into exactly this case and the trace could not
     * say which it was.
     */
    private static net.minecraft.util.math.Vec3d lockStartTargetPos;

    /**
     * Barren-lock streaks kept PER TARGET, because one shared streak is wiped by alternation.
     *
     * <p>tryPathToEntity zeroes {@code barrenStreak} whenever the entity changes -- right for a
     * genuinely new target, wrong for one we keep coming back to. mine_diamond wants TWO diamonds,
     * so a bot cycling between two drops it cannot reach resets the streak on every switch and
     * MAX_BARREN_LOCKS is never reached. Measured: lock=49/0/50, forty-nine barren locks and not
     * one refusal, while the bot sat parked and the run failed.
     *
     * <p>Access-ordered and bounded: a run can see many drops, and this only needs the handful it
     * is currently failing against.
     */
    private static final java.util.Map<Integer, Integer> barrenByEntity =
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Integer> eldest) {
                    return size() > 8;
                }
            };

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
    /**
     * Why tryPathTo said no. The wander runs 4104 ticks of a ten-minute run and the body moves
     * in 674 of them, so five ticks in six are spent standing while a destination is already
     * chosen -- wanderTung=11/2, nine picks in eleven refused here. Which of these four gates
     * does the refusing is the last unmeasured link in the residual dead time.
     */
    public static volatile int tpNotLoaded, tpFailCount, tpBarren, tpCooldown, tpAccepted;

    /**
     * Walk to a coordinate. NOT an entity pursuit -- see the barren gate below.
     */
    public static boolean tryPathTo(Vec3d target) {
        return tryPathTo(target, false);
    }

    /** Ticks the barren gate was skipped because the caller is not chasing an entity. */
    public static volatile int tpBarrenBypassed;

    private static boolean tryPathTo(Vec3d target, boolean isEntityLock) {
        if (!isTungstenLoaded()) { tpNotLoaded++; return false; }
        if (failCount >= MAX_FAIL_COUNT) { tpFailCount++; return false; }
        // Refuse to take a fresh 30-second window when the last two got nowhere. Without this the
        // window renews for ever and the caller is told "tungsten has it" while nothing moves.
        // THE GATE LATCHED AND THE ONLY KEY WAS BEHIND IT. barrenStreak is incremented ONLY by
        // scoreExpiredLock, when an ENTITY pursuit expires without closing distance, and it is
        // cleared only by a productive entity lock or by starting a new one. But it gated every
        // caller of tryPathTo, the wander included. Two fruitless pursuits pinned it at 2, the
        // wander was refused from then on, the bot stopped chasing anything because it could no
        // longer move -- and the one action that clears the streak is the action the streak was
        // preventing. Measured, not guessed: tp=0/0/1444/0 against wanderTung=1449/24, with the
        // body moving in 64 of 7047 wander ticks. That is the bot standing still for minutes.
        //
        // A statistic about chasing entities has no bearing on walking to a coordinate, so the
        // gate belongs to the lock path only. The decay is the second half: even for entities a
        // streak must not outlive the situation that earned it.
        boolean barrenApplies = !kaptainwutax.tungsten.TungstenConfig.get().barrenGateIsForEntityLocksOnly
                || isEntityLock;
        if (kaptainwutax.tungsten.TungstenConfig.get().barrenGateIsForEntityLocksOnly
                && !isEntityLock && barrenStreak >= MAX_BARREN_LOCKS) {
            tpBarrenBypassed++;
        }
        if (barrenApplies
                && kaptainwutax.tungsten.TungstenConfig.get().barrenLockCountsAsFailure
                && barrenStreak >= MAX_BARREN_LOCKS && !isLocked()) {
            tpBarren++;
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - lastStartTime < COOLDOWN_MS && !isLocked()) { tpCooldown++; return false; }

        try {
            var player = AltoClef.getInstance().getPlayer();
            var world = AltoClef.getInstance().getWorld();
            if (player == null || world == null) return false;

            PathFinder pf = TungstenModDataContainer.PATHFINDER;
            boolean tungstenBusy = pf.active.get() || TungstenModDataContainer.isExecutorRunning();

            // If locked and Tungsten is still working, just retarget periodically
            if (isLocked() && tungstenBusy) {
                if (now - lastRetargetTime > RETARGET_INTERVAL_MS) {
                    // ⛔ TEN REPLANS PER LOCK IS WHY THE BOT GOES BACK AND FORTH.
                    //
                    // The per-tick anatomy of a lock says the body MOVES on 31-41% of its ticks
                    // while idling on 0.2%, so a barren lock is motion that nets to zero rather
                    // than a freeze -- and the search is active on 58-60% of them, which is what
                    // replanning every three seconds looks like from the inside. Each new plan
                    // starts wherever the body has reached and sends it elsewhere; the executor
                    // walks a prefix of each one.
                    //
                    // Retargeting is for a target that MOVES. If it has not, keep the route.
                    lockRetargetDone++;
                    boolean targetStands = lastRetargetTarget != null
                            && lastRetargetTarget.squaredDistanceTo(target)
                               < kaptainwutax.tungsten.TungstenConfig.get().lockRetargetMoveBlocks
                                 * kaptainwutax.tungsten.TungstenConfig.get().lockRetargetMoveBlocks;
                    // KIND, NOT DISTANCE. A settled drop is a fixed point for ever, so replanning to it
                    // discards the route being walked and finds the same one again -- ten times per lock,
                    // which the lock anatomy showed is the twitching itself. Anything ALIVE may sit inside
                    // a small radius while never standing still, which is exactly how the 1.5-block
                    // threshold version stranded chase_terrain while a 0.35 one returned nothing.
                    boolean settledDrop = lockedEntity instanceof net.minecraft.entity.ItemEntity
                            && lockedEntity.isOnGround()
                            && lockedEntity.getVelocity().horizontalLengthSquared() < 0.0025;
                    boolean skipReplan =
                            (kaptainwutax.tungsten.TungstenConfig.get().lockSkipsReplanForSettledDrops
                             && settledDrop)
                            || (targetStands
                                && (kaptainwutax.tungsten.TungstenConfig.get().lockSkipsReplanWhileTargetStands
                                    || (kaptainwutax.tungsten.TungstenConfig.get().lockKeepsRouteWhileTargetStands
                                        && adris.altoclef.control.Nav.isExecutingRoute())));
                    if (skipReplan) {
                        lockRetargetSkipped++;
                        lockRetargetDone--;          // it was counted as done; it was not done
                    } else {
                        applyFallbackTuning(pf);
                        pf.find(world, target, player);
                        lastRetargetTarget = target;
                        Debug.logInternal("[TungstenHelper] Retargeted to " + formatVec(target));
                    }
                    lastRetargetTime = now;
                }
                return true;
            }

            // If locked but Tungsten finished a segment, restart it
            if (isLocked()) {
                applyFallbackTuning(pf);
                // ⛔ AND THIS BRANCH IS THE WORSE HALF OF THE SAME DEFECT, which the first pass at
                // it missed. It runs when we are LOCKED and tungsten is NOT busy -- precisely the
                // parked state the trace shows. If find() declines here, returning true tells the
                // caller navigation has the target while nothing is running, and the lock keeps the
                // bot standing there for the remainder of its thirty seconds.
                //
                // The branch above (locked AND busy) may keep returning true: tungsten genuinely is
                // working there, so a declined retarget is harmless.
                boolean restarted = pf.find(world, target, player);
                if (!restarted) {
                    findRefused++;
                    if (kaptainwutax.tungsten.TungstenConfig.get().pathStartMustSucceed) {
                        return false;
                    }
                }
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
            // COUNT ALWAYS, ACT ONLY WHEN FLAGGED -- and this is the THIRD time today I have had to
            // make that correction (navSearchOnly, lockBarren, now this). Gating the COUNTER on the
            // flag makes "how often does find() refuse with the fix OFF" zero by construction, and
            // that is the number the premise rests on. The counter is an OBSERVATION; the flag
            // decides the BEHAVIOUR. Writing it the other way round is how a mechanism gate gets
            // declared and never exposed, which voided a forty-launch series by its own rule.
            if (!started) {
                findRefused++;
                if (kaptainwutax.tungsten.TungstenConfig.get().pathStartMustSucceed) {
                    return false;
                }
            }
            lastStartTime = now;
            lastRetargetTime = now;
            lockUntil = now + LOCK_DURATION_MS;
            // Latch how far away the target is, so the lock can be JUDGED when it expires rather
            // than silently renewed. See isLocked().
            try {
                lockStartDist = lockedEntity != null && !lockedEntity.isRemoved()
                        ? player.getPos().distanceTo(lockedEntity.getPos()) : -1;
                lockStartPlayerPos = player.getPos();
                lockStartTargetPos = lockedEntity != null && !lockedEntity.isRemoved()
                        ? lockedEntity.getPos() : null;
            } catch (Exception ignored) {
                lockStartDist = -1;
                lockStartPlayerPos = null;
                lockStartTargetPos = null;
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
        if (kaptainwutax.tungsten.TungstenConfig.get().barrenStreakPerEntity) {
            // Carry THIS entity's own streak back in, so returning to a target we already failed
            // against resumes its escalation instead of starting it again from zero.
            barrenStreak = barrenByEntity.getOrDefault(entity.getId(), 0);
        } else if (!entity.equals(lockedEntity)) {
            barrenStreak = 0;
        }
        lockedEntity = entity;
        return tryPathTo(entity.getPos(), true);
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

    /**
     * Where the BODY was when the lock was taken, and a rolling note of what each barren lock was
     * reaching for.
     *
     * <p>Two hypotheses about mine_coal have now been refuted by A/B -- the scan reorder and the
     * per-entity streak -- and both were guesses about a mechanism the counters could not see. What
     * the counters DO say is unambiguous: every passing run reads {@code lock=0/0/0} and every
     * failing one carries at least one barren lock. So the next question is not "which flag" but
     * "what was the bot reaching for, and did it move at all", and that is a recording, not a
     * behaviour change: {@code type:startDist>endDist,body-moved}. Three entries is enough to see
     * whether the barren locks are one target refusing repeatedly or a different one each time.
     */
    private static net.minecraft.util.math.Vec3d lockStartPlayerPos = null;
    private static final java.util.Deque<String> barrenGeom = new java.util.ArrayDeque<>();
    /** Locks that expired without the bot getting closer, and locks that made progress. */
    public static volatile int lockBarren, lockProductive;

    /** Times find() declined to start and this method reported the refusal. Read as lock's 3rd. */
    public static volatile int findRefused;

    /** How much closer the bot must get during a 30s lock for that lock to count as working. */
    private static final double LOCK_PROGRESS_BLOCKS = 0.5;

    /**
     * WHAT IS THE BOT ACTUALLY DOING DURING A BARREN LOCK? Counted per tick, because five
     * consecutive fixes were aimed at this stall from reasoning and every one of them turned out to
     * be pointed at a branch that does not execute here.
     *
     * <p>A lock runs thirty seconds and up to eleven of them go barren in a single playthrough, so
     * this is the largest measured loss the run has. What has never been established is which of
     * the plausible stories is true, and they cannot all be:
     *
     * <ul>
     *   <li>SEARCHING -- the pathfinder is grinding and never returns a route;
     *   <li>EXECUTING -- a route exists and the body will not follow it;
     *   <li>WALKING / QUEUE -- one of the other drivers owns the body and gets nowhere;
     *   <li>IDLE -- nothing at all is running, and the lock is simply parking the bot.
     * </ul>
     *
     * <p>These are mutually exclusive predictions and one counter separates them. IDLE dominating
     * means the fix is "give the tick to something", which is what four of my attempts assumed
     * without checking. SEARCHING dominating means the fix is in the search. EXECUTING dominating
     * means the route is fine and the body is stuck, which is a different file entirely.
     *
     * <p>Read lockAnat=total/search/exec/walk/queue/idle. Sampled from the client tick, reads only.
     */
    /** Replans inside a lock: actually done, and skipped because the target stood still. */
    public static volatile int lockRetargetDone, lockRetargetSkipped;
    private static net.minecraft.util.math.Vec3d lastRetargetTarget;

    public static volatile int lockTicks, lockSearching, lockExecuting, lockWalking,
            lockQueue, lockIdle, lockMoved;

    private static net.minecraft.util.math.Vec3d lockLastPos;

    /** Called once per client tick from AltoClef.onClientTick. Reads only; presses nothing. */
    public static void tickLockAnatomy() {
        try {
            if (!isLocked()) { lockLastPos = null; return; }
            lockTicks++;
            boolean searching = TungstenModDataContainer.PATHFINDER.active.get();
            boolean exec = TungstenModDataContainer.isExecutorRunning();
            boolean walk = kaptainwutax.tungsten.task.BlockPathWalker.isRunning();
            boolean queue = kaptainwutax.tungsten.path.movements.MovementQueue.isRunning();
            if (searching) lockSearching++;
            if (exec) lockExecuting++;
            if (walk) lockWalking++;
            if (queue) lockQueue++;
            if (!searching && !exec && !walk && !queue) lockIdle++;
            // Did the BODY move this tick? A driver that runs and achieves nothing reads the same
            // as no driver at all in every counter above, and they need different fixes.
            var self = AltoClef.getInstance().getPlayer();
            if (self != null) {
                if (lockLastPos != null && self.getPos().squaredDistanceTo(lockLastPos) > 0.0004) {
                    lockMoved++;
                }
                lockLastPos = self.getPos();
            }
        } catch (Throwable ignored) {
            // an instrument must never be the thing that breaks a tick
        }
    }

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
                recordBarrenGeometry(player, now);
                barrenStreak++;
                if (kaptainwutax.tungsten.TungstenConfig.get().barrenStreakPerEntity) {
                    barrenByEntity.put(lockedEntity.getId(), barrenStreak);
                }
            } else {
                // Real progress spends the escalation, the same rule PickupDroppedItemTask applies
                // to its wander radius: being stuck on THIS target is what should accumulate, and a
                // lock that closed ground is not stuck.
                lockProductive++;
                barrenStreak = 0;
                if (kaptainwutax.tungsten.TungstenConfig.get().barrenStreakPerEntity) {
                    barrenByEntity.remove(lockedEntity.getId());
                }
            }
        } catch (Exception ignored) {
            // never let the accounting be the thing that breaks navigation
        } finally {
            lockStartDist = -1;
        }
    }

    /** What did this barren lock reach for, and did the body move? Never throws; an instrument. */
    private static void recordBarrenGeometry(net.minecraft.entity.player.PlayerEntity player, double endDist) {
        try {
            double moved = lockStartPlayerPos == null ? -1 : player.getPos().distanceTo(lockStartPlayerPos);
            // DID THE TARGET MOVE? Without this, 'it ran away' and 'we could not reach it'
            // are the same reading, and they need opposite fixes.
            double tMoved = (lockStartTargetPos == null || lockedEntity == null
                    || lockedEntity.isRemoved())
                    ? -1 : lockedEntity.getPos().distanceTo(lockStartTargetPos);
            String what = lockedEntity instanceof net.minecraft.entity.ItemEntity item
                    ? item.getStack().getItem().toString() : lockedEntity.getType().toString();
            // Trim the namespace so a course line stays readable next to twenty other counters.
            int dot = what.lastIndexOf('.');
            if (dot >= 0) what = what.substring(dot + 1);
            // SPLIT THE DISTANCE, because a 3D number cannot tell the two failures apart.
            //
            // The close walk holds MOVE_FORWARD along the yaw, which closes HORIZONTAL ground only.
            // A drop one block down in the hole the bot just dug sits at roughly h=0.5, dy=-1.0 --
            // 1.7 blocks away in 3D and unreachable by walking, because the gap that matters is the
            // one walking cannot close. It also explains an aim that will not settle: with the
            // target nearly underfoot the horizontal bearing is ill-conditioned, and a fifth of a
            // block of drift swings the wanted yaw by ninety degrees. Measured: aimed on 88 of 241
            // ticks, closer on 9.
            var lp = lockedEntity.getPos();
            double dy = lp.y - player.getPos().y;
            double horiz = Math.hypot(lp.x - player.getPos().x, lp.z - player.getPos().z);
            // ⛔ AND NAME THE TASK THAT OWNED THE BOT. dy=-1.0 in all twenty-one barren locks made
            // the geometry look like the answer, and then the control refused it: pickup_pit is the
            // SAME drop-one-block-down geometry and reads lock=0/0/0 over four runs -- not a barren
            // lock in sight. So the geometry is not what breaks it; what mine_coal adds is that the
            // bot MINED the block first. That difference lives in the task chain, so record the
            // chain rather than argue about which half of it matters.
            String owner = "?";
            try {
                var chain = AltoClef.getInstance().getTaskRunner().getCurrentTaskChain();
                if (chain != null && !chain.getTasks().isEmpty()) {
                    owner = chain.getTasks().get(chain.getTasks().size() - 1).toString();
                    if (owner.length() > 44) owner = owner.substring(0, 44);
                    owner = owner.replace(' ', '_');
                }
            } catch (Exception ignored) {
                // an instrument never breaks navigation
            }
            // A GEOMETRY CLASS IS NOT A PLACE, AND FIVE FIXES HAVE NOW BEEN BUILT ON CLASSES.
            // This records h and dy, which say the drop is "below" or "beside" -- and every remedy
            // aimed at those classes has been measured away: releasing sneak (604 fires, body moved
            // 15), clearing the camera lease (120 fires, the lease still live on all 120), claiming
            // the aim (86% -> 99.5%, body moved 14 of 602), and declining a deep drop, which was
            // built on one run reading deep=0/0/236 while the next read 209/2/0.
            // Absolute coordinates are the one thing none of them had. With them the failing spot
            // can be teleported to and read block by block, the way the navigation stall finally
            // was.
            String where = "";
            try {
                var self = AltoClef.getInstance().getPlayer();
                if (self != null) {
                    where = String.format(java.util.Locale.ROOT, "@bot[%.2f,%.2f,%.2f]",
                            self.getX(), self.getY(), self.getZ());
                }
            } catch (Throwable ignored) {
                // an instrument must never be the thing that breaks a run
            }
            barrenGeom.addLast(String.format(java.util.Locale.ROOT,
                    "%s:%.1f>%.1f,m%.1f,t%.1f,h%.1f,dy%+.1f%s|%s",
                    what, lockStartDist, endDist, moved, tMoved, horiz, dy, where, owner));
            while (barrenGeom.size() > 3) barrenGeom.removeFirst();
        } catch (Exception ignored) {
            // the accounting must never be the thing that breaks navigation
        }
    }

    /** Drop the recorded geometry; called by the per-run counter reset so it cannot outlive its run. */
    public static void clearBarrenGeometry() {
        barrenGeom.clear();
    }

    /** The last three barren locks as {@code type:start>end,moved}, or "-" if there were none. */
    public static String barrenGeometry() {
        return barrenGeom.isEmpty() ? "-" : String.join(";", barrenGeom);
    }

    /**
     * Release a lock that is holding the body STILL, and count it as barren while doing so.
     *
     * <p>WHY THIS IS NOT JUST {@code stop()}. The barren-lock accounting only runs when a lock
     * EXPIRES ({@link #isLocked} calls {@link #scoreExpiredLock} on timeout), and {@code stop()}
     * additionally zeroes {@code barrenStreak}. So releasing early with a plain {@code stop()}
     * would make the lock invisible to the escalation that exists to stop this repeating, and the
     * caller could release and re-lock for ever without {@code MAX_BARREN_LOCKS} ever converging.
     *
     * <p>Measured, and it is why the early release is wanted at all: on mine_diamond the failing
     * runs read {@code lock=1/0/0} -- ONE barren lock -- with the drop seen roughly 6000 times and
     * never collected. One barren lock is thirty seconds of a run spent frozen, and the limit needs
     * TWO before it acts, so on this course the guard can never fire. Noticing at six seconds that
     * the body has not moved is the same judgement, made before the thirty seconds are spent.
     *
     * <p>So: score it, stop it, and put the escalation back that {@code stop()} cleared.
     */
    public static void releaseIdleLock() {
        scoreExpiredLock();
        int keepStreak = barrenStreak;
        stop();
        barrenStreak = keepStreak;
    }

    /**
     * Hold the body still for a moment WITHOUT throwing the route away.
     *
     * <p>Replaces the legacy requestPause(), which five callers used before eating, filling a
     * bucket or opening a screen. Cancelling would make each of them re-plan afterwards, so the
     * distinction between "wait" and "forget where you were going" has to survive G-0.
     *
     * <p>Tungsten has no pause primitive, and it does not need one: releasing the movement keys
     * stops the body while the executor keeps its path, and the next tick resumes it.
     */
    public static void holdStill() {
        try {
            var opts = net.minecraft.client.MinecraftClient.getInstance().options;
            if (opts == null) return;
            opts.forwardKey.setPressed(false);
            opts.backKey.setPressed(false);
            opts.leftKey.setPressed(false);
            opts.rightKey.setPressed(false);
            opts.jumpKey.setPressed(false);
        } catch (Exception ignored) {
            // never let a hold be the thing that breaks a tick
        }
    }

    /** Stop Tungsten pathfinding if it's running. Also clears the lock. */
    public static void stop() {
        if (!isTungstenLoaded()) return;
        try {
            kaptainwutax.tungsten.path.PathFinder.noteStop("TungstenHelper@600");
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
        // ⛔ AND THE BARREN STREAK, WHICH LEAKED ACROSS RUNS AND BLOCKED NAVIGATION OUTRIGHT.
        //
        // MAX_BARREN_LOCKS is 2: after two barren locks tryPathTo refuses EVERY request until
        // something clears the streak. Nothing did, between courses -- so a suite carried the
        // streak forward and later courses found navigation permanently refused.
        //
        // Measured on wander_recovery, same build, same course: 19.9 blocks covered when it runs
        // ALONE (fresh client, streak 0) and 0.0 inside the full craft suite, where the streak
        // arrived already spent. That is a state leak between runs, not behaviour within one.
        //
        // The guard itself is untouched -- two barren locks still stop a doomed chase inside a run.
        // What stops is the streak outliving the run it was counted for.
        barrenStreak = 0;
    }

    private static String formatVec(Vec3d v) {
        return String.format("(%.0f, %.0f, %.0f)", v.x, v.y, v.z);
    }
}
