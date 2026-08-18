package kaptainwutax.tungsten.path.movements;

import java.util.HashSet;
import java.util.Set;

import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldView;

/**
 * Port of {@code baritone/pathing/movement/movements/MovementParkour.java} — a RUNNING JUMP across
 * a gap of two to four blocks, level or one up.
 *
 * <h2>Why this exists, measured</h2>
 *
 * It is the last edge shape the queue could not play, and on real terrain it is the one that
 * matters. From a stalled playthrough run, task chain on {@code <Getting to block 74,127,-57>}:
 *
 * <pre>
 *   mqStarted=28   mqSteps=25   mqTicks=3207   qNoMove=25   mqNoClass=27
 * </pre>
 *
 * The queue started 28 chains and advanced 25 steps in 160 seconds, and {@code mqNoClass=27} is
 * the reason: 27 of them were truncated at an edge with no movement class. The queue runs the
 * prefix it can play — usually one step — the identical route is planned again, and the run is
 * spent re-walking that step. The shape that falls through was already recorded in the queue's own
 * comment from a live run: {@code {90,134,-36} -> {86,135,-34}}, four blocks across and one up.
 *
 * <p>WHY NOW, AND NOT EARLIER. {@code isTraversableEdge}'s note records that wiring ONE class alone
 * measured worse — ascend by itself left the contiguous prefix at ~6% and took chase_terrain from
 * 12 freezes to 22, because a prefix cannot form while any common shape is still missing. Traverse,
 * ascend, descend, diagonal, fall, pillar and swim are all wired now. Parkour is the last one, so
 * this is the point where completing the set can pay rather than cost.
 *
 * <p>Cost is NOT duplicated here: the route is priced by the planner, and two pricing functions for
 * one move is the duplication this project keeps paying for. What is ported is how the jump is
 * EXECUTED — upstream's {@code updateState}, kept in its original order.
 */
public class MovementParkour extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};

    /** Longest gap upstream will price, and the longest this class will accept. */
    public static final int MAX_DIST = 4;

    private final Direction direction;
    private final int dist;
    private final boolean ascend;

    public MovementParkour(BetterBlockPos src, BetterBlockPos dest) {
        super(src, dest, EMPTY, dest.below());
        int dx = dest.getX() - src.getX();
        int dz = dest.getZ() - src.getZ();
        this.direction = dx != 0
                ? (dx > 0 ? Direction.EAST : Direction.WEST)
                : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        this.dist = Math.abs(dx) + Math.abs(dz);
        this.ascend = dest.getY() > src.getY();
    }

    /**
     * MovementParkour.java:232-241 — every cell the body passes over, at foot and head height. A
     * jump that is off-path for its whole flight would otherwise read as a route abandonment.
     */
    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        for (int i = 0; i <= dist; i++) {
            for (int y = 0; y < 2; y++) {
                set.add(src.relative(direction, i).above(y));
            }
        }
        set.add(dest);
        set.add(dest.above());
        return set;
    }

    /**
     * MovementParkour.java:243-249. Once airborne there is no way to take the momentum back, so the
     * jump may only be cancelled before it has been ticked.
     */
    @Override
    public boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING;
    }

    /**
     * {@code updateState} — MovementParkour.java:251-308, ported rather than paraphrased.
     *
     * <p>The order is load-bearing. Falling below the source is failure and is checked first; the
     * sprint is armed only for the jumps that need it; and the late-jump guard for the three-cell
     * gap exists because jumping a tick early clears it and a tick late does not.
     */
    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        PlayerEntity player = ctx.player();
        WorldView world = player.getEntityWorld();

        if (ctx.playerFeet().getY() < src.getY()) {
            // We have fallen out of the jump; nothing here can recover it.
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (dist >= 4 || ascend) {
            state.setInput(Input.SPRINT, true);
        }
        MovementHelperB.moveTowards(player, state, dest);

        if (ctx.playerFeet().equals(dest)) {
            Block d = world.getBlockState(dest).getBlock();
            if (d == Blocks.VINE || d == Blocks.LADDER) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            // Landing is claimed only once the body has actually settled onto the cell, which is
            // what keeps a lilypad or a slab from reading as a miss.
            if (player.getEntityPos().y - ctx.playerFeet().getY() < 0.094) {
                state.setStatus(MovementStatus.SUCCESS);
            }
            return state;
        }

        if (ctx.playerFeet().equals(src)) {
            return state;
        }

        if (ctx.playerFeet().equals(src.relative(direction))
                || player.getEntityPos().y - src.getY() > 0.0001) {
            // THE LATE-JUMP GUARD, and it is not a tuning constant. For a three-cell gap the body
            // must leave the lip rather than the middle of the source cell: jumping while still
            // behind 0.7 of the way across lands short every time.
            if (dist == 3 && !ascend) {
                double xDiff = (src.getX() + 0.5) - player.getEntityPos().x;
                double zDiff = (src.getZ() + 0.5) - player.getEntityPos().z;
                if (Math.max(Math.abs(xDiff), Math.abs(zDiff)) < 0.7) {
                    return state;
                }
            }
            return state.setInput(Input.JUMP, true);
        }

        if (!ctx.playerFeet().equals(dest.relative(direction, -1))) {
            // Overshot or drifted off the run-up: walk back to the lip and try again rather than
            // jumping from wherever the body happens to be.
            state.setInput(Input.SPRINT, false);
            if (ctx.playerFeet().equals(src.relative(direction, -1))) {
                MovementHelperB.moveTowards(player, state, src);
            } else {
                MovementHelperB.moveTowards(player, state, src.relative(direction, -1));
            }
        }
        return state;
    }
}
