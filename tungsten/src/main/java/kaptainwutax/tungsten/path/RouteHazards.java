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
                || b == net.minecraft.block.Blocks.POWDER_SNOW
                || b instanceof net.minecraft.block.BubbleColumnBlock;
    }

    public static boolean hazard(WorldView w, BlockPos p) {
        return hazard(w.getBlockState(p));
    }

    /**
     * Is standing with the feet in column (x, z) at height {@code feetY} lethal? True if the feet
     * or head cell is a hazard, the cell stood ON is a hazard, or -- when there is nothing to stand
     * on -- the column ends in LAVA before it reaches a solid block (baritone's Descend/Fall
     * landing rule with allowFallIntoLava=false, and Parkour's refusal of a lava gap floor).
     */
    public static boolean lethalColumn(WorldView w, int x, int feetY, int z, BlockPos.Mutable s) {
        if (hazard(w.getBlockState(s.set(x, feetY, z)))) return true;
        if (hazard(w.getBlockState(s.set(x, feetY + 1, z)))) return true;
        int bottom = w.getBottomY();
        for (int y = feetY - 1; y >= Math.max(bottom, feetY - LAVA_COLUMN_DEPTH); y--) {
            BlockState st = w.getBlockState(s.set(x, y, z));
            if (hazard(st)) return true;                              // magma floor, or lava below
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
