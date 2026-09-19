package adris.altoclef.util.progresscheck;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class MovementProgressChecker {

    private final IProgressChecker<Vec3d> distanceChecker;
    private final IProgressChecker<Double> mineChecker;

    private BlockPos lastBreakingBlock = null;
    private double lastMiningFraction;
    private double miningWork;

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
            // Damage belongs to one block. A fresh block's 0% cannot be compared with
            // the previous block's 88%: that falsely abandoned productive stair mining.
            // Accumulate observed work instead; switching targets with zero damage earns
            // neither progress nor a new timeout, so oscillating aim still times out.
            double fraction = mod.getControllerExtras().getBreakingBlockProgress();
            if (lastBreakingBlock == null || !lastBreakingBlock.equals(breakBlock)) {
                lastMiningFraction = 0;
            }
            miningWork += Math.max(0, fraction - lastMiningFraction);
            lastMiningFraction = fraction;
            lastBreakingBlock = breakBlock == null ? null : breakBlock.toImmutable();
            lastBreakingWasSolid = breakBlock != null && !WorldHelper.isAir(breakBlock);
            mineChecker.setProgress(miningWork);
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
    /**
     * Net distance (blocks) the body must travel FROM the grace anchor to count as genuinely
     * moving rather than shimmying in place. Squared for {@link Vec3d#squaredDistanceTo}.
     *
     * <p>⛔ WHY THIS REPLACED A 0.02-BLOCK PER-TICK BAR (2026-09-19). The old test compared each
     * tick only against the one before it (a FOLLOWING anchor) with a 0.0004 (=0.02²) threshold.
     * A blocked build drain does not FREEZE -- it SHIMMIES, oscillating ~0.4 blocks a tick against
     * the cell it cannot place. Measured on the portal front scaffold: the body sat at
     * (2359.5,-56,359.5)±0.4 for 75+ seconds under "Placing cobblestone at 2359,-57,360", every
     * tick clearing the 0.02 bar, so {@code ticksSinceMoved} reset to 0 every tick, the grace never
     * expired, and the checker was reset for ever -- the exact stall it exists to catch, wearing the
     * costume of motion. A FIXED anchor with a net-distance bar treats a shimmy as stationary (it
     * never gets far from the anchor) while a real walk or pillar leaves the anchor at once and
     * re-anchors. 1.0 block sits far above any shimmy's amplitude and far below a walking stride
     * over the grace window (a walk covers ~8 blocks in 40 ticks).
     */
    private static final double STALL_MOVE_MIN_SQ = 1.0;
    private int ticksSinceMoved = 0;
    private Vec3d lastMoveTickPos = null;

    /**
     * True when the body has stayed within {@link #STALL_MOVE_MIN_SQ} of a FIXED anchor for at
     * least {@link #STALL_MOVE_GRACE} ticks -- a genuine in-place stall whether the body is frozen
     * OR shimmying. The distance checker alone can miss a shimmy, because a body oscillating in
     * place keeps showing motion to a per-tick test; this reads the accumulated grace directly, so
     * the caller can escape a shimmy deterministically instead of waiting on the distance timeout.
     */
    public boolean stalledInPlace() {
        return ticksSinceMoved >= STALL_MOVE_GRACE;
    }

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
            // A FIXED anchor and a NET-distance bar (see STALL_MOVE_MIN_SQ). The anchor is only
            // replaced once the body has genuinely LEFT it -- travelled STALL_MOVE_MIN blocks away
            // -- not on every sub-bar twitch. A shimmy stays inside that radius and accumulates
            // ticksSinceMoved toward the grace; a real walk or pillar leaves the radius at once and
            // re-anchors, keeping the grace fresh. (The earlier 0.02-block bar with a following
            // anchor did the opposite: a shimmy cleared it every tick and the grace never grew.)
            if (lastMoveTickPos == null) {
                lastMoveTickPos = pos;
            } else if (pos.squaredDistanceTo(lastMoveTickPos) > STALL_MOVE_MIN_SQ) {
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
