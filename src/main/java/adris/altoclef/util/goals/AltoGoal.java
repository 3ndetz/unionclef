package adris.altoclef.util.goals;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Where the bot is trying to get to, owned by altoclef rather than by a pathfinder.
 *
 * <h2>Why this exists</h2>
 *
 * G-0 is "stop depending on baritone", and the thing that actually holds altoclef to it is not an
 * algorithm — it is a TYPE. Counted across src/main: {@code baritone.api.pathing.goals.Goal} in 23
 * files, plus GoalNear, GoalBlock, GoalYLevel, GoalXZ and GoalRunAway in a dozen more. Every task
 * that wants to walk somewhere names a baritone class to say so, which is why the dependency
 * cannot be removed file by file.
 *
 * <p>A goal only ever answers two questions, and neither of them needs a pathfinder:
 * <ul>
 *   <li>WHERE should the bot head? — {@link #target()}, which is what the tungsten drive already
 *       reduces every baritone goal to (see {@code CustomBaritoneGoalTask.goalToVec}, a chain of
 *       instanceof over six goal classes that exists purely to recover this vector);</li>
 *   <li>ARE WE THERE? — {@link #reached(BlockPos)}.</li>
 * </ul>
 *
 * <p>With those two the drive needs no knowledge of goal classes at all, and a task can be moved
 * over one at a time while everything still compiles — the migration is mechanical rather than a
 * flag day.
 */
public interface AltoGoal {

    /** The point to head for, in world coordinates. Never null. */
    Vec3d target();

    /**
     * Is this position good enough to call the goal met?
     *
     * <p>The default is the honest general case: the same block. Goals with a radius, a Y-level or
     * a keep-away rule override it, and that is the ONLY place tolerance lives — a caller never has
     * to guess what "close enough" meant for a particular goal.
     */
    default boolean reached(BlockPos pos) {
        Vec3d t = target();
        return pos.getX() == (int) Math.floor(t.x)
                && pos.getY() == (int) Math.floor(t.y)
                && pos.getZ() == (int) Math.floor(t.z);
    }

    /** A goal that is simply a block. */
    static AltoGoal block(BlockPos pos) {
        return new AltoGoal() {
            @Override
            public Vec3d target() {
                return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            }

            @Override
            public String toString() {
                return "block(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
            }
        };
    }

    /** A goal that is a block, satisfied from anywhere within {@code range} of it. */
    static AltoGoal near(BlockPos pos, int range) {
        return new AltoGoal() {
            @Override
            public Vec3d target() {
                return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            }

            @Override
            public boolean reached(BlockPos at) {
                return at.getSquaredDistance(pos) <= (double) range * range;
            }

            @Override
            public String toString() {
                return "near(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " r=" + range + ")";
            }
        };
    }

    /** A goal on the horizontal plane only — any Y will do. */
    static AltoGoal xz(int x, int z) {
        return new AltoGoal() {
            @Override
            public Vec3d target() {
                // The Y is filled in by the caller from the player, because an XZ goal genuinely
                // has none; the drive treats it as "keep your height, change your ground".
                return new Vec3d(x + 0.5, Double.NaN, z + 0.5);
            }

            @Override
            public boolean reached(BlockPos at) {
                return at.getX() == x && at.getZ() == z;
            }

            @Override
            public String toString() {
                return "xz(" + x + "," + z + ")";
            }
        };
    }

    /** A goal that is a height, wherever you happen to stand. */
    static AltoGoal yLevel(int y) {
        return new AltoGoal() {
            @Override
            public Vec3d target() {
                return new Vec3d(Double.NaN, y, Double.NaN);
            }

            @Override
            public boolean reached(BlockPos at) {
                return at.getY() == y;
            }

            @Override
            public String toString() {
                return "y(" + y + ")";
            }
        };
    }
}
