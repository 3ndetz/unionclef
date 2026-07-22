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

    /**
     * Would advancing horizontally from {@code pos} in direction (dx,dz) step off
     * a drop deeper than {@code maxSafeFall}, while {@code pos} itself is on safe
     * ground? Samples a few short distances ahead at feet level.
     *
     * This is the core void-safety primitive for FREE-FORM movement (combat aura,
     * LEAP) — it keeps the bot on bridges/islands by refusing forward motion that
     * leaves solid ground. Pathfinder moves are NOT gated by this (the block-space
     * search already routes over solid cells and may descend deliberately).
     *
     * @return true if a step ahead lands over a serious drop (caller should not advance)
     */
    public static boolean edgeAhead(Vec3d pos, double dx, double dz, WorldView world, int maxSafeFall) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) return false;
        dx /= len; dz /= len;
        // If we're already over a drop (mid-parkour/descent), this guard doesn't apply.
        if (fallHeight(pos, world) > maxSafeFall) return false;
        for (double d : new double[]{0.55, 0.9, 1.35}) {
            Vec3d ahead = new Vec3d(pos.x + dx * d, pos.y, pos.z + dz * d);
            if (fallHeight(ahead, world) > maxSafeFall) return true;
        }
        return false;
    }

    /** True if any cell within Chebyshev {@code radius} of pos (feet level) drops
     *  more than {@code maxSafeFall} — a wider "am I near a ledge" test used to
     *  cut sprint/jump before momentum builds toward the rim. */
    public static boolean voidWithin(Vec3d pos, WorldView world, int radius, int maxSafeFall) {
        int cx = MathHelper.floor(pos.x), cz = MathHelper.floor(pos.z);
        int y = MathHelper.floor(pos.y);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (fallHeight(new Vec3d(cx + dx + 0.5, y, cz + dz + 0.5), world) > maxSafeFall) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * Like edgeScore but counts neighbor as unsafe if fall > maxSafeFall blocks.
     * Used for NARROW_BATTLE detection where 5+ block drops are dangerous, not just void.
     */
    public static double edgeScoreWithFallThreshold(Vec3d pos, WorldView world, int maxSafeFall) {
        int x = MathHelper.floor(pos.x);
        int y = MathHelper.floor(pos.y);
        int z = MathHelper.floor(pos.z);

        int unsafeCount = 0;
        int[][] offsets = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int[] off : offsets) {
            int fall = fallHeight(new Vec3d(x + off[0] + 0.5, y, z + off[1] + 0.5), world);
            if (fall > maxSafeFall) {
                unsafeCount++;
            }
        }
        return unsafeCount / 8.0;
    }
}
