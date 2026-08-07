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
 *
 * <p>The shapes are RECORDS rather than anonymous classes on purpose: while the legacy baritone
 * fallback is still wired up, one adapter has to translate a goal back into baritone's vocabulary,
 * and it can only do that if the shape is still visible. They also compare and print sensibly,
 * which the drive's debug lines rely on.
 */
public interface AltoGoal {

    /**
     * The point to head for, in world coordinates.
     *
     * <p>Null when the goal genuinely cannot name a point right now — a flee goal with nothing to
     * flee from is the real case — and the drive reads that as "no route this tick" rather than as
     * an error. The shapes below always return one.
     */
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
        return t != null
                && pos.getX() == (int) Math.floor(t.x)
                && pos.getY() == (int) Math.floor(t.y)
                && pos.getZ() == (int) Math.floor(t.z);
    }

    /** A goal that is simply a block. */
    record Block(BlockPos pos) implements AltoGoal {
        @Override
        public Vec3d target() {
            return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        }

        @Override
        public boolean reached(BlockPos at) {
            return at.getX() == pos.getX() && at.getY() == pos.getY() && at.getZ() == pos.getZ();
        }

        @Override
        public String toString() {
            return "block(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
        }
    }

    /** A goal that is a block, satisfied from anywhere within {@code range} of it. */
    record Near(BlockPos pos, int range) implements AltoGoal {
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
    }

    /** A goal on the horizontal plane only — any Y will do. */
    record Xz(int x, int z) implements AltoGoal {
        @Override
        public Vec3d target() {
            // An XZ goal genuinely has no Y, so it names its own height as NaN and the drive fills
            // it in from the player: "keep your height, change your ground".
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
    }

    /** A goal that is a height, wherever you happen to stand. */
    record YLevel(int y) implements AltoGoal {
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
    }

    /**
     * A goal that is a whole CHUNK — anywhere inside it will do.
     *
     * <p>Ported from {@code adris.altoclef.util.baritone.GoalChunk}, which implemented baritone's
     * Goal purely to answer these two questions: head for the middle, and count any column inside
     * the sixteen-by-sixteen as arrived. Neither needs a pathfinder, so the type does not either.
     */
    record Chunk(int startX, int startZ) implements AltoGoal {
        @Override
        public Vec3d target() {
            // The centre of the chunk, and NaN for Y: a chunk goal has no height, exactly as the
            // XZ goal above has none, and the drive fills it in from the player.
            return new Vec3d(startX + 8.0, Double.NaN, startZ + 8.0);
        }

        @Override
        public boolean reached(BlockPos at) {
            return at.getX() >= startX && at.getX() <= startX + 15
                    && at.getZ() >= startZ && at.getZ() <= startZ + 15;
        }

        @Override
        public String toString() {
            return "chunk(" + (startX >> 4) + "," + (startZ >> 4) + ")";
        }
    }

    /**
     * A goal that is a DIRECTION rather than a place: keep going that way.
     *
     * <p>Ported from {@code util.baritone.GoalDirectionXZ}, whose {@code isInGoal} was literally
     * {@code return false} — you never arrive, you just keep walking — and whose heuristic rewarded
     * distance along the line and punished drift off it.
     *
     * <p>WHAT IS NOT CARRIED OVER, AND WHY. The side penalty was a RANKING term for baritone's A*,
     * which chose between candidate nodes. The tungsten drive does not rank; it steers at a point.
     * So the direction is expressed the way a drive can use it — a target far along the line — and
     * staying on that line falls out of steering toward it rather than out of a cost. If a future
     * search wants the penalty back it belongs in that search, not in the goal type.
     */
    record Direction(double originX, double originZ, double dirX, double dirZ) implements AltoGoal {
        /** Far enough that the bot never runs out of line before something else re-targets it. */
        private static final double PROJECTION = 128.0;

        @Override
        public Vec3d target() {
            return new Vec3d(originX + dirX * PROJECTION, Double.NaN, originZ + dirZ * PROJECTION);
        }

        @Override
        public boolean reached(BlockPos at) {
            return false;   // a direction is never arrived at, exactly as upstream had it
        }

        @Override
        public String toString() {
            return String.format("dir(%.1f,%.1f -> %.2f,%.2f)", originX, originZ, dirX, dirZ);
        }
    }

    /**
     * Get AWAY from some places — the first goal here that names no destination of its own.
     *
     * <p>Ported from baritone's {@code GoalRunAway}. Fleeing is a DIRECTION, not a place, which is
     * exactly the case {@link #target()} documents as legitimately having no point: upstream
     * expressed it as a heuristic that grew with distance from the danger, and a search could work
     * with that. A drive cannot — it steers at something.
     *
     * <p>So the CALLER computes where "away" is and hands over a finished point, and this stays a
     * pure record like every other shape here. Dragging the client into the type to read the
     * player's position would be the easy wrong move: it would make the goal's answer depend on
     * when you asked it.
     *
     * <p>{@code reached} keeps upstream's meaning exactly — clear of EVERY danger position, not
     * merely the nearest one.
     */
    record Flee(Vec3d away, java.util.List<BlockPos> from, double distance) implements AltoGoal {
        @Override
        public Vec3d target() {
            return away;
        }

        @Override
        public boolean reached(BlockPos at) {
            for (BlockPos danger : from) {
                if (at.getSquaredDistance(danger) < distance * distance) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return "flee(" + from.size() + " danger(s), d=" + distance + ")";
        }
    }

    /**
     * A flee goal aimed away from the danger, as seen from {@code standingAt}.
     *
     * @param maintainY hold this height, or null to keep the player's own (NaN, filled by the drive)
     */
    static AltoGoal flee(Vec3d standingAt, java.util.List<BlockPos> from, double distance,
                         Integer maintainY) {
        double cx = 0, cz = 0;
        for (BlockPos p : from) {
            cx += p.getX() + 0.5;
            cz += p.getZ() + 0.5;
        }
        cx /= from.size();
        cz /= from.size();
        double dx = standingAt.x - cx, dz = standingAt.z - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4) {
            // Standing exactly on the danger: any direction is equally away, so pick one rather
            // than dividing by zero and steering the bot at NaN.
            dx = 1;
            dz = 0;
            len = 1;
        }
        // Aim past the ring, so arriving at the point means the reached() test is satisfied.
        double reach = distance + 2;
        Vec3d away = new Vec3d(cx + dx / len * reach,
                maintainY != null ? maintainY : Double.NaN,
                cz + dz / len * reach);
        return new Flee(away, java.util.List.copyOf(from), distance);
    }

    /** {@code offset} need not be normalised; it is flattened to XZ and normalised here. */
    static AltoGoal direction(Vec3d origin, Vec3d offset) {
        Vec3d flat = offset.multiply(1, 0, 1).normalize();
        return new Direction(origin.getX(), origin.getZ(), flat.x, flat.z);
    }

    static AltoGoal chunk(int startX, int startZ) {
        return new Chunk(startX, startZ);
    }

    static AltoGoal block(BlockPos pos) {
        return new Block(pos);
    }

    static AltoGoal near(BlockPos pos, int range) {
        return new Near(pos, range);
    }

    static AltoGoal xz(int x, int z) {
        return new Xz(x, z);
    }

    static AltoGoal yLevel(int y) {
        return new YLevel(y);
    }
}
