package kaptainwutax.tungsten.path.movements;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.WorldView;

/**
 * A step taken in liquid: swim across, swim up to the surface, or dive.
 *
 * <h2>Why this class has to exist</h2>
 *
 * {@code FastPlanner} already treats water as a first-class part of a route — inside water it
 * expands all SIX directions, including {@code (0,+1,0)} to surface and {@code (0,-1,0)} to dive,
 * and prices them with {@code SWIM_ONE_BLOCK_COST}. The executor had no matching class, so those
 * edges fell to whichever land predicate happened to match the geometry, and a vertical one
 * matches {@link MovementQueue#isPillarEdge}. Measured on chase_terrain: 34 hand-backs in a single
 * run, all of them the same step —
 *
 * <pre>MovementPillar (-177,62,290) -&gt; (-177,63,290)</pre>
 *
 * — and rcon on the live world says (-177,62,290) is {@code minecraft:water} while (-177,63,290) is
 * air. That is a bot floating in a lake being told to build a tower under itself to climb out.
 *
 * <p>Swimming needs no such machinery, and that is the whole point of the class: vanilla swims for
 * you. Hold the movement key toward the destination and hold JUMP to rise. The base
 * {@link Movement#update} already presses JUMP while the feet are in liquid and the body is below
 * {@code dest.y + 0.6}, which covers crossing and surfacing; this class adds the aim, the descent
 * (where that rule correctly does NOT press jump), and an arrival test that tolerates a body which
 * bobs between two cells instead of sitting in one.
 */
public class MovementSwim extends Movement {

    /**
     * Ticks of no approach before giving up. Shorter than a land movement's budget on purpose: a
     * one-block stroke is about ten ticks, and the queue's fallback timeout is
     * {@code cost + 100}, which on this course meant eight seconds of floating in place per
     * failed step.
     */
    private static final int NO_PROGRESS_TICKS = 40;

    private double bestDistSq = Double.MAX_VALUE;
    private int stuckTicks = 0;
    /** G64: ticks a level or rising stroke aimed at head height instead of the cell centre. */
    public static volatile int swimLevelAimTicks;

    public MovementSwim(BetterBlockPos src, BetterBlockPos dest) {
        // Nothing is broken to swim, and nothing is placed. The destination column is declared so
        // the base class still knows where we are going, but see needsClearBreaks() below.
        super(src, dest, new BetterBlockPos[]{dest});
    }

    @Override
    public void reset() {
        super.reset();
        bestDistSq = Double.MAX_VALUE;
        stuckTicks = 0;
    }

    /** A stroke, priced in ticks, so the queue's timeout is a backstop rather than the limit. */
    @Override
    public double calculateCost(WorldView world, ClientPlayerEntity player) {
        return 20.0;
    }

    /**
     * YOU DO NOT MINE WATER. Without this the chain vetting reads the declared destination cell,
     * finds a fluid it is forbidden to break, and refuses the step — the same mistake that used to
     * throw away whole routes at the first water cell.
     */
    @Override
    public boolean needsClearBreaks() {
        return false;
    }

    /** Nothing to prepare: a swim breaks nothing and places nothing. */
    @Override
    protected boolean prepared(MovementState state) {
        return true;
    }

    /** The body legitimately occupies either end while it drifts between them. */
    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.<BetterBlockPos>of(src, dest, src.above(), dest.above(), src.below(),
                dest.below());
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        PlayerEntity player = ctx.player();

        // ARRIVAL IS A DISTANCE, NOT A CELL. A swimmer is rarely centred in a block: the body
        // bobs, and a strict feet-equals-dest test can be missed for a whole stroke at a time.
        double dx = player.getEntityPos().x - (dest.getX() + 0.5);
        double dy = player.getEntityPos().y - dest.getY();
        double dz = player.getEntityPos().z - (dest.getZ() + 0.5);
        double distSq = dx * dx + dy * dy + dz * dz;
        if (ctx.playerFeet().equals(dest) || distSq < 0.36) {
            return state.setStatus(MovementStatus.SUCCESS);
        }

        if (distSq < bestDistSq - 0.01) {
            bestDistSq = distSq;
            stuckTicks = 0;
        } else if (++stuckTicks > NO_PROGRESS_TICKS) {
            return state.setStatus(MovementStatus.FAILED);
        }

        // AIM WHERE YOU ARE SWIMMING — PITCH INCLUDED. In water the body moves in the LOOK
        // direction, so moveTowards keeping the land pitch (level/down) pins a bot at the water's
        // edge when it must climb OUT onto a bank: measured on a lake bench it swam across fine then
        // bobbed at the far edge for 30s+ with pitch ~63 (looking down), JUMP+forward pushing it
        // down into the wall. Aiming the FULL rotation at the destination centre makes swim-forward
        // carry it up and out (and dive actively when the dest is below). (2026-09-11)
        if (kaptainwutax.tungsten.TungstenConfig.get().swimAimsAtDestPitch) {
            Rotation cur = RotationHelper.playerRotations(player);
            net.minecraft.util.math.Vec3d head = RotationHelper.playerHead(player);
            net.minecraft.util.math.Vec3d centre = RotationHelper.getBlockPosCenter(dest);
            // ⛔ A LEVEL STROKE LOOKS LEVEL (G64, 2026-09-12). The centre of the destination CELL
            // is at the feet's height; from the head, a block and a half up, that is a look of
            // forty to sixty degrees DOWN for a stroke to the cell beside us -- and in water the
            // body goes where the eyes look. "Forward" pushed the bot down, the base class's JUMP
            // pushed it up, and it bobbed in place: the 20:14 recording spent its whole ten
            // minutes afloat at (1200,61,-253), MovementSwim (1200,61,-253)->(1201,61,-253)
            // "FAILED at step 0" every twelve seconds, forty ticks without approach each time,
            // the identical plan re-issued, a wooden pickaxe on the lake bed seven below.
            //
            // So the aim point is where the HEAD will be, not where the feet will be, for any
            // stroke that is not a dive: level or rising, look level at the destination column and
            // let JUMP do the rising. A dive keeps the cell centre -- looking down is the dive.
            double eyeLift = head.y - player.getEntityPos().y;
            net.minecraft.util.math.Vec3d aimAt = dest.getY() < src.getY()
                    ? centre
                    : new net.minecraft.util.math.Vec3d(centre.x, dest.getY() + eyeLift, centre.z);
            if (dest.getY() >= src.getY()) swimLevelAimTicks++;
            Rotation aim = RotationHelper.calcRotationFromVec3d(head, aimAt, cur);
            state.setTarget(new MovementState.MovementTarget(aim, false))
                 .setInput(Input.MOVE_FORWARD, true);
        } else {
            MovementHelperB.moveTowards(player, state, dest);
        }
        // Rising is holding JUMP; the base update() already does that while the feet are in liquid
        // and the body is under dest.y + 0.6. Diving is simply NOT pressing it — vanilla sinks a
        // player who stops swimming up — so there is deliberately no input here for dest below us.
        return state;
    }
}
