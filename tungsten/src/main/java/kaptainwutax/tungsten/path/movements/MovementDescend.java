package kaptainwutax.tungsten.path.movements;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Port of {@code baritone/pathing/movement/movements/MovementDescend.java} — ONE one-block cardinal
 * step DOWN: {@code dest = src + one cardinal, -1 Y}. Walk off the edge and land on the block below.
 *
 * <h2>Why this one, and why now</h2>
 *
 * {@link MovementQueue} takes a CONTIGUOUS run of steps it has classes for, counted from the start
 * of the route. Measured on chase_terrain, adding {@link MovementAscend} alone did not lengthen
 * that run at all — 64 route cells, 4 taken, the same ~6% — because a route over terrain meets its
 * first DESCEND almost immediately and the count stops there. Climbs and drops have to arrive
 * together before a prefix of any length can form.
 *
 * <p>As with the ascend: cost stays in {@code FastPlanner}, which is what plans these routes. Two
 * pricing functions for one move is the duplication this project keeps paying for. What is copied
 * is the execution — including the two pieces that are easy to miss and are the whole reason a
 * descend is not "walk forward and fall":
 *
 * <ul>
 *   <li>the OVERSHOOT target ({@code fakeDest}, one step further in the same direction) for the
 *       first 20 ticks, which is what carries the body off the lip instead of stalling on it;</li>
 *   <li>{@code safeMode}, which walks 83% of the way to the destination centre instead of
 *       sprinting through, whenever the cell we would overshoot into is something to avoid.</li>
 * </ul>
 */
public class MovementDescend extends Movement {

    /** MovementDescend.java:43. Ticks spent aiming at the overshoot target. */
    private int numTicks = 0;
    /** MovementDescend.java:44. Set by the caller when only it can see that safe mode is needed. */
    public boolean forceSafeMode = false;

    /**
     * MovementDescend.java:46-48. {@code positionsToBreak = {end.up(2), end.up(), end}} — the whole
     * column we drop through; {@code positionToPlace = end.down()} is what we land on.
     */
    public MovementDescend(BetterBlockPos start, BetterBlockPos end) {
        // BetterBlockPos.above()/below() are tungsten's names for baritone's up()/down().
        super(start, end,
                new BetterBlockPos[]{end.above().above(), end.above(), end},
                end.below());
    }

    @Override
    public void reset() {
        super.reset();
        numTicks = 0;
        forceSafeMode = false;
    }

    /** MovementDescend.java:58-60. */
    public void forceSafeMode() {
        forceSafeMode = true;
    }

    /** MovementDescend.java:75-77. */
    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.<BetterBlockPos>of(src, dest.above(), dest);
    }

    /**
     * {@code updateState} — MovementDescend.java:226-266, copied rather than paraphrased.
     *
     * <p>Success waits until the bot is ACTUALLY down: upstream's comment says why — "sometimes we
     * continue to fall if the next action starts immediately", so the next movement must not begin
     * while the body is still in the air.
     */
    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        PlayerEntity player = ctx.player();
        WorldView world = player.getEntityWorld();

        BlockPos playerFeet = ctx.playerFeet();
        BlockPos fakeDest = new BlockPos(dest.getX() * 2 - src.getX(), dest.getY(),
                dest.getZ() * 2 - src.getZ());
        if ((playerFeet.equals(dest) || playerFeet.equals(fakeDest))
                && (MovementHelperB.isLiquid(world, dest) || player.getEntityPos().y - dest.getY() < 0.5)) {
            // Wait until we're actually on the ground before saying we're done because sometimes
            // we continue to fall if the next action starts immediately
            return state.setStatus(MovementStatus.SUCCESS);
        }

        if (safeMode()) {
            double destX = (src.getX() + 0.5) * 0.17 + (dest.getX() + 0.5) * 0.83;
            double destZ = (src.getZ() + 0.5) * 0.17 + (dest.getZ() + 0.5) * 0.83;
            state.setTarget(new MovementState.MovementTarget(
                    RotationHelper.calcRotationFromVec3d(RotationHelper.playerHead(player),
                            new Vec3d(destX, dest.getY(), destZ),
                            RotationHelper.playerRotations(player))
                            .withPitch(RotationHelper.playerRotations(player).getPitch()),
                    false
            )).setInput(Input.MOVE_FORWARD, true);
            return state;
        }

        double diffX = player.getEntityPos().x - (dest.getX() + 0.5);
        double diffZ = player.getEntityPos().z - (dest.getZ() + 0.5);
        double ab = Math.sqrt(diffX * diffX + diffZ * diffZ);
        double x = player.getEntityPos().x - (src.getX() + 0.5);
        double z = player.getEntityPos().z - (src.getZ() + 0.5);
        double fromStart = Math.sqrt(x * x + z * z);
        if (!playerFeet.equals(dest) || ab > 0.25) {
            if (numTicks++ < 20 && fromStart < 1.25) {
                MovementHelperB.moveTowards(player, state, fakeDest);
            } else {
                MovementHelperB.moveTowards(player, state, dest);
            }
        }
        return state;
    }

    /**
     * MovementDescend.java:268-289. The block we would run into if we sprinted straight through
     * this descend; if it is anything to avoid, walk it instead of overshooting.
     */
    public boolean safeMode() {
        if (forceSafeMode) {
            return true;
        }
        WorldView world = ctx.player().getEntityWorld();
        // (dest - src.down()) + dest is offset 1 more in the same direction
        BlockPos into = dest.subtract(src.below()).add(dest);
        if (skipToAscend()) {
            return true;
        }
        for (int y = 0; y <= 2; y++) { // we could hit any of the three blocks
            BlockPos p = into.up(y);
            if (MovementHelperB.avoidWalkingInto(world.getBlockState(p))) {
                return true;
            }
        }
        return false;
    }

    /** MovementDescend.java:291-294. */
    public boolean skipToAscend() {
        WorldView world = ctx.player().getEntityWorld();
        BlockPos into = dest.subtract(src.below()).add(dest);
        return !MovementHelperB.canWalkThrough(world, into)
                && MovementHelperB.canWalkThrough(world, into.up())
                && MovementHelperB.canWalkThrough(world, into.up(2));
    }
}
