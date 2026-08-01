package kaptainwutax.tungsten.path.movements;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.WorldView;

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

    /**
     * Ticks of no approach before this step admits defeat.
     *
     * <p>It has to be its own limit, not the queue's. The queue times a step out at
     * {@code cost + 100} ticks, and a fallback has no instance cost — {@code calculateCost} throws,
     * the queue catches it and applies the {@code MAX_COST_ESTIMATE} ceiling of 60. So an edge a
     * plain steer cannot do would burn 160 ticks, EIGHT SECONDS, standing still, which the chase
     * harness counts as a freeze and the user counts as the bot being broken.
     *
     * <p>A dumb steer needs no such patience: one cell is about ten ticks away, so a second and a
     * half of getting no closer means it will never arrive. Failing fast is also the honest signal
     * — the planner handed us an edge the executor cannot execute, and the answer to that is a new
     * route, not a longer wait.
     */
    private static final int NO_PROGRESS_TICKS = 30;

    /** Closest we have come to {@code dest}, squared; the yardstick for "still making progress". */
    private double bestDistSq = Double.MAX_VALUE;
    private int stuckTicks = 0;

    public MovementFallback(BetterBlockPos src, BetterBlockPos dest) {
        super(src, dest, new BetterBlockPos[]{dest, dest.above()});
    }

    @Override
    public void reset() {
        super.reset();
        bestDistSq = Double.MAX_VALUE;
        stuckTicks = 0;
    }

    /**
     * A one-cell steer, priced in ticks so the queue's timeout is a backstop rather than the
     * primary limit. Without this the queue falls back to its 60-tick ceiling for an unpriced
     * movement — see {@link #NO_PROGRESS_TICKS}.
     */
    @Override
    public double calculateCost(WorldView world, ClientPlayerEntity player) {
        return 20.0;
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
        // Give up the moment we stop closing on the cell, rather than after the queue's eight
        // seconds. Measured against the destination CENTRE so that merely sliding along a wall,
        // which keeps the feet in a new block every few ticks, does not read as progress.
        double dx = player.getEntityPos().x - (dest.getX() + 0.5);
        double dy = player.getEntityPos().y - dest.getY();
        double dz = player.getEntityPos().z - (dest.getZ() + 0.5);
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < bestDistSq - 0.01) {
            bestDistSq = distSq;
            stuckTicks = 0;
        } else if (++stuckTicks > NO_PROGRESS_TICKS) {
            return state.setStatus(MovementStatus.FAILED);
        }

        MovementHelperB.moveTowards(player, state, dest);
        // Stepping up and pressed against something is the one case a plain steer cannot solve.
        if (dest.getY() > src.getY() && player.horizontalCollision) {
            state.setInput(Input.JUMP, true);
        }
        return state;
    }
}
