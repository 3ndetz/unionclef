package kaptainwutax.tungsten.path.movements;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LadderBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.WaterFluid;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/**
 * Port of {@code baritone/pathing/movement/movements/MovementFall.java} — a MULTI-block drop:
 * {@code dest} is one cardinal step away and TWO OR MORE blocks down. Walk off the lip, steer the
 * body onto the destination column while airborne, and land.
 *
 * <h2>Why this one</h2>
 *
 * {@code FastPlanner} emits drops of up to {@code MAX_FALL = 3} (FastPlanner.java:50, :798), and the
 * only drop class the executor had was {@link MovementDescend}, which is exactly −1 Y
 * ({@code isDescendEdge}, MovementQueue.java:228-233). Every 2- and 3-block drop therefore fell
 * through to {@link MovementFallback}, a steer with no fall model at all: it aims at the destination
 * centre and presses forward, which off a lip is a walk into the air with nothing correcting the
 * horizontal drift on the way down and nothing that knows when the landing has happened.
 *
 * <p>As with the other ports in this package, cost stays in {@code FastPlanner} — upstream's
 * {@code calculateCost} (MovementFall.java:60-68, which re-runs {@code MovementDescend.cost} and
 * checks {@code result.y == dest.y}) is deliberately NOT duplicated, because two pricing functions
 * for one move is the duplication this project keeps paying for. What is copied is the execution.
 *
 * <h2>What the execution actually consists of</h2>
 * <ul>
 *   <li>the FALL-THROUGH steer (MovementFall.java:137-143): while the body is more than 0.1 from the
 *       destination centre in X or Z <em>after adding this tick's velocity</em>, press MOVE_FORWARD;
 *       and while falling fast ({@code |vy| > 0.4}) also press SNEAK, which shrinks the hitbox and
 *       is what stops a fast fall from clipping the lip on the way past;</li>
 *   <li>the LADDER AVOIDANCE (MovementFall.java:144-158, {@link #avoid()}): a ladder anywhere in the
 *       15 cells below the feet makes the aim point 0.125 to the side of the destination centre,
 *       away from the ladder's face, because falling onto a ladder catches you on its EDGE, not its
 *       centre. With no ladder, {@code avoid} degrades to {@code src - dest}, i.e. the aim is nudged
 *       back towards the cell we came from;</li>
 *   <li>the SUCCESS test (MovementFall.java:119-136), which is not "feet are in dest": it also wants
 *       the body within 0.094 of the cell floor ("0.094 because lilypads"), or water.</li>
 * </ul>
 *
 * <h2>Geometry (MovementFall.java:179-189)</h2>
 * {@code positionsToBreak} is the whole DESTINATION column from {@code src.y + 1} down to
 * {@code dest.y}, i.e. {@code |dy| + 2} cells — the head and feet cells we walk into plus everything
 * we fall through. There is no {@code positionToPlace}: a fall places nothing.
 *
 * <p>Note the redundant arithmetic in {@link #buildPositionsToBreak}: {@code src.getX() - diffX} with
 * {@code diffX = src.getX() - dest.getX()} is just {@code dest.getX()}. Copied as written — upstream's
 * quirk, and rewriting it would make a future diff against upstream lie.
 *
 * <h2>Adapters written because tungsten has no baritone service</h2>
 * <ul>
 *   <li>{@code IPlayerContext} → the base {@link Movement.PlayerCtx}, so every {@code ctx.playerFeet()}
 *       / {@code ctx.playerHead()} / {@code ctx.playerRotations()} / {@code ctx.isLookingAt(pos)} below
 *       is upstream's own line. {@code ctx.playerController().getBlockReachDistance()} →
 *       {@code ctx.blockReachDistance()}.</li>
 *   <li>{@code RotationUtils} / {@code VecUtils} → {@link RotationHelper}; {@code MovementHelper} →
 *       {@link MovementHelperB}.</li>
 *   <li>{@code ctx.player().getPos()} → {@code getEntityPos()} and
 *       {@code getInventory().selectedSlot = n} → {@code getInventory().setSelectedSlot(n)}, which is
 *       what the compiled shredder copy of this same file uses on this MC version (shredder
 *       MovementFall.java:104-105, :122).</li>
 *   <li>{@code AltoClefSettings.getInstance().shouldNotPlaceBucketButStillFall()} (MovementFall.java:99)
 *       — see {@link #updateState}, ADAPTER note at the call site.</li>
 *   <li>{@code MovementDescend.dynamicFallCost} → {@link #willPlaceBucket()}, ADAPTER note on that
 *       method.</li>
 *   <li>{@link BetterBlockPos} spells baritone's {@code up()/down()} as {@code above()/below()}; the
 *       inherited yarn {@code up()/down()} compile but return the wrong TYPE, so the renames are
 *       mandatory rather than cosmetic.</li>
 * </ul>
 */
public class MovementFall extends Movement {

    /** MovementFall.java:53-54. Compared by {@code getSlotWithStack}, which matches on item + components. */
    private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);
    private static final ItemStack STACK_BUCKET_EMPTY = new ItemStack(Items.BUCKET);

    /** {@code context.minFallHeight} — CalculationContext.java:135, hardcoded 3 upstream too
     *  ("Minimum fall height used by MovementFall"). */
    private static final int MIN_FALL_HEIGHT = 3;

    /** {@code Baritone.settings().maxFallHeightNoWater} (Settings.java:536) — default 3, i.e. the
     *  deepest drop that deals no damage. This is the number that makes {@link #willPlaceBucket()}
     *  false for everything {@code FastPlanner} can emit; see that method. */
    private static final int MAX_FALL_HEIGHT_NO_WATER = 3;

    /** {@code Baritone.settings().maxFallHeightBucket} (Settings.java:542) — default 20. */
    private static final int MAX_FALL_HEIGHT_BUCKET = 20;

    /** {@code Baritone.settings().allowWaterBucketFall} (Settings.java:140) — default true. */
    private static final boolean ALLOW_WATER_BUCKET_FALL = true;

    /** {@code Baritone.settings().assumeWalkOnWater} (Settings.java:148) — default false. */
    private static final boolean ASSUME_WALK_ON_WATER = false;

    /** {@code context.allowFallIntoLava} (CalculationContext.java:117) — hardcoded false upstream
     *  as well ("Super secret internal setting for ElytraBehavior"). */
    private static final boolean ALLOW_FALL_INTO_LAVA = false;

    /**
     * MovementFall.java:56-58. Four-argument super upstream, three here: a fall declares no
     * {@code positionToPlace}, so {@link Movement}'s three-argument constructor is the same thing.
     */
    public MovementFall(BetterBlockPos src, BetterBlockPos dest) {
        super(src, dest, buildPositionsToBreak(src, dest));
    }

    /**
     * MovementFall.java:70-78. Every cell of the destination column from the level we left down to
     * the landing cell, PLUS {@code src}. That is the whole airborne trajectory, and enumerating it
     * is what keeps {@code MovementQueue.closestPathPos} small while the body is in the air: without
     * these cells a fall reads as a body far from every cell of the route, which is the state the
     * queue cancels on. It is NOT the whole answer, though — upstream additionally exempts a fall
     * from its off-path check outright ({@code PathExecutor.possiblyOffPath}, :304-317, which swaps
     * the 3D distance for a FLAT one against the fall's destination), because horizontal drift off
     * the lip is not being off the path.
     */
    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        set.add(src);
        for (int y = src.y - dest.y; y >= 0; y--) {
            set.add(dest.above(y)); // BetterBlockPos.above() is tungsten's name for baritone's up()
        }
        return set;
    }

    /**
     * MovementFall.java:80-84 — "did the planner only allow this fall because a water bucket would
     * be placed at the bottom?". The single gate on the whole MLG-bucket branch in
     * {@link #updateState}.
     *
     * <p>ADAPTER: upstream is one line — {@code MovementDescend.dynamicFallCost(context, src.x,
     * src.y, src.z, dest.x, dest.z, 0, context.get(dest.x, src.y - 2, dest.z), result)} — and takes
     * that function's BOOLEAN RETURN, discarding the {@code MutableMoveResult} it fills. Tungsten has
     * no {@code CalculationContext} and does not price falls (FastPlanner does), so what is ported
     * here is that function's CONTROL FLOW (MovementDescend.java:136-222) with the cost arithmetic
     * left out — {@code costSoFar} / {@code tentativeCost} are written only into {@code res}, which
     * this call site throws away, so removing them cannot change the answer. Every {@code return} is
     * preserved, in upstream's order, including the four inside the water branch. Nothing is
     * simplified: the sole {@code return true} upstream is the bucket branch at :213-218, so this
     * method answers exactly the question its name asks.
     *
     * <p>Two upstream quirks reproduced rather than fixed:
     * <ul>
     *   <li>{@code frontBreak} is the literal {@code 0} at this call site, so
     *       MovementDescend.java:137-142 ("if we're breaking blocks in front, don't let anything fall
     *       through this column") is DEAD here. It is not written out because a dead branch that
     *       reads {@code context.get(destX, y + 2, destZ)} would only invite someone to "fix" the
     *       parameter; it is recorded here instead.</li>
     *   <li>the {@code newY < 0} guard (MovementDescend.java:150-153) is upstream's void check and it
     *       is one world-bottom out of date — 1.21 worlds start at −64, not 0. Left alone: it can
     *       only make this method answer "no bucket" early, never "yes", and changing it would be
     *       re-tuning a fall model this port does not own.</li>
     * </ul>
     *
     * <p>WHAT IT ANSWERS TODAY: always {@code false}. {@link #MAX_FALL_HEIGHT_NO_WATER} is 3 and
     * {@code unprotectedFallHeight} is {@code src.y - dest.y + 1}, so a drop of 3 gives 4, which is
     * {@code <= MAX_FALL_HEIGHT_NO_WATER + 1} and returns at MovementDescend.java:205-212 — before
     * the bucket branch. {@code FastPlanner.MAX_FALL} is 3, so no fall this queue is handed can reach
     * the bucket. The branch is ported anyway because it is the ONLY thing standing between a deeper
     * drop and fall damage the moment that constant is raised, and because a half-ported condition is
     * how this package acquired a severe bug once already.
     */
    private boolean willPlaceBucket() {
        WorldView world = ctx.world();
        BlockPos.Mutable scratch = new BlockPos.Mutable();
        final int y = src.y;
        final int destX = dest.x;
        final int destZ = dest.z;

        // MovementFall.java:83 — `below` is the cell TWO below the level we are leaving, read in the
        // destination column.
        BlockState below = stateAt(world, scratch, destX, y - 2, destZ);

        // MovementDescend.java:143-145.
        if (!MovementHelperB.canWalkThrough(world, destX, y - 2, destZ, below)) {
            return false;
        }
        int effectiveStartHeight = y;
        for (int fallHeight = 3; true; fallHeight++) {
            int newY = y - fallHeight;
            if (newY < 0) {
                // when pathing in the end, where you could plausibly fall into the void
                // this check prevents it from getting the block at y=-1 and crashing
                return false;
            }
            boolean reachedMinimum = fallHeight >= MIN_FALL_HEIGHT;
            BlockState ontoBlock = stateAt(world, scratch, destX, newY, destZ);
            // equal to fallHeight - y + effectiveFallHeight, which is equal to -newY + effectiveFallHeight
            int unprotectedFallHeight = fallHeight - (y - effectiveStartHeight);
            if (reachedMinimum && MovementHelperB.isWater(ontoBlock)) {
                if (!MovementHelperB.canWalkThrough(world, destX, newY, destZ, ontoBlock)) {
                    return false;
                }
                if (ASSUME_WALK_ON_WATER) {
                    return false; // TODO fix
                }
                if (MovementHelperB.isFlowing(destX, newY, destZ, ontoBlock, world)) {
                    return false; // TODO flowing check required here?
                }
                if (!MovementHelperB.canWalkOn(world, destX, newY - 1, destZ)) {
                    // we could punch right through the water into something else
                    return false;
                }
                // found a fall into water — upstream fills res and returns false: water is free, no
                // bucket is placed
                return false;
            }
            if (reachedMinimum && ALLOW_FALL_INTO_LAVA && MovementHelperB.isLava(ontoBlock)) {
                // found a fall into lava
                return false;
            }
            if (unprotectedFallHeight <= 11 && (ontoBlock.getBlock() == Blocks.VINE || ontoBlock.getBlock() == Blocks.LADDER)) {
                // if fall height is greater than or equal to 11, we don't actually grab on to vines or ladders. the more you know
                // this effectively "resets" our falling speed
                effectiveStartHeight = newY;
                continue;
            }
            if (MovementHelperB.canWalkThrough(world, destX, newY, destZ, ontoBlock)) {
                continue;
            }
            if (!MovementHelperB.canWalkOn(world, destX, newY, destZ, ontoBlock)) {
                return false;
            }
            if (MovementHelperB.isBottomSlab(ontoBlock)) {
                return false; // falling onto a half slab is really glitchy, and can cause more fall damage than we'd expect
            }
            if (reachedMinimum && unprotectedFallHeight <= MAX_FALL_HEIGHT_NO_WATER + 1) {
                // fallHeight = 4 means onto.up() is 3 blocks down, which is the max
                return false;
            }
            if (reachedMinimum && hasWaterBucket() && unprotectedFallHeight <= MAX_FALL_HEIGHT_BUCKET + 1) {
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * {@code context.hasWaterBucket} (CalculationContext.java:107), verbatim including the Nether
     * clause — a water bucket placed in the Nether evaporates, so a fall priced on one there would be
     * a fall priced on nothing.
     */
    private boolean hasWaterBucket() {
        ClientPlayerEntity player = ctx.player();
        return ALLOW_WATER_BUCKET_FALL
                && PlayerInventory.isValidHotbarIndex(player.getInventory().getSlotWithStack(STACK_BUCKET_WATER))
                && ctx.world().getRegistryKey() != World.NETHER;
    }

    /**
     * {@code updateState} — MovementFall.java:86-160, copied rather than paraphrased. The ORDER is
     * load-bearing throughout and is not to be rearranged:
     *
     * <ol>
     *   <li>the bucket branch runs FIRST, because it is the only thing that may set a FORCED rotation
     *       (pitch 90, straight down) and everything below has to know whether one was set;</li>
     *   <li>the target is set from {@code targetRotation} or {@code toDest} BEFORE the success test,
     *       so a tick that succeeds still leaves a sane aim behind it;</li>
     *   <li>the success test comes before the steer, so an arrived movement presses nothing;</li>
     *   <li>the steer's SNEAK (fast fall) is set before {@link #avoid()} may clear it — a ladder in
     *       the column wants the body NOT sneaking as it goes past the 0.6 mark.</li>
     * </ol>
     */
    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        ClientPlayerEntity player = ctx.player();
        BlockPos playerFeet = ctx.playerFeet();
        Rotation toDest = RotationHelper.calcRotationFromVec3d(ctx.playerHead(),
                RotationHelper.getBlockPosCenter(dest), ctx.playerRotations());
        Rotation targetRotation = null;
        BlockState destState = ctx.world().getBlockState(dest);
        @SuppressWarnings("unused")
        Block destBlock = destState.getBlock(); // MovementFall.java:97 — unused upstream too; kept
        boolean isWater = destState.getFluidState().getFluid() instanceof WaterFluid;
        // ADAPTER — MovementFall.java:99 is a FOUR-part condition and the fourth is
        // `!AltoClefSettings.getInstance().shouldNotPlaceBucketButStillFall()` (AltoClefSettings.java:143-147,
        // reading the `_dontPlaceBucketButStillFall` flag). Tungsten has no AltoClefSettings and no
        // equivalent flag anywhere. The first version of this port DROPPED the conjunct, arguing
        // its default is false so `!false` reproduces upstream at rest. That argument is wrong in
        // THIS repository: AltoClef.java:681 calls configurePlaceBucketButDontFall(true)
        // UNCONDITIONALLY at init, so upstream's fourth conjunct is permanently false here and
        // shredder never enters this branch at all. Dropping it did not reproduce upstream — it
        // inverted it, and put two MLG owners in the same tick, which is precisely what altoclef
        // sets that flag to prevent. Restored under a name tungsten has.
        if (!isWater && willPlaceBucket() && !playerFeet.equals(dest)
                && kaptainwutax.tungsten.TungstenConfig.get().allowBucketMlg) {
            if (!PlayerInventory.isValidHotbarIndex(player.getInventory().getSlotWithStack(STACK_BUCKET_WATER))
                    || ctx.world().getRegistryKey() == World.NETHER) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }

            if (player.getEntityPos().y - dest.getY() < ctx.blockReachDistance() && !player.isOnGround()) {
                player.getInventory().setSelectedSlot(player.getInventory().getSlotWithStack(STACK_BUCKET_WATER));

                targetRotation = new Rotation(toDest.getYaw(), 90.0F);

                if (ctx.isLookingAt(dest) || ctx.isLookingAt(dest.below())) {
                    state.setInput(Input.CLICK_RIGHT, true);
                }
            }
        }
        if (targetRotation != null) {
            state.setTarget(new MovementState.MovementTarget(targetRotation, true));
        } else {
            state.setTarget(new MovementState.MovementTarget(toDest, false));
        }
        if (playerFeet.equals(dest) && (player.getEntityPos().y - playerFeet.getY() < 0.094 || isWater)) { // 0.094 because lilypads
            if (isWater) { // only match water, not flowing water (which we cannot pick up with a bucket)
                if (PlayerInventory.isValidHotbarIndex(player.getInventory().getSlotWithStack(STACK_BUCKET_EMPTY))) {
                    player.getInventory().setSelectedSlot(player.getInventory().getSlotWithStack(STACK_BUCKET_EMPTY));
                    if (player.getVelocity().y >= 0) {
                        return state.setInput(Input.CLICK_RIGHT, true);
                    } else {
                        return state;
                    }
                } else {
                    if (player.getVelocity().y >= 0) {
                        return state.setStatus(MovementStatus.SUCCESS);
                    } // don't else return state; we need to stay centered because this water might be flowing under the surface
                }
            } else {
                return state.setStatus(MovementStatus.SUCCESS);
            }
        }
        Vec3d destCenter = RotationHelper.getBlockPosCenter(dest); // we are moving to the 0.5 center not the edge (like if we were falling on a ladder)
        if (Math.abs(player.getEntityPos().x + player.getVelocity().x - destCenter.x) > 0.1
                || Math.abs(player.getEntityPos().z + player.getVelocity().z - destCenter.z) > 0.1) {
            if (!player.isOnGround() && Math.abs(player.getVelocity().y) > 0.4) {
                state.setInput(Input.SNEAK, true);
            }
            state.setInput(Input.MOVE_FORWARD, true);
        }
        Vec3i avoid = Optional.ofNullable(avoid()).map(Direction::getVector).orElse(null);
        if (avoid == null) {
            avoid = src.subtract(dest);
        } else {
            double dist = Math.abs(avoid.getX() * (destCenter.x - avoid.getX() / 2.0 - player.getEntityPos().x))
                    + Math.abs(avoid.getZ() * (destCenter.z - avoid.getZ() / 2.0 - player.getEntityPos().z));
            if (dist < 0.6) {
                state.setInput(Input.MOVE_FORWARD, true);
            } else if (!player.isOnGround()) {
                // Revokes the SNEAK the steer above may have set — MovementState.setInput overwrites,
                // and branch order is the arbiter (MovementState.java:24-27). Not a no-op.
                state.setInput(Input.SNEAK, false);
            }
        }
        if (targetRotation == null) {
            Vec3d destCenterOffset = new Vec3d(destCenter.x + 0.125 * avoid.getX(), destCenter.y,
                    destCenter.z + 0.125 * avoid.getZ());
            state.setTarget(new MovementState.MovementTarget(
                    RotationHelper.calcRotationFromVec3d(ctx.playerHead(), destCenterOffset, ctx.playerRotations()),
                    false));
        }
        return state;
    }

    /**
     * MovementFall.java:162-170. The FIRST ladder in the 15 cells below the feet, or null. Fifteen is
     * upstream's number and it is deliberately larger than any fall this planner emits: the ladder
     * that catches you is not necessarily the one at the landing cell.
     */
    private Direction avoid() {
        for (int i = 0; i < 15; i++) {
            BlockState state = ctx.world().getBlockState(ctx.playerFeet().below(i));
            if (state.getBlock() == Blocks.LADDER) {
                return state.get(LadderBlock.FACING);
            }
        }
        return null;
    }

    /** MovementFall.java:172-177. Upstream declares it public; {@link Movement} declares it
     *  protected, and widening is what {@link MovementAscend#safeToCancel} does too. */
    @Override
    public boolean safeToCancel(MovementState state) {
        // if we haven't started walking off the edge yet, or if we're in the process of breaking blocks before doing the fall
        // then it's safe to cancel this
        return ctx.playerFeet().equals(src) || state.getStatus() != MovementStatus.RUNNING;
    }

    /**
     * MovementFall.java:179-189, verbatim including the arithmetic that cancels out (see the class
     * header). The result is the DESTINATION column from {@code src.y + 1} down to {@code dest.y},
     * {@code |dy| + 2} cells, top-first.
     */
    private static BetterBlockPos[] buildPositionsToBreak(BetterBlockPos src, BetterBlockPos dest) {
        BetterBlockPos[] toBreak;
        int diffX = src.getX() - dest.getX();
        int diffZ = src.getZ() - dest.getZ();
        int diffY = Math.abs(src.getY() - dest.getY());
        toBreak = new BetterBlockPos[diffY + 2];
        for (int i = 0; i < toBreak.length; i++) {
            toBreak[i] = new BetterBlockPos(src.getX() - diffX, src.getY() + 1 - i, src.getZ() - diffZ);
        }
        return toBreak;
    }

    /**
     * MovementFall.java:191-204. Two quirks, both copied:
     * <ul>
     *   <li>the comment says "only break if one of the first three needs to be broken" and the loop
     *       bound is {@code i < 4} — FOUR cells. Upstream's own off-by-one; the loop is what runs;</li>
     *   <li>once ANY of those four is blocked it delegates to {@code super.prepared}, which then
     *       requires the WHOLE column clear, including the last cell it just said to ignore. So
     *       "specifically ignore the last one which might be water" holds only while the top of the
     *       column is already clear.</li>
     * </ul>
     * The early {@code WAITING} return is upstream's too and is what lets a fall that is already
     * under way skip the break pass entirely.
     */
    @Override
    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        // only break if one of the first three needs to be broken
        // specifically ignore the last one which might be water
        for (int i = 0; i < 4 && i < positionsToBreak.length; i++) {
            if (!MovementHelperB.canWalkThrough(ctx.world(), positionsToBreak[i])) {
                return super.prepared(state);
            }
        }
        return true;
    }

    /**
     * {@code context.get(x, y, z)}. Caller-owned scratch position, reused within one call; only
     * BlockStates escape, never the position — same convention as {@code MovementPillar.stateAt}.
     */
    private static BlockState stateAt(WorldView world, BlockPos.Mutable scratch, int x, int y, int z) {
        return world.getBlockState(scratch.set(x, y, z));
    }
}
