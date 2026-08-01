package kaptainwutax.tungsten.path.movements;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.WorldView;

/**
 * Port of {@code baritone/pathing/movement/movements/MovementDiagonal.java} — ONE step across a
 * corner: {@code dest = src + one cardinal + another cardinal}, at the same Y or one up or down.
 *
 * <h2>Why it is next</h2>
 *
 * {@link MovementQueue} takes a CONTIGUOUS run of steps it has classes for. With traverse, ascend
 * and descend wired, chase_terrain moved for the first time in batches — 19.0 to 17.3 to ~15.5
 * freezes — and the prefix now breaks on the first DIAGONAL, which is what a route across open
 * ground is mostly made of.
 *
 * <p>Simpler than its neighbours: a diagonal never places and never breaks to make its floor, so
 * there is no place branch and {@code prepared()} is unconditionally true upstream. What it does
 * carry is the corner: {@code positionsToBreak} holds BOTH corner columns as well as the
 * destination, because the body passes through the corner and either side can stop it.
 */
public class MovementDiagonal extends Movement {

    /**
     * MovementDiagonal.java:55. {@code positionsToBreak = {dir1, dir1.up(), dir2, dir2.up(), end,
     * end.up()}} — the two corner columns and the destination column. No {@code positionToPlace}:
     * a diagonal builds nothing.
     */
    public MovementDiagonal(BetterBlockPos start, BetterBlockPos end) {
        super(start, end, corners(start, end));
    }

    /** The two cells the corner can be cut through, plus the destination column. */
    private static BetterBlockPos[] corners(BetterBlockPos start, BetterBlockPos end) {
        BetterBlockPos dir1 = new BetterBlockPos(end.getX(), start.getY(), start.getZ());
        BetterBlockPos dir2 = new BetterBlockPos(start.getX(), start.getY(), end.getZ());
        return new BetterBlockPos[]{
                dir1, dir1.above(), dir2, dir2.above(), end, end.above()};
    }

    /**
     * MovementDiagonal.java:99-110. The set depends on whether the step also changes height —
     * going down, the corner cells one BELOW count as valid; going up, the ones above.
     */
    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        BetterBlockPos diagA = new BetterBlockPos(src.getX(), src.getY(), dest.getZ());
        BetterBlockPos diagB = new BetterBlockPos(dest.getX(), src.getY(), src.getZ());
        if (dest.getY() < src.getY()) {
            return ImmutableSet.<BetterBlockPos>of(src, dest.above(), diagA, diagB, dest,
                    diagA.below(), diagB.below());
        }
        if (dest.getY() > src.getY()) {
            return ImmutableSet.<BetterBlockPos>of(src, src.above(), diagA, diagB, dest,
                    diagA.above(), diagB.above());
        }
        return ImmutableSet.<BetterBlockPos>of(src, dest, diagA, diagB);
    }

    /** MovementDiagonal.java:256-275, copied. */
    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        PlayerEntity player = ctx.player();
        WorldView world = player.getEntityWorld();

        if (ctx.playerFeet().equals(dest)) {
            return state.setStatus(MovementStatus.SUCCESS);
        } else if (!playerInValidPosition()
                && !(MovementHelperB.isLiquid(world, src)
                        && getValidPositions().contains(ctx.playerFeet().above()))) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        // Walking into a corner while stepping up needs a jump; horizontalCollision is what says
        // the body is actually against something rather than merely near it.
        if (dest.getY() > src.getY() && player.getEntityPos().y < src.getY() + 0.1
                && player.horizontalCollision) {
            state.setInput(Input.JUMP, true);
        }
        if (sprint()) {
            state.setInput(Input.SPRINT, true);
        }
        MovementHelperB.moveTowards(player, state, dest);
        return state;
    }

    /**
     * MovementDiagonal.java:277-287: sprint only when BOTH corner columns are clear — clipping a
     * corner at sprint speed is how a diagonal turns into a collision.
     */
    private boolean sprint() {
        WorldView world = ctx.player().getEntityWorld();
        if (MovementHelperB.isLiquid(world, ctx.playerFeet())) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (!MovementHelperB.canWalkThrough(world, positionsToBreak[i])) {
                return false;
            }
        }
        return true;
    }

    /** MovementDiagonal.java:289-292: a diagonal has nothing to prepare — it never places. */
    @Override
    protected boolean prepared(MovementState state) {
        return true;
    }
}
