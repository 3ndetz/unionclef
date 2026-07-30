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
    /** The next leg ends at a jump the walker cannot do; this is where it lands. */
    private static volatile BlockPos nextPhysicsTarget = null;
    /** Same, for the leg currently being WALKED — consumed when the walker goes idle. */
    private static volatile BlockPos pendingPhysicsTarget = null;
    /** True while the physics engine is performing a jump we handed it. */
    private static volatile boolean awaitingPhysics = false;
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
        nextPhysicsTarget = null;
        pendingPhysicsTarget = null;
        pendingCrossing = null;
        awaitingPhysics = false;
        nextLegMovement = false;
        // A queue left running past the navigator would keep pressing keys with nobody steering.
        kaptainwutax.tungsten.path.movements.MovementQueue.stop();
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
        // BUILDING IS PROGRESS, even though the distance does not move. While the executor is
        // placing or mining, the bot stands still on purpose doing the work that makes the
        // rest of the route possible — and this watchdog counted that as failure and SHUT THE
        // NAVIGATOR DOWN, which is why a build route produced two plans in a whole run.
        var exec = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
        // A MovementQueue leg is building too: a sneak-backplace step spends several ticks
        // stationary while the aim converges, and the watchdog used to call that failure.
        boolean building = (exec != null && (exec.placeQueue != null || exec.breakQueue != null))
                || kaptainwutax.tungsten.path.movements.MovementQueue.isRunning();
        if (building) {
            stallTicks = 0;
        } else if (dist < lastDist - 0.25) {
            lastDist = dist;
            stallTicks = 0;
        } else if (++stallTicks > STALL_TICKS) {
            Debug.logWarning("FastNavigator: no progress, handing over");
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

        if (awaitingPhysics) {
            if (kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.active.get()
                    || kaptainwutax.tungsten.TungstenModDataContainer.isExecutorRunning()) {
                return;   // physics still working — do not touch the walker or the keys
            }
            awaitingPhysics = false;
            legTail = null;
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
                if (rise > PlayerFitJumpHeight() && horiz < 2.5
                        && TungstenConfig.get().planPlaceMoves
                        && !kaptainwutax.tungsten.task.PillarTask.isActive()) {
                    Debug.logMessage("Wall too high to jump — pillaring to y=" + jump.getY());
                    kaptainwutax.tungsten.task.PillarTask.startTo(jump.getY());
                    awaitingPhysics = false;
                    legTail = null;
                    return;
                }

                Debug.logMessage("FastNavigator: physics owns the jump -> "
                        + jump.getX() + "," + jump.getY() + "," + jump.getZ());
                BlockPathWalker.stop();      // the walker must not fight the jump
                nextLeg = null;              // drop any leg prepared for after the gap
                // Only commit to waiting if the search ACCEPTED the request. find() refuses
                // while a previous search is still tearing down, and it used to do so
                // silently — so we would sit in awaitingPhysics for a jump nobody was
                // computing, and the run stalled at the lip of the gap.
                boolean accepted = kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER.find(
                        world, Vec3d.ofBottomCenter(jump), player);
                if (accepted) {
                    awaitingPhysics = true;
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
            pendingIsBridge = nextPhysicsIsBridge;
            nextPhysicsTarget = null;
            nextPhysicsIsBridge = false;
            // ONE OWNER FOR A BUILD LEG. A leg whose route places blocks goes to the ported
            // MovementQueue: a chain of baritone MovementTraverse objects, each of which owns its own
            // one-block step — the walk, the aim, the sneak and the click — and none of which shares
            // a tick with the walker or the physics executor. This is the replacement for the split
            // path (walker moves the body, PathExecutor.tickPlacing aims and clicks), whose seam
            // measured clicked=0 across eleven thousand in-range ticks.
            boolean queued = false;
            if (nextLegMovement) {
                nextLegMovement = false;
                nextLegBridge = false;
                queued = kaptainwutax.tungsten.path.movements.MovementQueue.start(leg) > 0;
                if (!queued) {
                    // traversePrefix already vetted this leg on the planning thread, so a refusal
                    // here means the plan changed shape under us. Walk it instead of standing still.
                    Debug.logWarning("FastNavigator: MovementQueue refused the leg, walking it");
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
    /** The cut-out run is a BRIDGE (its waypoints place blocks), so BridgeTask owns it. */
    private static volatile boolean nextPhysicsIsBridge = false;
    private static volatile boolean pendingIsBridge = false;

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

    private static void planAhead(BlockPos from) {
        if (planning || goal == null) return;
        planning = true;
        // Read the pocket HERE, on the client thread — the search runs on its own thread and
        // must not touch the inventory, but it does need to know how long a bridge it may
        // promise (see FastPlanner.placeBudget).
        FastPlanner.placeBudget = FastPlanner.countPlaceable(TungstenMod.mc.player);
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
                    if (before - after < MIN_PARTIAL_PROGRESS) {
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
                    nextPhysicsIsBridge = false;
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
                    int covered = placeRun
                            ? kaptainwutax.tungsten.path.movements.MovementQueue.traversePrefix(
                                    cells.subList(0, Math.min(cells.size(), runEnd + 1)))
                            : 0;
                    // The chain must actually REACH the first cell that needs a block placed,
                    // otherwise routing it here achieves nothing and the queue would finish short of
                    // the gap, replan the identical plan and loop. Below that bar, keep the old path.
                    if (covered >= physics + 1) {
                        nextPhysicsTarget = null;
                        movementLeg = true;
                        cells = cells.subList(0, covered);
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
