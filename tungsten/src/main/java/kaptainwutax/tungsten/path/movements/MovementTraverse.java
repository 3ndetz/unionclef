package kaptainwutax.tungsten.path.movements;

import static kaptainwutax.tungsten.path.calculators.ActionCosts.SNEAK_ONE_BLOCK_COST;
import static kaptainwutax.tungsten.path.calculators.ActionCosts.SPRINT_MULTIPLIER;
import static kaptainwutax.tungsten.path.calculators.ActionCosts.WALK_ONE_BLOCK_COST;
import static kaptainwutax.tungsten.path.calculators.ActionCosts.WALK_ONE_IN_WATER_COST;
import static kaptainwutax.tungsten.path.calculators.ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST;

import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.Set;
import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.path.PlaceRules;
import kaptainwutax.tungsten.world.BetterBlockPos;
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
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * ONE one-block cardinal step, walked or bridged — a verbatim port of
 * {@code baritone/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java}
 * (the whole file: {@code cost} at :77-169, {@code updateState} at :171-362,
 * {@code safeToCancel} at :364-370, {@code prepared} at :372-381).
 *
 * <p>Why the whole thing had to come across in one piece: tungsten's own bridge was a walker
 * that moved the body and a separate place engine that owned the aim, and the seam between them
 * measured {@code called=11041 inRange=11040 clicked=0} (BARITONE-PORT-SPEC.md, pitfall P1).
 * Here a single object decides walk-vs-break-vs-side-place-vs-sneak-backplace itself, every
 * tick, from world state, and prices the same decision at plan time in {@link #cost}. The three
 * parts of the backplace — SNEAK, the swapped-argument yaw, and MOVE_BACK — are one manoeuvre;
 * porting the click without them got 11.6 blocks and stopped (pitfall P5).
 *
 * <p><b>Geometry</b> (upstream :58): {@code positionsToBreak[0] = dest.above()} (head cell),
 * {@code positionsToBreak[1] = dest} (feet cell), {@code positionToPlace = dest.below()}. The
 * one piece of mutable state, {@code wasTheBridgeBlockAlwaysThere}, is the only thing gating
 * SPRINT: a step whose floor this route placed is never sprinted out of (pitfall P3).
 *
 * <h2>Adapters used in place of baritone services (spec unit 1's substitution table)</h2>
 * <ul>
 *   <li>{@code IPlayerContext} — gone. The base {@link Movement} holds the tick's
 *       {@code ClientPlayerEntity} in {@code player}; {@code ctx.world()} is
 *       {@code player.getEntityWorld()}, {@code ctx.playerFeet()/playerHead()} are
 *       {@link RotationHelper}, and {@code ctx.playerRotations()} /
 *       {@code ctx.playerController().getBlockReachDistance()} / {@code ctx.isLookingAt(pos)}
 *       are the three private statics at the bottom of this file. {@code isLookingAt} goes
 *       through {@code RotationHelper.liveHit}: a raytrace recomputed THIS tick, never
 *       {@code mc.crosshairTarget}, which at the stand's measured 10 fps is one to two ticks
 *       stale — exactly the width of the sneak-pose window.</li>
 *   <li>{@code BlockStateInterface.get(ctx, pos)} — {@code world.getBlockState(pos)}.</li>
 *   <li>{@code CalculationContext} — gone; {@link #cost} takes {@code (world, player, …)} and
 *       {@code context.get(x,y,z)} is a {@link BlockPos.Mutable} scratch lookup. Its two
 *       derived values are the private {@link #canSprint} and {@link #costOfPlacingAt} below;
 *       {@code assumeWalkOnWater} / {@code waterWalkSpeed} / {@code walkOnWaterOnePenalty} are
 *       the named constants below.</li>
 *   <li>Baritone settings — no equivalent, so upstream's DEFAULTS are hardcoded as the
 *       constants below, each with its {@code Settings.java} line. Promote them to
 *       {@link TungstenConfig} if they ever need to be live-tunable; do not re-tune them here.</li>
 *   <li>{@code InventoryBehavior.selectThrowawayForLocation} — tungsten's equip hook takes no
 *       location, so "have we got a block" is main-hand-only ({@link #hasThrowaway}). Recorded
 *       divergence, not faked.</li>
 * </ul>
 *
 * <h2>Sibling API this file expects (unit 1 substrate, same package)</h2>
 * <ul>
 *   <li>{@code Movement} — the per-movement base: fields {@code src}, {@code dest},
 *       {@code positionsToBreak}, {@code positionToPlace}, {@code player}; methods
 *       {@code reset()}, {@code updateState(MovementState)} (the PREPPING→WAITING→RUNNING
 *       ladder, Movement.java:225-237), {@code prepared(MovementState)} (Movement.java:153-193)
 *       and {@code getDirection()} = {@code dest.subtract(src)} (Movement.java:240).</li>
 *   <li>{@code MovementState} with nested {@code MovementTarget} (public field
 *       {@code rotation}, {@code getRotation()} → {@code Optional<Rotation>}), and the
 *       top-level {@code MovementStatus} / {@code Input} enums.</li>
 *   <li>{@code MovementHelperB} — the ported {@code MovementHelper} statics, with
 *       {@code CalculationContext}/{@code BlockStateInterface} replaced by
 *       {@code WorldView} in the same argument position (plus {@code PlayerEntity} where the
 *       upstream body reads the tool set or an enchantment), and the nested
 *       {@code PlaceResult} enum.</li>
 *   <li>{@code RotationHelper} — {@code calcRotationFromVec3d}, {@code calculateBlockCenter},
 *       {@code getBlockPosCenter}, {@code reachable}, {@code playerFeet}, {@code playerHead},
 *       {@code liveHit}; and the {@code Rotation} value type
 *       ({@code getYaw/getPitch/isReallyCloseTo}).</li>
 * </ul>
 *
 * <h2>Lines that could not come across</h2>
 * <ul>
 *   <li>{@code AltoClefSettings.shouldAvoidWalkThroughForce} (upstream :192) — no equivalent;
 *       dropped, as the spec instructs.</li>
 *   <li>{@code AltoClefSettings.shouldTreatSoulSandAsOrdinaryBlock} (upstream :293) — no
 *       equivalent. Its default is false, so dropping the guard restores plain upstream
 *       baritone: soul sand is never ordinary here.</li>
 *   <li>{@code MovementPillar.getAgainst} (upstream :282) — unit 3's class does not exist yet,
 *       so the four-line helper is duplicated below verbatim; delete it and delegate once
 *       {@code MovementPillar} lands.</li>
 *   <li>The stray {@code System.out.println("In movement traverse")} (upstream :252) — a
 *       leftover print in a per-tick branch, and absent from the spec's own quote of this
 *       method. {@code logDebug} above it is kept.</li>
 * </ul>
 */
public class MovementTraverse extends Movement {

    // ---------------------------------------------------------------------------------------
    // Settings, hardcoded at baritone's verified defaults (no tungsten equivalent exists).
    // ---------------------------------------------------------------------------------------
    /** {@code Baritone.settings().walkWhileBreaking} (Settings.java:884) — default true. */
    private static final boolean WALK_WHILE_BREAKING = true;
    /** {@code Baritone.settings().overshootTraverse} (Settings.java:365) — default true. */
    private static final boolean OVERSHOOT_TRAVERSE = true;
    /** {@code Baritone.settings().assumeSafeWalk} (Settings.java:167) — default false. */
    private static final boolean ASSUME_SAFE_WALK = false;
    /** {@code Baritone.settings().sprintInWater} (Settings.java:801) — default TRUE. The unit
     *  study claimed false; the source is authoritative. */
    private static final boolean SPRINT_IN_WATER = true;
    /** {@code context.assumeWalkOnWater} (Settings.java:88) — default false. */
    private static final boolean ASSUME_WALK_ON_WATER = false;
    /** {@code context.walkOnWaterOnePenalty} (Settings.java:127) — default 3.0. */
    private static final double WALK_ON_WATER_ONE_PENALTY = 3.0D;
    /** {@code context.placeBlockCost} / {@code blockPlacementPenalty} (Settings.java:110) — 20. */
    private static final double BLOCK_PLACEMENT_PENALTY = 20.0D;

    /**
     * "Impossible", POSITIVE — every ported line reads {@code if (x >= COST_INF) return
     * COST_INF;}. The movements package declares its own on purpose: it must never pick up
     * {@code path.calculators.ActionCosts.COST_INF}, which was negative and silently inverted
     * every one of those comparisons.
     */
    private static final double COST_INF = 1000000;

    // The scan order (Movement.java:36) is INHERITED from Movement, exactly as upstream reads it
    // off its own superclass. A private copy lived here until the package was reconciled; upstream
    // has precisely one definition and it must stay that way, because DOWN-last is what makes
    // `preferDown` work and NORTH-first is what makes a side place deterministic — two arrays that
    // can drift out of that order is the bug, not the duplication.

    /**
     * Did we have to place a bridge block or was it always there
     */
    private boolean wasTheBridgeBlockAlwaysThere = true;

    public MovementTraverse(BetterBlockPos from, BetterBlockPos to) {
        // BetterBlockPos.above()/below() are tungsten's names for baritone's up()/down().
        super(from, to, new BetterBlockPos[]{to.above(), to}, to.below());
    }

    @Override
    public void reset() {
        super.reset();
        wasTheBridgeBlockAlwaysThere = true;
    }

    // ClientPlayerEntity, not PlayerEntity, so this genuinely overrides Movement.calculateCost —
    // with the wider parameter it was a silent OVERLOAD and the base's "no instance cost" throw
    // stayed live. The static cost(...) below keeps PlayerEntity, as upstream's CalculationContext
    // is not client-specific.
    @Override
    public double calculateCost(WorldView world, ClientPlayerEntity player) {
        return cost(world, player, src.x, src.y, src.z, dest.x, dest.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest); // src.above means that we don't get caught in an infinite loop in water
    }

    public static double cost(WorldView world, PlayerEntity player, int x, int y, int z, int destX, int destZ) {
        BlockPos.Mutable scratch = new BlockPos.Mutable();
        BlockState pb0 = world.getBlockState(scratch.set(destX, y + 1, destZ));
        BlockState pb1 = world.getBlockState(scratch.set(destX, y, destZ));
        BlockState destOn = world.getBlockState(scratch.set(destX, y - 1, destZ));
        BlockState srcDown = world.getBlockState(scratch.set(x, y - 1, z));
        Block srcDownBlock = srcDown.getBlock();
        boolean standingOnABlock = MovementHelperB.mustBeSolidToWalkOn(world, x, y - 1, z, srcDown);
        boolean frostWalker = standingOnABlock && !ASSUME_WALK_ON_WATER && MovementHelperB.canUseFrostWalker(player, destOn);
        if (frostWalker || MovementHelperB.canWalkOn(world, destX, y - 1, destZ, destOn)) { //this is a walk, not a bridge
            double WC = WALK_ONE_BLOCK_COST;
            boolean water = false;
            if (MovementHelperB.isWater(pb0) || MovementHelperB.isWater(pb1)) {
                WC = WALK_ONE_IN_WATER_COST; // context.waterWalkSpeed — no swim-speed model yet
                water = true;
            } else {
                if (destOn.getBlock() == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                } else if (frostWalker) {
                    // with frostwalker we can walk on water without the penalty, if we are sure we won't be using jesus
                } else if (destOn.getBlock() == Blocks.WATER) {
                    WC += WALK_ON_WATER_ONE_PENALTY;
                }
                if (srcDownBlock == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                }
            }
            double hardness1 = MovementHelperB.getMiningDurationTicks(world, player, destX, y, destZ, pb1, false);
            if (hardness1 >= COST_INF) {
                return COST_INF;
            }
            double hardness2 = MovementHelperB.getMiningDurationTicks(world, player, destX, y + 1, destZ, pb0, true); // only include falling on the upper block to break
            if (hardness1 == 0 && hardness2 == 0) {
                if (!water && canSprint(player)) {
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
            if (MovementHelperB.isReplaceable(destX, y - 1, destZ, destOn, world)) {
                boolean throughWater = MovementHelperB.isWater(pb0) || MovementHelperB.isWater(pb1);
                if (MovementHelperB.isWater(destOn) && throughWater) {
                    // this happens when assume walk on water is true and this is a traverse in water, which isn't allowed
                    return COST_INF;
                }
                double placeCost = costOfPlacingAt(world, player, destX, y - 1, destZ, destOn);
                if (placeCost >= COST_INF) {
                    return COST_INF;
                }
                double hardness1 = MovementHelperB.getMiningDurationTicks(world, player, destX, y, destZ, pb1, false);
                if (hardness1 >= COST_INF) {
                    return COST_INF;
                }
                double hardness2 = MovementHelperB.getMiningDurationTicks(world, player, destX, y + 1, destZ, pb0, true); // only include falling on the upper block to break
                double WC = throughWater ? WALK_ONE_IN_WATER_COST : WALK_ONE_BLOCK_COST;
                for (int i = 0; i < 5; i++) {
                    int againstX = destX + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getOffsetX();
                    int againstY = y - 1 + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getOffsetY();
                    int againstZ = destZ + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getOffsetZ();
                    if (againstX == x && againstZ == z) { // this would be a backplace
                        continue;
                    }
                    if (MovementHelperB.canPlaceAgainst(world, againstX, againstY, againstZ)) { // found a side place option
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
                Block blockSrc = world.getBlockState(scratch.set(x, y, z)).getBlock();
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
        // `ctx` is the base's IPlayerContext stand-in (Movement.PlayerCtx), so ctx.player() IS
        // upstream's ctx.player(). Bound to a local named `player` so every copied line below reads
        // as upstream wrote it; ctx.world() is the same world, reached through the player here
        // because the ported MovementHelper statics take a WorldView in that argument position.
        PlayerEntity player = ctx.player();
        WorldView world = player.getEntityWorld();
        BlockState pb0 = world.getBlockState(positionsToBreak[0]);
        BlockState pb1 = world.getBlockState(positionsToBreak[1]);
        if (state.getStatus() != MovementStatus.RUNNING) {
            // if the setting is enabled
            if (!WALK_WHILE_BREAKING) {
                return state;
            }
            // and if we're prepping (aka mining the block in front)
            if (state.getStatus() != MovementStatus.PREPPING) {
                return state;
            }
            // and if it's fine to walk into the blocks in front
            if (MovementHelperB.avoidWalkingInto(pb0)) {
                return state;
            }
            if (MovementHelperB.avoidWalkingInto(pb1)) {
                return state;
            }
            // upstream :192 also bails when altoclef has force-marked either cell as
            // "do not walk through". No tungsten equivalent — dropped, per the spec.
            // and we aren't already pressed up against the block
            double dist = Math.max(Math.abs(player.getEntityPos().x - (dest.getX() + 0.5D)), Math.abs(player.getEntityPos().z - (dest.getZ() + 0.5D)));
            if (dist < 0.83) {
                return state;
            }
            if (!state.getTarget().getRotation().isPresent()) {
                // this can happen rarely when the server lags and doesn't send the falling sand entity until you've already walked through the block and are now mining the next one
                return state;
            }

            // combine the yaw to the center of the destination, and the pitch to the specific block we're trying to break
            // it's safe to do this since the two blocks we break (in a traverse) are right on top of each other and so will have the same yaw
            float yawToDest = RotationHelper.calcRotationFromVec3d(RotationHelper.playerHead(player), RotationHelper.calculateBlockCenter(world, dest), playerRotations(player)).getYaw();
            float pitchToBreak = state.getTarget().getRotation().get().getPitch();
            if ((MovementHelperB.isBlockNormalCube(pb0) || pb0.getBlock() instanceof AirBlock && (MovementHelperB.isBlockNormalCube(pb1) || pb1.getBlock() instanceof AirBlock))) {
                // in the meantime, before we're right up against the block, we can break efficiently at this angle
                pitchToBreak = 26;
            }

            return state.setTarget(new MovementState.MovementTarget(new Rotation(yawToDest, pitchToBreak), true))
                    .setInput(Input.MOVE_FORWARD, true)
                    .setInput(Input.SPRINT, true);
        }

        //sneak may have been set to true in the PREPPING state while mining an adjacent block
        state.setInput(Input.SNEAK, false);

        Block fd = world.getBlockState(src.below()).getBlock();
        boolean ladder = fd == Blocks.LADDER || fd == Blocks.VINE;

        if (pb0.getBlock() instanceof DoorBlock || pb1.getBlock() instanceof DoorBlock) {
            // NB: upstream really does pass (src, dest) for the head cell and (dest, src) for
            // the feet cell — the second argument is the PLAYER position, and the pairing is
            // load-bearing. Copied as written (upstream :226).
            boolean notPassable = pb0.getBlock() instanceof DoorBlock && !MovementHelperB.isDoorPassable(world, src, dest) || pb1.getBlock() instanceof DoorBlock && !MovementHelperB.isDoorPassable(world, dest, src);
            boolean canOpen = !(Blocks.IRON_DOOR.equals(pb0.getBlock()) || Blocks.IRON_DOOR.equals(pb1.getBlock()));

            if (notPassable && canOpen) {
                return state.setTarget(new MovementState.MovementTarget(RotationHelper.calcRotationFromVec3d(RotationHelper.playerHead(player), RotationHelper.calculateBlockCenter(world, positionsToBreak[0]), playerRotations(player)), true))
                        .setInput(Input.CLICK_RIGHT, true);
            }
        }

        if (pb0.getBlock() instanceof FenceGateBlock || pb1.getBlock() instanceof FenceGateBlock) {
            BlockPos blocked = !MovementHelperB.isGatePassable(world, positionsToBreak[0], src.above()) ? positionsToBreak[0]
                    : !MovementHelperB.isGatePassable(world, positionsToBreak[1], src) ? positionsToBreak[1]
                    : null;
            if (blocked != null) {
                Optional<Rotation> rotation = RotationHelper.reachable(player, blocked);
                if (rotation.isPresent()) {
                    return state.setTarget(new MovementState.MovementTarget(rotation.get(), true)).setInput(Input.CLICK_RIGHT, true);
                }
            }
        }

        boolean isTheBridgeBlockThere = MovementHelperB.canWalkOn(world, positionToPlace) || ladder || MovementHelperB.canUseFrostWalker(player, positionToPlace);
        BlockPos feet = RotationHelper.playerFeet(player);
        if (feet.getY() != dest.getY() && !ladder) {
            // upstream logDebug — chat only when asked for, like baritone's chatDebug gate,
            // because this branch fires every tick the bot is off by a block.
            if (TungstenConfig.get().verboseDebugLogging) {
                Debug.logMessage("Wrong Y coordinate");
            }
            if (feet.getY() < dest.getY()) {
                return state.setInput(Input.JUMP, true);
            }
            return state;
        }

        if (isTheBridgeBlockThere) {
            if (feet.equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (OVERSHOOT_TRAVERSE && (feet.equals(dest.add(getDirection())) || feet.equals(dest.add(getDirection()).add(getDirection())))) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            Block low = world.getBlockState(src).getBlock();
            Block high = world.getBlockState(src.above()).getBlock();
            if (player.getEntityPos().y > src.y + 0.1D && !player.isOnGround() && (low == Blocks.VINE || low == Blocks.LADDER || high == Blocks.VINE || high == Blocks.LADDER)) {
                // hitting W could cause us to climb the ladder instead of going forward
                // wait until we're on the ground
                return state;
            }
            BlockPos into = dest.subtract(src).add(dest);
            BlockState intoBelow = world.getBlockState(into);
            BlockState intoAbove = world.getBlockState(into.up());
            // wasTheBridgeBlockAlwaysThere is the whole sprint guard: a step whose floor this
            // route just placed is NEVER sprinted out of. At 8-12 fps one sprinting tick
            // carries the bot past the lip — measured 20.7 and 22.5 blocks short, twice each.
            if (wasTheBridgeBlockAlwaysThere && (!MovementHelperB.isLiquid(world, feet) || SPRINT_IN_WATER) && (!MovementHelperB.avoidWalkingInto(intoBelow) || MovementHelperB.isWater(intoBelow)) && !MovementHelperB.avoidWalkingInto(intoAbove)) {
                state.setInput(Input.SPRINT, true);
            }

            BlockState destDown = world.getBlockState(dest.below());
            BlockPos against = positionsToBreak[0];
            if (feet.getY() != dest.getY() && ladder && (destDown.getBlock() == Blocks.VINE || destDown.getBlock() == Blocks.LADDER)) {
                against = destDown.getBlock() == Blocks.VINE ? getAgainst(world, dest.below()) : dest.offset(destDown.get(LadderBlock.FACING).getOpposite());
                if (against == null) {
                    // upstream logDirect — always shown; the movement is about to give up
                    Debug.logMessage("Unable to climb vines. Consider disabling allowVines.");
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
            }
            MovementHelperB.moveTowards(player, state, against);
            return state;
        } else {
            wasTheBridgeBlockAlwaysThere = false;
            Block standingOn = world.getBlockState(feet.down()).getBlock();
            // upstream also lets altoclef declare soul sand ordinary; its default is false, so
            // this is plain upstream baritone (:293, "see issue #118").
            if (standingOn.equals(Blocks.SOUL_SAND) || standingOn instanceof SlabBlock) { // see issue #118
                double dist = Math.max(Math.abs(dest.getX() + 0.5 - player.getEntityPos().x), Math.abs(dest.getZ() + 0.5 - player.getEntityPos().z));
                if (dist < 0.85) { // 0.5 + 0.3 + epsilon
                    MovementHelperB.moveTowards(player, state, dest);
                    return state.setInput(Input.MOVE_FORWARD, false)
                            .setInput(Input.MOVE_BACK, true);
                }
            }
            double dist1 = Math.max(Math.abs(player.getEntityPos().x - (dest.getX() + 0.5D)), Math.abs(player.getEntityPos().z - (dest.getZ() + 0.5D)));
            MovementHelperB.PlaceResult p = MovementHelperB.attemptToPlaceABlock(state, player, dest.below(), false, true);
            if ((p == MovementHelperB.PlaceResult.READY_TO_PLACE || dist1 < 0.6) && !ASSUME_SAFE_WALK) {
                state.setInput(Input.SNEAK, true);
            }
            switch (p) {
                case READY_TO_PLACE: {
                    // the sneak KEY is not the sneak POSE: the pose lags the key by a tick, and
                    // a click in that tick raytraces from the standing eye (1.62) instead of the
                    // sneaking one (1.27) and misses the face.
                    if (player.isInSneakingPose() || ASSUME_SAFE_WALK) {
                        state.setInput(Input.CLICK_RIGHT, true);
                    }
                    return state;
                }
                case ATTEMPTING: {
                    if (dist1 > 0.83) {
                        // might need to go forward a bit
                        float yaw = RotationHelper.calcRotationFromVec3d(RotationHelper.playerHead(player), RotationHelper.getBlockPosCenter(dest), playerRotations(player)).getYaw();
                        if (Math.abs(state.getTarget().rotation.getYaw() - yaw) < 0.1) {
                            // but only if our attempted place is straight ahead
                            return state.setInput(Input.MOVE_FORWARD, true);
                        }
                    } else if (playerRotations(player).isReallyCloseTo(state.getTarget().rotation)) {
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
                // Out.log(from + " " + to + " " + faceX + "," + faceY + "," + faceZ + " " + whereAmI);
                double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
                double faceY = (dest.getY() + src.getY() - 1.0D) * 0.5D;
                double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;
                // faceX, faceY, faceZ is the middle of the face between from and to
                // NB: the -1.0D on faceY is NOT the +0.5D of attemptToPlaceABlock's side-face
                // formula. This is the vertical face between src.below() and dest.below();
                // different geometry, do not unify them.
                BlockPos goalLook = src.below(); // this is the block we were just standing on, and the one we want to place against

                Rotation backToFace = RotationHelper.calcRotationFromVec3d(RotationHelper.playerHead(player), new Vec3d(faceX, faceY, faceZ), playerRotations(player));
                float pitch = backToFace.getPitch();
                double dist2 = Math.max(Math.abs(player.getEntityPos().x - faceX), Math.abs(player.getEntityPos().z - faceZ));
                if (dist2 < 0.29) { // see issue #208
                    // SWAPPED ARGUMENTS, deliberately: the yaw FROM the cell being paved
                    // TOWARDS the head, i.e. facing back up the bridge. With the body reversed,
                    // MOVE_BACK moves the bot forward along the bridge — which is why baritone
                    // bridges walking backwards, and why the click alone gets 11.6 blocks.
                    float yaw = RotationHelper.calcRotationFromVec3d(RotationHelper.getBlockPosCenter(dest), RotationHelper.playerHead(player), playerRotations(player)).getYaw();
                    state.setTarget(new MovementState.MovementTarget(new Rotation(yaw, pitch), true));
                    state.setInput(Input.MOVE_BACK, true);
                } else {
                    state.setTarget(new MovementState.MovementTarget(backToFace, true));
                }
                if (isLookingAt(player, goalLook)) {
                    return state.setInput(Input.CLICK_RIGHT, true); // wait to right click until we are able to place
                }
                // Out.log("Trying to look at " + goalLook + ", actually looking at" + Baritone.whatAreYouLookingAt());
                if (playerRotations(player).isReallyCloseTo(state.getTarget().rotation)) {
                    state.setInput(Input.CLICK_LEFT, true);
                }
                return state;
            }
            MovementHelperB.moveTowards(player, state, positionsToBreak[0]);
            return state;
            // TODO MovementManager.moveTowardsBlock(to); // move towards not look at because if we are bridging for a couple blocks in a row, it is faster if we dont spin around and walk forwards then spin around and place backwards for every block
        }
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // if we're in the process of breaking blocks before walking forwards
        // or if this isn't a sneak place (the block is already there)
        // then it's safe to cancel this
        return state.getStatus() != MovementStatus.RUNNING || MovementHelperB.canWalkOn(ctx.world(), dest.below());
    }

    @Override
    protected boolean prepared(MovementState state) {
        PlayerEntity player = ctx.player();
        BetterBlockPos feet = RotationHelper.playerFeet(player);
        if (feet.equals(src) || feet.equals(src.below())) {
            Block block = player.getEntityWorld().getBlockState(src.below()).getBlock();
            if (block == Blocks.LADDER || block == Blocks.VINE) {
                state.setInput(Input.SNEAK, true);
            }
        }
        return super.prepared(state);
    }

    // -------------------------------------------------------------------------------------------
    // Thin adapters for baritone services tungsten lacks. Nothing here invents behaviour: each
    // one is the substitution named in BARITONE-PORT-SPEC.md unit 1's table, and nothing else.
    // -------------------------------------------------------------------------------------------

    /** {@code ctx.playerRotations()} (IPlayerContext.java) — the CURRENT rotations, not a target. */
    private static Rotation playerRotations(PlayerEntity player) {
        return new Rotation(player.getYaw(), player.getPitch());
    }

    /** {@code ctx.playerController().getBlockReachDistance()} — baritone pins it at 4.5
     *  (Settings.java:385), so take whichever of the two is smaller. */
    private static double blockReachDistance(PlayerEntity player) {
        return Math.min(player.getBlockInteractionRange(), 4.5D);
    }

    /**
     * {@code ctx.isLookingAt(pos)} (IPlayerContext.java:117-119) — the ONE promotion to "click
     * now". It goes through {@code RotationHelper.liveHit}, a raytrace recomputed from this
     * tick's eye and this tick's rotations (BaritonePlayerContext.java:84-86), because
     * {@code mc.crosshairTarget} is a per-frame value and at the stand's 10 fps it is one to
     * two ticks stale — the exact width of the sneak-pose window this gate exists to wait for.
     */
    private static boolean isLookingAt(PlayerEntity player, BlockPos pos) {
        HitResult result = RotationHelper.liveHit(player, playerRotations(player), blockReachDistance(player));
        return result != null && result.getType() == HitResult.Type.BLOCK
                && ((BlockHitResult) result).getBlockPos().equals(pos);
    }

    /** {@code context.canSprint} (CalculationContext.java:108) — hunger above the sprint floor. */
    private static boolean canSprint(PlayerEntity player) {
        return player.getHungerManager().getFoodLevel() > 6;
    }

    /**
     * {@code context.hasThrowaway} (CalculationContext.java:187). Baritone searches the whole
     * inventory for a throwaway block; tungsten's equip hook takes no location and no filter,
     * so this can only ask whether there is a block in hand. KNOWN divergence (spec unit 1's
     * table) — not faked, and not a reason to skip the refusal it feeds.
     */
    private static boolean hasThrowaway(PlayerEntity player) {
        return TungstenConfig.get().allowPlace && player.getMainHandStack().getItem() instanceof BlockItem;
    }

    /**
     * {@code CalculationContext.costOfPlacingAt} (CalculationContext.java:186-200) — one place,
     * one price, and the refusals that make a bridge unplannable rather than unexecutable.
     * Upstream's other three refusals (its own area protection, the world border, and
     * altoclef's place-avoiders) are all inside {@link PlaceRules#canPlace} here, which also
     * adds a {@code isReplaceable()} test upstream leaves to the caller — harmless, because
     * every caller in this class has already made that test.
     *
     * <p>Kept private rather than shared: {@code MovementPillar} (unit 3) needs the same
     * adapter, and when it lands this belongs in one place.
     */
    private static double costOfPlacingAt(WorldView world, PlayerEntity player, int x, int y, int z, BlockState current) {
        if (!hasThrowaway(player)) { // only true if allowPlace is true, see hasThrowaway
            return COST_INF;
        }
        if (!PlaceRules.canPlace(world, new BlockPos(x, y, z))) {
            return COST_INF;
        }
        return BLOCK_PLACEMENT_PENALTY * TungstenConfig.get().placeCostMultiplier;
    }

    /**
     * {@code MovementPillar.getAgainst} (MovementPillar.java:161-168). Upstream calls the static on
     * MovementPillar from here (MovementTraverse.java:282); the private copy that lived in this file
     * while unit 3 was unwritten has been deleted and this delegates, as its own note said to.
     */
    private static BlockPos getAgainst(WorldView world, BetterBlockPos vine) {
        return MovementPillar.getAgainst(world, vine);
    }

}
