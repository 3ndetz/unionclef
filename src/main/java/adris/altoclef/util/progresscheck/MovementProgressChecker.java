package adris.altoclef.util.progresscheck;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class MovementProgressChecker {

    private final IProgressChecker<Vec3d> distanceChecker;
    private final IProgressChecker<Double> mineChecker;

    private BlockPos lastBreakingBlock = null;

    /**
     * Was the block we last aimed at actually SOLID when we took it?
     *
     * <p>⛔ WITHOUT THIS, AIMING AT AIR IS INDISTINGUISHABLE FROM MINING. The success test below
     * is "the block we were breaking is air now", and it is checked against a position recorded
     * on the previous tick. If that position was ALREADY air -- which is what aiming at an empty
     * cell looks like, and the captures show rayMiss in the hundreds -- the test passes on every
     * single tick and resets both checkers for ever.
     *
     * <p>Measured consequence: wanderChk=4406/0, i.e. the checker reported progress on all 4406
     * ticks of a stall in which the body covered 10.6 blocks, and wanderFail stayed 0 so the task
     * never gave up. The same checker guards DestroyBlockTask, where dbTick=7568 came with every
     * instrumented branch at zero. Both stalls in the playthrough corpus reduce to this line.
     */
    private boolean lastBreakingWasSolid = false;

    /** Resets refused because the "broken" block was never solid; 0 with the flag off. */
    public static volatile int airProgressDenied;

    public MovementProgressChecker(double distanceTimeout, double minDistance, double mineTimeout, double minMineProgress, int attempts) {
        distanceChecker = new ProgressCheckerRetry<>(new DistanceProgressChecker(distanceTimeout, minDistance), attempts);
        mineChecker = new LinearProgressChecker(mineTimeout, minMineProgress);
    }

    public MovementProgressChecker(double distanceTimeout, double minDistance, double mineTimeout, double minMineProgress) {
        this(distanceTimeout, minDistance, mineTimeout, minMineProgress, 1);
    }

    public MovementProgressChecker(int attempts) {
        this(6, 0.1, 0.5, 0.001, attempts);
    }

    public MovementProgressChecker() {
        this(1);
    }

    public boolean check(AltoClef mod) {

        // Allow pause on eat
        if (mod.getFoodChain().needsToEat()) {
            distanceChecker.reset();
            mineChecker.reset();
        }

        if (mod.getControllerExtras().isBreakingBlock()) {
            BlockPos breakBlock = mod.getControllerExtras().getBreakingBlockPos();
            // If we broke a block, we made progress.
            // We must also delay reseting the distance checker UNTIL we break a block.
            // Because otherwise we risk not failing if we keep retrtying to mine and don't succeed.
            if (lastBreakingBlock != null && WorldHelper.isAir(lastBreakingBlock)) {
                if (!kaptainwutax.tungsten.TungstenConfig.get().stallCheckNeedsMovement
                        || lastBreakingWasSolid) {
                    distanceChecker.reset();
                    mineChecker.reset();
                } else {
                    // We never broke anything: that cell was air when we aimed at it.
                    airProgressDenied++;
                }
            }
            lastBreakingBlock = breakBlock;
            lastBreakingWasSolid = breakBlock != null && !WorldHelper.isAir(breakBlock);
            mineChecker.setProgress(mod.getControllerExtras().getBreakingBlockProgress());
            return !mineChecker.failed();
        } else {
            mineChecker.reset();
            distanceChecker.setProgress(mod.getPlayer().getPos());
            return !distanceChecker.failed();
        }
    }

    public void reset() {
        distanceChecker.reset();
        mineChecker.reset();
    }

    /**
     * How long the body may be still before a "we are pathing" claim stops counting as
     * progress. Same threshold {@code TimeoutWanderTask}/{@code DestroyBlockTask} already use
     * for this exact purpose, duplicated verbatim in both before {@link
     * #resetIfPathingWithGrace} centralized it here.
     */
    private static final int STALL_MOVE_GRACE = 40;
    private int ticksSinceMoved = 0;
    private Vec3d lastMoveTickPos = null;

    /**
     * Reset this checker when {@code isPathing} is true -- UNLESS the body has been still for
     * {@link #STALL_MOVE_GRACE} ticks, in which case a stall this checker exists to catch is
     * already under way, and resetting now would hide it.
     *
     * <p>TODOS.md, the wander/DestroyBlock finding this generalizes: "a stall IS the state
     * where Nav says it is pathing and the body does not move, so resetting on that condition
     * wipes the detector exactly when it is needed. wanderFail=0 across 4406 ticks that covered
     * 10.6 blocks is what that looks like from the outside." A background search alone makes
     * {@code Nav.isPathing()} true without driving the body at all, so a caller that resets on
     * that condition unconditionally can never see a stall for as long as the search keeps
     * running. Gated on {@code TungstenConfig.stallCheckNeedsMovement} so a caller that has not
     * opted in (or the flag is off) keeps exactly its old, unconditional-reset behaviour.
     *
     * @param mod       current AltoClef instance, to read the player's position.
     * @param isPathing {@code Nav.isPathing()} (or the equivalent) at the call site.
     */
    public void resetIfPathingWithGrace(AltoClef mod, boolean isPathing) {
        var self = mod.getPlayer();
        if (self != null) {
            Vec3d pos = self.getPos();
            // ⛔ SELF-CAUGHT ON RE-AUDIT: this used to write `lastMoveTickPos = pos`
            // unconditionally, every call -- comparing each tick only against the ONE
            // immediately before it, rather than TimeoutWanderTask/DestroyBlockTask's original
            // fixed anchor (only replaced once real movement is detected, or on the first call).
            // Slow-but-genuine drift just under the 0.0004 threshold on any SINGLE tick would
            // then never accumulate into a detected move, however far the body travels over many
            // ticks -- the opposite of what the grace period is for. Only replace the anchor when
            // it actually moves (or is unset), matching the original exactly.
            if (lastMoveTickPos == null) {
                lastMoveTickPos = pos;
            } else if (pos.squaredDistanceTo(lastMoveTickPos) > 0.0004) {
                ticksSinceMoved = 0;
                lastMoveTickPos = pos;
            } else {
                ticksSinceMoved++;
            }
        }
        if (isPathing
                && (!kaptainwutax.tungsten.TungstenConfig.get().stallCheckNeedsMovement
                    || ticksSinceMoved < STALL_MOVE_GRACE)) {
            reset();
        }
    }

}
