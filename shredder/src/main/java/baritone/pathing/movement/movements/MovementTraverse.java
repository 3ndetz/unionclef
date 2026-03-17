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

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.block.AirBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class MovementTraverse extends Movement {

    /**
     * Did we have to place a bridge block or was it always there
     */
    private boolean wasTheBridgeBlockAlwaysThere = true;

    public MovementTraverse(IBaritone baritone, BetterBlockPos from, BetterBlockPos to) {
        super(baritone, from, to, new BetterBlockPos[]{to.up(), to}, to.down());
    }

    @Override
    public void reset() {
        super.reset();
        wasTheBridgeBlockAlwaysThere = true;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z, dest.x, dest.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest); // src.above means that we don't get caught in an infinite loop in water
    }

    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        BlockState pb0 = context.get(destX, y + 1, destZ);
        BlockState pb1 = context.get(destX, y, destZ);
        BlockState destOn = context.get(destX, y - 1, destZ);
        BlockState srcDown = context.get(x, y - 1, z);
        Block srcDownBlock = srcDown.getBlock();
        boolean standingOnABlock = MovementHelper.mustBeSolidToWalkOn(context, x, y - 1, z, srcDown);
        boolean frostWalker = standingOnABlock && !context.assumeWalkOnWater && MovementHelper.canUseFrostWalker(context, destOn);
        if (frostWalker || MovementHelper.canWalkOn(context, destX, y - 1, destZ, destOn)) { //this is a walk, not a bridge
            double WC = WALK_ONE_BLOCK_COST;
            boolean water = false;
            if (MovementHelper.isWater(pb0) || MovementHelper.isWater(pb1)) {
                WC = context.waterWalkSpeed;
                water = true;
            } else {
                if (destOn.getBlock() == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                } else if (frostWalker) {
                    // with frostwalker we can walk on water without the penalty, if we are sure we won't be using jesus
                } else if (destOn.getBlock() == Blocks.WATER) {
                    WC += context.walkOnWaterOnePenalty;
                }
                if (srcDownBlock == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                }
            }
            double hardness1 = MovementHelper.getMiningDurationTicks(context, destX, y, destZ, pb1, false);
            if (hardness1 >= COST_INF) {
                return COST_INF;
            }
            double hardness2 = MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true); // only include falling on the upper block to break
            if (hardness1 == 0 && hardness2 == 0) {
                if (!water && context.canSprint) {
                    // If there's nothing in the way, and this isn't water, and we aren't sneak placing
                    // We can sprint =D
                    // Don't check for soul sand, since we can sprint on that too
                    WC *= SPRINT_MULTIPLIER;
                }
                return WC;
            }
            if (srcDownBlock == Blocks.LADDER || srcDownBlock == Blocks.VINE) {
                hardness1 *= 5;
                hardness2 *= 5;
            }
            return WC + hardness1 + hardness2;
        } else {//this is a bridge, so we need to place a block
            if (srcDownBlock == Blocks.LADDER || srcDownBlock == Blocks.VINE) {
                return COST_INF;
            }
            if (MovementHelper.isReplaceable(destX, y - 1, destZ, destOn, context.bsi)) {
                boolean throughWater = MovementHelper.isWater(pb0) || MovementHelper.isWater(pb1);
                if (MovementHelper.isWater(destOn) && throughWater) {
                    // this happens when assume walk on water is true and this is a traverse in water, which isn't allowed
                    return COST_INF;
                }
                double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, destOn);
                if (placeCost >= COST_INF) {
                    return COST_INF;
                }
                double hardness1 = MovementHelper.getMiningDurationTicks(context, destX, y, destZ, pb1, false);
                if (hardness1 >= COST_INF) {
                    return COST_INF;
                }
                double hardness2 = MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true); // only include falling on the upper block to break
                double WC = throughWater ? context.waterWalkSpeed : WALK_ONE_BLOCK_COST;
                for (int i = 0; i < 5; i++) {
                    int againstX = destX + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getOffsetX();
                    int againstY = y - 1 + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getOffsetY();
                    int againstZ = destZ + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getOffsetZ();
                    if (againstX == x && againstZ == z) { // this would be a backplace
                        continue;
                    }
                    if (MovementHelper.canPlaceAgainst(context.bsi, againstX, againstY, againstZ)) { // found a side place option
                        return WC + placeCost + hardness1 + hardness2;
                    }
                }
                // now that we've checked all possible directions to side place, we actually need to backplace
                if (srcDownBlock == Blocks.SOUL_SAND || (srcDownBlock instanceof SlabBlock && srcDown.get(SlabBlock.TYPE) != SlabType.DOUBLE)) {
                    return COST_INF; // can't sneak and backplace against soul sand or half slabs (regardless of whether it's top half or bottom half) =/
                }
                if (!standingOnABlock) { // standing on water / swimming
                    return COST_INF; // this is obviously impossible
                }
                Block blockSrc = context.getBlock(x, y, z);
                if ((blockSrc == Blocks.LILY_PAD || blockSrc instanceof CarpetBlock) && !srcDown.getFluidState().isEmpty()) {
                    return COST_INF; // we can stand on these but can't place against them
                }
                WC = WC * (SNEAK_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST);//since we are sneak backplacing, we are sneaking lol
                return WC + placeCost + hardness1 + hardness2;
            }
            return COST_INF;
        }
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        BlockState pb0 = BlockStateInterface.get(ctx, positionsToBreak[0]);
        BlockState pb1 = BlockStateInterface.get(ctx, positionsToBreak[1]);
        if (state.getStatus() != MovementStatus.RUNNING) {
            // if the setting is enabled
            if (!Baritone.settings().walkWhileBreaking.value) {
                return state;
            }
            // and if we're prepping (aka mining the block in front)
            if (state.getStatus() != MovementStatus.PREPPING) {
                return state;
            }
            // and if it's fine to walk into the blocks in front
            if (MovementHelper.avoidWalkingInto(pb0)) {
                return state;
            }
            if (MovementHelper.avoidWalkingInto(pb1)) {
                return state;
            }
            if (AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(positionsToBreak[0]) || AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(positionsToBreak[1])) {
                return state;
            }
            // and we aren't already pressed up against the block
            double dist = Math.max(Math.abs(ctx.player().getPos().x - (dest.getX() + 0.5D)), Math.abs(ctx.player().getPos().z - (dest.getZ() + 0.5D)));
            if (dist < 0.83) {
                return state;
            }
            if (!state.getTarget().getRotation().isPresent()) {
                // this can happen rarely when the server lags and doesn't send the falling sand entity until you've already walked through the block and are now mining the next one
                return state;
            }

            // combine the yaw to the center of the destination, and the pitch to the specific block we're trying to break
            // it's safe to do this since the two blocks we break (in a traverse) are right on top of each other and so will have the same yaw
            float yawToDest = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.calculateBlockCenter(ctx.world(), dest), ctx.playerRotations()).getYaw();
            float pitchToBreak = state.getTarget().getRotation().get().getPitch();
            if ((MovementHelper.isBlockNormalCube(pb0) || pb0.getBlock() instanceof AirBlock && (MovementHelper.isBlockNormalCube(pb1) || pb1.getBlock() instanceof AirBlock))) {
                // in the meantime, before we're right up against the block, we can break efficiently at this angle
                pitchToBreak = 26;
            }

            return state.setTarget(new MovementState.MovementTarget(new Rotation(yawToDest, pitchToBreak), true))
                    .setInput(Input.MOVE_FORWARD, true)
                    .setInput(Input.SPRINT, true);
        }

        //sneak may have been set to true in the PREPPING state while mining an adjacent block
        state.setInput(Input.SNEAK, false);

        Block fd = BlockStateInterface.get(ctx, src.down()).getBlock();
        boolean ladder = fd == Blocks.LADDER || fd == Blocks.VINE;

        if (pb0.getBlock() instanceof DoorBlock || pb1.getBlock() instanceof DoorBlock) {
            boolean notPassable = pb0.getBlock() instanceof DoorBlock && !MovementHelper.isDoorPassable(ctx, src, dest) || pb1.getBlock() instanceof DoorBlock && !MovementHelper.isDoorPassable(ctx, dest, src);
            boolean canOpen = !(Blocks.IRON_DOOR.equals(pb0.getBlock()) || Blocks.IRON_DOOR.equals(pb1.getBlock()));

            if (notPassable && canOpen) {
                return state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.calculateBlockCenter(ctx.world(), positionsToBreak[0]), ctx.playerRotations()), true))
                        .setInput(Input.CLICK_RIGHT, true);
            }
        }

        if (pb0.getBlock() instanceof FenceGateBlock || pb1.getBlock() instanceof FenceGateBlock) {
            BlockPos blocked = !MovementHelper.isGatePassable(ctx, positionsToBreak[0], src.up()) ? positionsToBreak[0]
                    : !MovementHelper.isGatePassable(ctx, positionsToBreak[1], src) ? positionsToBreak[1]
                    : null;
            if (blocked != null) {
                Optional<Rotation> rotation = RotationUtils.reachable(ctx, blocked);
                if (rotation.isPresent()) {
                    return state.setTarget(new MovementState.MovementTarget(rotation.get(), true)).setInput(Input.CLICK_RIGHT, true);
                }
            }
        }

        boolean isTheBridgeBlockThere = MovementHelper.canWalkOn(ctx, positionToPlace) || ladder || MovementHelper.canUseFrostWalker(ctx, positionToPlace);
        BlockPos feet = ctx.playerFeet();
        if (feet.getY() != dest.getY() && !ladder) {
            logDebug("Wrong Y coordinate");
            if (feet.getY() < dest.getY()) {
                System.out.println("In movement traverse");
                return state.setInput(Input.JUMP, true);
            }
            return state;
        }

        if (isTheBridgeBlockThere) {
            if (feet.equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (Baritone.settings().overshootTraverse.value && (feet.equals(dest.add(getDirection())) || feet.equals(dest.add(getDirection()).add(getDirection())))) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            Block low = BlockStateInterface.get(ctx, src).getBlock();
            Block high = BlockStateInterface.get(ctx, src.up()).getBlock();
            if (ctx.player().getPos().y > src.y + 0.1D && !ctx.player().isOnGround() && (low == Blocks.VINE || low == Blocks.LADDER || high == Blocks.VINE || high == Blocks.LADDER)) {
                // hitting W could cause us to climb the ladder instead of going forward
                // wait until we're on the ground
                return state;
            }
            BlockPos into = dest.subtract(src).add(dest);
            BlockState intoBelow = BlockStateInterface.get(ctx, into);
            BlockState intoAbove = BlockStateInterface.get(ctx, into.up());
            if (wasTheBridgeBlockAlwaysThere && (!MovementHelper.isLiquid(ctx, feet) || Baritone.settings().sprintInWater.value) && (!MovementHelper.avoidWalkingInto(intoBelow) || MovementHelper.isWater(intoBelow)) && !MovementHelper.avoidWalkingInto(intoAbove)) {
                state.setInput(Input.SPRINT, true);
            }

            BlockState destDown = BlockStateInterface.get(ctx, dest.down());
            BlockPos against = positionsToBreak[0];
            if (feet.getY() != dest.getY() && ladder && (destDown.getBlock() == Blocks.VINE || destDown.getBlock() == Blocks.LADDER)) {
                against = destDown.getBlock() == Blocks.VINE ? MovementPillar.getAgainst(new CalculationContext(baritone), dest.down()) : dest.offset(destDown.get(LadderBlock.FACING).getOpposite());
                if (against == null) {
                    logDirect("Unable to climb vines. Consider disabling allowVines.");
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
            }
            MovementHelper.moveTowards(ctx, state, against);
            return state;
        } else {
            wasTheBridgeBlockAlwaysThere = false;
            Block standingOn = BlockStateInterface.get(ctx, feet.down()).getBlock();
            if ((standingOn.equals(Blocks.SOUL_SAND) && !AltoClefSettings.getInstance().shouldTreatSoulSandAsOrdinaryBlock()) || standingOn instanceof SlabBlock) { // see issue #118
                double dist = Math.max(Math.abs(dest.getX() + 0.5 - ctx.player().getPos().x), Math.abs(dest.getZ() + 0.5 - ctx.player().getPos().z));
                if (dist < 0.85) { // 0.5 + 0.3 + epsilon
                    MovementHelper.moveTowards(ctx, state, dest);
                    return state.setInput(Input.MOVE_FORWARD, false)
                            .setInput(Input.MOVE_BACK, true);
                }
            }

            // ── Slow mode bridging: sneak to edge, look down at stable angle, place calmly ──
            if (Baritone.settings().slowModeStraightBridging.value) {
                return updateSlowBridge(state, feet);
            }

            double dist1 = Math.max(Math.abs(ctx.player().getPos().x - (dest.getX() + 0.5D)), Math.abs(ctx.player().getPos().z - (dest.getZ() + 0.5D)));
            PlaceResult p = MovementHelper.attemptToPlaceABlock(state, baritone, dest.down(), false, true);
            if ((p == PlaceResult.READY_TO_PLACE || dist1 < 0.6) && !Baritone.settings().assumeSafeWalk.value) {
                state.setInput(Input.SNEAK, true);
            }
            switch (p) {
                case READY_TO_PLACE: {
                    if (ctx.player().isInSneakingPose() || Baritone.settings().assumeSafeWalk.value) {
                        state.setInput(Input.CLICK_RIGHT, true);
                    }
                    return state;
                }
                case ATTEMPTING: {
                    if (dist1 > 0.83) {
                        // might need to go forward a bit
                        float yaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()).getYaw();
                        if (Math.abs(state.getTarget().rotation.getYaw() - yaw) < 0.1) {
                            // but only if our attempted place is straight ahead
                            return state.setInput(Input.MOVE_FORWARD, true);
                        }
                    } else if (ctx.playerRotations().isReallyCloseTo(state.getTarget().rotation)) {
                        // well i guess theres something in the way
                        return state.setInput(Input.CLICK_LEFT, true);
                    }
                    return state;
                }
                default:
                    break;
            }
            if (feet.equals(dest)) {
                // If we are in the block that we are trying to get to, we are sneaking over air and we need to place a block beneath us against the one we just walked off of
                double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
                double faceY = (dest.getY() + src.getY() - 1.0D) * 0.5D;
                double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;
                BlockPos goalLook = src.down();

                Rotation backToFace = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3d(faceX, faceY, faceZ), ctx.playerRotations());
                float pitch = backToFace.getPitch();
                double dist2 = Math.max(Math.abs(ctx.player().getPos().x - faceX), Math.abs(ctx.player().getPos().z - faceZ));
                if (dist2 < 0.29) {
                    float yaw = RotationUtils.calcRotationFromVec3d(VecUtils.getBlockPosCenter(dest), ctx.playerHead(), ctx.playerRotations()).getYaw();
                    state.setTarget(new MovementState.MovementTarget(new Rotation(yaw, pitch), true));
                    state.setInput(Input.MOVE_BACK, true);
                } else {
                    state.setTarget(new MovementState.MovementTarget(backToFace, true));
                }
                if (ctx.isLookingAt(goalLook)) {
                    return state.setInput(Input.CLICK_RIGHT, true);
                }
                if (ctx.playerRotations().isReallyCloseTo(state.getTarget().rotation)) {
                    state.setInput(Input.CLICK_LEFT, true);
                }
                return state;
            }
            MovementHelper.moveTowards(ctx, state, positionsToBreak[0]);
            return state;
        }
    }

    /**
     * Slow-mode bridging: sneak to edge, place from edge looking backward, then walk forward.
     * Never walks forward until the bridge block is confirmed placed.
     */
    private MovementState updateSlowBridge(MovementState state, BlockPos feet) {
        // Always sneak during slow bridge — non-negotiable
        state.setInput(Input.SNEAK, true);

        // If the bridge block appeared (placed by us or someone else), walk forward
        boolean bridgePlaced = MovementHelper.canWalkOn(ctx, dest.down());
        if (bridgePlaced) {
            MovementHelper.moveTowards(ctx, state, dest);
            return state;
        }

        // Try normal placement (side-place against adjacent blocks)
        PlaceResult p = MovementHelper.attemptToPlaceABlock(state, baritone, dest.down(), false, true);
        if (p == PlaceResult.READY_TO_PLACE) {
            // Rotation is good — click to place, do NOT walk forward yet
            if (ctx.player().isInSneakingPose()) {
                state.setInput(Input.CLICK_RIGHT, true);
            }
            return state;
        }
        if (p == PlaceResult.ATTEMPTING) {
            // Still rotating to face the placement target — sneak toward edge if far
            double distToEdge = Math.max(
                    Math.abs(ctx.player().getPos().x - (dest.getX() + 0.5D)),
                    Math.abs(ctx.player().getPos().z - (dest.getZ() + 0.5D)));
            if (distToEdge > 0.83) {
                state.setInput(Input.MOVE_FORWARD, true);
            }
            return state;
        }

        // NO_OPTION: no side place found — need to backplace from edge
        if (feet.equals(dest)) {
            // Over the edge already — look back at src.down() and place against it
            double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
            double faceY = (dest.getY() + src.getY() - 1.0D) * 0.5D;
            double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;
            BlockPos goalLook = src.down();

            Rotation backToFace = RotationUtils.calcRotationFromVec3d(
                    ctx.playerHead(), new Vec3d(faceX, faceY, faceZ), ctx.playerRotations());
            state.setTarget(new MovementState.MovementTarget(backToFace, true));

            if (ctx.isLookingAt(goalLook)) {
                state.setInput(Input.CLICK_RIGHT, true);
            }
            return state;
        }

        // Not at edge yet — sneak forward toward edge, look at the placement face
        // Target: face between src and dest at block-below level
        double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
        double faceY = (dest.getY() + src.getY() - 1.0D) * 0.5D;
        double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;
        Rotation stableLook = RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(), new Vec3d(faceX, faceY, faceZ), ctx.playerRotations());
        state.setTarget(new MovementState.MovementTarget(stableLook, true));
        state.setInput(Input.MOVE_FORWARD, true);
        return state;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // if we're in the process of breaking blocks before walking forwards
        // or if this isn't a sneak place (the block is already there)
        // then it's safe to cancel this
        return state.getStatus() != MovementStatus.RUNNING || MovementHelper.canWalkOn(ctx, dest.down());
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (ctx.playerFeet().equals(src) || ctx.playerFeet().equals(src.down())) {
            Block block = BlockStateInterface.getBlock(ctx, src.down());
            if (block == Blocks.LADDER || block == Blocks.VINE) {
                state.setInput(Input.SNEAK, true);
            }
        }
        return super.prepared(state);
    }
}
