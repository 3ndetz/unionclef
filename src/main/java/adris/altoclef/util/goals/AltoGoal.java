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
     * The nearest cell that SATISFIES A TEST — the shape every goal left in altoclef actually needs.
     *
     * <h2>Why one type rather than four ports</h2>
     *
     * Taken one at a time, the goals still holding a baritone type look like separate jobs. They
     * are not: every one of them is a PREDICATE rather than a place.
     *
     * <ul>
     *   <li>escape water — "not water, and not next to water"</li>
     *   <li>escape lava — "not lava, and not next to lava"</li>
     *   <li>flee — "further than N from every threat"</li>
     *   <li>dodge — "not on the arrow's line"</li>
     * </ul>
     *
     * <p>Baritone could consume those directly because it SEARCHED: a predicate is a perfectly good
     * goal function for A*, which visits candidate nodes and asks each one. The tungsten drive does
     * not search — it STEERS AT A POINT. That mismatch is what cost an earlier session 368 of 596
     * navigation entries and left the bot motionless for over four minutes, and it is why
     * {@code GoalRunAwayFromEntities} grew a {@code suggestFleePoint} and why {@link Flee} makes
     * its caller compute the point.
     *
     * <p>So the point search belongs in ONE place, done properly once, rather than open-coded per
     * goal: expansion order, a radius cap, and a cache so a target read every tick does not rescan
     * the world sixty times a second.
     *
     * <h2>Deliberately unused for now</h2>
     *
     * Nothing calls this yet. Wiring water, lava, flee and dodge onto it changes BEHAVIOUR on paths
     * the bot uses to survive, and that wants a pass which can watch nav_water and the mob suite
     * react. Adding the type alone cannot regress anything — no caller, no effect — and it leaves
     * the next pass four call sites instead of four designs.
     */
    final class NearestSatisfying implements AltoGoal {

        private final java.util.function.Predicate<BlockPos> satisfies;
        private final BlockPos origin;
        private final int maxRadius;

        /** Cached answer, and the tick it was computed on — see the note about rescanning. */
        private Vec3d cached;
        private long cachedAtTick = Long.MIN_VALUE;

        public NearestSatisfying(java.util.function.Predicate<BlockPos> satisfies, BlockPos origin,
                                 int maxRadius) {
            this.satisfies = satisfies;
            this.origin = origin;
            this.maxRadius = maxRadius;
        }

        @Override
        public Vec3d target() {
            // ONE SCAN PER TICK AT MOST. target() is read by the drive every tick, and an
            // unbounded rescan of a radius-N shell sixty times a second is how a goal type turns
            // into a frame-rate problem.
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            long tick = mc.world == null ? 0 : mc.world.getTime();
            if (tick == cachedAtTick) {
                return cached;
            }
            cachedAtTick = tick;
            cached = search();
            return cached;
        }

        /**
         * Expand outward until the test passes.
         *
         * <p>Radius-first, so the answer is the NEAREST satisfying cell rather than merely one that
         * satisfies; within a shell the vertical offsets come last, because walking sideways is
         * cheaper than climbing and a goal one block up is rarely what "get out of the water" means.
         */
        private Vec3d search() {
            if (satisfies.test(origin)) {
                return new Vec3d(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
            }
            for (int r = 1; r <= maxRadius; r++) {
                for (int dy = 0; dy <= r; dy++) {
                    for (int sy : dy == 0 ? new int[]{0} : new int[]{dy, -dy}) {
                        for (int dx = -r; dx <= r; dx++) {
                            for (int dz = -r; dz <= r; dz++) {
                                // Only the SHELL at this radius; the inside was covered already.
                                if (Math.max(Math.abs(dx), Math.abs(dz)) != r && Math.abs(sy) != r) {
                                    continue;
                                }
                                BlockPos at = origin.add(dx, sy, dz);
                                if (satisfies.test(at)) {
                                    return new Vec3d(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                                }
                            }
                        }
                    }
                }
            }
            // NOTHING WITHIN REACH SATISFIES IT. Null is the honest answer, and the drive already
            // reads a null target as "no route this tick" rather than as an error -- see target().
            return null;
        }

        @Override
        public boolean reached(BlockPos at) {
            return satisfies.test(at);
        }

        @Override
        public String toString() {
            return "nearest(r<=" + maxRadius + ")";
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
     * Get away from things that MOVE — the flee goal that survives being cached.
     *
     * <h2>Why {@link Flee} could not be used for this</h2>
     *
     * {@code Flee} is a record holding a finished point, and {@code CustomBaritoneGoalTask.goal()}
     * caches the goal object for the life of the task and never invalidates it. A cached record is
     * a FIXED destination: the bot would run to the spot the mobs were standing on when the task
     * started and stop there while they followed it.
     *
     * <p>Today's live behaviour does not come from the cache being refreshed — it comes from
     * {@code goalToVec} re-interrogating baritone's {@code GoalRunAwayFromEntities} every tick.
     * Any port that hands over a snapshot therefore changes behaviour even though the types line
     * up, which is exactly the trap recorded against this task in TODOS.
     *
     * <p>So this is a class rather than a record, and it recomputes {@link #target()} from live
     * positions — at most once per tick, the same guard {@link NearestSatisfying} uses, because
     * the drive reads the target every tick and an unguarded recompute is how a goal type turns
     * into a frame-rate problem.
     *
     * <h2>The direction, and why it is the centroid</h2>
     *
     * Upstream averaged UNIT vectors away from each threat. That cancels: two mobs on opposite
     * sides sum to nothing, the method returns null, the drive gets no point and the bot stands
     * still between them. Taking the heading from the centroid degrades into "any direction" in
     * that case instead of into "no direction", which is the behaviour a cornered bot needs.
     */
    final class FleeLive implements AltoGoal {

        private final java.util.function.Supplier<java.util.List<Vec3d>> dangers;
        private final java.util.function.Supplier<Vec3d> standingAt;
        private final double distance;

        private Vec3d cached;
        private long cachedAtTick = Long.MIN_VALUE;

        public FleeLive(java.util.function.Supplier<java.util.List<Vec3d>> dangers,
                        java.util.function.Supplier<Vec3d> standingAt, double distance) {
            this.dangers = dangers;
            this.standingAt = standingAt;
            this.distance = distance;
        }

        @Override
        public Vec3d target() {
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            long tick = mc.world == null ? 0 : mc.world.getTime();
            if (tick == cachedAtTick) {
                return cached;
            }
            cachedAtTick = tick;
            cached = compute();
            return cached;
        }

        private Vec3d compute() {
            java.util.List<Vec3d> from = dangers.get();
            Vec3d me = standingAt.get();
            if (from == null || from.isEmpty() || me == null) {
                // NOTHING TO FLEE IS NOT A PLACE TO GO. Null tells the drive "no target", which is
                // what the nav courses see -- they run on PEACEFUL, so this branch is where every
                // flee goal lands there and why nav cannot be moved by this change.
                return null;
            }
            double cx = 0, cz = 0;
            for (Vec3d p : from) {
                cx += p.x;
                cz += p.z;
            }
            cx /= from.size();
            cz /= from.size();
            double dx = me.x - cx, dz = me.z - cz;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0E-4) {
                dx = 1;
                dz = 0;
                len = 1;
            }
            double reach = distance + 2;
            return new Vec3d(cx + dx / len * reach, me.y, cz + dz / len * reach);
        }

        @Override
        public boolean reached(BlockPos at) {
            java.util.List<Vec3d> from = dangers.get();
            if (from == null || from.isEmpty()) {
                return true;
            }
            for (Vec3d danger : from) {
                double dx = at.getX() + 0.5 - danger.x;
                double dz = at.getZ() + 0.5 - danger.z;
                if (dx * dx + dz * dz < distance * distance) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            java.util.List<Vec3d> from = dangers.get();
            return "fleeLive(" + (from == null ? 0 : from.size()) + " danger(s), d=" + distance + ")";
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
