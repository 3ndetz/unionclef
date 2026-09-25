package kaptainwutax.tungsten.path;

import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * ONE definition of "this step is lethal", shared by the planners and the executors -- ported
 * from baritone, where the same question is answered once in {@code MovementHelper} and asked by
 * every movement's cost AND again by the executor every tick.
 *
 * <p>WHY THIS EXISTS (G108 nether stage, 2026-09-23). The nether deaths were patched one driver at
 * a time: a lava gate on the walker's DIRECT mode (0.95.31, measured dirHzd=0 -- the drive never
 * enters that mode), then a lava-column gate on its BFS mode (0.95.32, fired 106 times and the bot
 * still died -- under the physics executor this time, exec1). Every driver that had not been
 * patched yet was a way in. Baritone never had this problem because it does not patch drivers:
 * <ul>
 *   <li>{@code MovementHelper.avoidWalkingInto} (baritone MovementHelper.java:420-431) is the one
 *       hazard predicate;</li>
 *   <li>each movement's COST refuses it: Traverse the cells walked into (:186/189), Diagonal the
 *       two corner cells AND the lava/magma under them (:158/162, :188/189), Parkour the gap floor
 *       and the landing (:81, :205), Descend/Fall a landing in lava unless allowFallIntoLava
 *       (:180 -- false upstream);</li>
 *   <li>{@code PathExecutor} (baritone PathExecutor.java:196-210) RE-COSTS the current movement
 *       and the next {@code costVerificationLookahead} ones EVERY TICK and cancels the path the
 *       moment one becomes impossible -- which is what catches a world that changed or a body
 *       that is not where the plan put it.</li>
 * </ul>
 * This class is the tungsten form of the first two; {@link #segmentLethal} is what the executors
 * call to do the third.
 *
 * <p>Water is deliberately NOT a hazard here, unlike baritone's blanket fluid clause: tungsten
 * swims (nav_water), and FastPlanner already made the same call. The void is not refused either --
 * a gap jump over the void is a real route (nav_gaps) with its own gates; lava below is not.
 */
public final class RouteHazards {

    private RouteHazards() {}

    /** How far below the feet a column is scanned for lava before it is called safe. */
    public static final int LAVA_COLUMN_DEPTH = 32;

    /** Counters: segments refused, by caller. Counted whenever the verdict is "lethal". */
    public static volatile int refusedWalker = 0, refusedExecutor = 0, refusedQueue = 0;
    /**
     * Every call of {@link #segmentLethal}. A refusal count of zero is ambiguous until this says the
     * check RAN (checklist RULE ONE's mirror image): measured 0.95.33, three lava entries under the
     * executors and every refusal counter at 0, which by itself cannot tell "checked and found
     * nothing" from "never asked".
     */
    public static volatile int segmentsChecked = 0;

    /**
     * baritone {@code avoidWalkingInto}, minus water. A body must never occupy, or stand on, a cell
     * for which this is true.
     */
    public static boolean hazard(BlockState state) {
        if (state.getFluidState().isIn(FluidTags.LAVA)) return true;
        var b = state.getBlock();
        return b == net.minecraft.block.Blocks.MAGMA_BLOCK
                || b == net.minecraft.block.Blocks.CACTUS
                || b == net.minecraft.block.Blocks.SWEET_BERRY_BUSH
                || b instanceof net.minecraft.block.AbstractFireBlock
                || b instanceof net.minecraft.block.EndPortalFrameBlock
                || b == net.minecraft.block.Blocks.END_PORTAL
                || b == net.minecraft.block.Blocks.COBWEB
                || b instanceof net.minecraft.block.BubbleColumnBlock;
    }

    /**
     * Does this block stop a body passing through it, so a route must break it first? baritone
     * {@code MovementHelper.canWalkThroughBlockState} (MovementHelper.java:182-230): anything with a
     * collision box, plus POWDER_SNOW (:193), which has none for a player without leather boots and
     * so reads as air to a collision test -- a floor that is not there, and a cell that freezes.
     *
     * <p>⛔ POWDER SNOW USED TO BE IN {@link #hazard} (lethal), which baritone never did (it is not in
     * avoidWalkingInto). Measured 2026-09-24: a bot that fell into a two-deep patch broke its own
     * column free and then stood at the bottom for good -- every way out ran through powder snow, the
     * planners refused all of it as lethal, and none would plan the cheap break (hardness 0.25) that
     * baritone plans. Now it is what baritone says: an obstacle to break, never a floor (no collision,
     * so supportTop finds none -- baritone canWalkOn is false for it too).
     */
    public static boolean blocksBody(BlockState st, WorldView w, BlockPos p) {
        return st.getBlock() == net.minecraft.block.Blocks.POWDER_SNOW
                || !st.getCollisionShape(w, p).isEmpty();
    }

    public static boolean hazard(WorldView w, BlockPos p) {
        return hazardAt(w, p.getX(), p.getY(), p.getZ(), new BlockPos.Mutable());
    }

    /**
     * baritone {@code AltoClefSettings.canSwimThroughLava} (baritone MovementHelper.java:166): while
     * set, a LAVA cell with no fluid above it is walked through like water. Only the lava escape
     * sets it -- a body that is already burning is better off crossing two blocks of lava surface
     * to the shore than standing still.
     *
     * <p>⛔ WHY THIS CAME BACK (2026-09-25). The port dropped the clause (MovementHelperB
     * canWalkThrough: "no tungsten equivalent"), so while escaping every planner refused every
     * lava cell, the block-space searches found no route out of a pool, and the escape fell through
     * to the physics search -- which took 10.97 s to answer with the body in lava at 5 hearts, on a
     * nether playthrough (checkpoint nether-nofood). The bot burned to death two blocks from dry
     * ground.
     */
    public static volatile boolean lavaSwim = false;

    /** Is (x, y, z) lava that the escape may swim through right now? */
    public static boolean swimmableLava(WorldView w, int x, int y, int z, BlockPos.Mutable s) {
        if (!lavaSwim) return false;
        if (!w.getBlockState(s.set(x, y, z)).getFluidState().isIn(FluidTags.LAVA)) return false;
        return w.getBlockState(s.set(x, y + 1, z)).getFluidState().isEmpty();
    }

    /** {@link #hazard(BlockState)} at a position, minus {@link #swimmableLava} while escaping. */
    public static boolean hazardAt(WorldView w, int x, int y, int z, BlockPos.Mutable s) {
        if (!hazard(w.getBlockState(s.set(x, y, z)))) return false;
        return !swimmableLava(w, x, y, z, s);
    }

    /**
     * Is standing with the feet in column (x, z) at height {@code feetY} lethal? True if the feet
     * or head cell is a hazard, the cell stood ON is a hazard, or -- when there is nothing to stand
     * on -- the column ends in LAVA before it reaches a solid block (baritone's Descend/Fall
     * landing rule with allowFallIntoLava=false, and Parkour's refusal of a lava gap floor).
     */
    public static boolean lethalColumn(WorldView w, int x, int feetY, int z, BlockPos.Mutable s) {
        if (hazardAt(w, x, feetY, z, s)) return true;
        if (hazardAt(w, x, feetY + 1, z, s)) return true;
        int bottom = w.getBottomY();
        for (int y = feetY - 1; y >= Math.max(bottom, feetY - LAVA_COLUMN_DEPTH); y--) {
            if (hazardAt(w, x, y, z, s)) return true;                 // magma floor, or lava below
            BlockState st = w.getBlockState(s.set(x, y, z));
            // Walking ONTO powder snow sinks the body into it (no collision): a free-form driver
            // stepping onto a snow field is exactly how the 0.95.41 playthrough froze to death.
            if (st.getBlock() == net.minecraft.block.Blocks.POWDER_SNOW) return true;
            if (!st.getCollisionShape(w, s).isEmpty()) return false;  // solid ground first: safe
        }
        return false;                                                 // void or deep: not ours
    }

    /**
     * Would moving the body in a straight line from {@code a} to {@code b} pass through a lethal
     * column? Samples every 0.3 blocks, and at every change of cell also the two CORNER columns
     * the body's 0.6 width brushes when it crosses diagonally (baritone Diagonal :158/162) -- the
     * route between two safe waypoints is exactly where a cut corner puts the feet in lava.
     */
    public static boolean segmentLethal(WorldView w, Vec3d a, Vec3d b) {
        segmentsChecked++;
        BlockPos.Mutable s = new BlockPos.Mutable();
        double dx = b.x - a.x, dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        int n = Math.max(1, (int) Math.ceil(len / 0.3));
        int feetY = MathHelper.floor(Math.min(a.y, b.y) + 0.01);
        int px = MathHelper.floor(a.x), pz = MathHelper.floor(a.z);
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            int x = MathHelper.floor(a.x + dx * t), z = MathHelper.floor(a.z + dz * t);
            int y = MathHelper.floor(a.y + (b.y - a.y) * t + 0.01);
            if (x == px && z == pz) continue;
            if (lethalColumn(w, x, Math.max(y, feetY), z, s)) return true;
            if (x != px && z != pz) {                                 // a diagonal cell change
                if (lethalColumn(w, x, Math.max(y, feetY), pz, s)) return true;
                if (lethalColumn(w, px, Math.max(y, feetY), z, s)) return true;
            }
            px = x; pz = z;
        }
        return false;
    }
}
