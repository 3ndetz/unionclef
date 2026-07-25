package kaptainwutax.tungsten.task;

import java.util.List;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.path.fast.FastPlanner;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Pipelined navigation: walk the current leg while the NEXT leg is already being
 * planned from the point where this one ENDS.
 *
 * The point is that the computation overlaps with the movement. The old flow
 * computed a whole physics path first and only then moved, so the bot stood
 * still while the machine thought (measured: 3.6 s to first step, and the search
 * deliberately slept while the executor walked). Here the first cheap leg starts
 * the bot in ~0.2 s, and every following leg is planned from the FUTURE position
 * (the tail of the leg being walked), so by the time the walker gets there the
 * continuation is ready and the hand-off costs nothing.
 *
 * Parkour is preserved: a waypoint the planner flagged {@code needsPhysics} (a
 * real gap jump) ends the walked leg, and the physics engine is asked for that
 * piece — the walker never sprints into a jump it cannot do.
 *
 * Toggle: TungstenConfig.fastBlockFirst (default true).
 */
public final class FastNavigator {

    /** How far ahead one leg reaches before we re-plan (blocks, approx). */
    private static final int LEG_LENGTH = 32;
    /** Arrived-at-goal tolerance. */
    private static final double ARRIVE_DIST = 2.0;
    /** Bail if the bot stops making progress for this many ticks. */
    private static final int STALL_TICKS = 60;
    /**
     * How much closer to the goal an INCOMPLETE plan must get us before it is
     * worth walking. This is what separates "the budget ran out on a long route"
     * (the plan still marches tens of blocks toward the goal — walk it) from
     * "walking cannot solve this at all" (a slime drop-bounce, a parkour gap:
     * the plan dead-ends within a couple of blocks — stand down and let the
     * physics engine, already searching in parallel, own the route).
     * Both cases are stand-proven: judging by waypoint COUNT instead broke the
     * bench (19.2s vs 16.4s baritone) while judging by progress keeps both the
     * slime course and the speed win.
     */
    private static final double MIN_PARTIAL_PROGRESS = 4.0;

    private static volatile boolean active = false;
    private static Vec3d goal = null;
    /** The leg computed ahead of time, ready to hand to the walker. */
    private static volatile List<BlockPos> nextLeg = null;
    private static volatile boolean nextLegNeedsPhysics = false;
    private static volatile boolean planning = false;
    private static BlockPos legTail = null;
    private static int stallTicks = 0;
    private static double lastDist = Double.MAX_VALUE;

    private FastNavigator() {}

    public static boolean isActive() { return active; }

    public static void start(Vec3d target) {
        stop();
        goal = target;
        active = true;
        stallTicks = 0;
        lastDist = Double.MAX_VALUE;
        planAhead(TungstenMod.mc.player != null
                ? TungstenMod.mc.player.getBlockPos() : BlockPos.ofFloored(target));
    }

    public static void stop() {
        active = false;
        goal = null;
        nextLeg = null;
        legTail = null;
        nextLegNeedsPhysics = false;
    }

    /** Ticked from the client mixin alongside the other tungsten tasks. */
    public static void tick(ClientPlayerEntity player) {
        if (!active || player == null || goal == null) return;

        double dist = player.getEntityPos().distanceTo(goal);
        if (dist <= ARRIVE_DIST) {
            Debug.logMessage("FastNavigator: arrived (" + String.format("%.1f", dist) + ")");
            BlockPathWalker.stop();
            stop();
            return;
        }

        // progress watchdog: the physics engine or a re-plan owns recovery, but a
        // navigator that silently stops is the failure the user reported, so make
        // it loud and let the caller (goto retry / physics search) take over.
        if (dist < lastDist - 0.25) {
            lastDist = dist;
            stallTicks = 0;
        } else if (++stallTicks > STALL_TICKS) {
            Debug.logWarning("FastNavigator: no progress, handing over");
            stop();
            return;
        }

        if (BlockPathWalker.isRunning()) {
            // walking: make sure the FOLLOWING leg is being computed from the tail
            if (nextLeg == null && !planning && legTail != null) planAhead(legTail);
            return;
        }

        // the walker is idle — start the leg that was prepared while we walked
        List<BlockPos> leg = nextLeg;
        if (leg != null && leg.size() >= 2) {
            nextLeg = null;
            legTail = leg.get(leg.size() - 1);
            BlockPathWalker.startBFS(leg);
            // immediately begin planning the leg after this one, from its tail
            planAhead(legTail);
            return;
        }
        if (!planning) {
            planAhead(player.getBlockPos());
        }
    }

    /**
     * Plan the next leg starting FROM {@code from} — which is normally a cell the
     * bot has not reached yet. This is the overlap that makes the whole thing
     * fast: the search for the next piece runs while the current piece is walked.
     */
    private static void planAhead(BlockPos from) {
        if (planning || goal == null) return;
        planning = true;
        final BlockPos start = from;
        final Vec3d target = goal;
        Thread t = new Thread(() -> {
            try {
                var world = TungstenMod.mc.world;
                if (world == null) return;
                BlockPos goalCell = BlockPos.ofFloored(target);
                FastPlanner.Result res = FastPlanner.plan(world, start, goalCell,
                        TungstenConfig.get().fastPlanBudgetMs);
                if (!active || res.isEmpty() || res.path.size() < 2) return;

                // Walking cannot solve this route — hand it to the physics engine
                // (already searching in parallel) and get out of its way.
                if (!res.complete) {
                    BlockPos tail = res.path.get(res.path.size() - 1).pos;
                    double before = Math.sqrt(start.getSquaredDistance(goalCell));
                    double after = Math.sqrt(tail.getSquaredDistance(goalCell));
                    if (before - after < MIN_PARTIAL_PROGRESS) {
                        Debug.logMessage(String.format(
                                "FastNavigator: walking dead-ends (%.1f -> %.1f) -> physics owns this",
                                before, after));
                        BlockPathWalker.stop();
                        stop();
                        return;
                    }
                }

                List<BlockPos> cells = res.positions();
                // cut at the first waypoint that needs a real jump: the physics
                // engine owns those (parkour), the walker must not run into one
                int physics = res.firstPhysicsIndex();
                if (physics > 0 && physics < cells.size()) {
                    cells = cells.subList(0, physics);
                    nextLegNeedsPhysics = true;
                } else {
                    nextLegNeedsPhysics = false;
                    if (cells.size() > LEG_LENGTH) cells = cells.subList(0, LEG_LENGTH);
                }
                if (cells.size() >= 2) nextLeg = cells;
            } catch (Exception e) {
                Debug.logWarning("FastNavigator plan failed: " + e.getMessage());
            } finally {
                planning = false;
            }
        });
        t.setName("FastNavigator-plan");
        t.setDaemon(true);
        t.start();
    }
}
