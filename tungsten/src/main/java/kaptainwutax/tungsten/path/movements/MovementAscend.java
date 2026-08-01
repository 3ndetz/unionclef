package kaptainwutax.tungsten.path.movements;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldView;

/**
 * Port of {@code baritone/pathing/movement/movements/MovementAscend.java} — ONE one-block cardinal
 * step UP: {@code dest = src + one cardinal, +1 Y}. Walk into it and jump, or place the block to
 * stand on if it is not there.
 *
 * <h2>Why this exists</h2>
 *
 * The ported movement queue could only take {@code MovementTraverse}, which is a same-Y cardinal
 * step, and its own comment said the rest — "a climb, a drop, a diagonal, a parkour gap" — is
 * "a different movement class that this port does not include yet". That was not an abstract gap:
 * measured on chase_terrain, across four chase routes totalling 193 cells the queue could take TEN,
 * five per cent, because a chase over terrain is made of climbs and drops. Everything else was
 * therefore driven by the hand-rolled BlockPathWalker, which is what the chase actually stalls on.
 *
 * <p>This is the first of the missing classes. Cost lives in {@code FastPlanner}, which is what
 * plans the route here; upstream's {@code cost()} is deliberately NOT duplicated, because two
 * pricing functions for one move is the duplication this project keeps paying for (register C2/C5).
 * What is copied is the part that has no counterpart: how the step is EXECUTED.
 */
public class MovementAscend extends Movement {

    /** MovementAscend.java:38. Counts ticks spent waiting for the block we must stand on. */
    private int ticksWithoutPlacement = 0;

    /**
     * MovementAscend.java:40-42. {@code positionsToBreak = {dest, src.up(2), dest.up()}} — the cell
     * we step into, the ceiling above the SOURCE (a jump needs headroom where it starts), and the
     * head cell at the destination. {@code positionToPlace = dest.down()} is the block to stand on.
     */
    public MovementAscend(BetterBlockPos src, BetterBlockPos dest) {
        // BetterBlockPos.above()/below() are tungsten's names for baritone's up()/down().
        super(src, dest,
                new BetterBlockPos[]{dest, src.above().above(), dest.above()},
                dest.below());
    }

    @Override
    public void reset() {
        super.reset();
        ticksWithoutPlacement = 0;
    }

    /**
     * MovementAscend.java:56-63. Includes the cell BEHIND the source at head height, because the
     * movement legitimately backs up to place the block it will stand on.
     */
    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        BetterBlockPos prior = new BetterBlockPos(src.subtract(getDirection())).above();
        return ImmutableSet.<BetterBlockPos>of(src, src.above(), dest, prior);
    }

    /**
     * {@code updateState} — MovementAscend.java:158-220, copied rather than paraphrased.
     *
     * <p>The order is load-bearing and is not to be rearranged: the below-source check runs even
     * while PREPPING (we may be breaking), success is claimed the moment the feet reach the
     * destination, and the PLACE branch returns before any jump input so the bot never leaps at a
     * block that does not exist yet. The {@code ticksWithoutPlacement > 10} back-up is upstream's
     * own answer to "we are standing in the spot we are trying to fill".
     */
    @Override
    public MovementState updateState(MovementState state) {
        if (ctx.playerFeet().getY() < src.getY()) {
            // this check should run even when in preparing state (breaking blocks)
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        PlayerEntity player = ctx.player();
        WorldView world = player.getEntityWorld();

        // upstream: dest.add(getDirection().down()) — the cell one step further and one down, i.e.
        // we overshot onto the level we came from. Written out because BlockPos has no down() here.
        net.minecraft.util.math.Vec3i dir = getDirection();
        if (ctx.playerFeet().equals(dest)
                || ctx.playerFeet().equals(dest.add(dir.getX(), dir.getY() - 1, dir.getZ()))) {
            return state.setStatus(MovementStatus.SUCCESS);
        }

        BlockState jumpingOnto = world.getBlockState(positionToPlace);
        if (!MovementHelperB.canWalkOn(world, positionToPlace, jumpingOnto)) {
            ticksWithoutPlacement++;
            if (MovementHelperB.attemptToPlaceABlock(state, player, dest.below(), false, true)
                    == MovementHelperB.PlaceResult.READY_TO_PLACE) {
                state.setInput(Input.SNEAK, true);
                if (player.isInSneakingPose()) {
                    state.setInput(Input.CLICK_RIGHT, true);
                }
            }
            if (ticksWithoutPlacement > 10) {
                // After 10 ticks without placement, we might be standing in the way, move back
                state.setInput(Input.MOVE_BACK, true);
            }
            return state;
        }

        MovementHelperB.moveTowards(player, state, dest);
        if (MovementHelperB.isBottomSlab(jumpingOnto)
                && !MovementHelperB.isBottomSlab(world.getBlockState(src.below()))) {
            return state; // don't jump while walking from a non double slab into a bottom slab
        }

        if (ctx.playerFeet().equals(src.above())) {
            // no need to hit space if we're already jumping
            return state;
        }

        int xAxis = Math.abs(src.getX() - dest.getX()); // either 0 or 1
        int zAxis = Math.abs(src.getZ() - dest.getZ()); // either 0 or 1
        double flatDistToNext = xAxis * Math.abs((dest.getX() + 0.5D) - player.getEntityPos().x)
                + zAxis * Math.abs((dest.getZ() + 0.5D) - player.getEntityPos().z);
        double sideDist = zAxis * Math.abs((dest.getX() + 0.5D) - player.getEntityPos().x)
                + xAxis * Math.abs((dest.getZ() + 0.5D) - player.getEntityPos().z);

        double lateralMotion = xAxis * player.getVelocity().z + zAxis * player.getVelocity().x;
        if (Math.abs(lateralMotion) > 0.1) {
            return state;
        }

        if (headBonkClear()) {
            return state.setInput(Input.JUMP, true);
        }

        if (flatDistToNext > 1.2 || sideDist > 0.2) {
            return state;
        }

        // Once we are pointing the right way and moving, start jumping
        return state.setInput(Input.JUMP, true);
    }

    /** MovementAscend.java:222-233. */
    public boolean headBonkClear() {
        BetterBlockPos startUp = src.above().above();
        WorldView world = ctx.player().getEntityWorld();
        for (int i = 0; i < 4; i++) {
            BetterBlockPos check = new BetterBlockPos(
                    startUp.offset(Direction.fromHorizontalQuarterTurns(i)));
            if (!MovementHelperB.canWalkThrough(world, check)) {
                // We might bonk our head
                return false;
            }
        }
        return true;
    }

    /** MovementAscend.java:235-239: a movement that had to place must not be paused mid-place. */
    @Override
    public boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING || ticksWithoutPlacement == 0;
    }
}
