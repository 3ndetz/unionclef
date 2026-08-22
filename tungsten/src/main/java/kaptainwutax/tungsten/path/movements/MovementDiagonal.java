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
 * {@link MovementQueue} takes a CONTIGUOUS run of steps it has classes for, and a route across
 * open ground is mostly diagonals, so the prefix used to break on the first one. (The freeze
 * counts once quoted here are retracted — register C5.19: they were measured against a lever that
 * was never wired.)
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

    /** Consecutive ticks this diagonal has been walled at the destination's height. */
    private int walledTicks = 0;
    /** Where the body was when it last actually advanced, so a stall is measured against motion. */
    private double lastX = Double.NaN, lastZ = Double.NaN;
    /** Forty ticks is two seconds -- past any honest pause at a corner, well under a run. */
    private static final int WALLED_LIMIT = 40;
    /** Diagonals given up because the body was walled at the destination's height. */
    public static volatile int diagonalWalled = 0;

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
        // ⛔ AN ASCENDING DIAGONAL THAT HAS ALREADY CLIMBED CAN PUSH INTO A WALL FOREVER.
        //
        // Traced on a playthrough, the same line repeating for the whole run:
        //
        //   MV (85,124,-54)->(84,125,-55) st=RUNNING feet=(85,125,-54) pos=85.30,125.00,-53.70
        //   stuck=[on:stone in:air head:air coll:Y v:0.00 fwd:Y jump:n Diagonal/RUNNING/idx0of5]
        //
        // Three things hold that state open together, and none of them is wrong by itself:
        //   - calculateValidPositions() lists src.above() for an ascend, and that is exactly where
        //     the body is, so playerInValidPosition() is true and the movement is never UNREACHABLE;
        //   - the jump gate above tests the body against SRC, and the body has already climbed off
        //     src, so it can never fire again;
        //   - corners() vetted the two corner columns at start.getY(), one level BELOW the body it
        //     now has, so the block actually in the way was never the one checked.
        //
        // The body is at the destination's height, hard against something, at zero velocity, with
        // forward held. Nothing in the movement can change that, and it kept the whole chain alive
        // for a five-minute run: mqStarted=64 against mqSteps=9, dbTargets=12/0, no rungs.
        //
        // Say so instead. UNREACHABLE hands the chain back, the navigator replans, and a route that
        // cannot be walked from here stops being the one being walked. This is deliberately not a
        // nudge or a shove: inventing a jump here would be guessing at which corner is blocked.
        // HEIGHT IS THE WRONG INVARIANT HERE, AND THE FIRST VERSION OF THIS LEARNED IT THE HARD
        // WAY. It asked for the body to be at the destination's height, colliding, at zero speed,
        // for twenty consecutive ticks -- and it fired ZERO times across six runs of a repro that
        // pins the bot at the exact stuck spot every time. The positions say why: y reads 125.2,
        // 124.8, 124.5, 124.0, 125.3. The bot is BOUNCING, so a streak conditioned on height
        // resets on every hop and never reaches its limit.
        //
        // What is actually invariant while this fails is that the body does not ADVANCE. Track the
        // horizontal position and nothing else; a diagonal that has not moved in x or z for two
        // seconds is not going to.
        if (kaptainwutax.tungsten.TungstenConfig.get().diagonalGivesUpWhenWalled) {
            double px = player.getEntityPos().x;
            double pz = player.getEntityPos().z;
            if (Math.abs(px - lastX) < 0.05 && Math.abs(pz - lastZ) < 0.05) {
                if (++walledTicks > WALLED_LIMIT) {
                    diagonalWalled++;
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
            } else {
                walledTicks = 0;
                lastX = px;
                lastZ = pz;
            }
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

    /**
     * MovementDiagonal.java:294-307. Only indices 4..5 — {@code end} and {@code end.above()} — are
     * cells this movement would ever BREAK. The first four are the corner columns, and upstream
     * never breaks those: {@code cost()} accepts a diagonal with ONE solid corner and edges around
     * it. Inheriting the base scan over all six made every wall-hugging diagonal look like it
     * needed mining.
     */
    @Override
    public java.util.List<net.minecraft.util.math.BlockPos> toBreak(WorldView bsi) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        java.util.List<net.minecraft.util.math.BlockPos> result = new java.util.ArrayList<>();
        for (int i = 4; i < 6; i++) {
            if (!MovementHelperB.canWalkThrough(bsi, positionsToBreak[i])) {
                result.add(positionsToBreak[i]);
            }
        }
        toBreakCached = result;
        return result;
    }

    /** MovementDiagonal.java:309-322: the corner columns are cells we WALK INTO, not break. */
    @Override
    public java.util.List<net.minecraft.util.math.BlockPos> toWalkInto(WorldView bsi) {
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new java.util.ArrayList<>();
        }
        for (int i = 0; i < 4; i++) {
            if (!MovementHelperB.canWalkThrough(bsi, positionsToBreak[i])) {
                toWalkIntoCached.add(positionsToBreak[i]);
            }
        }
        return toWalkIntoCached;
    }

    /** MovementDiagonal.java:289-292: a diagonal has nothing to prepare — it never places. */
    @Override
    protected boolean prepared(MovementState state) {
        return true;
    }

    /** ...and therefore it must not be vetted on its declared breaks either. */
    @Override
    public boolean needsClearBreaks() {
        return false;
    }
}
