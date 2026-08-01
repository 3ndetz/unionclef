package kaptainwutax.tungsten.path.movements;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.entity.player.PlayerEntity;

/**
 * The step we have no upstream class for — walked, not skipped.
 *
 * <h2>Why this is not a band-aid</h2>
 *
 * {@code MovementQueue} owns a CONTIGUOUS run and stops at the first edge it cannot type. Measured
 * on chase_terrain that costs almost everything: 2732 route cells across 43 routes, 108 taken — 4%
 * — because a terrain route mixes shapes constantly and one untyped edge discards the whole tail.
 * The tail then goes back to {@code BlockPathWalker}, and a clean measurement (nothing preempting
 * it, a route in hand, replanning every 40 ticks, {@code dist=89.3} unmoved) says the walker cannot
 * cross that terrain at all. So the queue hands its remaining route to something that will not walk
 * it.
 *
 * <p>Porting more classes does not fix that: ascend, descend and diagonal are all in, and an
 * interleaved A/B on diagonals moved the freeze count by -2 and +1 — nothing — while genuinely
 * lengthening the prefix. The ceiling is the RULE, not the class list.
 *
 * <p>This class exists so the rule can go. It does for an untyped edge exactly what the walker did
 * for a waypoint — steer at the cell, jump if the body is against something and the step is up —
 * but inside the queue's one-owner-per-tick discipline, which is the part that works. It is
 * deliberately dumb: when a real class for that shape is ported, the shape stops reaching here.
 */
public class MovementFallback extends Movement {

    public MovementFallback(BetterBlockPos src, BetterBlockPos dest) {
        super(src, dest, new BetterBlockPos[]{dest, dest.above()});
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.<BetterBlockPos>of(src, dest, src.above(), dest.above());
    }

    /** Nothing to break or place before a plain steer. */
    @Override
    protected boolean prepared(MovementState state) {
        return true;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        PlayerEntity player = ctx.player();
        if (ctx.playerFeet().equals(dest)) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        MovementHelperB.moveTowards(player, state, dest);
        // Stepping up and pressed against something is the one case a plain steer cannot solve.
        if (dest.getY() > src.getY() && player.horizontalCollision) {
            state.setInput(Input.JUMP, true);
        }
        return state;
    }
}
