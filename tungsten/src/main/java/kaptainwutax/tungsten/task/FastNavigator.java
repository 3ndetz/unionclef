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
    /** Arrived-at-goal tolerance. Public: Py4jEntryPoint.pathStatus() reads this directly so its
     *  own "arrived" field can never drift from the engine's actual arrival threshold again (it
     *  used to hardcode a stricter 1.5, which left pathStatus reporting arrived=false forever on
     *  any goto FastNavigator had already completed and stopped between 1.5 and 2.0 blocks out —
     *  reproduced live 2026-09-01, see TODOS.md). */
    public static final double ARRIVE_DIST = 2.0;
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
    /**
     * When set, arrival means STANDING IN THIS CELL, not "within {@link #ARRIVE_DIST} of it" —
     * baritone's {@code GoalBlock}, whose {@code isInGoal} compares block coordinates and nothing
     * else. Every goal here was a two-block sphere, which is the right answer for "go over there"
     * and useless for "stand exactly here": the builder asked to stand in a cell 2.5 blocks away,
     * the navigator walked half a block, declared arrival at 2.0 and stopped — then the builder
     * asked again, and was instantly "arrived" from the same spot, over and over.
     */
    private static BlockPos exactCell = null;
    /** G55: the exact cell was armed by the altoclef DRIVE (a solid block goal to be dug into),
     *  not by the builder -- the drive's own bookkeeping (adoption, "the route dies with its
     *  drive") applies to it, the builder's yield does not. */
    private static boolean exactFromDrive = false;
    /** The leg computed ahead of time, ready to hand to the walker. */
    private static volatile List<BlockPos> nextLeg = null;
    /** The next leg ends at a jump the walker cannot do; this is where it lands. */
    private static volatile BlockPos nextPhysicsTarget = null;
    /** Same, for the leg currently being WALKED — consumed when the walker goes idle. */
    private static volatile BlockPos pendingPhysicsTarget = null;
    /** True while the physics engine is performing a jump we handed it. */
    private static volatile boolean awaitingPhysics = false;
    /** G49: the planning thread asked the navigator to give the route up (a goal below with no
     *  walkable partial); the tick honours it on the client thread. */
    private static volatile boolean pendingGiveUp = false;
    /** G49: partials walked because they were long enough, and routes given up because a goal
     *  below had no partial at all. Read navPartial=walked/noneBelow. */
    public static volatile int navPartialWalked, navNoPartialBelow;
    /**
     * Far side of a slime pad the route crosses. The walker only ever gets a LEG, and the
     * leg is cut at LEG_LENGTH, so on a pad wider than that its last waypoint sits ON the
     * slime and a crossing has nothing to aim at — measured as 59 trigger hits and zero
     * crossings started. The navigator holds the whole route, so it is the one that can see
     * the exit; same shape as pendingPhysicsTarget for a jump.
     */
    private static volatile BlockPos pendingCrossing = null;
    private static volatile boolean planning = false;
    private static BlockPos legTail = null;
    private static int stallTicks = 0;
    private static int tickLog = 0;

    /** Height a plain jump clears; above this only pillaring gets the bot up. */
    private static double PlayerFitJumpHeight() {
        return kaptainwutax.tungsten.helpers.PlayerFit.JUMP_HEIGHT;
    }
    private static double lastDist = Double.MAX_VALUE;

    private FastNavigator() {}

    public static boolean isActive() { return active; }

    /** Walk until standing IN {@code cell} — baritone's GoalBlock. For callers that need a
     *  position rather than a neighbourhood, such as the builder standing where it will pillar. */
    public static void startExact(BlockPos cell) {
        start(new Vec3d(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5));
        exactCell = cell;
    }

    /** G55: the drive's version of {@link #startExact} -- a block goal whose cell is solid is
     *  DUG INTO (baritone's GoalBlock), so the planner completes only on the exact cell and the
     *  arrival test is the exact cell too. */
    public static void startExactForDrive(BlockPos cell) {
        startExact(cell);
        exactFromDrive = true;
    }

    /** The exact cell the DRIVE armed this run for, or null (builder cells are not reported). */
    public static BlockPos driveExactCell() { return active && exactFromDrive ? exactCell : null; }

    /** G53: plans that started from the cell SUPPORTING the body instead of the one under its
     *  centre, because the centre column had nothing under it. */
    public static volatile int navStartFromSupport;
    /** G56: wall hand-offs that mined the ceiling above the body before the tower, and routes
     *  given up because that ceiling could not be broken. */
    public static volatile int navCeilingMined, navCeilingRefused;
    /** G58: searches re-run once with four times the budget before a goal below is given up,
     *  because the first search had spent its whole budget. */
    public static volatile int navBudgetBoosted;
    private static volatile boolean budgetBoostNext = false;
    private static volatile boolean budgetBoostedThisRoute = false;

    // ── G68 (2026-09-12): the physics engine gets baritone's budget, and two failures end the route ──
    //
    // ⛔ "STANDS THERE COMPUTING FOR EVER" (operator, on the recording): the bot at the mouth of a
    // one-block slot it could not fit through, the physics search drawn out toward it, nothing
    // moving. The mechanism: walking dead-ends, the goal is handed to the physics engine, the
    // engine searches for its config budget (fifteen seconds) and then to its no-progress cap
    // (twenty), returns nothing, the navigator re-plans from the same feet, the plan dead-ends at
    // the same cell, the same hand-off, the same twenty seconds. Baritone's PathingBehavior plans
    // for primaryTimeoutMS (500 ms), re-plans once with failureTimeoutMS (2000 ms), and on the
    // second failure says "Unable to find path" and lets the process drop the goal. This is that:
    // a hand-off's search gets 500 ms; one that moved the body nowhere is asked again with 2000;
    // a second failure gives the route up out loud and remembers the cell for a minute, so a
    // re-plan from the same feet does not hand it over a third time.
    private static final long PHYSICS_PRIMARY_MS = 500L;
    private static final long PHYSICS_FAILURE_MS = 2000L;
    private static final long PHYSICS_REFUSAL_MS = 60_000L;
    /** The hand-off in flight: where the body stood when it was sent, and to what. */
    private static Vec3d physicsHandoffFrom = null;
    private static BlockPos physicsHandoffTarget = null;
    /** Hand-offs to the same cell, in a row, that moved the body nowhere. */
    private static int physicsFailStreak = 0;
    /** The cell the engine failed twice on, and when. Outlives the route on purpose. */
    private static BlockPos physicsRefusedTarget = null;
    private static long physicsRefusedAtMs = 0;
    /** Hand-offs that moved the body nowhere, and routes given up after the second such. */
    public static volatile int navPhysicsFailed, navPhysicsGaveUp;

    private static boolean physicsRefusedRecently(BlockPos cell) {
        return cell != null && physicsRefusedTarget != null && physicsRefusedTarget.equals(cell)
                && System.currentTimeMillis() - physicsRefusedAtMs < PHYSICS_REFUSAL_MS;
    }

    /**
     * THE BODY'S CELL IS THE CELL THAT HOLDS IT UP (G53, 2026-09-11). A body resting on the
     * edge of a block has its centre over the next column; that column may be a drop, and every
     * planner that starts there finds a cell with no floor: the grid BFS says "no route", the
     * fast planner rescues the start with the player's own level and then cannot dig the floor
     * (there is none) nor fall in place (falls are moves to a neighbour), and the physics search
     * "runs out of nodes". tree_drop, round 13: the bot at (803.1,-53) with (803,-54) air, the
     * stick four below in that very column, sixty seconds of "NO ROUTE" and "Ran out of nodes".
     * Among the cells the hitbox overlaps, take the nearest one with a solid block under it --
     * the same test PillarTask uses before it builds -- and let the route begin from a floor.
     */
    public static BlockPos supportedFeet(ClientPlayerEntity player) {
        BlockPos centre = kaptainwutax.tungsten.path.movements.RotationHelper.playerFeet(player);
        if (!TungstenConfig.get().planFromSupportedCell || !player.isOnGround()) return centre;
        var world = player.getEntityWorld();
        BlockPos under = centre.down();
        if (!world.getBlockState(under).getCollisionShape(world, under).isEmpty()) return centre;
        net.minecraft.util.math.Box box = player.getBoundingBox();
        double[][] corners = {
            {box.minX + 0.01, box.minZ + 0.01}, {box.minX + 0.01, box.maxZ - 0.01},
            {box.maxX - 0.01, box.minZ + 0.01}, {box.maxX - 0.01, box.maxZ - 0.01},
        };
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (double[] c : corners) {
            BlockPos cell = new BlockPos(net.minecraft.util.math.MathHelper.floor(c[0]), centre.getY(),
                    net.minecraft.util.math.MathHelper.floor(c[1]));
            if (cell.equals(centre)) continue;
            BlockPos b = cell.down();
            if (world.getBlockState(b).getCollisionShape(world, b).isEmpty()) continue;
            double dx = cell.getX() + 0.5 - player.getX(), dz = cell.getZ() + 0.5 - player.getZ();
            double d = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = cell; }
        }
        if (best == null) return centre;
        navStartFromSupport++;
        return best;
    }

    /** Whose altoclef goal this navigator run was armed for. Diagnostic only. */
    private static String startedFor = "-";

    /** The goal description captured when {@link #start} was last called. */
    public static String startedFor() {
        return startedFor;
    }

    /**
     * The block a REACH route is heading for, or null for an ordinary position goal. While set the
     * planner completes on any cell adjacent to it ({@code FastPlanner.adjacentToBlock}) and may dig
     * its way there; arrival is that same adjacency, not a distance sphere -- a mining target is
     * solid, so no sphere around it is ever entered (docs/BARITONE-GAPS.md G25).
     */
    private static volatile BlockPos reachBlock = null;

    /**
     * A BREAK RUN IS THE NAVIGATOR'S OWN JOB (2026-09-11). FastPlanner's dig moves (breakDown /
     * breakThrough / breakStair) come out flagged, and a flagged run used to be handed to the
     * physics engine with the GOAL as its target, on the theory that its guide would truncate at
     * the first break and the "At the wall" shortcut would mine it. That holds when the bot is
     * already at the wall (the dig bench) and fails on real terrain: the physics leg does not
     * deliver the body ("walking dead-ends", "Mining aborted: ticks=1 dist=5.19"), the miner's
     * far give-up condemns the ore every six seconds, and iron nine blocks under the feet is
     * never dug (recorded @gamer run, t=239-352 s). So: walk the leg to the cell BEFORE the
     * first break waypoint, then start the executor's mining on that waypoint's cells ourselves,
     * wait for "Mining done", and re-plan from wherever the dig left the body. No physics
     * search in the loop at all.
     */
    private static volatile List<BlockPos> nextBreakCells = null;
    private static volatile List<BlockPos> pendingBreakCells = null;
    private static volatile boolean awaitingBreak = false;
    /**
     * THE CELL THE BODY MUST BE IN WHEN THE DIG STARTS. A breakDown mines the floor of the node
     * it was planned from, and the walker declares a leg done from up to a block away -- so on
     * the terrace bench the bot stood at z=299.7, the plan expected it in z=300, and it mined a
     * neat shaft in the NEIGHBOURING column three blocks deep without ever dropping (then the next
     * cell was 4.7 away and "out of reach"). Baritone's MovementDownward centres the body on
     * src before it mines; this is that step.
     */
    private static volatile BlockPos nextBreakStand = null;
    private static volatile BlockPos pendingBreakStand = null;
    private static int centerTicks = 0;
    private static final int CENTER_TICKS_MAX = 80;
    public static volatile int navBreakCentered, navBreakCenterTimeout;
    /** Break runs owned here: started, refused because the walker stopped short, and resumed
     *  after "Mining done". Read as navBreak=started/tooFar/resumed. */
    public static volatile int navBreakStarted, navBreakTooFar, navBreakResumed;
    /** G42: planned pillar runs cut out of a queue leg and handed to PillarTask. */
    public static volatile int navPillarRuns;
    /** G51: towers whose hand-off first walked the body onto the plan's column, and the ticks
     *  spent doing so for the current hand-off. */
    public static volatile int navPillarSteered;
    private static int pillarSteerTicks = 0;
    /** G60: hand-offs refused because the goal was below and the tower wanted to go up. */
    public static volatile int navTowerRefusedBelow;
    /** G64b: legs started afloat that went to the queue instead of the walker / that the queue
     *  refused (and were re-planned rather than walked). */
    public static volatile int navWetLegQueued, navWetLegRefused;

    /** The cell a stalled route was last re-planned from; a second stall in the same cell is the
     *  honest "unreachable from here" verdict. Read navStall=replans/gaveUp. */
    private static volatile BlockPos stallReplanCell = null;
    public static volatile int navStallReplans, navStallGaveUp;

    /** True while this navigator is mining a planned break run (PathExecutor asks, so that its
     *  post-mining resume does not start a physics search underneath us). */
    public static boolean ownsBreakRun() { return active && awaitingBreak; }

    /** Feet in the cell and within 0.3 of its centre horizontally. */
    private static boolean centeredOn(ClientPlayerEntity player, BlockPos cell) {
        BlockPos feet = kaptainwutax.tungsten.path.movements.RotationHelper.playerFeet(player);
        if (!feet.equals(cell)) return false;
        double dx = cell.getX() + 0.5 - player.getX(), dz = cell.getZ() + 0.5 - player.getZ();
        return dx * dx + dz * dz < 0.3 * 0.3;
    }

    /** Face the cell's centre (mouse pipeline, no gaze teleport) and walk to it, sneaking for
     *  the last block so the body cannot overshoot an edge. */
    private static void steerTo(ClientPlayerEntity player, BlockPos cell) {
        double dx = cell.getX() + 0.5 - player.getX(), dz = cell.getZ() + 0.5 - player.getZ();
        float yaw = (float) Math.toDegrees(-Math.atan2(dx, dz));
        kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.setTarget(yaw, player.getPitch());
        var o = TungstenMod.mc.options;
        if (o == null) return;
        // only push once the body faces roughly the right way, or a wide yaw error walks it off
        float err = Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(yaw - player.getYaw()));
        o.forwardKey.setPressed(err < 25f);
        o.sneakKey.setPressed(dx * dx + dz * dz < 1.0);
        o.sprintKey.setPressed(false);
    }

    private static void releaseSteer() {
        var o = TungstenMod.mc == null ? null : TungstenMod.mc.options;
        if (o == null) return;
        o.forwardKey.setPressed(false);
        o.sneakKey.setPressed(false);
    }

    /** The block a running reach route serves, or null. Lets the drive tell "armed for this
     *  block" from "armed for something else" without stopping a route that is doing its job. */
    public static BlockPos reachBlock() { return reachBlock; }

    /** The reach route has arrived: the same test the planner completed on, evaluated for the
     *  body's real feet cell against the live world. */
    private static boolean reachArrived(ClientPlayerEntity player, BlockPos block) {
        BlockPos feet = kaptainwutax.tungsten.path.movements.RotationHelper.playerFeet(player);
        return FastPlanner.reachGoalSatisfied(player.getEntityWorld(), feet.getX(), feet.getY(),
                feet.getZ(), block);
    }

    /** Route to a NEIGHBOUR of {@code block} (baritone's GoalGetToBlock), digging if needed.
     *  {@code target} is the block's centre, which the heuristic and the stall watchdog steer by. */
    public static void start(Vec3d target, BlockPos block) {
        start(target);
        reachBlock = block;
    }

    /**
     * ⛔ THE GOAL DECIDES ARRIVAL, NOT THE NAVIGATOR (G69, 2026-09-12). Baritone's PathingBehavior
     * has no radius of its own: a path is done when {@code Goal.isInGoal(feet)} says so. This
     * navigator arrived on a two-block sphere of its own, and the 22:10 run shows what the gap
     * costs: a cobblestone drop in the one-deep hole the bot had just dug, the goal nearLive(r=1)
     * on the drop's cell (353,145,1), the body on the rim at (353.5,146,0.1) -- 1.72 from the
     * target, "FastNavigator: arrived (1.7)", stop; the drive's own test (block distance <= 1)
     * says NOT reached, restarts the route, "arrived (1.7)" again, every fifteen seconds; the
     * pursuit's not-closing watchdog gives the drop up after twenty-five, the blacklist restores
     * it, four attempts, a hundred seconds, the drop never touched. The route must go INTO the
     * hole, and only the goal knows that.
     *
     * @param reached the caller's own arrival test on the feet cell; the two-block sphere stays
     *                the default for callers without one (the goto command, chases).
     */
    public static void start(Vec3d target, java.util.function.Predicate<BlockPos> reached) {
        start(target);
        arrivalTest = reached;
    }

    /** G69: the caller's arrival test on the feet cell (baritone's Goal.isInGoal); null = the
     *  two-block sphere. Cleared by {@link #stop()}. */
    private static volatile java.util.function.Predicate<BlockPos> arrivalTest = null;
    /** G69: arrivals the sphere would have declared that the goal's own test refused. */
    public static volatile int navArrivalRefusedByGoal;

    public static void start(Vec3d target) {
        stop();
        // WHOSE goal is this route serving? The drive publishes the altoclef goal every tick, but
        // a route outlives the tick that started it -- so the CURRENT goal and the goal a running
        // route was armed for can differ, and on mine_stone they do: the drive reads flee(...)
        // while the navigator climbs toward (0.5,10.0,0.5). Stamping it at start is the only way
        // to tell a stale route from a live one.
        startedFor = kaptainwutax.tungsten.combat.CombatTrace.hostGoal;
        goal = target;
        active = true;
        stallTicks = 0;
        lastDist = Double.MAX_VALUE;
        planAhead(TungstenMod.mc.player != null
                ? TungstenMod.mc.player.getBlockPos() : BlockPos.ofFloored(target));
    }

    /**
     * The goal being served right now, for diagnostics. "-" when the navigator is idle.
     *
     * <p>Exists so a route can say WHY it exists at the moment it starts, rather than being
     * reasoned about afterwards. Six mechanisms were proposed for one climbing route on mine_stone
     * and five were refuted; none of them could have been proposed at all if the goal had been
     * printed next to the route.
     */
    public static String goalDescription() {
        Vec3d g = goal;
        return g == null ? "-" : String.format("(%.1f,%.1f,%.1f)", g.x, g.y, g.z);
    }

    /** The goal being served right now, or null when idle. The altoclef drive asks, so a route
     *  armed by an earlier task instance can be ADOPTED when it serves the same goal and STOPPED
     *  when it does not, instead of running underneath a second driver (G40). */
    public static Vec3d currentGoal() { return active ? goal : null; }

    /** True while a caller asked to stand IN one exact cell (the builder positioning itself). */
    public static boolean hasExactCell() { return active && exactCell != null && !exactFromDrive; }

    public static void stop() {
        active = false;
        goal = null;
        exactCell = null;
        exactFromDrive = false;
        arrivalTest = null;
        pendingGiveUp = false;
        budgetBoostNext = false;
        budgetBoostedThisRoute = false;
        if (pillarSteerTicks > 0) releaseSteer();
        pillarSteerTicks = 0;
        reachBlock = null;
        nextBreakCells = null;
        pendingBreakCells = null;
        awaitingBreak = false;
        nextBreakStand = null;
        pendingBreakStand = null;
        if (centerTicks > 0) releaseSteer();
        centerTicks = 0;
        stallReplanCell = null;
        nextLeg = null;
        legTail = null;
        nextPhysicsTarget = null;
        pendingPhysicsTarget = null;
        pendingCrossing = null;
        awaitingPhysics = false;
        physicsHandoffFrom = null;
        physicsHandoffTarget = null;
        physicsFailStreak = 0;   // the refusal memory stays: it is about the cell, not the route
        nextLegMovement = false;
        // A queue left running past the navigator would keep pressing keys with nobody steering.
        kaptainwutax.tungsten.path.movements.MovementQueue.stop();
        // Drop the plan overlay we published while navigating.
        kaptainwutax.tungsten.TungstenModRenderContainer.PLACE_PLAN.clear();
        kaptainwutax.tungsten.TungstenModRenderContainer.BREAK_PLAN.clear();
    }

    /** Ticked from the client mixin alongside the other tungsten tasks. */
    public static void tick(ClientPlayerEntity player) {
        if (!active || player == null || goal == null) return;
        if (pendingGiveUp) {
            pendingGiveUp = false;
            navStallGaveUp++;
            BlockPathWalker.stop();
            stop();
            return;
        }

        double dist = player.getEntityPos().distanceTo(goal);
        // ARRIVAL IS NOT A 3D QUESTION WHEN THE GOAL IS ABOVE YOU. This test was a plain sphere
        // of radius 2, and this file already knows why that is wrong — the pillar hand-off below
        // carries the same finding in its own words: "you cannot walk upwards; a cell above your
        // head is the one place you are most definitely NOT already at". That fix was applied
        // there and not here, a hundred lines up, where every goal passes.
        //
        // Measured while chasing the last cell of diag_build. The builder asked to stand at
        // (5,-58,0) to place downwards; the bot was at (5,-59,0), one block below. Distance 1.0,
        // inside the radius, so the navigator declared arrival and shut down WITHOUT MOVING —
        // four times in a row, from the identical position:
        //   [3 for=(5,-59,0) stand=(5,-58,0) from=(5,-59,0)] [4] [5] [6] EXHAUSTED
        //
        // Only the upward case changes: a goal level with the player or below it still arrives
        // exactly as before, which is every goal the nav courses use.
        double goalRise = goal.y - player.getY();
        // A REACH ROUTE ARRIVES BESIDE THE BLOCK, on the same predicate the planner completed on.
        BlockPos reach = reachBlock;
        // ⛔ ARRIVAL IS A STATE, NOT A MOMENT (G41, 2026-09-11). nav_bridge in the regression: the
        // physics engine sprint-jumped the gap, the body passed within 2.0 of the goal in the air,
        // this test said "arrived", the navigator stopped -- and the executor's replay, still
        // running, walked the body back to x=18.84, 4.2 blocks short, where it stood for the rest
        // of the course with nobody left to plan ("nav=false path=-1"). Twice in a row, identical
        // coordinates. A body that is airborne or still sprinting has not arrived anywhere; ask
        // again when it is on the ground and slow, and then also stop the replay so nothing can
        // carry it off again.
        boolean settledBody = !TungstenConfig.get().arrivalNeedsSettledBody
                || ((player.isOnGround() || player.isTouchingWater() || player.isClimbing())
                    && player.getVelocity().horizontalLengthSquared() < 0.05);
        // G55: the feet cell with baritone's +0.1251 -- on a chest or a slab the naive block
        // position reads the cell BELOW the one the body stands in, and an exact arrival on the
        // chest under a buried goal was missed for it (buried_goal, round 13).
        boolean sphereArrived = dist <= ARRIVE_DIST && goalRise < 1.0 && settledBody;
        java.util.function.Predicate<BlockPos> goalTest = arrivalTest;
        boolean arrived = exactCell != null
                ? kaptainwutax.tungsten.path.movements.RotationHelper.playerFeet(player).equals(exactCell)
                : reach != null
                    ? reachArrived(player, reach)
                    : goalTest != null
                        // G69: the goal's own test on the feet cell, the body settled as before
                        ? (settledBody && goalTest.test(
                                kaptainwutax.tungsten.path.movements.RotationHelper.playerFeet(player)))
                        : sphereArrived;
        if (!arrived && goalTest != null && sphereArrived) navArrivalRefusedByGoal++;
        if (arrived) {
            Debug.logMessage("FastNavigator: arrived (" + String.format("%.1f", dist) + ")");
            BlockPathWalker.stop();
            if (TungstenConfig.get().arrivalNeedsSettledBody) {
                var exA = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
                if (exA != null && exA.isRunning() && exA.breakQueue == null && exA.placeQueue == null) {
                    exA.stop = true;   // a replay past the goal is the thing that un-arrives us
                }
            }
            // ⛔ A FINISHED GOTO IS NOT A GOTO TO RESUME, ANY MORE THAN A STOPPED ONE IS.
            //
            // stopNavigation() clears the "a real goto was requested" flag and says exactly that
            // about STOPPING. Arriving was never covered: TungstenMod.TARGET keeps the destination
            // and the flag keeps saying real, so the next mining segment hands
            // resumeGotoAfterMining a goto that has already been completed.
            //
            // Harmless immediately after arrival -- that method returns when the goal is within 2
            // blocks -- and NOT harmless later: once the bot has walked away to mine, the distance
            // is large again and it walks BACK to a destination it already reached. That is the
            // same defect as the debug constant y=10 that built cobblestone towers on mine_stone,
            // with a stale real target instead of a stale default one, and the fix for that case
            // did not cover this one.
            TungstenMod.clearGotoTarget();
            stop();
            return;
        }

        // progress watchdog: the physics engine or a re-plan owns recovery, but a
        // navigator that silently stops is the failure the user reported, so make
        // it loud and let the caller (goto retry / physics search) take over.
        // BUILDING IS PROGRESS, even though the distance does not move. While the executor is
        // placing or mining, the bot stands still on purpose doing the work that makes the
        // rest of the route possible — and this watchdog counted that as failure and SHUT THE
        // NAVIGATOR DOWN, which is why a build route produced two plans in a whole run.
        var exec = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
        // A MovementQueue leg is building too: a sneak-backplace step spends several ticks
        // stationary while the aim converges, and the watchdog used to call that failure.
        // AND SO IS THE ENGINE WE JUST HANDED THE LEG TO. `awaitingPhysics` means this navigator
        // deliberately stopped moving and asked the physics search to own the next piece; counting
        // that wait as "no progress" makes the navigator shoot itself while doing exactly what it
        // decided to do. Measured on nav_bridge at ~9 fps, immediately after the bridge itself was
        // fixed: the bot bridged the lip, "physics owns the jump -> 19,-60,0", and three seconds
        // later "no progress, handing over" — then the jump LANDED it at x=19.57 with nobody left
        // to plan the last 3.4 blocks, and it stood there for 104 of the 120 seconds (final_dist
        // 3.5, tolerance 2.5). Same shape as the BUILDING-IS-PROGRESS fix above; the physics engine
        // was simply left out of the list.
        boolean building = (exec != null && (exec.placeQueue != null || exec.breakQueue != null))
                || kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()
                || kaptainwutax.tungsten.task.PillarTask.isActive()
                || kaptainwutax.tungsten.task.SwimOutTask.isActive()
                || (awaitingPhysics
                        && (kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.active.get()
                            || kaptainwutax.tungsten.TungstenModDataContainer.isExecutorRunning()));
        // ⛔ "RUNNING" IS NOT "PROGRESSING", AND THE WATCHDOG WAS TAKING IT AS SUCH.
        //
        // A MovementQueue leg that is RUNNING resets stallTicks every tick, so the one mechanism
        // that could rescue the bot is switched off by the very thing that is stuck. Traced end to
        // end on a stall that reproduces on demand: a MovementDiagonal built from a cell one block
        // below the body, boxed between two solid corners, holding forward at v=0.00 -- and the
        // navigator sat beside it for five minutes without replanning once (pdPlan=0/0).
        //
        // This is the same defect that was fixed in MovementProgressChecker ("we broke it, so
        // that is progress") and the queue's own null-route note warns of it in as many words: a
        // queue that perpetually runs a route to nowhere means the checker CANNOT trip.
        //
        // Standing still IS legitimate while building -- placing a block takes a moment and the
        // body does not move for it. So do not remove the exemption, put a clock on it: three
        // seconds of the body not moving horizontally is far past any placement, and nothing that
        // is genuinely working looks like that.
        boolean bodyMoved = true;
        if (kaptainwutax.tungsten.TungstenConfig.get().stallWatchdogNeedsMotion) {
            double px = player.getEntityPos().x;
            double pz = player.getEntityPos().z;
            if (Math.abs(px - lastBodyX) < 0.05 && Math.abs(pz - lastBodyZ) < 0.05) {
                bodyMoved = ++stillTicks <= STILL_LIMIT;
            } else {
                stillTicks = 0;
                lastBodyX = px;
                lastBodyZ = pz;
            }
            if (building && !bodyMoved) {
                navWatchdogUngagged++;
                building = false;
            }
        }
        if (building) {
            stallTicks = 0;
        } else if (dist < lastDist - 0.25) {
            lastDist = dist;
            stallTicks = 0;
        } else if (++stallTicks > STALL_TICKS) {
            // "HANDING OVER" TO NOBODY IS NOT A RECOVERY (operator, 2026-09-11: "the navigator must
            // NEVER get stuck; a fallback is not a fix"). No progress means the route being walked
            // is wrong for the world as it is now -- so plan again from the cell the body is
            // actually in, with everything the planner has (dig, pillar, bridge). Only when a
            // fresh plan from this same cell ALSO goes nowhere is the goal unreachable from here,
            // and that is said out loud so the caller can blacklist with a reason.
            BlockPos here = player.getBlockPos();
            if (stallReplanCell == null || !stallReplanCell.equals(here)) {
                stallReplanCell = here;
                stallTicks = 0;
                navStallReplans++;
                Debug.logMessage("FastNavigator: no progress at " + here.toShortString()
                        + " — re-planning from here");
                BlockPathWalker.stop();
                kaptainwutax.tungsten.path.movements.MovementQueue.stop();
                nextLeg = null; legTail = null;
                nextPhysicsTarget = null; pendingPhysicsTarget = null;
                nextBreakCells = null; pendingBreakCells = null;
                nextBreakStand = null; pendingBreakStand = null;
                if (centerTicks > 0) releaseSteer();
                centerTicks = 0;
                awaitingPhysics = false; awaitingBreak = false;
                planAhead(here);
                return;
            }
            navStallGaveUp++;
            Debug.logWarning("FastNavigator: no progress at " + here.toShortString()
                    + " after a re-plan — goal unreachable from here, giving the route up");
            // TODOS.md, live void-death repro 2026-09-03: this said "handing over" but did not —
            // stop() (below) does not touch BlockPathWalker, so a BFS leg mid-walk when the stall
            // fires keeps pressing movement keys with the navigator no longer watching at all.
            // The ARRIVAL branch above this one (successful exit) already calls
            // BlockPathWalker.stop() before stop() for exactly this reason; this failure exit is
            // the same kind of "this route is done" moment and was missing the same line. Live
            // reproduction: gotoXYZ 4.3 blocks away produced repeated trivial re-plans (no new leg
            // ever armed), the stall fired, and the bot fell 46 blocks into the void within one
            // more second and died -- consistent with an already-walking leg continuing unowned
            // past a descending waypoint, which BlockPathWalker's own hole-refusal gate does not
            // guard (walkerRefusesHoleOnLevelRun only covers level/ascending runs, by design, so
            // nav_descend's intentional 3-block drops keep working).
            BlockPathWalker.stop();
            stop();
            return;
        }

        if (TungstenConfig.get().verboseDebugLogging && (tickLog++ % 20) == 0) {
            Debug.logMessage(String.format(
                    "NAVSTATE walker=%b awaiting=%b pending=%s next=%s planning=%b pfActive=%b exec=%b",
                    BlockPathWalker.isRunning(), awaitingPhysics,
                    pendingPhysicsTarget == null ? "-" : "set",
                    nextPhysicsTarget == null ? "-" : "set", planning,
                    kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.active.get(),
                    kaptainwutax.tungsten.TungstenModDataContainer.isExecutorRunning()));
        }

        // A MovementQueue leg owns the body, the keys AND the camera for its whole run — that is the
        // entire point of the port (spec pitfall P1: a second per-tick writer measured
        // called=11041 inRange=11040 clicked=0). So: hands off, and do NOT pre-plan the following
        // leg either. Pre-planning is safe behind a WALK because the cells the walk crosses already
        // exist; behind a BRIDGE it is not — the planks are not in the world yet, so a plan from the
        // chain's tail would price a route out of a cell that is still air and come back nonsense.
        // The queue is short; replanning from the bot's real position when it finishes costs one
        // planning round and cannot be wrong.
        if (kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()) return;

        if (BlockPathWalker.isRunning()) {
            // walking: make sure the FOLLOWING leg is being computed from the tail
            if (nextLeg == null && !planning && legTail != null) planAhead(legTail);
            return;
        }

        // The walked leg ended at a jump: THIS is the hand-off to the physics engine.
        // It is the piece that never existed — see nextPhysicsTarget above.
        // While the physics engine performs a jump we handed it, the navigator must keep
        // its hands off. Starting the next walk leg here means the walker presses movement
        // keys DURING the jump — two owners of the same keys in the most timing-sensitive
        // manoeuvre there is. That is exactly what the log showed:
        //   FastNavigator: physics owns the jump -> 9,-60,0
        //   Walker: BFS 3 wp                      <- walker steps on the jump
        // (Making the navigator yield entirely while a place/break queue exists was tried and
        // MEASURED WORSE: placement activity fell from 37 ticks to 12 and a second run added
        // nothing at all. The builder took the body and nobody gave it back. Whatever the
        // right arbitration is here, "stop navigating" is not it.)
        // A BRIDGE STEP IS ONE MANOEUVRE. BridgeTask owns the walk AND the placement, the way
        // baritone's MovementTraverse does and the way PillarTask already owns a tower here.
        // Splitting them — walker steps, executor places — failed at three different seams in
        // a row: the placer froze the body 5.5 blocks short, and with that fixed the leg was cut
        // and handed to physics on every leg (12 legs, 12 HANDOFFs, WALKSTOP=0, nobody walking).
        if (kaptainwutax.tungsten.task.BridgeTask.isActive()) return;
        // A TOWER IS ONE MANOEUVRE TOO (G42, 2026-09-11). While PillarTask (or the swim-out) owns
        // the body, starting a walker leg here presses movement keys under a jump-and-place --
        // the same two-owners seam as the bridge above, and the reason the tower must be handed
        // over as a whole (see the pillar-run cut in planAhead) rather than one MovementPillar
        // step at a time.
        if (kaptainwutax.tungsten.task.PillarTask.isActive()
                || kaptainwutax.tungsten.task.SwimOutTask.isActive()) return;

        // ── A break run this navigator owns (see nextBreakCells) ──────────────
        var exB = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
        if (awaitingBreak) {
            if (exB != null && exB.breakQueue != null) return;   // still mining, hands off
            awaitingBreak = false;
            legTail = null;
            navBreakResumed++;
            planAhead(player.getBlockPos());   // the dig moved the body; plan from where it is
            return;
        }
        if (pendingBreakCells != null && !BlockPathWalker.isRunning()
                && !kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()) {
            // STAND WHERE THE PLAN STANDS BEFORE DIGGING (see nextBreakStand).
            BlockPos stand = pendingBreakStand;
            if (stand != null && !centeredOn(player, stand)) {
                if (++centerTicks <= CENTER_TICKS_MAX) {
                    steerTo(player, stand);
                    return;
                }
                navBreakCenterTimeout++;   // could not get there; dig from here rather than never
            } else if (stand != null) {
                navBreakCentered++;
            }
            releaseSteer();
            centerTicks = 0;
            pendingBreakStand = null;
            List<BlockPos> cells = pendingBreakCells;
            pendingBreakCells = null;
            double dig = player.getEyePos().distanceTo(Vec3d.ofCenter(cells.get(0)));
            if (exB != null && dig < 4.5) {
                navBreakStarted++;
                Debug.logMessage("FastNavigator: at the dig — mining " + cells.size()
                        + " block(s) at " + cells.get(0).toShortString());
                BlockPathWalker.stop();
                nextLeg = null;
                exB.stop = false;
                exB.startBreaking(cells);
                awaitingBreak = true;
                return;
            }
            // The walker stopped short of the dig: fall back to the physics hand-off, which is
            // what every break run went through before.
            navBreakTooFar++;
            pendingPhysicsTarget = BlockPos.ofFloored(goal);
        }

        if (awaitingPhysics) {
            if (kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.active.get()
                    || kaptainwutax.tungsten.TungstenModDataContainer.isExecutorRunning()) {
                return;   // physics still working — do not touch the walker or the keys
            }
            awaitingPhysics = false;
            legTail = null;
            // ⛔ A HAND-OFF THAT MOVED THE BODY NOWHERE IS A FAILURE, AND IT IS COUNTED (G68). The
            // engine's own "no route" never reached this task: it re-planned from the same feet as
            // if nothing had been tried. Baritone: the first failure re-plans with the longer
            // failure timeout, the second gives the route up.
            if (physicsHandoffTarget != null && physicsHandoffFrom != null) {
                Vec3d now = player.getEntityPos();
                Vec3d tc = Vec3d.ofBottomCenter(physicsHandoffTarget);
                boolean progressed = now.distanceTo(physicsHandoffFrom) >= 1.0
                        || now.distanceTo(tc) < physicsHandoffFrom.distanceTo(tc) - 0.5;
                if (progressed) {
                    physicsFailStreak = 0;
                } else {
                    navPhysicsFailed++;
                    physicsFailStreak++;
                    if (physicsFailStreak >= 2) {
                        navPhysicsGaveUp++;
                        physicsRefusedTarget = physicsHandoffTarget;
                        physicsRefusedAtMs = System.currentTimeMillis();
                        Debug.logWarning(String.format(
                                "FastNavigator: physics found no way to %s from (%.1f,%.1f,%.1f) in %d ms and again in %d ms — giving the route up",
                                physicsHandoffTarget.toShortString(), physicsHandoffFrom.x, physicsHandoffFrom.y,
                                physicsHandoffFrom.z, PHYSICS_PRIMARY_MS, PHYSICS_FAILURE_MS));
                        physicsFailStreak = 0;
                        physicsHandoffTarget = null;
                        pendingGiveUp = true;
                        return;
                    }
                    Debug.logMessage(String.format(
                            "FastNavigator: physics found no way to %s in %d ms — once more with %d ms",
                            physicsHandoffTarget.toShortString(), PHYSICS_PRIMARY_MS, PHYSICS_FAILURE_MS));
                }
            }
            planAhead(player.getBlockPos());   // continue from wherever we actually landed
            return;
        }

        BlockPos jump = pendingPhysicsTarget;
        if (jump != null) {
            // "ALREADY THERE" IS A HORIZONTAL QUESTION. This used to be a plain 3D distance
            // test, and a pillar target sits ONE BLOCK STRAIGHT UP — distance 1.0, inside the
            // 1.5 radius — so every pillar hand-off was thrown away as "nothing to do" before
            // anyone could perform it. You cannot walk upwards; a cell above your head is the
            // one place you are most definitely NOT already at.
            //
            // Measured on nav_wall2, which this silently broke: 54 of 82 plans flagged a
            // pillar as their first move, HANDOFF and PillarTask fired ZERO times, and the
            // navigator replanned 26 legs while the bot stood 7.5 blocks short at the foot of
            // its wall. The course used to pass because a 2-block climb landed 2.2 away and
            // survived this test by 0.7 of a block — it was never right, just lucky.
            Vec3d here = player.getEntityPos();
            double horiz = Math.hypot((jump.getX() + 0.5) - here.x, (jump.getZ() + 0.5) - here.z);
            double rise = (jump.getY() + 0.5) - here.y;
            if (horiz < 1.5 && Math.abs(rise) < 1.0) {
                pendingPhysicsTarget = null;   // genuinely standing on it
                jump = null;
            }
        }
        if (jump != null) {
            // Check BUSY *before* consuming the target. The other order threw the target
            // away on any tick where physics happened to be working — and physics is busy
            // almost always (a failing search runs the full 20 s budget), so the hand-off
            // was destroyed before it could ever happen. Measured: the plan really does
            // carry a flagged waypoint (firstPhysics=12, flagged=1), it was consumed here.
            if (kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.active.get()
                    || kaptainwutax.tungsten.TungstenModDataContainer.isExecutorRunning()) {
                return;   // physics busy — KEEP the target and retry next tick
            }
            // ⛔ A TOWER IS BUILT IN THE PLAN'S COLUMN, NOT WHEREVER THE BODY STOPPED (G51,
            // 2026-09-11). The planner chose the column with open sky; the walk left the body
            // one cell over, under the edge of a canopy, and the hand-off below started the
            // tower THERE -- the jump capped by the leaf two above the feet, "insideCell=80
            // placed=0" sixteen times on canopy_drop. Walk to the column first (the same steer a
            // dig gets in G34), then pillar; the target is kept until the body is on it.
            {
                Vec3d hereP = player.getEntityPos();
                double riseP = (jump.getY() + 0.5) - hereP.y;
                double horizP = Math.hypot(jump.getX() + 0.5 - hereP.x, jump.getZ() + 0.5 - hereP.z);
                if (riseP > PlayerFitJumpHeight() && horizP < 2.5 && TungstenConfig.get().planPlaceMoves
                        && TungstenConfig.get().pillarInPlannedColumn
                        && !kaptainwutax.tungsten.task.PillarTask.isActive()
                        && !player.isTouchingWater()
                        && FastPlanner.countPlaceable(player) > 0) {
                    BlockPos column = new BlockPos(jump.getX(), player.getBlockPos().getY(), jump.getZ());
                    if (!centeredOn(player, column)) {
                        if (++pillarSteerTicks <= 80) {
                            steerTo(player, column);
                            return;   // keep the target; try the tower once the body is on its column
                        }
                        // could not get onto the column: build from here rather than never
                    } else if (pillarSteerTicks > 0) {
                        navPillarSteered++;
                    }
                    releaseSteer();
                    pillarSteerTicks = 0;
                }
            }
            pendingPhysicsTarget = null;
            var world = TungstenMod.mc.world;
            if (world != null) {
                // DIAGNOSTIC: four attempts to hook pillaring here failed with no output at
                // all, so print the actual numbers this branch sees instead of guessing.
                if (TungstenConfig.get().verboseDebugLogging) {
                    double dRise = (jump.getY() + 0.5) - player.getEntityPos().y;
                    double dHoriz = Math.hypot(jump.getX() + 0.5 - player.getEntityPos().x,
                                               jump.getZ() + 0.5 - player.getEntityPos().z);
                    Debug.logMessage(String.format(
                            "HANDOFF target=(%d,%d,%d) rise=%.2f horiz=%.2f planPlace=%b",
                            jump.getX(), jump.getY(), jump.getZ(), dRise, dHoriz,
                            TungstenConfig.get().planPlaceMoves));
                }
                // A target ABOVE US and almost overhead is a WALL, not a jump. The physics
                // engine cannot climb one: above jump height the only real way up is to
                // place a block under yourself. PillarTask implements exactly that (stay
                // centred, jump, place while airborne), is ticked from the client mixin and
                // exposed over py4j — the capability was complete, navigation simply never
                // asked for it. By the time we reach here the walker has already delivered
                // us to the foot of the wall. Measured on nav_wall2: rise=1.48 horiz=2.18.
                double rise = (jump.getY() + 0.5) - player.getEntityPos().y;
                double horiz = Math.hypot(jump.getX() + 0.5 - player.getEntityPos().x,
                                          jump.getZ() + 0.5 - player.getEntityPos().z);
                // ROUTING A BUILD RUN TO BridgeTask — TRIED THREE TIMES, WORSE EVERY TIME,
                // AND THE SPRINT HYPOTHESIS IS REFUTED. Wired at the lip: 22.5, void fall, 3 of
                // 3. Wired one cell earlier: 22.5, 3 of 3. With BridgeTask paced two cells ahead
                // and sneaking near a lip (kept — its godbridge model is broken without it now
                // that placement is honest): 22.5, 3 of 3 again. So it is NOT sprint overshoot.
                // Against 11.6 standing still. Falling is a strictly worse failure, so no.
                //
                // Three refuted hypotheses in a row on one seam is the signal to stop guessing:
                // the port has to come from baritone's execution MODEL (Movement owns its inputs,
                // its failure condition and its valid positions), not from wiring tungsten's
                // existing tasks into a plan that was never shaped for them. See
                // docs/BARITONE-PORT-SPEC.md.
                // A PILLAR NEEDS BLOCKS. This hand-off fired with an empty inventory on the first
                // @gamer run of the reach goal (2026-09-11): "Pillaring up to y=118" -> "Pillar:
                // out of blocks" -> stop -> replan -> the same hand-off, for 150 s under a tree.
                // Swimming out needs none; a tower does, so ask the pocket first.
                boolean canPillar = player.isTouchingWater()
                        || FastPlanner.countPlaceable(player) > 0;
                // ⛔ A TOWER TOWARD A GOAL THAT IS BELOW YOU IS THE PLAN GOING THE WRONG WAY (G60,
                // 2026-09-12). The 19:57 recording, 5:20: coal at (631,67,724), two blocks UNDER
                // the feet. The reach plan handed off "pillaring to y=66", then "no progress",
                // "mining the ceiling first (3)", "pillaring to y=72", "pillaring to y=82" -- 14
                // blocks placed up a spruce in 25 seconds, then a 14-block fall, hp 20 -> 12, and
                // the coal still two below where it started. Each re-plan from the tower's top
                // hands the next run to PillarTask, so the tower feeds itself.
                //
                // A climb on the way DOWN is a real move (over a lip, round a wall), so this
                // refuses only the runaway shape: the goal below, and a tower that wants to go
                // more than two blocks UP from where the body stands.
                if (TungstenConfig.get().noTowerWhenGoalIsBelow && goal != null
                        && goal.y < player.getY() - 2.0
                        && jump.getY() > player.getBlockPos().getY() + 2) {
                    Debug.logWarning(String.format(
                            "Wall too high to jump, but the goal is %.0f below — not towering up",
                            player.getY() - goal.y));
                    navTowerRefusedBelow++;
                    pendingGiveUp = true;
                    return;
                }
                // ⛔ AND A COLUMN THAT HAS ALREADY REFUSED A TOWER DOES NOT GET ASKED AGAIN (G62,
                // 2026-09-12). PillarTask gives up after four seconds without vertical progress,
                // this hand-off re-plans, sees the same wall and starts the same tower: the 16:30
                // recording spent seven and a half minutes at (259.5, 68, -539.5) in that loop,
                // ten towers, nothing placed, azalea in the hand. A refusal that does not outlive
                // the task that made it is not a refusal.
                if (canPillar && !player.isTouchingWater()
                        && kaptainwutax.tungsten.task.PillarTask.refusedRecently(
                                kaptainwutax.tungsten.path.movements.RotationHelper.playerFeet(player))) {
                    Debug.logWarning("Wall too high to jump and this column has already refused a tower"
                            + " — giving the route up");
                    kaptainwutax.tungsten.task.PillarTask.pillarColumnRefused++;
                    pendingGiveUp = true;
                    return;
                }
                // ⛔ A TOWER THROUGH ROCK IS A DIG FIRST (G56, 2026-09-11). pit_escape on round
                // 14: the goal cell was the surface pad itself, the plan climbed into it with a
                // break above the head, and this hand-off started PillarTask under that pad --
                // "Pillar stopped: no headroom, stone at 204,-53,200" (G51), re-plan, the same
                // hand-off, sixty seconds at y=-55. The ceiling the plan promised to mine is
                // mined here BEFORE the tower, through the navigator's own dig (the executor's
                // break run, "at the dig"), and only a ceiling that cannot be broken refuses
                // the route.
                if (rise > PlayerFitJumpHeight() && horiz < 2.5 && TungstenConfig.get().planPlaceMoves
                        && canPillar && !player.isTouchingWater()
                        && TungstenConfig.get().towerMinesItsCeiling
                        && !kaptainwutax.tungsten.task.PillarTask.isActive()) {
                    BlockPos feetC = kaptainwutax.tungsten.path.movements.RotationHelper.playerFeet(player);
                    java.util.List<BlockPos> ceiling = new java.util.ArrayList<>();
                    boolean unbreakable = false;
                    for (int y = feetC.getY() + 2; y <= jump.getY() + 1; y++) {
                        BlockPos c = new BlockPos(feetC.getX(), y, feetC.getZ());
                        var st = world.getBlockState(c);
                        if (st.getCollisionShape(world, c).isEmpty()) continue;
                        if (!kaptainwutax.tungsten.path.BreakRules.canBreak(world, c, st)) { unbreakable = true; break; }
                        ceiling.add(c);
                    }
                    if (unbreakable) {
                        Debug.logWarning("Wall too high to jump and the ceiling above cannot be mined — giving the route up");
                        navCeilingRefused++;
                        pendingGiveUp = true;
                        return;
                    }
                    if (!ceiling.isEmpty()) {
                        Debug.logMessage("Wall too high to jump — mining the ceiling first (" + ceiling.size()
                                + " block(s) from " + ceiling.get(0).toShortString() + ")");
                        navCeilingMined++;
                        pendingBreakCells = ceiling;
                        pendingBreakStand = feetC;
                        awaitingPhysics = false;
                        return;   // the dig runs next tick; the re-plan after it brings the tower back
                    }
                }
                if (rise > PlayerFitJumpHeight() && horiz < 2.5
                        && TungstenConfig.get().planPlaceMoves && canPillar
                        && !kaptainwutax.tungsten.task.PillarTask.isActive()
                        && !kaptainwutax.tungsten.task.SwimOutTask.isActive()) {
                    if (TungstenConfig.get().swimOutOfWaterNotPillar && player.isTouchingWater()) {
                        // FROM WATER YOU RISE BY SWIMMING, NOT PLACING. Pillaring needs footing and
                        // only bobs in water; climb out with the swim-out primitive instead (G24).
                        Debug.logMessage("At a bank in water — swimming out to y=" + jump.getY());
                        kaptainwutax.tungsten.task.SwimOutTask.startTo(jump);
                    } else {
                        Debug.logMessage("Wall too high to jump — pillaring to y=" + jump.getY());
                        kaptainwutax.tungsten.task.PillarTask.startTo(jump.getY());
                        // The climb is built in chunks to leg waypoints, but the tower really goes up
                        // to the goal — tell the visual so the WHOLE green column shows, not one chunk.
                        if (goal != null) kaptainwutax.tungsten.task.PillarTask.setClimbGoal(
                                (int) Math.ceil(goal.y));
                    }
                    awaitingPhysics = false;
                    legTail = null;
                    return;
                }

                // G68: a cell the engine has already failed twice on is not handed over again.
                if (physicsRefusedRecently(jump)) {
                    Debug.logWarning("FastNavigator: physics already found no way to " + jump.toShortString()
                            + " twice — giving the route up");
                    navPhysicsGaveUp++;
                    pendingGiveUp = true;
                    return;
                }
                long budget = (physicsFailStreak > 0 && jump.equals(physicsHandoffTarget))
                        ? PHYSICS_FAILURE_MS : PHYSICS_PRIMARY_MS;
                Debug.logMessage("FastNavigator: physics owns the jump -> "
                        + jump.getX() + "," + jump.getY() + "," + jump.getZ() + " (" + budget + " ms)");
                BlockPathWalker.stop();      // the walker must not fight the jump
                nextLeg = null;              // drop any leg prepared for after the gap
                // Only commit to waiting if the search ACCEPTED the request. find() refuses
                // while a previous search is still tearing down, and it used to do so
                // silently — so we would sit in awaitingPhysics for a jump nobody was
                // computing, and the run stalled at the lip of the gap.
                boolean accepted = kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.find(
                        world, Vec3d.ofBottomCenter(jump), player, budget);
                if (accepted) {
                    awaitingPhysics = true;
                    physicsHandoffFrom = player.getEntityPos();
                    if (!jump.equals(physicsHandoffTarget)) physicsFailStreak = 0;
                    physicsHandoffTarget = jump;
                } else {
                    pendingPhysicsTarget = jump;   // keep it; retry on a later tick
                }
                return;
            }
        }

        // A crossing was planned and the walk to the lip is done — hand the pad over.
        if (pendingCrossing != null && !BlockPathWalker.isRunning()
                && !kaptainwutax.tungsten.task.SlimeBounceTask.isActive()) {
            BlockPos exit = pendingCrossing;
            pendingCrossing = null;
            legTail = null;
            kaptainwutax.tungsten.task.SlimeBounceTask.startTo(exit);
            return;
        }

        // the walker is idle — start the leg that was prepared while we walked
        List<BlockPos> leg = nextLeg;
        if (leg != null && leg.size() >= 2) {
            nextLeg = null;
            legTail = leg.get(leg.size() - 1);
            // if this leg ends at a jump, arm the hand-off for when the walk finishes
            pendingPhysicsTarget = nextPhysicsTarget;
            nextPhysicsTarget = null;
            // ...and if it ends at a dig, arm the break run for the same moment
            pendingBreakCells = nextBreakCells;
            pendingBreakStand = nextBreakStand;
            nextBreakCells = null;
            nextBreakStand = null;
            // ONE OWNER FOR A BUILD LEG. A leg whose route places blocks goes to the ported
            // MovementQueue: a chain of baritone MovementTraverse objects, each of which owns its own
            // one-block step — the walk, the aim, the sneak and the click — and none of which shares
            // a tick with the walker or the physics executor. This is the replacement for the split
            // path (walker moves the body, PathExecutor.tickPlacing aims and clicks), whose seam
            // measured clicked=0 across eleven thousand in-range ticks.
            boolean queued = false;
            if (nextLegMovement) {
                nextLegMovement = false;
                // ⛔ DO NOT FORGET THIS IS A BRIDGE UNTIL THE QUEUE HAS ACTUALLY TAKEN IT.
                //
                // nextLegBridge used to be cleared HERE, one line before the queue was asked. So
                // for a leg that is both a movement leg and a build, a refusal fell through to the
                // `if (nextLegBridge)` below with the flag already false, and the route went to
                // BlockPathWalker -- a component that walks the cells it is given and cannot place
                // a block. The cells of a bridge are, by construction, the ones with nothing under
                // them: measured on the expansion probe, four fifths of the cells inside these
                // legs have no floor. The walker sprints along them and the bot falls. On the
                // navigation repro that reads as a fall from y=127 to y=118 with health going 20
                // to 5.5 in fifty seconds, while the target sat at y=127 the whole time.
                //
                // The comment that used to sit here said a refusal means "the plan changed shape
                // under us", implying it is rare. It is not: single runs measure qShort=4397 and
                // qNoClass=1204 against ten accepted starts. Refusal is the common case, so the
                // fallback is not an edge path -- it is the main one, and it was routing bridges
                // to the one component that cannot build them.
                boolean tookIt =
                        kaptainwutax.tungsten.path.movements.MovementQueue.start(leg) > 0;
                queued = tookIt;
                if (tookIt || !kaptainwutax.tungsten.TungstenConfig.get()
                        .navBridgeSurvivesQueueRefusal) {
                    nextLegBridge = false;
                } else if (nextLegBridge) {
                    navBridgeRescued++;
                }
                if (!queued) {
                    Debug.logWarning("FastNavigator: MovementQueue refused the leg, walking it");
                }
            }
            // ⛔ A LEG STARTED AFLOAT BELONGS TO THE QUEUE, NEVER TO THE WALKER (G64b, 2026-09-12).
            // BlockPathWalker cannot swim: it steers at a cell and presses forward, and in water
            // that is a body bobbing against the surface. The 20:14 run: once the queue's swim had
            // failed, this dispatch handed the walker "BFS 18 wp" / "BFS 6 wp" from the middle of a
            // lake every six seconds for five minutes -- "no progress at 1199,62,-253", navStall
            // 65/64, items=0 -- because a plain water leg never sets nextLegMovement and so never
            // reached the queue at all. The queue types liquid edges as MovementSwim and is the
            // one component here that can cross a pond; ask it first whenever the body is in
            // water, and if it refuses, re-plan rather than sprint at the water.
            if (!queued && TungstenConfig.get().wetLegGoesToTheQueue && player.isTouchingWater()
                    && !nextLegBridge) {
                boolean tookIt = kaptainwutax.tungsten.path.movements.MovementQueue.start(leg) > 0;
                if (tookIt) {
                    queued = true;
                    navWetLegQueued++;
                } else {
                    navWetLegRefused++;
                    Debug.logWarning("FastNavigator: afloat and the queue refused the leg — re-planning, not walking");
                    legTail = null;
                    replanFromHere();
                    return;
                }
            }
            if (!queued) {
                if (nextLegBridge) {
                    nextLegBridge = false;
                    kaptainwutax.tungsten.task.BridgeTask.startTo(
                            legTail.getX(), legTail.getY(), legTail.getZ());
                } else {
                    BlockPathWalker.startBFS(leg);
                }
                // immediately begin planning the leg after this one, from its tail
                planAhead(legTail);
            } else {
                // See the isRunning() guard above for why a build leg is NOT pre-planned.
                legTail = null;
            }
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
    /** The prepared leg places blocks, so BridgeTask owns it rather than the walker. */
    private static volatile boolean nextLegBridge = false;
    /**
     * The prepared leg is a contiguous run of one-block cardinal steps that includes a PLACE, so the
     * ported {@code MovementQueue} owns it end to end — walk edges and bridge edges alike. Mutually
     * exclusive with {@link #nextLegBridge}: one owner per leg.
     */
    private static volatile boolean nextLegMovement = false;
    // ⛔ REMOVED 2026-09-05: this used to also carry `nextPhysicsIsBridge`/`pendingIsBridge`, a
    // write-only pair -- `pendingIsBridge` was assigned from `nextPhysicsIsBridge` in exactly one
    // place and never read anywhere in the codebase, and `nextPhysicsIsBridge` itself was only
    // ever set to `false` (its declaration and two reset sites), never `true`. Superseded by
    // `nextLegBridge`/`nextLegMovement` above, which the actual bridge-vs-physics routing logic
    // reads. Confirmed dead via a codebase-wide grep before removing.

    /**
     * Re-plan from the bot's ACTUAL position, for a component that has just given up its leg.
     *
     * <p>Upstream's failure path is closed: PathingBehavior re-searches on the same tick a
     * segment fails. Ours ended at a stop() with nobody to hand back to — recorded in the audit
     * as the biggest execution gap and measured as the reason a bridge dies at low fps, where a
     * drift past a 20-tps-shaped tolerance ends the crossing instead of restarting it.
     */
    public static void replanFromHere() {
        if (!active) return;
        nextLeg = null;
        legTail = null;
        nextPhysicsTarget = null;
        pendingPhysicsTarget = null;
        awaitingPhysics = false;
        var p = TungstenMod.mc.player;
        if (p != null && !planning) planAhead(p.getBlockPos());
    }

    /**
     * Bridge legs that reached BridgeTask because the flag was no longer being thrown away when
     * the queue refused them. Zero means the fix never fired and any comparison using it is empty.
     */
    /**
     * WHAT THE ROUTE SEARCH ACTUALLY RETURNS. The size<2 branch below returned silently with
     * no counter -- the same shape that has cost this project several passes. Measured on a
     * zero-rung run: every BFS tick produced a route that collapsed and was refused as short
     * (tickBfs=1229, qNullEdge=1229, mqRefused(short=1229)) while the goal sat fourteen
     * blocks away and the bot never closed inside 12.8. A healthy run hits that on 70% of
     * ticks and still travels, so the ratio is the discriminator, not the event.
     * Read as navRes=short/empty/incomplete/ok.
     */
    public static volatile int navShortRes, navEmptyRes, navIncomplete, navOkRes, navDeadEnd;

    public static volatile int navBridgeRescued = 0;

    /**
     * Legs re-planned from the bot's real cell because the remembered tail disagreed with it.
     * Zero means the tail was always right and this changed nothing.
     */
    public static volatile int navPlannedFromStaleTail = 0;

    /** Where the body was when it last advanced, so "building" can be told from "wedged". */
    private static double lastBodyX = Double.NaN, lastBodyZ = Double.NaN;
    private static int stillTicks = 0;
    /** Sixty ticks is three seconds -- far past a block placement, far short of a run. */
    private static final int STILL_LIMIT = 60;
    /** Ticks the stall watchdog was allowed to run despite a queue claiming to be busy. */
    public static volatile int navWatchdogUngagged = 0;

    private static void planAhead(BlockPos from) {
        if (planning || goal == null) return;
        // ⛔ PLAN FROM WHERE THE BOT IS, NOT FROM WHERE THE LAST LEG SAID IT WOULD END.
        //
        // Callers pass legTail -- the tail the PREVIOUS leg claimed -- and when the body ends up
        // one cell off, every following leg is built from a place the bot is not. Traced to a
        // block, on a stall this repro reproduces every time:
        //
        //     src      (85,124,-54)  AIR      <- the leg started here
        //     srcAbove (85,125,-54)  air      <- the bot's feet are actually here
        //     cornerA  (85,125,-55)  grass_block   SOLID, at the body's real level
        //     cornerB  (84,125,-54)  dirt          SOLID, at the body's real level
        //     cornerAlow (85,124,-55) air     <- and THIS is the corner that was vetted
        //
        // MovementDiagonal checks its two corner columns at the START cell's height, so with the
        // start one block low it cleared a corner that is air one level down while the body sat
        // boxed in between two solid ones. The bot pressed forward at v=0.00 for a whole run:
        // mqStarted=64 against mqSteps=9, dbTargets=12/0, no rungs. Making the movement give up
        // does not help -- measured, diagonalWalled=22 per run with the bot still pinned -- because
        // the planner simply re-emits the same edge from the same wrong cell.
        //
        // A tail is a PREDICTION. The bot's block position is a fact. When they disagree, the fact
        // wins; when they agree this changes nothing at all.
        // G53: "actual" is the cell that supports the body, not the one under its centre.
        BlockPos actual = TungstenMod.mc.player != null ? supportedFeet(TungstenMod.mc.player) : null;
        if (kaptainwutax.tungsten.TungstenConfig.get().planFromActualPosition
                && actual != null && from != null && !actual.equals(from)) {
            navPlannedFromStaleTail++;
            from = actual;
        }
        planning = true;
        // Read the pocket HERE, on the client thread — the search runs on its own thread and
        // must not touch the inventory, but it does need to know how long a bridge it may
        // promise (see FastPlanner.placeBudget).
        FastPlanner.placeBudget = FastPlanner.countPlaceable(TungstenMod.mc.player);
        final BlockPos start = from;
        final Vec3d target = goal;
        final BlockPos reach = reachBlock;
        // G55: an exact-cell route completes IN the cell, never one above it (the planner's
        // one-block height tolerance is for "go over there" goals only).
        final boolean exact = exactCell != null;
        // G58: one search per route may run with four times the budget (see the dead-end branch).
        final boolean boost = budgetBoostNext;
        if (boost) { budgetBoostNext = false; budgetBoostedThisRoute = true; }
        final long budgetMs = TungstenConfig.get().fastPlanBudgetMs * (boost ? 4 : 1);
        Thread t = new Thread(() -> {
            try {
                var world = TungstenMod.mc.world;
                if (world == null) return;
                BlockPos goalCell = BlockPos.ofFloored(target);
                FastPlanner.Result res = FastPlanner.plan(world, start, goalCell, budgetMs, reach, exact);
                if (!active) return;
                // A ONE-WAYPOINT PLAN IS AN ANSWER: THERE IS NOTHING TO WALK FROM HERE.
                // FastPlanner returns exactly that when the start already satisfies the goal --
                // "1 nodes, 1 wp, complete" (FastPlanner.java:445-457, expanded=1 and the goal node
                // IS the start). This used to fall into the same `return` as a failed plan, so the
                // tail that produced it stayed set, the tick loop asked again from that same tail,
                // and got the same answer. Measured on a failing @gamer run: 218 of those in five
                // minutes, about one every one and a half seconds, all identical.
                // Forgetting the tail is what "there is no further leg from there" means; the
                // arrival check owns finishing the navigation, and any genuinely new situation
                // replans from the bot's real position anyway.
                if (res.path.size() < 2) {
                    navShortRes++;
                    if (legTail != null && legTail.equals(start)) {
                        legTail = null;
                    }
                    return;
                }
                if (res.isEmpty()) { navEmptyRes++; return; }

                // PUBLISH THE WHOLE PLAN FOR THE VISUAL. res.path carries every waypoint's
                // toPlace/toBreak, so this shows the ENTIRE column/bridge/tunnel the route will
                // build at once (user 2026-09-10: "only the first placed block rendered"). The
                // per-tick single-cell writers (PathExecutor/PillarTask) stand down while this
                // navigator is active, so this is the authority. Persists until the next plan.
                if (kaptainwutax.tungsten.TungstenConfig.get().renderPlacePlan
                        || kaptainwutax.tungsten.TungstenConfig.get().renderBreakPlan) {
                    kaptainwutax.tungsten.TungstenModRenderContainer.PLACE_PLAN.clear();
                    kaptainwutax.tungsten.TungstenModRenderContainer.BREAK_PLAN.clear();
                    for (FastPlanner.Waypoint w : res.path) {
                        if (w.toPlace != null) for (BlockPos p : w.toPlace)
                            kaptainwutax.tungsten.TungstenModRenderContainer.PLACE_PLAN.add(
                                    new kaptainwutax.tungsten.render.Cuboid(
                                            new Vec3d(p.getX() + 0.1, p.getY() + 0.1, p.getZ() + 0.1),
                                            new Vec3d(0.8, 0.8, 0.8),
                                            new kaptainwutax.tungsten.render.Color(60, 220, 120)));
                        if (w.toBreak != null) for (BlockPos p : w.toBreak)
                            kaptainwutax.tungsten.TungstenModRenderContainer.BREAK_PLAN.add(
                                    new kaptainwutax.tungsten.render.Cuboid(
                                            new Vec3d(p.getX() + 0.05, p.getY() + 0.05, p.getZ() + 0.05),
                                            new Vec3d(0.9, 0.9, 0.9),
                                            new kaptainwutax.tungsten.render.Color(255, 170, 40)));
                    }
                }

                // Walking cannot solve this route — hand it to the physics engine
                // (already searching in parallel) and get out of its way.
                if (!res.complete) {
                    navIncomplete++;
                    BlockPos tail = res.path.get(res.path.size() - 1).pos;
                    double before = Math.sqrt(start.getSquaredDistance(goalCell));
                    double after = Math.sqrt(tail.getSquaredDistance(goalCell));
                    // (Refusing to hand off a LONG incomplete route — "nineteen waypoints the
                    // walker could have walked" — was tried here and did not move the number:
                    // nav_water sat at 2-3 passes in 4 either way. Reverted rather than kept on
                    // faith, because this branch is on the path of every course.)
                    // ⛔ DO NOT "FIX" THIS BY REFUSING THE HAND-OFF. It looks like the cap on
                    // bridging — the trace is a loop of walk a leg, "walking dead-ends
                    // (8.9 -> 8.1)", hand the goal to a physics search that cannot solve it,
                    // wait out its budget, place ONE block, repeat — but skipping the hand-off
                    // when the plan contains a place/break took placements to ZERO and the
                    // distance to 20.7 in three runs of three. The reason is structural: the
                    // place plan only reaches the executor THROUGH the physics path, in
                    // PathFinder.truncateAtBreaks. No hand-off, no bridging at all. Giving the
                    // block planner its own route to the executor is the real fix, and it is a
                    // bigger job than a condition here.
                    // ⛔ A PARTIAL WORTH FIVE BLOCKS IS WALKED, PROGRESS OR NOT (G49, 2026-09-11).
                    // The coefficient rule (FastPlanner, G44) hands back the node baritone would
                    // walk to; baritone walks it and re-plans from there -- that is how it gets
                    // past a corner the budget could not see round. Judging it here by "did the
                    // straight-line distance shrink by four" threw those legs away: the 16:26
                    // recording sat five minutes on "walking dead-ends (9.1 -> 8.1) -> physics owns
                    // the rest" with the goal nine blocks BELOW, and physics cannot dig. So: a
                    // partial at least MIN_DIST_PATH from the start is a leg; and a goal below
                    // that yields no such partial is given up out loud, never handed to an engine
                    // without a shovel.
                    double partialLen = Math.sqrt(tail.getSquaredDistance(start));
                    boolean walkThePartial = TungstenConfig.get().planPartialLikeBaritone
                            && res.path.size() >= 2 && partialLen >= 5.0;
                    if (walkThePartial) {
                        navPartialWalked++;
                    } else if (before - after < MIN_PARTIAL_PROGRESS
                            && TungstenConfig.get().planPartialLikeBaritone
                            && goalCell.getY() < start.getY() - 2) {
                        // ⛔ A SEARCH THAT SPENT ITS WHOLE BUDGET HAS NOT SAID "UNREACHABLE" (G58,
                        // 2026-09-11). The 19:34 recording stood ninety seconds on a cliff above a
                        // drop: "no leg from here toward a goal 5 below (5.7 -> 3.0)" every two
                        // seconds, each search 7000 nodes in 251 ms of a 250 ms budget, the best
                        // partial inside five blocks because the dig moves round a cliff are dear
                        // and the frontier never got past them. Baritone plans for half a second
                        // and two on failure; give this search one more go at four times the
                        // budget before the honest give-up, and count how often that was enough.
                        if (TungstenConfig.get().planBudgetBoostBeforeGiveUp && !budgetBoostedThisRoute
                                && res.millis >= budgetMs - 10) {
                            navBudgetBoosted++;
                            budgetBoostNext = true;
                            Debug.logMessage(String.format(
                                    "FastNavigator: the search toward a goal %d below spent its budget (%d nodes, %d ms) — one more try with 4x",
                                    start.getY() - goalCell.getY(), res.expanded, res.millis));
                            return;   // the tick loop asks again; the next search runs boosted
                        }
                        navDeadEnd++;
                        navNoPartialBelow++;
                        Debug.logWarning(String.format(
                                "FastNavigator: no leg from here toward a goal %d below (%.1f -> %.1f) — giving the route up",
                                start.getY() - goalCell.getY(), before, after));
                        pendingGiveUp = true;
                        return;
                    }
                    if (!walkThePartial && before - after < MIN_PARTIAL_PROGRESS) {
                        navDeadEnd++;
                        // Walking cannot solve this — hand the TAIL to the physics engine
                        // and wait for it. This branch used to print "physics owns this"
                        // and then call stop(), which nulls pendingPhysicsTarget: physics
                        // was never actually asked, nothing else was running, and the bot
                        // stood at the lip of the obstacle until the run timed out. The
                        // message described a hand-off that did not happen.
                        // Hand physics the GOAL, not the tail. The tail is where WALKING
                        // gave up, which is the cell the bot is already standing on — asking
                        // the physics engine to travel to its own feet is a no-op, and the
                        // navigator then re-planned the same dead end forever. Observed on
                        // nav_steep: "physics owns the jump -> 6,-60,0" repeated while the
                        // bot sat motionless at x=5.6.
                        // Physics is precisely the engine that models jumps and parkour, so
                        // when walking cannot solve the route, it owns the REST of the route.
                        Debug.logMessage(String.format(
                                "FastNavigator: walking dead-ends (%.1f -> %.1f) -> physics owns the rest",
                                before, after));
                        BlockPathWalker.stop();
                        nextLeg = null;
                        nextPhysicsTarget = null;
                        pendingPhysicsTarget = goalCell;
                        return;
                    }
                }
                navOkRes++;

                if (TungstenConfig.get().verboseDebugLogging) {
                    int flagged = 0;
                    for (var w : res.path) if (w.needsPhysics) flagged++;
                    Debug.logMessage(String.format(
                            "PLAN n=%d complete=%b firstPhysics=%d flagged=%d",
                            res.path.size(), res.complete, res.firstPhysicsIndex(), flagged));
                }
                List<BlockPos> cells = res.positions();
                /** Set below only for a leg the ported MovementQueue is taking over. */
                boolean movementLeg = false;
                // cut at the first waypoint that needs a real jump: the physics
                // engine owns those (parkour), the walker must not run into one
                int physics = res.firstPhysicsIndex();
                if (physics > 0 && physics < cells.size()) {
                    // REMEMBER where the jump lands. The old code cut the leg here and set
                    // to the edge of the gap and then no one performed the jump: the
                    // navigator just replanned 2-cell legs until its stall watchdog fired.
                    // That single dead flag is why every parkour course failed.
                    // hand physics the FAR SIDE of the whole physics-only run, not just its
                    // first cell (see FastPlanner.physicsRunEnd — a ladder's first flagged
                    // cell is level with the bot, so that was a no-op that stalled forever)
                    // A BREAK waypoint must NOT become a walking target for the physics
                    // engine: that cell is SOLID, so the search spends its whole budget
                    // trying to stand inside a wall and reports "goal unreachable". Mining
                    // has its own path (pendingBreaks -> the "At the wall" shortcut), and
                    // that shortcut only fires once the bot is within 4 blocks of the
                    // block — which is exactly what the walker leg, cut here, delivers.
                    // So: walk up to the wall, then aim physics at the GOAL and let the
                    // mining machinery take over.
                    boolean breakCell = res.path.get(physics).toBreak != null;
                    // PLACE-AS-A-MOVE IS FLAGGED viaJump, SO THE BRIDGE WAS BEING HANDED TO THE
                    // ENGINE THAT CANNOT BUILD. placeAcross emits its planks flagged, so the leg
                    // is cut at the first plank and the rest goes to the physics search — which
                    // has no place move at all (capability table in docs/NAVIGATION.md). It only
                    // ever "worked" because the placement was forged and did not care where the
                    // body was; with placement going through the real ray trace it stopped dead
                    // at 11.6 blocks, 12 legs and 12 hand-offs a run with nobody walking.
                    // Route it like a pillar instead: to the component that owns BOTH the step
                    // and the placement.
                    var flaggedWp = res.path.get(physics);
                    // CUT BY MOVE KIND, AND FOR A BUILD CUT ONE CELL EARLIER. A waypoint that
                    // PLACES is not "physics" — the physics engine has no place move at all —
                    // so its run goes to BridgeTask, which owns the step and the placement
                    // together the way PillarTask owns a tower. Handing it over AT the lip was
                    // already tried and fell into the void, 22.5 three times: the walker's
                    // advance radius plus momentum carry the bot past the lip before the
                    // hand-off fires, so the owner inherits a body already in the air. Stopping
                    // the walker one cell SHORT lets the owner walk that last cell itself, with
                    // sneak, and arrive at the lip in control.
                    // CUT BY MOVE KIND. A waypoint that PLACES is not "physics" — that engine
                    // has no place move at all — so its run goes to BridgeTask, which owns the
                    // step and the placement together as PillarTask owns a tower. Cut one cell
                    // EARLIER for a build so the owner takes over BEFORE the lip: handing over
                    // AT the lip inherits a body already carried past it by the walker.
                    int runEnd = res.physicsRunEnd(physics);
                    // A PLACE RUN IS NOT PHYSICS, AND IT IS NOT A SEPARATE LEG EITHER. placeAcross
                    // emits its planks flagged viaJump (FastPlanner.java:958), which routed them to
                    // the one engine with no place move — pitfall P2. It is also why the previous
                    // three attempts handed the bridge over AT the lip and fell into the void 22.5
                    // blocks in: whoever inherits a body already carried past the edge has lost
                    // before it starts. So do not cut at all. The WALK edges and the PLACE edges go
                    // to the SAME owner, as one contiguous chain of MovementTraverse: the movement
                    // that steps onto the lip is the one that decides whether to sprint out of it
                    // (wasTheBridgeBlockAlwaysThere), and the next one sneaks and places. There is no
                    // hand-off left to fumble.
                    boolean placeRun = flaggedWp.toPlace != null && !flaggedWp.toPlace.isEmpty();
                    // ⛔ A TOWER GOES TO PillarTask, NOT TO A CHAIN OF MovementPillar STEPS (G42,
                    // 2026-09-11). A place run that starts straight UP is a pillar, and the queue's
                    // traverse prefix happily covers it -- "MovementQueue: 9 movement(s) -302,110,-213
                    // -> -302,115,-214 CLIMB+5". Measured on the 14:00 recorded run, under open sky:
                    // "step 2 has taken too long (126 ticks, expected 25) MovementPillar
                    // (-302,111,-211)->(-302,112,-211)" eleven times in four minutes, two blocks
                    // placed in all, the chain dropped and re-planned identically each time while a
                    // log lay on the canopy four blocks up. PillarTask is the tower primitive that
                    // clears pit_escape, nav_wall2 and drop_ledge (jump, place while airborne, stay
                    // centred); the ported per-step pillar with its sneak-pose click window through
                    // the mouse pipeline is not. So the leg is cut at the tower's foot and the top
                    // of the vertical run goes through the same hand-off a wall does.
                    int pillarTop = -1;
                    if (placeRun && TungstenConfig.get().pillarRunsGoToPillarTask) {
                        BlockPos foot = cells.get(physics - 1);
                        BlockPos first = flaggedWp.pos;
                        if (first.getX() == foot.getX() && first.getZ() == foot.getZ()
                                && first.getY() == foot.getY() + 1) {
                            int i = physics;
                            while (i + 1 < cells.size()
                                    && cells.get(i + 1).getX() == first.getX()
                                    && cells.get(i + 1).getZ() == first.getZ()
                                    && cells.get(i + 1).getY() == cells.get(i).getY() + 1) i++;
                            pillarTop = i;
                            navPillarRuns++;
                        }
                    }
                    int covered = placeRun && pillarTop < 0
                            ? kaptainwutax.tungsten.path.movements.MovementQueue.traversePrefix(
                                    cells.subList(0, Math.min(cells.size(), runEnd + 1)))
                            : 0;
                    // The chain must actually REACH the first cell that needs a block placed,
                    // otherwise routing it here achieves nothing and the queue would finish short of
                    // the gap, replan the identical plan and loop. Below that bar, keep the old path.
                    if (pillarTop >= 0) {
                        // Walk to the foot; the hand-off below sees "rise above jump height,
                        // nearly overhead" and starts PillarTask to the top of the run.
                        nextPhysicsTarget = cells.get(pillarTop);
                        cells = cells.subList(0, physics);
                    } else if (covered >= physics + 1) {
                        nextPhysicsTarget = null;
                        movementLeg = true;
                        cells = cells.subList(0, covered);
                    } else if (breakCell && TungstenConfig.get().navOwnsBreakRuns) {
                        // A DIG IS OURS: walk to the cell before it, then mine (see nextBreakCells).
                        nextPhysicsTarget = null;
                        nextBreakCells = new java.util.ArrayList<>(flaggedWp.toBreak);
                        nextBreakStand = cells.get(physics - 1);   // the node the dig was planned from
                        cells = cells.subList(0, physics);
                    } else {
                        nextPhysicsTarget = breakCell ? goalCell : cells.get(runEnd);
                        cells = cells.subList(0, physics);
                    }
                } else {
                    nextPhysicsTarget = null;
                    // A SLIME PAD IS ONE MANOEUVRE. Walk to its LIP and let the crossing own
                    // the pad itself, aimed at the first cell past it — which only the full
                    // route knows, since a truncated leg ends on the slime.
                    int padStart = -1, padExit = -1;
                    for (int i = 0; i < cells.size(); i++) {
                        BlockPos below = cells.get(i).down();
                        var st = world.getBlockState(below);
                        boolean slime = st.getBlock() instanceof net.minecraft.block.SlimeBlock;
                        if (slime && padStart < 0) padStart = i;
                        // "NOT SLIME" IS NOT THE SAME AS "SOMEWHERE TO STAND". The exit has to
                        // be a cell with a real floor under it: the first version took the
                        // first non-slime cell and aimed the crossing at x=14 — one step past
                        // the pad, straight over the void between it and the ledge — so the
                        // bot flew at it and fell (traced: horiz closing to 0.3 while dropping
                        // to y=-88).
                        boolean standable = !st.getCollisionShape(world, below).isEmpty();
                        if (padStart >= 0 && !slime && standable) { padExit = i; break; }
                    }
                    if (TungstenConfig.get().slimeCrossing && padStart > 0 && padExit > padStart) {
                        pendingCrossing = cells.get(padExit);
                        cells = cells.subList(0, padStart);
                    } else if (cells.size() > LEG_LENGTH) {
                        cells = cells.subList(0, LEG_LENGTH);
                    }
                }
                if (cells.size() >= 2) {
                    boolean builds = false;
                    for (int i = 1; i < Math.min(res.path.size(), cells.size()); i++) {
                        var w = res.path.get(i);
                        if (w.toPlace != null && !w.toPlace.isEmpty()) { builds = true; break; }
                    }
                    nextLegMovement = movementLeg;
                    // BridgeTask only gets a look-in when the MovementQueue did not take the leg;
                    // the two must never both be armed (two owners of the keys is pitfall P1).
                    nextLegBridge = builds && !movementLeg;
                    nextLeg = cells;
                } else if (nextPhysicsTarget != null) {
                    // The jump is the very FIRST move from here — there is nothing to walk.
                    // Hand it straight to physics instead of dropping the plan (the old code
                    // required size>=2 and silently discarded this case, which is exactly the
                    // "standing at the lip of the gap" state).
                    pendingPhysicsTarget = nextPhysicsTarget;
                    nextPhysicsTarget = null;
                } else if (nextBreakCells != null) {
                    // The dig is the very FIRST move from here: nothing to walk, mine now.
                    pendingBreakCells = nextBreakCells;
                    pendingBreakStand = nextBreakStand;
                    nextBreakCells = null;
                    nextBreakStand = null;
                }
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
