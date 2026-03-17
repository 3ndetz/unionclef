/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.path;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.*;
import baritone.utils.BlockStateInterface;
import java.util.*;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import static baritone.api.pathing.movement.MovementStatus.*;

/**
 * Behavior to execute a precomputed path
 *
 * @author leijurv
 */
public class PathExecutor implements IPathExecutor, Helper {

    private static final double MAX_MAX_DIST_FROM_PATH = 3;
    private static final double MAX_DIST_FROM_PATH = 2;

    /**
     * Default value is equal to 10 seconds. It's find to decrease it, but it must be at least 5.5s (110 ticks).
     * For more information, see issue #102.
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/102">Issue #102</a>
     * @see <a href="https://i.imgur.com/5s5GLnI.png">Anime</a>
     */
    private static final double MAX_TICKS_AWAY = 200;

    private final IPath path;
    private int pathPosition;
    private int ticksAway;
    private int ticksOnCurrent;
    private Double currentMovementOriginalCostEstimate;
    private Integer costEstimateIndex;
    private boolean failed;
    private boolean recalcBP = true;
    private HashSet<BlockPos> toBreak = new HashSet<>();
    private HashSet<BlockPos> toPlace = new HashSet<>();
    private HashSet<BlockPos> toWalkInto = new HashSet<>();

    private final PathingBehavior behavior;
    private final IPlayerContext ctx;

    private boolean sprintNextTick;
    private boolean sprintJumping;

    private final baritone.tungsten.TungstenBridge tungstenBridge = new baritone.tungsten.TungstenBridge();

    // Jump bridging state
    private enum JumpBridgePhase { NONE, SPRINT, AIRBORNE }
    private JumpBridgePhase jumpBridgePhase = JumpBridgePhase.NONE;
    private boolean jumpBridging;
    private int jumpBridgeTicksInPhase;
    private int jumpBridgeNextClickTick;
    private int jumpBridgeMoveIndex;
    private int jumpBridgeDirX, jumpBridgeDirZ; // world-space movement direction
    private BlockPos jumpBridgeLastSolid; // last existing/placed block to click against
    private static final java.util.Random jumpBridgeRandom = new java.util.Random();

    public PathExecutor(PathingBehavior behavior, IPath path) {
        this.behavior = behavior;
        this.ctx = behavior.ctx;
        this.path = path;
        this.pathPosition = 0;
    }

    /**
     * Tick this executor
     *
     * @return True if a movement just finished (and the player is therefore in a "stable" state, like,
     * not sneaking out over lava), false otherwise
     */
    public boolean onTick() {
        if (pathPosition == path.length() - 1) {
            pathPosition++;
        }
        if (pathPosition >= path.length()) {
            return true; // stop bugging me, I'm done
        }

        // Tungsten bridge: if active, let tungsten drive and skip shredder movement logic
        if (tungstenBridge.isActive()) {
            boolean tungstenDriving = tungstenBridge.tick(ctx);
            if (tungstenDriving) {
                // Tungsten is handling movement — clear shredder keys and yield
                clearKeys();
                return false;
            }
            // Tungsten finished — snap pathPosition forward to resume point
            int resume = tungstenBridge.getShredderResumePosition();
            if (resume > pathPosition && resume < path.length()) {
                pathPosition = resume;
                onChangeInPathPosition();
            }
        }

        // Tungsten bridge: evaluate if current segment should be delegated
        if (!tungstenBridge.isActive() && !sprintJumping && pathPosition < path.movements().size()) {
            int simpleAhead = tungstenBridge.evaluateSegment(path.movements(), pathPosition, ctx);
            if (simpleAhead > 0) {
                BlockPos target = path.movements().get(pathPosition + simpleAhead - 1).getDest();
                int resumeAt = Math.min(pathPosition + simpleAhead, path.movements().size() - 1);
                tungstenBridge.delegate(target, resumeAt, ctx);
                clearKeys();
                return false;
            }
        }

        // Jump bridging: airborne block placement
        if (jumpBridging) {
            return tickJumpBridge();
        }

        Movement movement = (Movement) path.movements().get(pathPosition);
        BetterBlockPos whereAmI = ctx.playerFeet();

        // Sprint-jump airborne handling: skip backtrack, keep aiming forward, snap on landing
        if (sprintJumping) {
            if (!ctx.player().isOnGround()) {
                // still airborne — maintain sprint, aim at look-ahead target, skip position checks
                behavior.baritone.getInputOverrideHandler().clearAllKeys();
                behavior.baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                behavior.baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
                sprintNextTick = true;
                // aim at furthest safe point ahead
                BlockPos lookTarget = getSprintJumpLookAhead();
                if (lookTarget != null) {
                    behavior.baritone.getLookBehavior().updateTarget(
                            RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                                    VecUtils.getBlockPosCenter(lookTarget),
                                    ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch()),
                            false);
                }
                return false;
            }
            // landed — snap forward to furthest matching position
            sprintJumping = false;
            for (int i = Math.min(path.length() - 2, pathPosition + 10); i > pathPosition; i--) {
                if (((Movement) path.movements().get(i)).getValidPositions().contains(whereAmI)) {
                    pathPosition = i;
                    onChangeInPathPosition();
                    break;
                }
            }
        }

        if (!movement.getValidPositions().contains(whereAmI)) {
            for (int i = 0; i < pathPosition && i < path.length(); i++) {//this happens for example when you lag out and get teleported back a couple blocks
                if (((Movement) path.movements().get(i)).getValidPositions().contains(whereAmI)) {
                    int previousPos = pathPosition;
                    pathPosition = i;
                    for (int j = pathPosition; j <= previousPos; j++) {
                        path.movements().get(j).reset();
                    }
                    onChangeInPathPosition();
                    onTick();
                    return false;
                }
            }
            for (int i = pathPosition + 3; i < path.length() - 1; i++) { //dont check pathPosition+1. the movement tells us when it's done (e.g. sneak placing)
                // also don't check pathPosition+2 because reasons
                if (((Movement) path.movements().get(i)).getValidPositions().contains(whereAmI)) {
                    if (i - pathPosition > 2) {
                        logDebug("Skipping forward " + (i - pathPosition) + " steps, to " + i);
                    }
                    //System.out.println("Double skip sundae");
                    pathPosition = i - 1;
                    onChangeInPathPosition();
                    onTick();
                    return false;
                }
            }
        }
        Pair<Double, BlockPos> status = closestPathPos(path);
        if (possiblyOffPath(status, MAX_DIST_FROM_PATH)) {
            ticksAway++;
            System.out.println("FAR AWAY FROM PATH FOR " + ticksAway + " TICKS. Current distance: " + status.getLeft() + ". Threshold: " + MAX_DIST_FROM_PATH);
            if (ticksAway > MAX_TICKS_AWAY) {
                logDebug("Too far away from path for too long, cancelling path");
                cancel();
                return false;
            }
        } else {
            ticksAway = 0;
        }
        if (possiblyOffPath(status, MAX_MAX_DIST_FROM_PATH)) { // ok, stop right away, we're way too far.
            logDebug("too far from path");
            cancel();
            return false;
        }
        //long start = System.nanoTime() / 1000000L;
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        for (int i = pathPosition - 10; i < pathPosition + 10; i++) {
            if (i < 0 || i >= path.movements().size()) {
                continue;
            }
            Movement m = (Movement) path.movements().get(i);
            List<BlockPos> prevBreak = m.toBreak(bsi);
            List<BlockPos> prevPlace = m.toPlace(bsi);
            List<BlockPos> prevWalkInto = m.toWalkInto(bsi);
            m.resetBlockCache();
            if (!prevBreak.equals(m.toBreak(bsi))) {
                recalcBP = true;
            }
            if (!prevPlace.equals(m.toPlace(bsi))) {
                recalcBP = true;
            }
            if (!prevWalkInto.equals(m.toWalkInto(bsi))) {
                recalcBP = true;
            }
        }
        if (recalcBP) {
            HashSet<BlockPos> newBreak = new HashSet<>();
            HashSet<BlockPos> newPlace = new HashSet<>();
            HashSet<BlockPos> newWalkInto = new HashSet<>();
            for (int i = pathPosition; i < path.movements().size(); i++) {
                Movement m = (Movement) path.movements().get(i);
                newBreak.addAll(m.toBreak(bsi));
                newPlace.addAll(m.toPlace(bsi));
                newWalkInto.addAll(m.toWalkInto(bsi));
            }
            toBreak = newBreak;
            toPlace = newPlace;
            toWalkInto = newWalkInto;
            recalcBP = false;
        }
        /*long end = System.nanoTime() / 1000000L;
        if (end - start > 0) {
            System.out.println("Recalculating break and place took " + (end - start) + "ms");
        }*/
        if (pathPosition < path.movements().size() - 1) {
            IMovement next = path.movements().get(pathPosition + 1);
            if (!behavior.baritone.bsi.worldContainsLoadedChunk(next.getDest().x, next.getDest().z)) {
                logDebug("Pausing since destination is at edge of loaded chunks");
                clearKeys();
                return true;
            }
        }
        boolean canCancel = movement.safeToCancel();
        if (costEstimateIndex == null || costEstimateIndex != pathPosition) {
            costEstimateIndex = pathPosition;
            // do this only once, when the movement starts, and deliberately get the cost as cached when this path was calculated, not the cost as it is right now
            currentMovementOriginalCostEstimate = movement.getCost();
            for (int i = 1; i < Baritone.settings().costVerificationLookahead.value && pathPosition + i < path.length() - 1; i++) {
                if (((Movement) path.movements().get(pathPosition + i)).calculateCost(behavior.secretInternalGetCalculationContext()) >= ActionCosts.COST_INF && canCancel) {
                    logDebug("Something has changed in the world and a future movement has become impossible. Cancelling.");
                    cancel();
                    return true;
                }
            }
        }
        double currentCost = movement.recalculateCost(behavior.secretInternalGetCalculationContext());
        if (currentCost >= ActionCosts.COST_INF && canCancel) {
            logDebug("Something has changed in the world and this movement has become impossible. Cancelling.");
            cancel();
            return true;
        }
        if (!movement.calculatedWhileLoaded() && currentCost - currentMovementOriginalCostEstimate > Baritone.settings().maxCostIncrease.value && canCancel) {
            // don't do this if the movement was calculated while loaded
            // that means that this isn't a cache error, it's just part of the path interfering with a later part
            logDebug("Original cost " + currentMovementOriginalCostEstimate + " current cost " + currentCost + ". Cancelling.");
            cancel();
            return true;
        }
        if (shouldPause()) {
            logDebug("Pausing since current best path is a backtrack");
            clearKeys();
            return true;
        }
        // Jump bridging: detect consecutive bridge movements and start jump-place sequence
        if (Baritone.settings().jumpBridging.value && !jumpBridging
                && ctx.player().isOnGround() && tryStartJumpBridge(movement)) {
            return false;
        }

        MovementStatus movementStatus = movement.update();
        if (movementStatus == UNREACHABLE || movementStatus == FAILED) {
            logDebug("Movement returns status " + movementStatus);
            cancel();
            return true;
        }
        if (movementStatus == SUCCESS) {
            //System.out.println("Movement done, next path");
            pathPosition++;
            onChangeInPathPosition();
            onTick();
            return true;
        } else {
            boolean complexTerrain = isNearComplexTerrain();
            sprintNextTick = shouldSprintNextTick();
            if (!complexTerrain && sprintNextTick && canSprintJump()) {
                behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                sprintJumping = true;
            }
            if (!sprintNextTick) {
                ctx.player().setSprinting(false); // letting go of control doesn't make you stop sprinting actually
            }
            if (!complexTerrain) {
                overrideLookAheadIfSafe();
                applyEntropyDeviation();
            }
            ticksOnCurrent++;
            if (ticksOnCurrent > currentMovementOriginalCostEstimate + Baritone.settings().movementTimeoutTicks.value) {
                // only cancel if the total time has exceeded the initial estimate
                // as you break the blocks required, the remaining cost goes down, to the point where
                // ticksOnCurrent is greater than recalculateCost + 100
                // this is why we cache cost at the beginning, and don't recalculate for this comparison every tick
                logDebug("This movement has taken too long (" + ticksOnCurrent + " ticks, expected " + currentMovementOriginalCostEstimate + "). Cancelling.");
                cancel();
                return true;
            }
        }
        return canCancel; // movement is in progress, but if it reports cancellable, PathingBehavior is good to cut onto the next path
    }

    private Pair<Double, BlockPos> closestPathPos(IPath path) {
        double best = -1;
        BlockPos bestPos = null;
        for (IMovement movement : path.movements()) {
            for (BlockPos pos : ((Movement) movement).getValidPositions()) {
                double dist = VecUtils.entityDistanceToCenter(ctx.player(), pos);
                if (dist < best || best == -1) {
                    best = dist;
                    bestPos = pos;
                }
            }
        }
        return new Pair<>(best, bestPos);
    }

    private boolean shouldPause() {
        Optional<AbstractNodeCostSearch> current = behavior.getInProgress();
        if (!current.isPresent()) {
            return false;
        }
        if (!ctx.player().isOnGround()) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, ctx.playerFeet().down())) {
            // we're in some kind of sketchy situation, maybe parkouring
            return false;
        }
        if (!MovementHelper.canWalkThrough(ctx, ctx.playerFeet()) || !MovementHelper.canWalkThrough(ctx, ctx.playerFeet().up())) {
            // suffocating?
            return false;
        }
        if (!path.movements().get(pathPosition).safeToCancel()) {
            return false;
        }
        Optional<IPath> currentBest = current.get().bestPathSoFar();
        if (!currentBest.isPresent()) {
            return false;
        }
        List<BetterBlockPos> positions = currentBest.get().positions();
        if (positions.size() < 3) {
            return false; // not long enough yet to justify pausing, its far from certain we'll actually take this route
        }
        // the first block of the next path will always overlap
        // no need to pause our very last movement when it would have otherwise cleanly exited with MovementStatus SUCCESS
        positions = positions.subList(1, positions.size());
        return positions.contains(ctx.playerFeet());
    }

    private boolean possiblyOffPath(Pair<Double, BlockPos> status, double leniency) {
        double distanceFromPath = status.getLeft();
        if (distanceFromPath > leniency) {
            // when we're midair in the middle of a fall, we're very far from both the beginning and the end, but we aren't actually off path
            if (path.movements().get(pathPosition) instanceof MovementFall) {
                BlockPos fallDest = path.positions().get(pathPosition + 1); // .get(pathPosition) is the block we fell off of
                return VecUtils.entityFlatDistanceToCenter(ctx.player(), fallDest) >= leniency; // ignore Y by using flat distance
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    /**
     * Regardless of current path position, snap to the current player feet if possible
     *
     * @return Whether or not it was possible to snap to the current player feet
     */
    public boolean snipsnapifpossible() {
        if (!ctx.player().isOnGround() && ctx.world().getFluidState(ctx.playerFeet()).isEmpty()) {
            // if we're falling in the air, and not in water, don't splice
            return false;
        } else {
            // we are either onGround or in liquid
            if (ctx.player().getVelocity().y < -0.1) {
                // if we are strictly moving downwards (not stationary)
                // we could be falling through water, which could be unsafe to splice
                return false; // so don't
            }
        }
        int index = path.positions().indexOf(ctx.playerFeet());
        if (index == -1) {
            return false;
        }
        pathPosition = index; // jump directly to current position
        clearKeys();
        return true;
    }

    private boolean shouldSprintNextTick() {
        boolean requested = behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.SPRINT);

        // we'll take it from here, no need for minecraft to see we're holding down control and sprint for us
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);

        // first and foremost, if allowSprint is off, or if we don't have enough hunger, don't try and sprint
        if (!new CalculationContext(behavior.baritone, false).canSprint) {
            return false;
        }
        IMovement current = path.movements().get(pathPosition);

        // traverse requests sprinting, so we need to do this check first
        if (current instanceof MovementTraverse && pathPosition < path.length() - 3) {
            IMovement next = path.movements().get(pathPosition + 1);
            if (next instanceof MovementAscend && sprintableAscend(ctx, (MovementTraverse) current, (MovementAscend) next, path.movements().get(pathPosition + 2))) {
                if (skipNow(ctx, current)) {
                    logDebug("Skipping traverse to straight ascend");
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick();
                    behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    return true;
                } else {
                    logDebug("Too far to the side to safely sprint ascend");
                }
            }
        }

        // if the movement requested sprinting, then we're done
        if (requested) {
            return true;
        }

        // however, descend and ascend don't request sprinting, because they don't know the context of what movement comes after it
        if (current instanceof MovementDescend) {

            if (pathPosition < path.length() - 2) {
                // keep this out of onTick, even if that means a tick of delay before it has an effect
                IMovement next = path.movements().get(pathPosition + 1);
                if (MovementHelper.canUseFrostWalker(ctx, next.getDest().down())) {
                    // frostwalker only works if you cross the edge of the block on ground so in some cases we may not overshoot
                    // Since MovementDescend can't know the next movement we have to tell it
                    if (next instanceof MovementTraverse || next instanceof MovementParkour) {
                        boolean couldPlaceInstead = Baritone.settings().allowPlace.value && behavior.baritone.getInventoryBehavior().hasGenericThrowaway() && next instanceof MovementParkour; // traverse doesn't react fast enough
                        // this is true if the next movement does not ascend or descends and goes into the same cardinal direction (N-NE-E-SE-S-SW-W-NW) as the descend
                        // in that case current.getDirection() is e.g. (0, -1, 1) and next.getDirection() is e.g. (0, 0, 3) so the cross product of (0, 0, 1) and (0, 0, 3) is taken, which is (0, 0, 0) because the vectors are colinear (don't form a plane)
                        // since movements in exactly the opposite direction (e.g. descend (0, -1, 1) and traverse (0, 0, -1)) would also pass this check we also have to rule out that case
                        // we can do that by adding the directions because traverse is always 1 long like descend and parkour can't jump through current.getSrc().down()
                        boolean sameFlatDirection = !current.getDirection().up().add(next.getDirection()).equals(BlockPos.ORIGIN)
                                && current.getDirection().up().crossProduct(next.getDirection()).equals(BlockPos.ORIGIN); // here's why you learn maths in school
                        if (sameFlatDirection && !couldPlaceInstead) {
                            ((MovementDescend) current).forceSafeMode();
                        }
                    }
                }
            }
            if (((MovementDescend) current).safeMode() && !((MovementDescend) current).skipToAscend()) {
                logDebug("Sprinting would be unsafe");
                return false;
            }

            if (pathPosition < path.length() - 2) {
                IMovement next = path.movements().get(pathPosition + 1);
                if (next instanceof MovementAscend && current.getDirection().up().equals(next.getDirection().down())) {
                    // a descend then an ascend in the same direction
                    pathPosition++;
                    onChangeInPathPosition();
                    onTick();
                    // okay to skip clearKeys and / or onChangeInPathPosition here since this isn't possible to repeat, since it's asymmetric
                    logDebug("Skipping descend to straight ascend");
                    return true;
                }
                if (canSprintFromDescendInto(ctx, current, next)) {

                    if (next instanceof MovementDescend && pathPosition < path.length() - 3) {
                        IMovement next_next = path.movements().get(pathPosition + 2);
                        if (next_next instanceof MovementDescend && !canSprintFromDescendInto(ctx, next, next_next)) {
                            return false;
                        }

                    }
                    if (ctx.playerFeet().equals(current.getDest())) {
                        pathPosition++;
                        onChangeInPathPosition();
                        onTick();
                    }

                    return true;
                }
                //logDebug("Turning off sprinting " + movement + " " + next + " " + movement.getDirection() + " " + next.getDirection().down() + " " + next.getDirection().down().equals(movement.getDirection()));
            }
        }
        if (current instanceof MovementAscend && pathPosition != 0) {
            IMovement prev = path.movements().get(pathPosition - 1);
            if (prev instanceof MovementDescend && prev.getDirection().up().equals(current.getDirection().down())) {
                BlockPos center = current.getSrc().up();
                // playerFeet adds 0.1251 to account for soul sand
                // farmland is 0.9375
                // 0.07 is to account for farmland
                if (ctx.player().getPos().y >= center.getY() - 0.07) {
                    behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
                    return true;
                }
            }
            if (pathPosition < path.length() - 2 && prev instanceof MovementTraverse && sprintableAscend(ctx, (MovementTraverse) prev, (MovementAscend) current, path.movements().get(pathPosition + 1))) {
                return true;
            }
        }
        if (current instanceof MovementFall) {
            Pair<Vec3d, BlockPos> data = overrideFall((MovementFall) current);
            if (data != null) {
                BetterBlockPos fallDest = new BetterBlockPos(data.getRight());
                if (!path.positions().contains(fallDest)) {
                    throw new IllegalStateException();
                }
                if (ctx.playerFeet().equals(fallDest)) {
                    pathPosition = path.positions().indexOf(fallDest);
                    onChangeInPathPosition();
                    onTick();
                    return true;
                }
                clearKeys();
                behavior.baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), data.getLeft(), ctx.playerRotations()), false);
                behavior.baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                return true;
            }
        }
        return false;
    }

    private Pair<Vec3d, BlockPos> overrideFall(MovementFall movement) {
        Vec3i dir = movement.getDirection();
        if (dir.getY() < -3) {
            return null;
        }
        if (!movement.toBreakCached.isEmpty()) {
            return null; // it's breaking
        }
        Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
        int i;
        outer:
        for (i = pathPosition + 1; i < path.length() - 1 && i < pathPosition + 3; i++) {
            IMovement next = path.movements().get(i);
            if (!(next instanceof MovementTraverse)) {
                break;
            }
            if (!flatDir.equals(next.getDirection())) {
                break;
            }
            for (int y = next.getDest().y; y <= movement.getSrc().y + 1; y++) {
                BlockPos chk = new BlockPos(next.getDest().x, y, next.getDest().z);
                if (!MovementHelper.fullyPassable(ctx, chk)) {
                    break outer;
                }
            }
            if (!MovementHelper.canWalkOn(ctx, next.getDest().down())) {
                break;
            }
        }
        i--;
        if (i == pathPosition) {
            return null; // no valid extension exists
        }
        double len = i - pathPosition - 0.4;
        return new Pair<>(
                new Vec3d(flatDir.getX() * len + movement.getDest().x + 0.5, movement.getDest().y, flatDir.getZ() * len + movement.getDest().z + 0.5),
                movement.getDest().add(flatDir.getX() * (i - pathPosition), 0, flatDir.getZ() * (i - pathPosition)));
    }

    private static boolean skipNow(IPlayerContext ctx, IMovement current) {
        double offTarget = Math.abs(current.getDirection().getX() * (current.getSrc().z + 0.5D - ctx.player().getPos().z)) + Math.abs(current.getDirection().getZ() * (current.getSrc().x + 0.5D - ctx.player().getPos().x));
        if (offTarget > 0.1) {
            return false;
        }
        // we are centered
        BlockPos headBonk = current.getSrc().subtract(current.getDirection()).up(2);
        if (MovementHelper.fullyPassable(ctx, headBonk)) {
            return true;
        }
        // wait 0.3
        double flatDist = Math.abs(current.getDirection().getX() * (headBonk.getX() + 0.5D - ctx.player().getPos().x)) + Math.abs(current.getDirection().getZ() * (headBonk.getZ() + 0.5 - ctx.player().getPos().z));
        return flatDist > 0.8;
    }

    private static boolean sprintableAscend(IPlayerContext ctx, MovementTraverse current, MovementAscend next, IMovement nextnext) {
        if (!Baritone.settings().sprintAscends.value) {
            return false;
        }
        if (!current.getDirection().equals(next.getDirection().down())) {
            return false;
        }
        if (nextnext.getDirection().getX() != next.getDirection().getX() || nextnext.getDirection().getZ() != next.getDirection().getZ()) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, current.getDest().down())) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, next.getDest().down())) {
            return false;
        }
        if (!next.toBreakCached.isEmpty()) {
            return false; // it's breaking
        }
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                BlockPos chk = current.getSrc().up(y);
                if (x == 1) {
                    chk = chk.add(current.getDirection());
                }
                if (!MovementHelper.fullyPassable(ctx, chk)) {
                    return false;
                }
            }
        }
        if (MovementHelper.avoidWalkingInto(ctx.world().getBlockState(current.getSrc().up(3)))) {
            return false;
        }
        if (AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(current.getSrc().up(3))
                || AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(current.getSrc().up(2))) {
            return false;
        }
        return !MovementHelper.avoidWalkingInto(ctx.world().getBlockState(next.getDest().up(2))); // codacy smh my head
    }

    private static boolean canSprintFromDescendInto(IPlayerContext ctx, IMovement current, IMovement next) {
        if (next instanceof MovementDescend && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        if (!MovementHelper.canWalkOn(ctx, current.getDest().add(current.getDirection()))) {
            return false;
        }
        if (next instanceof MovementTraverse && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        return next instanceof MovementDiagonal && Baritone.settings().allowOvershootDiagonalDescend.value;
    }

    /**
     * On perfectly clear paths, override look target to a far point.
     * Uses world raycast to verify actual line of sight — if anything
     * blocks the view between eyes and target, bail out entirely.
     */
    private void overrideLookAheadIfSafe() {
        if (!Baritone.settings().pathLookAhead.value) {
            return;
        }
        IMovement current = path.movements().get(pathPosition);
        if (!(current instanceof MovementTraverse) && !(current instanceof MovementDiagonal)) {
            return;
        }
        if (current.getDirection().getY() != 0) {
            return;
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        Movement currentM = (Movement) current;
        if (!currentM.toBreak(bsi).isEmpty() || !currentM.toPlace(bsi).isEmpty()) {
            return;
        }
        // scan ahead — every movement must be flat, simple, same Y, no breaking/placing
        int baseY = current.getSrc().getY();
        int safeCount = 0;
        BlockPos bestTarget = null;
        for (int i = pathPosition + 1; i < path.movements().size() && i <= pathPosition + 12; i++) {
            IMovement m = path.movements().get(i);
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
            safeCount++;
            bestTarget = m.getDest();
        }
        if (safeCount < 4 || bestTarget == null) {
            return;
        }
        // world raycast: verify clear line of sight from eyes to target center
        Vec3d eyes = ctx.player().getCameraPosVec(1.0f);
        Vec3d targetCenter = VecUtils.getBlockPosCenter(bestTarget);
        net.minecraft.util.hit.HitResult hit = ctx.world().raycast(
                new net.minecraft.world.RaycastContext(
                        eyes, targetCenter,
                        net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                        net.minecraft.world.RaycastContext.FluidHandling.NONE,
                        ctx.player()));
        if (hit.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
            // something blocks the view — don't override
            return;
        }
        behavior.baritone.getLookBehavior().updateTarget(
                RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                        targetCenter,
                        ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch()),
                false);
    }

    /**
     * Find a look-ahead target for sprint-jumping: the furthest safe destination
     * on the path within a reasonable range.
     */
    private BlockPos getSprintJumpLookAhead() {
        int maxLook = Math.min(pathPosition + 8, path.movements().size() - 1);
        BlockPos best = null;
        for (int i = pathPosition; i <= maxLook; i++) {
            IMovement m = path.movements().get(i);
            if (m.getDirection().getY() != 0) {
                break;
            }
            best = m.getDest();
        }
        return best;
    }

    private boolean canSprintJump() {
        if (!Baritone.settings().sprintJumpOnFlatStraights.value) {
            return false;
        }
        if (!ctx.player().isOnGround()) {
            return false;
        }
        Movement current = (Movement) path.movements().get(pathPosition);
        if (!(current instanceof MovementTraverse) && !(current instanceof MovementDiagonal)) {
            return false;
        }
        // don't jump while breaking blocks
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        if (!current.toBreak(bsi).isEmpty()) {
            return false;
        }
        Vec3i direction = current.getDirection();
        // must be flat (no Y change)
        if (direction.getY() != 0) {
            return false;
        }
        Class<? extends IMovement> movementType = current.getClass();
        // need lookahead + 2 straight blocks: lookahead to trigger, +2 margin for jump arc before turn
        int lookahead = Baritone.settings().sprintJumpLookahead.value + 2;
        int straightCount = 0;
        for (int i = pathPosition + 1; i < path.movements().size() && straightCount < lookahead; i++) {
            IMovement next = path.movements().get(i);
            if (!movementType.isInstance(next)) {
                break;
            }
            if (!next.getDirection().equals(direction)) {
                break;
            }
            // don't jump into blocks that need breaking
            if (!((Movement) next).toBreak(bsi).isEmpty()) {
                break;
            }
            // check the path ahead is walkable and clear
            if (!MovementHelper.canWalkOn(ctx, next.getDest().down())) {
                break;
            }
            if (!MovementHelper.canWalkThrough(ctx, next.getDest()) || !MovementHelper.canWalkThrough(ctx, next.getDest().up())) {
                break;
            }
            straightCount++;
        }
        if (straightCount >= lookahead) {
            return true;
        }
        // staircase optimization: alternating perpendicular Traverses form a diagonal
        if (current instanceof MovementTraverse) {
            return canSprintJumpStaircase();
        }
        return false;
    }

    /**
     * Detect staircase patterns (alternating perpendicular Traverse moves that form a diagonal)
     * and allow sprint-jumping through them.
     */
    private boolean canSprintJumpStaircase() {
        int lookahead = Baritone.settings().sprintJumpLookahead.value + 2;
        // need at least 2*lookahead alternating moves (pairs of perpendicular traverses)
        int required = lookahead * 2;
        if (pathPosition + required >= path.movements().size()) {
            return false;
        }
        IMovement first = path.movements().get(pathPosition);
        if (!(first instanceof MovementTraverse)) {
            return false;
        }
        Vec3i dir1 = first.getDirection();
        if (dir1.getY() != 0) {
            return false;
        }
        IMovement second = path.movements().get(pathPosition + 1);
        if (!(second instanceof MovementTraverse)) {
            return false;
        }
        Vec3i dir2 = second.getDirection();
        if (dir2.getY() != 0) {
            return false;
        }
        // must be perpendicular: dot product = 0 and not same direction
        if (dir1.equals(dir2) || dir1.getX() * dir2.getX() + dir1.getZ() * dir2.getZ() != 0) {
            return false;
        }
        // check the alternating pattern continues
        for (int i = pathPosition; i < pathPosition + required; i++) {
            IMovement m = path.movements().get(i);
            if (!(m instanceof MovementTraverse)) {
                return false;
            }
            Vec3i expected = (i - pathPosition) % 2 == 0 ? dir1 : dir2;
            if (!m.getDirection().equals(expected)) {
                return false;
            }
            if (!MovementHelper.canWalkOn(ctx, m.getDest().down())) {
                return false;
            }
            if (!MovementHelper.canWalkThrough(ctx, m.getDest()) || !MovementHelper.canWalkThrough(ctx, m.getDest().up())) {
                return false;
            }
        }
        return true;
    }

    private void onChangeInPathPosition() {
        clearKeys();
        ticksOnCurrent = 0;
    }

    // ── Jump bridging ────────────────────────────────────────────────────────

    /**
     * Check if current position has ≥3 consecutive bridge movements and start jump bridge.
     * Phase 1 (PRE_ROTATE): sneak to edge, rotate backward to look at placement face.
     * Phase 2 (JUMPING): jump + walk backward, start placing.
     * Phase 3 (AIRBORNE): keep placing blocks with randomized CPS while airborne.
     */
    private boolean tryStartJumpBridge(Movement current) {
        if (!(current instanceof MovementTraverse)) return false;

        BlockStateInterface bsi = new BlockStateInterface(ctx);
        if (current.toPlace(bsi).isEmpty()) return false;

        // Need consistent direction (straight line bridge)
        Vec3i dir = current.getDirection();
        if (dir.getY() != 0) return false;

        int bridgeCount = 1;
        for (int i = pathPosition + 1; i < path.movements().size() && i <= pathPosition + 6; i++) {
            IMovement m = path.movements().get(i);
            if (!(m instanceof MovementTraverse)) break;
            if (!m.getDirection().equals(dir)) break;
            if (((Movement) m).toPlace(bsi).isEmpty()) break;
            bridgeCount++;
        }
        if (bridgeCount < 3) return false;
        if (!behavior.baritone.getInventoryBehavior().hasGenericThrowaway()) return false;

        // Select throwaway block
        BlockPos firstPlace = current.getDest().down();
        if (!behavior.baritone.getInventoryBehavior().selectThrowawayForLocation(true, firstPlace.getX(), firstPlace.getY(), firstPlace.getZ())) {
            return false;
        }

        jumpBridging = true;
        jumpBridgePhase = JumpBridgePhase.SPRINT;
        jumpBridgeTicksInPhase = 0;
        jumpBridgeMoveIndex = pathPosition;
        jumpBridgeDirX = dir.getX();
        jumpBridgeDirZ = dir.getZ();
        // The last solid block is the one we're standing on (src of current movement, one down)
        jumpBridgeLastSolid = current.getSrc().down();
        jumpBridgeNextClickTick = 0;

        behavior.baritone.getInputOverrideHandler().clearAllKeys();
        return true;
    }

    /**
     * Tick the jump bridge state machine.
     *
     * SPRINT: look forward (movement dir), sprint toward edge, jump when close.
     * AIRBORNE: inertia carries forward, look BACKWARD at side face of last solid block,
     *           right-click to place blocks, advance placement target after each click.
     */
    private boolean tickJumpBridge() {
        jumpBridgeTicksInPhase++;
        behavior.baritone.getInputOverrideHandler().clearAllKeys();

        // Yaw that points in the movement direction (world-space)
        float forwardYaw = (float) Math.toDegrees(Math.atan2(-jumpBridgeDirX, jumpBridgeDirZ));
        // Opposite direction for looking backward
        float backwardYaw = forwardYaw + 180.0f;

        switch (jumpBridgePhase) {
            case SPRINT: {
                // Look forward and sprint toward the edge
                behavior.baritone.getLookBehavior().updateTarget(
                        new Rotation(forwardYaw, 0.0f), true);
                behavior.baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                behavior.baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);

                // Check if we're near the edge (close to the first gap block)
                BlockPos firstDest = path.movements().get(jumpBridgeMoveIndex).getDest();
                double distToDest = Math.max(
                        Math.abs(ctx.player().getPos().x - (firstDest.getX() + 0.5)),
                        Math.abs(ctx.player().getPos().z - (firstDest.getZ() + 0.5)));

                // Jump when within 1 block of the edge
                if (distToDest < 1.2) {
                    behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                }

                // Transition to airborne once off ground (or after 10 ticks safety)
                if (!ctx.player().isOnGround() && jumpBridgeTicksInPhase > 1) {
                    jumpBridgePhase = JumpBridgePhase.AIRBORNE;
                    jumpBridgeTicksInPhase = 0;
                    jumpBridgeNextClickTick = 1; // first click on tick 1
                }

                // Safety: abort if sprinting too long without leaving ground
                if (jumpBridgeTicksInPhase > 20) {
                    jumpBridging = false;
                    jumpBridgePhase = JumpBridgePhase.NONE;
                }
                return false;
            }

            case AIRBORNE: {
                // DON'T press movement keys — inertia from sprint-jump carries us forward.
                // Pressing MOVE_FORWARD while facing backward would kill momentum.

                // Look BACKWARD at the side face of the last solid block.
                // The side face center: lastSolid offset by +dir on the relevant axis, at mid-height.
                Vec3d faceCenterPoint = new Vec3d(
                        jumpBridgeLastSolid.getX() + 0.5 + jumpBridgeDirX * 0.5,
                        jumpBridgeLastSolid.getY() + 0.5,
                        jumpBridgeLastSolid.getZ() + 0.5 + jumpBridgeDirZ * 0.5);
                Rotation backLook = RotationUtils.calcRotationFromVec3d(
                        ctx.playerHead(), faceCenterPoint, ctx.playerRotations());
                behavior.baritone.getLookBehavior().updateTarget(backLook, true);

                // Landed?
                if (ctx.player().isOnGround() && jumpBridgeTicksInPhase > 2) {
                    jumpBridging = false;
                    jumpBridgePhase = JumpBridgePhase.NONE;
                    BetterBlockPos whereAmI = ctx.playerFeet();
                    for (int i = Math.min(path.length() - 2, pathPosition + 8); i > pathPosition; i--) {
                        if (((Movement) path.movements().get(i)).getValidPositions().contains(whereAmI)) {
                            pathPosition = i;
                            onChangeInPathPosition();
                            break;
                        }
                    }
                    return false;
                }

                // Abort if airborne too long
                if (jumpBridgeTicksInPhase > 25) {
                    jumpBridging = false;
                    jumpBridgePhase = JumpBridgePhase.NONE;
                    return false;
                }

                // Place blocks with timed clicks
                if (jumpBridgeTicksInPhase >= jumpBridgeNextClickTick) {
                    behavior.baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);

                    // After placing, the new block becomes the last solid.
                    // It's at lastSolid + dir (the block we just placed against the face of).
                    jumpBridgeLastSolid = jumpBridgeLastSolid.add(jumpBridgeDirX, 0, jumpBridgeDirZ);

                    // Advance move index
                    jumpBridgeMoveIndex++;

                    // Next click: 2-4 ticks later (need time to rotate to new face)
                    jumpBridgeNextClickTick = jumpBridgeTicksInPhase + 2 + jumpBridgeRandom.nextInt(2);
                }
                return false;
            }

            default: {
                jumpBridging = false;
                jumpBridgePhase = JumpBridgePhase.NONE;
                return false;
            }
        }
    }

    private void clearKeys() {
        // i'm just sick and tired of this snippet being everywhere lol
        behavior.baritone.getInputOverrideHandler().clearAllKeys();
    }

    /**
     * Check if the player is near complex terrain that should disable all movement optimizations.
     * Liquids, ladders, vines, flowing water, scaffolding, etc.
     */
    private boolean isNearComplexTerrain() {
        BlockPos feet = ctx.playerFeet();
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        // Check a 3x3x3 area around player feet
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    net.minecraft.block.BlockState state = bsi.get0(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (state == null) continue;
                    net.minecraft.block.Block block = state.getBlock();
                    if (!state.getFluidState().isEmpty()) return true; // any liquid
                    if (block == net.minecraft.block.Blocks.LADDER) return true;
                    if (block == net.minecraft.block.Blocks.VINE) return true;
                    if (block == net.minecraft.block.Blocks.SCAFFOLDING) return true;
                    if (block == net.minecraft.block.Blocks.COBWEB) return true;
                    if (block == net.minecraft.block.Blocks.SWEET_BERRY_BUSH) return true;
                    if (block instanceof net.minecraft.block.TrapdoorBlock) return true;
                }
            }
        }
        // Also check upcoming movements for break/place
        if (pathPosition < path.movements().size()) {
            Movement m = (Movement) path.movements().get(pathPosition);
            if (!m.toBreak(bsi).isEmpty() || !m.toPlace(bsi).isEmpty()) return true;
        }
        return false;
    }

    private static final java.util.Random entropyRandom = new java.util.Random();

    /**
     * On safe flat paths, apply small random yaw deviations to look more human.
     * The deviation is tiny (±1.5°) and only on traverse/diagonal with no Y change.
     */
    private void applyEntropyDeviation() {
        if (!Baritone.settings().pathLookAhead.value) return; // only when look-ahead is on
        if (pathPosition >= path.movements().size()) return;

        IMovement current = path.movements().get(pathPosition);
        if (!(current instanceof MovementTraverse) && !(current instanceof MovementDiagonal)) return;
        if (current.getDirection().getY() != 0) return;

        // Small random yaw offset ±1.5°, applied with 30% probability per tick
        if (entropyRandom.nextFloat() < 0.3f) {
            float deviation = (entropyRandom.nextFloat() - 0.5f) * 3.0f; // ±1.5°
            Rotation current_rot = ctx.playerRotations();
            behavior.baritone.getLookBehavior().updateTarget(
                    new Rotation(current_rot.getYaw() + deviation, current_rot.getPitch()),
                    false);
        }
    }

    private void cancel() {
        clearKeys();
        sprintJumping = false;
        jumpBridging = false;
        jumpBridgePhase = JumpBridgePhase.NONE;
        tungstenBridge.reset();
        behavior.baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        pathPosition = path.length() + 3;
        failed = true;
    }

    @Override
    public int getPosition() {
        return pathPosition;
    }

    public PathExecutor trySplice(PathExecutor next) {
        if (next == null) {
            return cutIfTooLong();
        }
        return SplicedPath.trySplice(path, next.path, false).map(path -> {
            if (!path.getDest().equals(next.getPath().getDest())) {
                throw new IllegalStateException();
            }
            PathExecutor ret = new PathExecutor(behavior, path);
            ret.pathPosition = pathPosition;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            ret.costEstimateIndex = costEstimateIndex;
            ret.ticksOnCurrent = ticksOnCurrent;
            return ret;
        }).orElseGet(this::cutIfTooLong); // dont actually call cutIfTooLong every tick if we won't actually use it, use a method reference
    }

    private PathExecutor cutIfTooLong() {
        if (pathPosition > Baritone.settings().maxPathHistoryLength.value) {
            int cutoffAmt = Baritone.settings().pathHistoryCutoffAmount.value;
            CutoffPath newPath = new CutoffPath(path, cutoffAmt, path.length() - 1);
            if (!newPath.getDest().equals(path.getDest())) {
                throw new IllegalStateException();
            }
            logDebug("Discarding earliest segment movements, length cut from " + path.length() + " to " + newPath.length());
            PathExecutor ret = new PathExecutor(behavior, newPath);
            ret.pathPosition = pathPosition - cutoffAmt;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            if (costEstimateIndex != null) {
                ret.costEstimateIndex = costEstimateIndex - cutoffAmt;
            }
            ret.ticksOnCurrent = ticksOnCurrent;
            return ret;
        }
        return this;
    }

    @Override
    public IPath getPath() {
        return path;
    }

    public boolean failed() {
        return failed;
    }

    public boolean finished() {
        return pathPosition >= path.length();
    }

    public Set<BlockPos> toBreak() {
        return Collections.unmodifiableSet(toBreak);
    }

    public Set<BlockPos> toPlace() {
        return Collections.unmodifiableSet(toPlace);
    }

    public Set<BlockPos> toWalkInto() {
        return Collections.unmodifiableSet(toWalkInto);
    }

    public boolean isSprinting() {
        return sprintNextTick;
    }
}
