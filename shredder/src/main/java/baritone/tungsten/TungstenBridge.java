package baritone.tungsten;

import baritone.Baritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.VecUtils;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.BlockStateInterface;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.path.PathFinder;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Coordinates shredder pathfinding with tungsten's physics-based movement.
 * <p>
 * Detects when path segments are simple enough for tungsten to handle
 * (better sprint-jump physics, more natural movement), manages the
 * handoff, monitors progress, and returns control to shredder.
 */
public class TungstenBridge {

    public enum State {
        /** Shredder handles all movement. */
        INACTIVE,
        /** Tungsten is computing a path to the delegated target. */
        PATHFINDING,
        /** Tungsten executor is running the computed path. */
        EXECUTING,
        /** Tungsten finished or failed — returning control to shredder. */
        RETURNING
    }

    private State state = State.INACTIVE;
    private BlockPos tungstenTarget;
    private int shredderResumePosition;
    private int stallTicks;
    private Vec3d lastPlayerPos;

    private static final int MAX_STALL_TICKS = 60; // 3 seconds without progress → abort
    // MIN_SEGMENT_LENGTH read from Settings.tungstenMinSegment at evaluation time
    private static final double ARRIVAL_THRESHOLD = 1.5; // blocks from target to consider arrived

    /**
     * Evaluate whether the current path position should be delegated to tungsten.
     * Call this from PathExecutor.onTick() each tick.
     *
     * @return number of simple movements ahead (0 = don't delegate)
     */
    public int evaluateSegment(List<? extends IMovement> movements, int position, IPlayerContext ctx) {
        if (!Baritone.settings().useTungsten.value) {
            return 0;
        }
        if (state != State.INACTIVE) {
            return 0; // already delegated
        }
        if (TungstenModDataContainer.PATHFINDER.active.get()
                || TungstenModDataContainer.EXECUTOR.isRunning()) {
            return 0; // tungsten is busy with something else
        }

        IMovement current = movements.get(position);
        if (!(current instanceof MovementTraverse) && !(current instanceof MovementDiagonal)) {
            return 0;
        }
        if (current.getDirection().getY() != 0) {
            return 0;
        }

        BlockStateInterface bsi = new BlockStateInterface(ctx);
        int baseY = current.getSrc().getY();
        int simpleCount = 0;
        BlockPos farthestDest = null;

        for (int i = position; i < movements.size() && i <= position + 30; i++) {
            IMovement m = movements.get(i);
            if (!(m instanceof MovementTraverse) && !(m instanceof MovementDiagonal)) {
                break;
            }
            if (m.getDest().getY() != baseY) {
                break;
            }
            Movement mm = (Movement) m;
            if (!mm.toBreak(bsi).isEmpty() || !mm.toPlace(bsi).isEmpty()) {
                break;
            }
            simpleCount++;
            farthestDest = m.getDest();
        }

        if (simpleCount < Baritone.settings().tungstenMinSegment.value || farthestDest == null) {
            return 0;
        }
        return simpleCount;
    }

    /**
     * Start tungsten delegation for a flat segment.
     *
     * @param target          the far end of the simple segment
     * @param resumePosition  path index to resume shredder at when tungsten finishes
     * @param ctx             player context
     */
    public void delegate(BlockPos target, int resumePosition, IPlayerContext ctx) {
        delegate(target, resumePosition, ctx, Optional.empty());
    }

    /**
     * Start tungsten delegation with a baritone block-path hint.
     * Tungsten uses the waypoints to guide its physics search instead of computing
     * block-space A* from scratch.
     *
     * @param target          the far end of the segment
     * @param resumePosition  path index to resume shredder at when tungsten finishes
     * @param ctx             player context
     * @param blockPathHint   baritone waypoints converted to tungsten BlockNodes
     */
    public void delegate(BlockPos target, int resumePosition, IPlayerContext ctx, Optional<List<BlockNode>> blockPathHint) {
        this.tungstenTarget = target;
        this.shredderResumePosition = resumePosition;
        this.stallTicks = 0;
        this.lastPlayerPos = ctx.player().getPos();
        this.state = State.PATHFINDING;

        Vec3d targetVec = VecUtils.getBlockPosCenter(target);

        PathFinder pf = TungstenModDataContainer.PATHFINDER;
        if (blockPathHint.isPresent()) {
            // Experimental: guided search with baritone waypoints
            pf.searchTimeoutMs = 5000L;
            pf.minPathSizeForTimeout = 5;
            pf.find(ctx.world(), targetVec, ctx.player(), blockPathHint);
        } else {
            // Standard: tungsten finds its own block-space path
            pf.searchTimeoutMs = 3000L;
            pf.minPathSizeForTimeout = 3;
            pf.find(ctx.world(), targetVec, ctx.player());
        }
    }

    /**
     * Evaluate if the current segment can be delegated to tungsten in experimental mode.
     * More permissive than {@link #evaluateSegment}: allows Y changes (ascend/descend),
     * but still requires no block breaking or placing.
     *
     * @return number of qualifying movements ahead (0 = don't delegate)
     */
    public int evaluateExperimentalSegment(List<? extends IMovement> movements, int position, IPlayerContext ctx) {
        if (!Baritone.settings().experimentalPathfinding.value) return 0;
        if (state != State.INACTIVE) return 0;
        if (TungstenModDataContainer.PATHFINDER.active.get() || TungstenModDataContainer.EXECUTOR.isRunning()) return 0;

        IMovement current = movements.get(position);
        if (!isExperimentalCompatible(current)) return 0;

        BlockStateInterface bsi = new BlockStateInterface(ctx);
        int count = 0;

        for (int i = position; i < movements.size() && i <= position + 25; i++) {
            IMovement m = movements.get(i);
            if (!isExperimentalCompatible(m)) break;
            Movement mm = (Movement) m;
            if (!mm.toBreak(bsi).isEmpty() || !mm.toPlace(bsi).isEmpty()) break;
            count++;
        }

        if (count < Math.max(3, Baritone.settings().tungstenMinSegment.value / 2)) return 0;
        return count;
    }

    private static boolean isExperimentalCompatible(IMovement m) {
        return m instanceof MovementTraverse
            || m instanceof MovementDiagonal
            || m instanceof MovementAscend
            || m instanceof MovementDescend;
    }

    /**
     * Build a list of tungsten BlockNodes from a slice of baritone movements.
     * Includes the src of the first movement and dest of each movement.
     */
    public static List<BlockNode> buildBlockPath(List<? extends IMovement> movements, int from, int count, BlockPos finalDest, PlayerEntity player) {
        Goal goal = new Goal(finalDest.getX(), finalDest.getY(), finalDest.getZ());
        List<BlockNode> nodes = new ArrayList<>();

        BlockPos src = movements.get(from).getSrc();
        nodes.add(new BlockNode(src.getX(), src.getY(), src.getZ(), goal, player));

        for (int i = from; i < from + count && i < movements.size(); i++) {
            BlockPos dest = movements.get(i).getDest();
            nodes.add(new BlockNode(dest.getX(), dest.getY(), dest.getZ(), goal, player));
        }
        return nodes;
    }

    /**
     * Tick the bridge. Returns true if tungsten is active (shredder should yield movement control).
     */
    public boolean tick(IPlayerContext ctx) {
        switch (state) {
            case INACTIVE:
                return false;

            case PATHFINDING:
                return tickPathfinding(ctx);

            case EXECUTING:
                return tickExecuting(ctx);

            case RETURNING:
                state = State.INACTIVE;
                return false;

            default:
                return false;
        }
    }

    private boolean tickPathfinding(IPlayerContext ctx) {
        stallTicks++;

        // Pathfinder still running
        if (TungstenModDataContainer.PATHFINDER.active.get()) {
            if (stallTicks > 100) { // 5 seconds — pathfinder is taking too long
                abort();
                return false;
            }
            return true; // yield to tungsten, but don't clear shredder keys yet
        }

        // Pathfinder finished — check if executor got a path
        if (TungstenModDataContainer.EXECUTOR.isRunning()) {
            state = State.EXECUTING;
            stallTicks = 0;
            lastPlayerPos = ctx.player().getPos();

            // Set callback for when tungsten finishes
            TungstenModDataContainer.EXECUTOR.cb = () -> {
                state = State.RETURNING;
            };
            return true;
        }

        // Pathfinder finished but no path produced — abort
        abort();
        return false;
    }

    private boolean tickExecuting(IPlayerContext ctx) {
        if (!TungstenModDataContainer.EXECUTOR.isRunning()) {
            // Executor finished
            state = State.RETURNING;
            return false;
        }

        // Stall detection: check if player is actually making progress
        Vec3d currentPos = ctx.player().getPos();
        if (lastPlayerPos != null && currentPos.squaredDistanceTo(lastPlayerPos) < 0.01) {
            stallTicks++;
        } else {
            stallTicks = 0;
        }
        lastPlayerPos = currentPos;

        if (stallTicks > MAX_STALL_TICKS) {
            abort();
            return false;
        }

        // Check if close enough to target (tungsten might overshoot slightly)
        Vec3d targetCenter = VecUtils.getBlockPosCenter(tungstenTarget);
        double dx = currentPos.x - targetCenter.x;
        double dz = currentPos.z - targetCenter.z;
        double distToTarget = Math.sqrt(dx * dx + dz * dz);
        if (distToTarget < ARRIVAL_THRESHOLD) {
            stopTungsten();
            state = State.RETURNING;
            return false;
        }

        return true; // tungsten is driving
    }

    /**
     * Abort tungsten delegation and return control to shredder.
     */
    public void abort() {
        stopTungsten();
        state = State.INACTIVE;
        tungstenTarget = null;
    }

    private void stopTungsten() {
        TungstenModDataContainer.PATHFINDER.stop.set(true);
        if (TungstenModDataContainer.EXECUTOR != null) {
            TungstenModDataContainer.EXECUTOR.stop = true;
        }
    }

    /**
     * Reset when path changes or world changes.
     */
    public void reset() {
        abort();
        shredderResumePosition = 0;
        stallTicks = 0;
    }

    public State getState() {
        return state;
    }

    public boolean isActive() {
        return state != State.INACTIVE;
    }

    public int getShredderResumePosition() {
        return shredderResumePosition;
    }

    public BlockPos getTungstenTarget() {
        return tungstenTarget;
    }
}
