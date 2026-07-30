package kaptainwutax.tungsten.path.movements;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.util.WindMouseRotation;
import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Port of {@code baritone/src/main/java/baritone/pathing/movement/Movement.java} (1-296), the
 * abstract base of one one-block step: it prices itself once, breaks what is in the way before it
 * runs ({@link #prepared}), and is the SINGLE per-tick writer of keys and of the camera target
 * ({@link #update}). Verified line-by-line against {@code shredder/.../pathing/movement/Movement.java},
 * which is the same file already compiling on this MC version — that is where {@code getEntityPos()}
 * (vs upstream's {@code getPos()}) comes from.
 *
 * <p>Why the "release everything, then press exactly what this tick declared, then clear the map"
 * order is load-bearing (Movement.java:139-143): a key not set this tick is released this tick, so
 * nothing latches. That is what makes "one owner of the inputs" true rather than aspirational, and
 * it is the fix for measured pitfall P1 (BARITONE-PORT-SPEC.md: {@code called=11041 inRange=11040
 * clicked=0} — a second per-tick writer racing this one). Whatever ticks a Movement MUST therefore
 * suppress every other input/aim writer for that tick (BlockPathWalker, PathExecutor, combat).
 *
 * <h2>Adapters written here because tungsten has no baritone service</h2>
 * <ul>
 *   <li>{@code IPlayerContext} -&gt; {@link PlayerCtx}, a nested adapter with only the members the
 *       movements use. {@code playerFeet}/{@code playerHead} are ported verbatim
 *       (IPlayerContext.java:62-95) including the {@code +0.1251} and the slab correction;
 *       {@code objectMouseOver} is a LIVE raytrace, per BaritonePlayerContext.java:84-86 — never
 *       {@code mc.crosshairTarget}, which at the stand's measured 10 fps is one to two ticks stale,
 *       exactly the width of the sneak-pose and jump-apex windows.</li>
 *   <li>{@code LookBehavior.updateTarget(rot, force)} -&gt; {@link WindMouseRotation}: forced
 *       targets go to {@code setTarget}, unforced ({@code moveTowards}) to {@code setTargetFast}
 *       with the pitch preserved. The camera is NEVER moved with {@code setYaw}/{@code setPitch} —
 *       rotation has to go through the vanilla mouse pipeline or it is trivially detectable.</li>
 *   <li>{@code InputOverrideHandler} -&gt; {@link #clearAllKeys()} / {@link #applyInputs} over
 *       {@code MinecraftClient.getInstance().options}, including the
 *       CLICK_LEFT-cancels-CLICK_RIGHT interlock (InputOverrideHandler.java:94-96) and the
 *       {@code BlockPlaceHelper} 4-tick right-click gate (BlockPlaceHelper.java:29-57).</li>
 *   <li>{@code MovementHelper.switchToBestToolFor} -&gt; {@code TungstenModDataContainer.equipToolHook}
 *       (TungstenModDataContainer.java:30). Tungsten never touches the inventory itself; altoclef
 *       owns the hotbar, so baritone's {@code ToolSet} is not ported.</li>
 *   <li>{@code CalculationContext} -&gt; {@code (WorldView, ClientPlayerEntity)} per the spec's
 *       substitution table. {@code Baritone.settings()} -&gt; hardcoded upstream defaults, each
 *       named at its use site.</li>
 *   <li>{@code PathingBehavior.pathStart()} — no equivalent; see {@link #playerInValidPosition()}.</li>
 * </ul>
 *
 * <h2>What this file expects from the rest of the package (unit 1 contract)</h2>
 * {@code MovementState} ({@code setStatus}/{@code getStatus}/{@code setInput}/{@code getTarget}/
 * {@code getInputStates}, nested {@code MovementTarget(Rotation, boolean)} with {@code getRotation()}
 * and {@code hasToForceRotations()}), the {@code MovementStatus} enum (with {@code isComplete()}),
 * the {@code Input} enum, {@code Rotation} (with {@code getYaw}/{@code getPitch}/
 * {@code isReallyCloseTo}), {@code RotationHelper.reachable/calcRotationFromVec3d/getBlockPosCenter},
 * and {@code MovementHelperB.isLiquid/canWalkThrough/canWalkOn}.
 */
public abstract class Movement {

    /**
     * Movement.java:36. The order is load-bearing and is not to be sorted: DOWN last is what makes
     * {@code preferDown} work, and NORTH-first is what makes a side place deterministic.
     */
    public static final Direction[] HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    /** Baritone {@code blockReachDistance}, Settings.java:385, and the creative 5.0 branch from
     *  IPlayerController.java:58-60. Hardcoded default, not a tungsten setting. */
    private static final double BLOCK_REACH_DISTANCE = 4.5;

    /** Baritone {@code rightClickSpeed}, Settings.java:375 = 4, minus BlockPlaceHelper's
     *  {@code BASE_PLACE_DELAY = 1} (BlockPlaceHelper.java:29). */
    private static final int RIGHT_CLICK_SPEED = 4;
    private static final int BASE_PLACE_DELAY = 1;

    /** Baritone {@code pauseMiningForFallingBlocks}, Settings.java:370 = true. */
    private static final boolean PAUSE_MINING_FOR_FALLING_BLOCKS = true;

    /**
     * BlockPlaceHelper.java:31 keeps this per-Baritone-instance, i.e. per player, NOT per movement:
     * the 4-tick place cooldown has to survive the handover from one movement to the next, or a
     * chain of one-block bridge steps would place on every single tick. Hence static.
     */
    private static int rightClickTimer;

    /**
     * TELEMETRY ONLY — no branch reads these. They exist because the split place engine's counters
     * ({@code PathExecutor.placeCalled/placeInRange/placeClicked}) are what proved it broken —
     * {@code called=11041 inRange=11040 clicked=0} — and the replacement has to be measurable at the
     * same granularity or "it works now" is a claim rather than a number. Exposed over py4j as
     * {@code placeStats}.
     *
     * <p>{@code placeRequested} = ticks a movement asked for CLICK_RIGHT (i.e. its own crosshair gate
     * had already passed); {@code placeOnCooldown} = of those, the ones the 4-tick BlockPlaceHelper
     * gate swallowed; {@code placeNoHit} = the raytrace was not on a block after all;
     * {@code placeClicked} = {@code interactBlock} returned SUCCESS.
     */
    public static volatile int placeRequested, placeOnCooldown, placeNoHit, placeClicked;

    /** The {@code IPlayerContext} stand-in. See {@link PlayerCtx}. */
    protected final PlayerCtx ctx = new PlayerCtx();

    private MovementState currentState = new MovementState().setStatus(MovementStatus.PREPPING);

    protected final BetterBlockPos src;

    protected final BetterBlockPos dest;

    /**
     * The positions that need to be broken before this movement can ensue
     */
    protected final BetterBlockPos[] positionsToBreak;

    /**
     * The position where we need to place a block before this movement can ensue
     */
    protected final BetterBlockPos positionToPlace;

    private Double cost;

    public List<BlockPos> toBreakCached = null;
    public List<BlockPos> toPlaceCached = null;
    public List<BlockPos> toWalkIntoCached = null;

    private Set<BetterBlockPos> validPositionsCached = null;

    private Boolean calculatedWhileLoaded;

    protected Movement(BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak, BetterBlockPos toPlace) {
        this.src = src;
        this.dest = dest;
        this.positionsToBreak = toBreak;
        this.positionToPlace = toPlace;
    }

    protected Movement(BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak) {
        this(src, dest, toBreak, null);
    }

    public double getCost() throws NullPointerException {
        return cost;
    }

    public double getCost(WorldView world, ClientPlayerEntity player) {
        if (cost == null) {
            cost = calculateCost(world, player);
        }
        return cost;
    }

    /**
     * Movement.java:91 declares this abstract, taking a {@code CalculationContext}. Tungsten has no
     * such object, so it takes the world and the player instead (spec substitution table). It is
     * left CONCRETE on purpose: a ported movement exposes its price as the upstream STATIC
     * {@code cost(...)} and the planner feeds it in through {@link #override(double)}, so forcing
     * every subclass to also carry an instance form would break compilation for no behaviour. A
     * subclass that wants the cache overrides this.
     */
    public double calculateCost(WorldView world, ClientPlayerEntity player) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has no instance cost — call its static cost(...) and override(double) the result");
    }

    public double recalculateCost(WorldView world, ClientPlayerEntity player) {
        cost = null;
        return getCost(world, player);
    }

    public void override(double cost) {
        this.cost = cost;
    }

    protected abstract Set<BetterBlockPos> calculateValidPositions();

    public Set<BetterBlockPos> getValidPositions() {
        if (validPositionsCached == null) {
            validPositionsCached = calculateValidPositions();
            Objects.requireNonNull(validPositionsCached);
        }
        return validPositionsCached;
    }

    /**
     * Movement.java:112-114 also accepts {@code PathingBehavior.pathStart()} — the cell the path is
     * anchored at, which differs from the feet cell only while airborne. Tungsten has no
     * PathingBehavior, and inventing an anchor here would be inventing behaviour, so only the feet
     * half comes across. ADAPTER: divergence recorded, not faked.
     */
    protected boolean playerInValidPosition() {
        return getValidPositions().contains(ctx.playerFeet());
    }

    /**
     * Handles the execution of the latest Movement
     * State, and offers a Status to the calling class.
     *
     * @return Status
     */
    public MovementStatus update() {
        ClientPlayerEntity player = ctx.player();
        if (player == null) {
            // Not upstream: IPlayerContext.player() is never null while pathing, but
            // MinecraftClient.player is (disconnect, world unload). Returning the unchanged status
            // is the no-op; nothing else in this method is safe without a player.
            return currentState.getStatus();
        }
        player.getAbilities().flying = false;
        currentState = updateState(currentState);
        if (MovementHelperB.isLiquid(ctx.world(), ctx.playerFeet()) && player.getEntityPos().y < dest.y + 0.6) {
            currentState.setInput(Input.JUMP, true);
        }
        if (player.isInsideWall()) {
            ctx.getSelectedBlock().ifPresent(pos -> switchToBestToolFor(pos, ctx.world().getBlockState(pos)));
            currentState.setInput(Input.CLICK_LEFT, true);
        }

        // If the movement target has to force the new rotations, or we aren't using silent move, then force the rotations
        currentState.getTarget().getRotation().ifPresent(rotation ->
                updateAimTarget(
                        player,
                        rotation,
                        currentState.getTarget().hasToForceRotations()));
        clearAllKeys();
        // Movement.java:139-148 is `clearAllKeys(); apply; clear; if (complete) clearAllKeys();` —
        // upstream can force the inputs and then take them back, because the force map is consumed
        // a tick later by InputOverrideHandler.onTick. Here the apply IS the tick, and a place click
        // cannot be un-clicked, so the trailing cancellation has to be the guard in front instead.
        // Net behaviour is identical: a completed movement forces no inputs.
        if (!currentState.getStatus().isComplete()) {
            applyInputs(player, currentState.getInputStates());
        }
        currentState.getInputStates().clear();

        return currentState.getStatus();
    }

    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        boolean somethingInTheWay = false;
        for (BetterBlockPos blockPos : positionsToBreak) {
            if (!ctx.world().getNonSpectatingEntities(FallingBlockEntity.class, new Box(0, 0, 0, 1, 1.1, 1).offset(blockPos)).isEmpty() && PAUSE_MINING_FOR_FALLING_BLOCKS) {
                return false;
            }
            if (!MovementHelperB.canWalkThrough(ctx.world(), blockPos)) { // can't break air, so don't try
                somethingInTheWay = true;
                switchToBestToolFor(blockPos, ctx.world().getBlockState(blockPos));
                Optional<Rotation> reachable = RotationHelper.reachable(ctx.player(), blockPos, ctx.blockReachDistance());
                if (reachable.isPresent()) {
                    Rotation rotTowardsBlock = reachable.get();
                    state.setTarget(new MovementState.MovementTarget(rotTowardsBlock, true));
                    if (ctx.isLookingAt(blockPos) || ctx.playerRotations().isReallyCloseTo(rotTowardsBlock)) {
                        state.setInput(Input.CLICK_LEFT, true);
                    }
                    return false;
                }
                //get rekt minecraft
                //i'm doing it anyway
                //i dont care if theres snow in the way!!!!!!!
                //you dont own me!!!!
                state.setTarget(new MovementState.MovementTarget(RotationHelper.calcRotationFromVec3d(ctx.playerHead(),
                        RotationHelper.getBlockPosCenter(blockPos), ctx.playerRotations()), true)
                );
                // don't check selectedblock on this one, this is a fallback when we can't see any face directly, it's intended to be breaking the "incorrect" block
                state.setInput(Input.CLICK_LEFT, true);
                return false;
            }
        }
        if (somethingInTheWay) {
            // There's a block or blocks that we can't walk through, but we have no target rotation to reach any
            // So don't return true, actually set state to unreachable
            state.setStatus(MovementStatus.UNREACHABLE);
            return true;
        }
        return true;
    }

    public boolean safeToCancel() {
        return safeToCancel(currentState);
    }

    protected boolean safeToCancel(MovementState currentState) {
        return true;
    }

    public BetterBlockPos getSrc() {
        return src;
    }

    public BetterBlockPos getDest() {
        return dest;
    }

    public void reset() {
        currentState = new MovementState().setStatus(MovementStatus.PREPPING);
    }

    /**
     * Calculate latest movement state. Gets called once a tick.
     *
     * @param state The current state
     * @return The new state
     */
    public MovementState updateState(MovementState state) {
        if (!prepared(state)) {
            return state.setStatus(MovementStatus.PREPPING);
        } else if (state.getStatus() == MovementStatus.PREPPING) {
            state.setStatus(MovementStatus.WAITING);
        }

        if (state.getStatus() == MovementStatus.WAITING) {
            state.setStatus(MovementStatus.RUNNING);
        }

        return state;
    }

    public BlockPos getDirection() {
        return getDest().subtract(getSrc());
    }

    /** Movement.java:244-246. {@code bsi.worldContainsLoadedChunk(x, z)} is
     *  {@code provider.isChunkLoaded(x >> 4, z >> 4)} (BlockStateInterface.java:79-81). */
    public void checkLoadedChunk(WorldView world) {
        calculatedWhileLoaded = world.isChunkLoaded(dest.x >> 4, dest.z >> 4);
    }

    public boolean calculatedWhileLoaded() {
        return calculatedWhileLoaded;
    }

    public void resetBlockCache() {
        toBreakCached = null;
        toPlaceCached = null;
        toWalkIntoCached = null;
    }

    public List<BlockPos> toBreak(WorldView bsi) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (BetterBlockPos positionToBreak : positionsToBreak) {
            if (!MovementHelperB.canWalkThrough(bsi, positionToBreak.x, positionToBreak.y, positionToBreak.z)) {
                result.add(positionToBreak);
            }
        }
        toBreakCached = result;
        return result;
    }

    public List<BlockPos> toPlace(WorldView bsi) {
        if (toPlaceCached != null) {
            return toPlaceCached;
        }
        List<BlockPos> result = new ArrayList<>();
        if (positionToPlace != null && !MovementHelperB.canWalkOn(bsi, positionToPlace.x, positionToPlace.y, positionToPlace.z)) {
            result.add(positionToPlace);
        }
        toPlaceCached = result;
        return result;
    }

    public List<BlockPos> toWalkInto(WorldView bsi) { // overridden by movementdiagonal
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        return toWalkIntoCached;
    }

    public BlockPos[] toBreakAll() {
        return positionsToBreak;
    }

    // ------------------------------------------------------------------------------------------
    // Adapters. Everything below stands in for a baritone service tungsten does not have; none of
    // it adds a decision the movement did not make.
    // ------------------------------------------------------------------------------------------

    /**
     * {@code MovementHelper.switchToBestToolFor(ctx, state)} (MovementHelper.java:698-713), which
     * ends in {@code player.getInventory().selectedSlot = ts.getBestSlot(...)}. Tungsten never
     * touches the inventory — altoclef owns the hotbar and registers
     * {@code equipToolHook} for exactly this (TungstenModDataContainer.java:22-30) — so the hook is
     * the port and baritone's {@code ToolSet} is not carried over. The hook must never be able to
     * break mining, hence the swallow (same convention as PathExecutor.java:398-403).
     */
    private static void switchToBestToolFor(BlockPos pos, BlockState state) {
        if (TungstenModDataContainer.equipToolHook != null) {
            try {
                TungstenModDataContainer.equipToolHook.accept(pos, state);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * {@code LookBehavior.updateTarget(rotation, force)} (Movement.java:135-138). Forced targets are
     * the ones a break/place gate will be tested against, so they get the full aim; the unforced one
     * is {@code MovementHelper.moveTowards} (MovementHelper.java:715-722), where the pitch must stay
     * where the player left it — a walk must not tilt the camera. Never {@code setYaw}/{@code setPitch}:
     * WindMouseRotation feeds pixel deltas through the vanilla mouse pipeline, so the server sees a
     * mouse, not a teleport.
     */
    private static void updateAimTarget(ClientPlayerEntity player, Rotation rotation, boolean force) {
        if (force) {
            WindMouseRotation.INSTANCE.setTarget(rotation.getYaw(), rotation.getPitch());
        } else {
            WindMouseRotation.INSTANCE.setTargetFast(rotation.getYaw(), player.getPitch());
        }
    }

    /**
     * {@code InputOverrideHandler.clearAllKeys()} (InputOverrideHandler.java:80-83). Upstream clears
     * a force map that mixins read; here the keys themselves are released, which is the same thing
     * one layer down. Called before every apply, so no input can latch across ticks, and again on a
     * completed movement (Movement.java:146-148).
     *
     * <p>Package-visible, not private: upstream's {@code clearAllKeys} is public API on
     * {@code InputOverrideHandler} and {@code PathExecutor.onLostControl} calls it when it hands the
     * body back (PathExecutor.java:346). {@link MovementQueue#stop()} is that call here — a queue
     * that stops mid-sneak must not leave SHIFT latched.
     */
    static void clearAllKeys() {
        GameOptions options = MinecraftClient.getInstance().options;
        if (options == null) {
            return;
        }
        options.forwardKey.setPressed(false);
        options.backKey.setPressed(false);
        options.leftKey.setPressed(false);
        options.rightKey.setPressed(false);
        options.jumpKey.setPressed(false);
        options.sneakKey.setPressed(false);
        options.sprintKey.setPressed(false);
        options.attackKey.setPressed(false);
        // CLICK_RIGHT is not a key here (see blockPlaceHelperTick), but release useKey anyway so
        // this stays the only owner of the inputs for the tick.
        options.useKey.setPressed(false);
    }

    /**
     * The press half of Movement.java:139-143 — exactly what this tick declared, nothing else.
     *
     * <p>The two clicks are not keys: {@code CLICK_LEFT} is the attack key held down (vanilla runs
     * the break progress), while {@code CLICK_RIGHT} goes through the {@code BlockPlaceHelper} gate
     * because a use-key press would place on every tick and out of the player's real crosshair.
     * {@code CLICK_LEFT} forced on a tick cancels {@code CLICK_RIGHT} (InputOverrideHandler.java:94-96)
     * — breaking and placing never overlap — and that has to be decided BEFORE anything is applied,
     * since the map's iteration order is not the branch order.
     */
    private void applyInputs(ClientPlayerEntity player, Map<Input, Boolean> inputStates) {
        GameOptions options = MinecraftClient.getInstance().options;
        if (options == null) {
            return;
        }
        boolean clickLeft = Boolean.TRUE.equals(inputStates.get(Input.CLICK_LEFT));
        boolean clickRight = Boolean.TRUE.equals(inputStates.get(Input.CLICK_RIGHT));
        if (clickLeft) {
            clickRight = false;
        }
        inputStates.forEach((input, forced) -> {
            switch (input) {
                case MOVE_FORWARD:
                    options.forwardKey.setPressed(forced);
                    break;
                case MOVE_BACK:
                    options.backKey.setPressed(forced);
                    break;
                case MOVE_LEFT:
                    options.leftKey.setPressed(forced);
                    break;
                case MOVE_RIGHT:
                    options.rightKey.setPressed(forced);
                    break;
                case JUMP:
                    options.jumpKey.setPressed(forced);
                    break;
                case SNEAK:
                    options.sneakKey.setPressed(forced);
                    break;
                case SPRINT:
                    options.sprintKey.setPressed(forced);
                    break;
                case CLICK_LEFT:
                case CLICK_RIGHT:
                    // handled below, after the interlock
                    break;
                default:
                    break;
            }
        });
        options.attackKey.setPressed(clickLeft);
        blockPlaceHelperTick(player, clickRight);
    }

    /**
     * {@code BlockPlaceHelper.tick} (BlockPlaceHelper.java:38-57): one attempt every four ticks, and
     * the cooldown is charged even when the attempt fails. Upstream is ticked by the input handler
     * on every tick; here it is ticked by whichever movement is applying inputs, so the countdown
     * pauses on the ticks no movement is running. That only ever makes the gate more conservative.
     *
     * <p>The hit result handed to {@code interactBlock} is THE ONE THE RAYTRACE PRODUCED — never a
     * {@link BlockHitResult} built from a face centre, which would be a packet claiming a click the
     * player never made. Nor is there a target position to check here: the movement's own
     * {@code attemptToPlaceABlock} already refused to ask for {@code CLICK_RIGHT} until the real
     * crosshair agreed (MovementHelper.java:840-851), so a second gate would only re-implement it.
     */
    private void blockPlaceHelperTick(ClientPlayerEntity player, boolean rightClickRequested) {
        if (rightClickRequested) {
            placeRequested++;
        }
        if (rightClickTimer > 0) {
            rightClickTimer--;
            if (rightClickRequested) {
                placeOnCooldown++;
            }
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.interactionManager == null) {
            return;
        }
        HitResult mouseOver = ctx.objectMouseOver();
        if (!rightClickRequested || player.isRiding() || mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
            if (rightClickRequested) {
                placeNoHit++;
            }
            return;
        }
        rightClickTimer = RIGHT_CLICK_SPEED - BASE_PLACE_DELAY;
        for (Hand hand : Hand.values()) {
            if (mc.interactionManager.interactBlock(player, hand, (BlockHitResult) mouseOver) == ActionResult.SUCCESS) {
                player.swingHand(hand);
                placeClicked++;
                return;
            }
            if (!player.getStackInHand(hand).isEmpty() && mc.interactionManager.interactItem(player, hand) == ActionResult.SUCCESS) {
                return;
            }
        }
    }

    /**
     * {@code IPlayerContext} (IPlayerContext.java:62-120) reduced to what the ported movements
     * actually read. Deliberately not a general-purpose facade: the point of the adapter is that a
     * verbatim {@code ctx.playerFeet()} / {@code ctx.isLookingAt(pos)} from upstream compiles here
     * and means the same thing.
     */
    protected static final class PlayerCtx {

        public ClientPlayerEntity player() {
            return MinecraftClient.getInstance().player;
        }

        /** {@code ctx.world()}. ClientWorld rather than WorldView because {@link #prepared} needs
         *  {@code getNonSpectatingEntities}; it is still a WorldView everywhere else. */
        public ClientWorld world() {
            return MinecraftClient.getInstance().world;
        }

        /**
         * IPlayerContext.java:62-79, verbatim. The {@code +0.1251} and the slab correction are not
         * cosmetic: a plain floor of y reports the wrong cell on landing ticks, which is exactly
         * when SUCCESS is tested. ({@code feet.above()} is tungsten's BetterBlockPos-typed
         * {@code up()} — BetterBlockPos.java:107.)
         */
        public BetterBlockPos playerFeet() {
            ClientPlayerEntity player = player();
            BetterBlockPos feet = new BetterBlockPos(player.getEntityPos().x, player.getEntityPos().y + 0.1251, player.getEntityPos().z);
            try {
                if (world().getBlockState(feet).getBlock() instanceof SlabBlock) {
                    return feet.above();
                }
            } catch (NullPointerException ignored) {}
            return feet;
        }

        /**
         * IPlayerContext.java:93-95. NOT {@code getEyePos()}: that folds in the current pose, which
         * destroys the standing-vs-sneaking eye separation (1.62 vs 1.27) the backplace pitch is
         * built on.
         */
        public Vec3d playerHead() {
            ClientPlayerEntity player = player();
            return new Vec3d(player.getEntityPos().x, player.getEntityPos().y + player.getStandingEyeHeight(), player.getEntityPos().z);
        }

        public Rotation playerRotations() {
            ClientPlayerEntity player = player();
            return new Rotation(player.getYaw(), player.getPitch());
        }

        /**
         * BaritonePlayerContext.java:84-86 — a LIVE raytrace recomputed this tick from the real eye
         * and the real current rotations, not the per-frame {@code mc.crosshairTarget}.
         * {@code Entity.raycast(reach, 1.0F, false)} is that raytrace exactly: it starts at
         * {@code getCameraPosVec}, aims along the current rotations, and uses
         * {@code ShapeType.OUTLINE} with {@code FluidHandling.NONE} — the same three choices as
         * RayTraceUtils.java:33-48.
         */
        public HitResult objectMouseOver() {
            return player().raycast(blockReachDistance(), 1.0F, false);
        }

        public Optional<BlockPos> getSelectedBlock() {
            HitResult result = objectMouseOver();
            if (result != null && result.getType() == HitResult.Type.BLOCK) {
                return Optional.of(((BlockHitResult) result).getBlockPos());
            }
            return Optional.empty();
        }

        public boolean isLookingAt(BlockPos pos) {
            return getSelectedBlock().equals(Optional.of(pos));
        }

        /** {@code playerController().getBlockReachDistance()} (IPlayerController.java:58-60). */
        public double blockReachDistance() {
            return player().isCreative() ? 5.0 : BLOCK_REACH_DISTANCE;
        }
    }
}
