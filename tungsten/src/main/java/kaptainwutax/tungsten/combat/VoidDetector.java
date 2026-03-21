package kaptainwutax.tungsten.combat;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Checks terrain safety: void, edges, fall height.
 */
public final class VoidDetector {

    private static final int MAX_SCAN_DEPTH = 30;

    private VoidDetector() {}

    /** True if there is at least one solid block below pos within scan depth. */
    public static boolean isSafe(Vec3d pos, WorldView world) {
        return fallHeight(pos, world) < MAX_SCAN_DEPTH;
    }

    /**
     * How many blocks would you fall from this position before hitting ground?
     * Returns MAX_SCAN_DEPTH if void (no ground found).
     */
    public static int fallHeight(Vec3d pos, WorldView world) {
        int x = MathHelper.floor(pos.x);
        int z = MathHelper.floor(pos.z);
        int startY = MathHelper.floor(pos.y);
        int bottomY = world.getBottomY();

        for (int dy = 0; dy < MAX_SCAN_DEPTH; dy++) {
            int y = startY - dy;
            if (y < bottomY) return MAX_SCAN_DEPTH; // void
            BlockPos bp = new BlockPos(x, y, z);
            BlockState state = world.getBlockState(bp);
            if (!state.getCollisionShape(world, bp).isEmpty()) return dy;
        }
        return MAX_SCAN_DEPTH;
    }

    /**
     * Returns a 0-1 edge proximity score. 0 = safe (solid all around), 1 = surrounded by void.
     * Checks 8 neighbors at feet level.
     */
    public static double edgeScore(Vec3d pos, WorldView world) {
        int x = MathHelper.floor(pos.x);
        int y = MathHelper.floor(pos.y) - 1;
        int z = MathHelper.floor(pos.z);
        int bottomY = world.getBottomY();

        if (y < bottomY) return 1.0;

        int unsafeCount = 0;
        int[][] offsets = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int[] off : offsets) {
            if (!hasGroundAt(x + off[0], y, z + off[1], world, bottomY)) {
                unsafeCount++;
            }
        }
        return unsafeCount / 8.0;
    }

    private static boolean hasGroundAt(int x, int startY, int z, WorldView world, int bottomY) {
        for (int dy = 0; dy <= 2; dy++) {
            int y = startY - dy;
            if (y < bottomY) return false;
            BlockPos bp = new BlockPos(x, y, z);
            if (!world.getBlockState(bp).getCollisionShape(world, bp).isEmpty()) return true;
        }
        return false;
    }
}
