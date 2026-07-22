package kaptainwutax.tungsten.path;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Snap a navigation goal to the nearest STANDABLE cell.
 *
 * A goal on a non-standable cell — floating in the air, or on a no-collision plant
 * like tall grass / a flower (a click reports the plant's own cell, which has no
 * floor to stand on) — can never be reached exactly, so the physics search re-roots
 * near it forever and the bot "computes" without ever arriving. Snapping the goal to
 * the surface above a solid block, or down to the ground beneath the air/plant, makes
 * it a real, reachable target. Valid standable goals are returned unchanged, so normal
 * navigation is untouched.
 */
public final class GoalSnap {

    private GoalSnap() {}

    private static boolean solid(WorldView w, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        return !w.getBlockState(p).getCollisionShape(w, p).isEmpty();
    }

    /** Feet can occupy (x,y,z): solid floor below, body + head clear. */
    public static boolean standable(WorldView w, int x, int y, int z) {
        return solid(w, x, y - 1, z) && !solid(w, x, y, z) && !solid(w, x, y + 1, z);
    }

    /** Snap {@code gp} to the nearest standable cell in its column. Already-standable
     *  goals are returned unchanged. If the column has nothing standable within reach
     *  (a goal over the void), the original is returned — the search bound then gives
     *  up instead of spinning. */
    public static Vec3d snap(Vec3d gp, WorldView w) {
        if (gp == null || w == null) return gp;
        int gx = (int) Math.floor(gp.x), gy = (int) Math.floor(gp.y), gz = (int) Math.floor(gp.z);
        if (standable(w, gx, gy, gz)) return gp;                  // already fine
        if (solid(w, gx, gy, gz)) {                               // inside a block → stand on top
            for (int y = gy + 1; y <= gy + 5; y++)
                if (standable(w, gx, y, gz)) return new Vec3d(gx + 0.5, y, gz + 0.5);
        }
        for (int y = gy; y >= gy - 8; y--)                        // air/plant → drop to the ground
            if (standable(w, gx, y, gz)) return new Vec3d(gx + 0.5, y, gz + 0.5);
        return gp;                                                // nothing standable — leave it
    }
}
