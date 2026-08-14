package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.block.BlockState;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.WorldView;

/**
 * Core entity-following engine. Contains ALL routing logic:
 * LEAP (PvP close-range), Tungsten A*, TRAILING.
 *
 * Two usage modes:
 *   1. Direct:  start(entity, closeEnough) — auto-stops when entity is removed
 *   2. Managed: startManaged(closeEnough) + updateTarget() — FollowPlayerTask
 *               controls the entity lifecycle; continues with lastKnownPos on removal
 */
public class FollowEntityTask {

    private static final double LEAP_DIST          = 6.0;
    private static final double DEFAULT_CLOSE_ENOUGH = 2.0;
    // Re-plan hysteresis. The old values (recalc every 15 ticks when the target
    // moved >1.5 blocks) killed the search every 0.75s — a sprinting target
    // moves 4+ blocks in that window, so the pathfinder (budget 0.5-3s) was
    // restarted forever and never emitted a path: the bot just stood there.
    private static final int    RECALC_TICKS       = 40;   // min 2s between re-plans
    /** Ticks of zero horizontal progress with the walker "running" before we force a re-plan. */
    // 30 ticks is 1.5 SECONDS, and that constant had never actually run: it was declared and
    // never read until the watchdog was wired up. A walker climbing a hillside legitimately makes
    // no HORIZONTAL progress for longer than that, so the first live value was measured, not
    // assumed — the log showed the same route restarted 80 times ("Walker: BFS 82" x80).
    private static final int    WALKER_STUCK_TICKS = 80;   // 4s
    private static Vec3d walkerAnchor = null;
    private static int   walkerStuckTicks = 0;
    /** Why the chase stands still, in four numbers. `planTooShort` = the block planner returned
     *  fewer than two cells and the plan was dropped with no alternative; `physicsFallbacks` =
     *  times the physics search was asked instead. Read over py4j as chaseStats. */
    public static volatile int planCalls, planUsable, planTooShort, physicsFallbacks;
    /** Of the cells a chase route contains, how many the PORTED movements could actually take —
     *  they cover flat cardinal steps only. The ratio says whether unit 2 as ported can drive a
     *  chase over terrain at all. */
    public static volatile int traversableCells, routeCells, routeSamples;

    /** Engine-independent jam detection (see the watchdog in tick()). */
    private static Vec3d jamAnchor = null;
    private static int   jamTicks = 0;
    private static final double MIN_MOVE_DIST      = 3.0;  // absolute floor for the threshold
    private static final int    STUCK_TICKS        = 30;

    // ── state ───────────────────────────────────────────────────────────────────
    private static Entity  targetEntity    = null;
    private static Vec3d   lastKnownPos    = null;
    private static boolean active          = false;
    /** Rate-limit for the local physics leg (the obstacle hand-off). */
    private static final long LOCAL_LEG_COOLDOWN_MS = 3000;
    private static long lastLocalLegMs = 0;
    private static double  closeEnough     = DEFAULT_CLOSE_ENOUGH;
    private static boolean managed         = false; // true = FollowPlayerTask controls entity

    // ── LEAP mode (PvP close-range: sprint+jump, no camera — altoclef handles aim) ─
    private static boolean leapActive = false;

    // ── pathfinder state ────────────────────────────────────────────────────────
    private static Vec3d   lastTargetPos  = null;
    private static int     tickCounter    = 0;
    private static int     stuckTicks     = 0;
    private static boolean stopRequested  = false;

    // ── live direct-steer cooldown ────────────────────────────────────────────────
    // After a live-steer attempt bails (stall against a wall / LOS lost / danger),
    // don't re-steer straight into the same obstacle — yield to BFS + physics A* for
    // a window so it can path AROUND before we try the straight line again.
    private static int     steerCooldownTicks     = 0;
    private static boolean steerRequestedLastTick = false;
    private static final int STEER_COOLDOWN = 40;   // ~2s of BFS/physics after a bail

    // ── TRAILING ────────────────────────────────────────────────────────────────
    private static final TrailTracker trail = new TrailTracker("FollowEntity");

    // ─────────────────────────────────────────────────────────────────────────────

    /** Start following an entity directly. Auto-stops when entity is removed. */
    public static void start(Entity entity) {
        start(entity, DEFAULT_CLOSE_ENOUGH);
    }

    /** Start following an entity directly with custom distance. */
    public static void start(Entity entity, double closeEnough) {
        resetState();
        targetEntity = entity;
        FollowEntityTask.closeEnough = closeEnough;
        managed = false;
        active = true;
        Debug.logMessage("Following: " + (entity != null ? entity.getName().getString() : "null"));
    }

    /** Start in managed mode (FollowPlayerTask controls entity via updateTarget). */
    public static void startManaged(double closeEnough) {
        resetState();
        FollowEntityTask.closeEnough = Math.max(closeEnough, 0.5);
        managed = true;
        active = true;
    }

    private static void resetState() {
        targetEntity       = null;
        lastKnownPos       = null;
        lastTargetPos      = null;
        tickCounter        = 0;
        stuckTicks         = 0;
        walkerAnchor       = null;
        walkerStuckTicks   = 0;
        stopRequested      = false;
        leapActive         = false;
        steerCooldownTicks = 0;
        steerRequestedLastTick = false;
        trail.reset();
    }

    public static void stop() {
        // A chase that started the queue must also be able to end it — see StopCommand.
        kaptainwutax.tungsten.path.movements.MovementQueue.stop();
        active             = false;
        managed            = false;
        targetEntity       = null;
        lastKnownPos       = null;
        leapActive         = false;
        stopRequested      = false;
        stuckTicks         = 0;
        walkerAnchor       = null;
        walkerStuckTicks   = 0;
        trail.reset();
        BlockPathWalker.stop();
        releaseKeys();
        TungstenModDataContainer.PATHFINDER.stop.set(true);
        if (TungstenModDataContainer.EXECUTOR != null) TungstenModDataContainer.EXECUTOR.stop = true;
        Debug.logMessage("Follow stopped.");
    }

    /** Update target entity without resetting pathfinding state. */
    public static void updateTarget(Entity entity) {
        if (entity != targetEntity) {
            targetEntity = entity;
        }
    }

    public static boolean isActive()  { return active; }

    // Lifetime counters for the chase. Never reset: a per-fight counter read after the run
    // reports zero, which already misled once this session.
    public static volatile int followTicks = 0, steerTicks = 0,
            leapTicks = 0, cooldownTicks = 0, losBlocked = 0;
    // Counted at the very TOP, because the first version of this counter sat deep in the
    // method behind several early returns and so measured "reached the steering decision",
    // not "was called" — a difference that would have been read as the bot idling.
    public static volatile int tickCalled = 0, tickInactive = 0, tickActive = 0;
    public static Entity  getTarget() { return targetEntity; }
    public static boolean isManaged() { return managed; }

    // ─────────────────────────────────────────────────────────────────────────────

    /** Called every game tick from MixinClientPlayerEntity. */
    public static void tick(WorldView world, ClientPlayerEntity player) {
        tickCalled++;
        if (!active) { tickInactive++; return; }
        tickActive++;

        // resolve target position
        Vec3d   targetPos;
        boolean hasEntity;

        if (targetEntity != null && !targetEntity.isRemoved()) {
            targetPos    = snapToGround(world, targetEntity);
            lastKnownPos = targetPos;
            hasEntity    = true;
        } else if (lastKnownPos != null) {
            // The entity object is gone — but on a long chase that usually means the
            // prey simply ran out of the client's tracking range, not that it
            // vanished. Stopping there is why a 150-block chase died silently with
            // every engine idle: keep travelling to the last place we saw it, and
            // re-acquire when it comes back into range. (Managed mode always did
            // this; direct mode used to just stop.)
            targetPos = lastKnownPos;
            hasEntity = false;
            if (!managed && player.getEntityPos().distanceTo(lastKnownPos) < 3.0) {
                stop();   // arrived at the last sighting and it is really not there
                return;
            }
        } else {
            return; // managed but no position known yet
        }

        double dist          = player.getEntityPos().distanceTo(targetPos);
        boolean outsideRadius = closeEnough <= 0 || dist >= closeEnough;

        // ── Trail recording + TRAILING state ───────────────────────────────────
        if (kaptainwutax.tungsten.TungstenConfig.get().enableTrailing) {
            if (hasEntity) trail.recordPosition(targetPos);
            trail.update(player.getEntityPos(), targetPos);
        }

        // ── LEAP: PvP close-range sprint+jump (no camera — altoclef handles aim+attacks)
        if (kaptainwutax.tungsten.TungstenConfig.get().enableLeap) {
            boolean canLeap = dist < LEAP_DIST && outsideRadius
                    && hasEntity && hasLineOfSight(player, targetPos)
                    && isFlatGround(player, targetPos);

            if (canLeap && !TungstenModDataContainer.isExecutorRunning()) {
                doLeap(player);
                leapActive = true;
            } else if (leapActive) {
                releaseLeapKeys();
                leapActive = false;
            }
        } else if (leapActive) {
            releaseLeapKeys();
            leapActive = false;
        }
        // A* always runs — fall through to pathfinding below

        // ── Within closeEnough: hold position ─────────────────────────────────
        if (closeEnough > 0 && !outsideRadius && hasEntity) {
            return;
        }

        // ── Resolve effective target: waypoint when TRAILING, else real target ─
        Vec3d effectiveTarget = targetPos;
        if (kaptainwutax.tungsten.TungstenConfig.get().enableTrailing && trail.isTrailing()) {
            Vec3d wp = trail.getWaypoint(player.getEntityPos());
            if (wp != null) {
                effectiveTarget = wp;
            }
        }
        double effectiveDist = player.getEntityPos().distanceTo(effectiveTarget);

        // ── LIVE DIRECT-STEER (moving target with a clear line) ───────────────
        // If we can SEE the target, sprint STRAIGHT at its LIVE position instead of
        // pathing to a ~2s-stale snapshot. The walker re-aims every tick from the
        // bot's real position (drift-immune), so the bot cuts across and CLOSES on a
        // runner — the physics executor traced the target's PAST path ~30 blocks
        // behind and drift-stopped (churn, never caught up). tickDirect self-guards
        // holes/ledges/landing and stall; on any of those the walker stops and we
        // fall through to the BFS + physics A* flow below. Defer to LEAP if active.
        // If a prior live-steer just bailed, the walker will have stopped — start a
        // cooldown so we don't re-steer into the same wall before BFS can route around.
        if (steerRequestedLastTick && !BlockPathWalker.isRunning()
                && BlockPathWalker.wasStoppedByBail()) {
            steerCooldownTicks = STEER_COOLDOWN;
        }
        steerRequestedLastTick = false;
        if (steerCooldownTicks > 0) steerCooldownTicks--;

        // Counters over py4j, because this is the question the chat cannot answer: of the
        // ticks the chase gets, how many actually steer, and which gate eats the rest.
        followTicks++;
        if (leapActive) leapTicks++;
        if (steerCooldownTicks > 0) cooldownTicks++;
        if (!hasLineOfSight(player, effectiveTarget.add(0, 1.0, 0))) losBlocked++;

        // THE QUEUE OWNS THE BODY WHILE IT RUNS, SO DO NOT STEER OVER IT. steerLive() would set
        // the walker active with a fresh direct target that the mixin never ticks — a zombie
        // owner that resumes a stale steer the moment the chain ends.
        boolean queueRunning = kaptainwutax.tungsten.path.movements.MovementQueue.isRunning();
        if (hasEntity && !leapActive && !queueRunning && steerCooldownTicks == 0
                && effectiveDist > Math.max(closeEnough, 1.5)
                && hasLineOfSight(player, effectiveTarget.add(0, 1.0, 0))) {  // body-centre, not feet (terrain lips)
            steerTicks++;
            // keep the drift-prone physics path OFF so it can't seize the executor
            if (TungstenModDataContainer.PATHFINDER.active.get())
                TungstenModDataContainer.PATHFINDER.stop.set(true);
            if (TungstenModDataContainer.EXECUTOR != null
                    && TungstenModDataContainer.EXECUTOR.isRunning())
                TungstenModDataContainer.EXECUTOR.stop = true;
            BlockPathWalker.steerLive(effectiveTarget);
            steerRequestedLastTick = true;
            lastTargetPos = effectiveTarget;
            tickCounter = 0;
            stuckTicks = 0;
            return;
        }

        // ── Tungsten A*: always runs as primary pathfinder ───────────────────
        // While BFS walker is running, suppress recalc — let it finish its segment.
        // A* is already computing from BFS endpoint; recalc would restart everything.
        // "IS SOMETHING ALREADY WALKING THIS ROUTE" — the queue counts as much as the walker.
        // Reading only the walker made a queue-driven chase look permanently IDLE: the 40-tick
        // replan fired every time, ran FastPlanner synchronously on the client thread, and then
        // threw the result away because the hand-off below refuses while the queue is running.
        boolean walkerRunning   = BlockPathWalker.isRunning() || queueRunning;
        tickCounter++;
        boolean executorRunning  = TungstenModDataContainer.isExecutorRunning();
        boolean pathfinderActive = TungstenModDataContainer.PATHFINDER.active.get();

        // WHICH GATE IS SHUTTING? 7 complete plans and 4 walked routes in 180 s, with an
        // interval of 40 ticks, means the planning branch is reached far less often than it
        // could be — and there are four booleans that decide it. Print them rather than guess;
        // every diagnostic in this repo that paid off printed what the code SAW.
        if (kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging
                && (tickCounter % 40 == 0)) {
            kaptainwutax.tungsten.Debug.logMessage(String.format(
                    "FOLLOWGATE walker=%b stopReq=%b pf=%b exec=%b dist=%.1f tc=%d",
                    walkerRunning, stopRequested, pathfinderActive, executorRunning,
                    effectiveDist, tickCounter));
        }

        // THE BLOCK ROUTE MUST NOT WAIT FOR THE PHYSICS ENGINE TO FINISH. Every branch below
        // is gated on physics being idle, so during a chase — where the simulation search runs
        // almost continuously — the block route was computed only when physics happened to be
        // between searches. Measured on chase_terrain: four route walks in 180 seconds.
        // TODOS.md AC-2.3 puts it the other way round: the block route comes FIRST and physics
        // is the last resort, so plan and walk it on its own cadence while physics does whatever
        // it is doing. Only when the walker is idle — a running walker is already following one.
        // A ROUTE TO WHERE THE RUNNER WAS IS NOT A CHASE. The gate diagnostic settled this:
        // `walker=true` in 91 samples of 91, i.e. the walker is running essentially the whole
        // pursuit, so every planning branch is skipped by design — and the route it is walking
        // is 25-31 blocks long, aimed at where the target stood when it was planned. By the far
        // end the runner is long gone. TODOS.md AC-1.2: a moving target is tracked by REPLACING
        // the plan as it strays, not by finishing a stale one first. So re-plan mid-walk once
        // the target has moved a meaningful fraction of the remaining distance — the same
        // staleness test the physics branch below already uses.
        boolean targetStrayed = lastTargetPos != null
                && effectiveTarget.distanceTo(lastTargetPos)
                        > Math.max(MIN_MOVE_DIST, effectiveDist * 0.25);
        if ((!walkerRunning || targetStrayed) && !stopRequested
                && kaptainwutax.tungsten.TungstenConfig.get().followBlockPathFinderEnabled
                && effectiveDist > 6
                && tickCounter >= RECALC_TICKS) {
            lastTargetPos = effectiveTarget;
            tickCounter = 0;
            // DRIVING THE CHASE THROUGH FastNavigator — MEASURED WORSE, REVERTED. The
            // reasoning still looks right and is worth keeping written down: everything that
            // makes the nav suite 12/12 (handing a climb to PillarTask, a build to MovementQueue,
            // cutting legs by move kind) lives in the navigator, and the chase bypasses all of it
            // by driving BlockPathWalker directly. But routing the approach through
            // FastNavigator.start(target) did not take chase_terrain's gate AND made the passing
            // course worse: chase_flat avg dist 3.74 -> 4.9 (gate < 7). A moving goal restarts
            // the navigator's leg planning from scratch on every re-aim, which is the opposite of
            // AC-1.2. Extending a plan towards a moving target is the thing that has to be built;
            // pointing a static-goal navigator at a runner is not it.
            var res = kaptainwutax.tungsten.path.fast.FastPlanner.plan(
                    world, planStart(world, player),
                    net.minecraft.util.math.BlockPos.ofFloored(effectiveTarget),
                    kaptainwutax.tungsten.TungstenConfig.get().fastPlanBudgetMs);
            var cells = res.positions();
            // COUNT WHAT HAPPENS TO THE PLAN. A plan of fewer than two cells is dropped here
            // WITHOUT an alternative, and the tick counter has just been reset — so if the planner
            // keeps returning short, nobody drives the bot at all. That is exactly the state the
            // chase freezes in (`path=-1 nav=false pfActive=false`), and "the plan was short" and
            // "the physics fallback refused" need opposite fixes. Telemetry only; read as chaseStats.
            planCalls++;
            if (cells.size() >= 2) {
                planUsable++;
                BlockPathWalker.startBFS(cells, true);
            } else {
                planTooShort++;
            }
        }

        if (walkerRunning) {
            // A WALKER THAT IS "RUNNING" AND MOVING NOTHING IS THE ONE STATE NOTHING COULD SEE.
            // The replan gate needs (!walkerRunning || targetStrayed); a stuck walker fails the
            // first, and a chase heading for a REMEMBERED position fails the second, so the gate
            // is shut from both sides. And this branch used to reset stuckTicks outright — the
            // watchdog was cleared by exactly the state it exists for, while its own trigger sits
            // in a later else-if a running walker can never reach.
            //
            // Measured with the FOLLOWGATE diagnostic, 440 ticks of it:
            //   FOLLOWGATE walker=true stopReq=false pf=false exec=false dist=86.1 tc=2720..3160
            // walker running, distance frozen, bot not moving, nobody replanning.
            //
            // WALKER_STUCK_TICKS, walkerAnchor and walkerStuckTicks were declared for precisely
            // this — with a javadoc saying "ticks of zero horizontal progress with the walker
            // running before we force a re-plan" — and NOTHING READ THEM. Wiring them up is the
            // whole fix: stopping the walker opens the replanning path that already exists (and
            // BlockPathWalker.stop() also releases any armed executor path).
            Vec3d hereNow = player.getEntityPos();
            if (walkerAnchor == null
                    || Math.hypot(hereNow.x - walkerAnchor.x, hereNow.z - walkerAnchor.z) > 0.25) {
                walkerAnchor = hereNow;
                walkerStuckTicks = 0;
            } else if (++walkerStuckTicks >= WALKER_STUCK_TICKS) {
                kaptainwutax.tungsten.Debug.logMessage(
                        "Chase: walker running but not moving — stopping it so the route can be replanned");
                BlockPathWalker.stop();
                walkerStuckTicks = 0;
                walkerAnchor = null;
            }
            // walker active — don't touch pathfinder, just let it compute
            stuckTicks = 0;
        } else if (!pathfinderActive && !executorRunning && !stopRequested) {
            stuckTicks = 0;
            physicsFallbacks++;
            startFind(world, player, effectiveTarget, effectiveDist);
        } else if (stopRequested && !pathfinderActive) {
            stopRequested = false;
            stuckTicks    = 0;
            startFind(world, player, effectiveTarget, effectiveDist);
        } else if (!stopRequested && tickCounter >= RECALC_TICKS
                && lastTargetPos != null
                && effectiveTarget.distanceTo(lastTargetPos)
                        > Math.max(MIN_MOVE_DIST, effectiveDist * 0.25)) {
            // Re-plan only when the goal strayed by a meaningful fraction of the
            // remaining distance — a far target drifting sideways does not
            // invalidate the path start. Stop pathfinder but keep executor
            // running: the bot continues along the current path meanwhile.
            TungstenModDataContainer.PATHFINDER.stop.set(true);
            stopRequested = true;
            tickCounter   = 0;
        } else if (!executorRunning && !pathfinderActive) {
            if (++stuckTicks >= STUCK_TICKS) {
                TungstenModDataContainer.PATHFINDER.stop.set(true);
                stopRequested = true;
                stuckTicks    = 0;
            }
        } else {
            stuckTicks = 0;
        }

    }

    /**
     * Where a plan should START, ported from baritone's {@code PathingBehavior.pathStart()}
     * (baritone/src/main/java/baritone/behavior/PathingBehavior.java:423-461).
     *
     * <p>WHY: a plan seeded from the raw feet cell is unplannable whenever that cell has no
     * floor — standing off the edge of a block, or mid-jump — and the search returns a
     * one-waypoint stump. Measured during a chase, where the bot is airborne most of the time:
     * of 97 plans, **93 came back "partial" with ONE waypoint** and were discarded, leaving
     * two walked routes in three minutes. Upstream solves it by faking a plausible start:
     * the nearest neighbouring cell we could be sneaking off, or the cell below when falling.
     */
    private static net.minecraft.util.math.BlockPos planStart(WorldView world,
                                                              ClientPlayerEntity player) {
        net.minecraft.util.math.BlockPos feet = player.getBlockPos();
        net.minecraft.util.math.BlockPos.Mutable scratch = new net.minecraft.util.math.BlockPos.Mutable();
        scratch.set(feet.getX(), feet.getY(), feet.getZ());
        if (!Double.isNaN(kaptainwutax.tungsten.helpers.PlayerFit.supportTop(world, scratch))) {
            return feet;
        }
        double px = player.getEntityPos().x, pz = player.getEntityPos().z;
        if (player.isOnGround()) {
            java.util.List<net.minecraft.util.math.BlockPos> closest = new java.util.ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    closest.add(new net.minecraft.util.math.BlockPos(
                            feet.getX() + dx, feet.getY(), feet.getZ() + dz));
                }
            }
            closest.sort(java.util.Comparator.comparingDouble(pos ->
                    ((pos.getX() + 0.5D) - px) * ((pos.getX() + 0.5D) - px)
                            + ((pos.getZ() + 0.5D) - pz) * ((pos.getZ() + 0.5D) - pz)));
            for (int i = 0; i < 4 && i < closest.size(); i++) {
                net.minecraft.util.math.BlockPos support = closest.get(i);
                double xDist = Math.abs((support.getX() + 0.5D) - px);
                double zDist = Math.abs((support.getZ() + 0.5D) - pz);
                if (xDist > 0.8 && zDist > 0.8) continue;   // too far to be sneaking off it
                scratch.set(support.getX(), support.getY(), support.getZ());
                if (!Double.isNaN(kaptainwutax.tungsten.helpers.PlayerFit.supportTop(world, scratch))
                        && kaptainwutax.tungsten.helpers.PlayerFit.bodyFits(
                                world, support.getX() + 0.5, support.getY(), support.getZ() + 0.5)) {
                    return support;
                }
            }
        } else {
            // mid-jump or falling: the cell below is where we are heading
            scratch.set(feet.getX(), feet.getY() - 1, feet.getZ());
            if (!Double.isNaN(kaptainwutax.tungsten.helpers.PlayerFit.supportTop(world, scratch))) {
                return feet.down();
            }
        }
        return feet;
    }

    /** How far along the block route the physics search is aimed. FastNavigator's LEG_LENGTH is
     *  32 cells and it is the reason far goals never reach the physics engine there. */
    private static final int PHYSICS_LEG_CELLS = 32;

    private static void startFind(WorldView world, ClientPlayerEntity player, Vec3d target, double dist) {
        java.util.List<net.minecraft.util.math.BlockPos> bfsGuide = null;
        tickCounter   = 0;
        lastTargetPos = target;
        TungstenMod.TARGET = target;
        TungstenMod.markGotoTarget();

        // ── THE BLOCK ROUTE, FROM THE PLANNER THAT CAN ACTUALLY REACH ────────────────
        // This branch used CombatPathfinder, whose search is capped at MAX_RADIUS = 25 blocks
        // (CombatPathfinder.java:29). A chase runs over far greater distances than that — the
        // bench sends the runner 140 blocks — so the route could never be built, the branch
        // silently produced nothing, and the pursuit fell through to a beeline plus the PHYSICS
        // A*. Measured on chase_terrain: contact=None, kills=0 over 120 s, with ZERO
        // "FastPlanner:", ZERO "Walker: BFS" and ZERO "MovementQueue:" lines in the whole run.
        // That is the user's "100+ blocks behind" complaint, and it is an engine mismatch rather
        // than a tuning problem: the slow simulation search was being asked to keep up with a
        // runner that flees on baritone's block route.
        //
        // FastPlanner is the block planner that drives the bot everywhere else, has no radius
        // cap, and expands 202 nodes in 1.7 ms. Per TODOS.md AC-2.1 the block route comes FIRST
        // and physics stays the last resort — which is what the physics search below now is.
        if (kaptainwutax.tungsten.TungstenConfig.get().followBlockPathFinderEnabled && dist > 6) {
            var fastRes = kaptainwutax.tungsten.path.fast.FastPlanner.plan(
                    world, planStart(world, player),
                    net.minecraft.util.math.BlockPos.ofFloored(target),
                    kaptainwutax.tungsten.TungstenConfig.get().fastPlanBudgetMs);
            java.util.List<net.minecraft.util.math.BlockPos> bfsPath = fastRes.positions();
            bfsGuide = bfsPath;
            if (bfsPath.size() >= 2) {
                // IF WE HAVE A ROUTE, WALK THE ROUTE. A beeline is what you do when you have
                // no route, not what you do instead of one. `start(target, path)` begins in
                // DIRECT mode with the path merely as a fallback (BlockPathWalker.java:87), and
                // measured on chase_terrain that is what the chase actually did: over ~180 s the
                // log shows "Walker: direct" 3 times and "Walker: BFS" ZERO — a plan was
                // computed and then ignored while the bot sprinted at a runner it never caught
                // (contact=None, kills=0). AC-1.4 says the exact block route is run IMMEDIATELY.
                // HOW MUCH OF A CHASE ROUTE COULD THE PORTED MOVEMENTS TAKE? MovementQueue only
                // accepts same-Y cardinal steps (isTraverseEdge); climbs, drops and diagonals are
                // "a different movement class that this port does not include yet". On a course
                // named chase_terrain that is the whole question, so it gets a number instead of
                // an opinion. Telemetry only — nothing branches on it.
                kaptainwutax.tungsten.path.movements.MovementQueue.histogram(bfsPath);
                int pfx = kaptainwutax.tungsten.path.movements.MovementQueue.traversePrefix(bfsPath);
                traversableCells += pfx;
                routeCells += bfsPath.size();
                routeSamples++;
                // THE CHASE KEEPS ITS BLOCK ROUTE. Without this the physics search below starts
                // the executor and the walker switches itself off — measured as the same route
                // restarted eighty times without progress. AC-2.1: block route first, physics last.
                // THE CHASE'S ROUTE GOES TO THE PORTED MOVEMENTS, NOT THE WALKER.
                // Until C5.18 MovementQueue.start() had exactly ONE caller — FastNavigator:373 —
                // and only for a leg that PLACES blocks. Every plain walk in the mod, nav and
                // chase alike, was therefore the hand-rolled BlockPathWalker, and a clean
                // FOLLOWGATE read (walker=true, a route in hand, replanning every 40 ticks,
                // dist=89.3 unmoved) says the walker cannot cross this terrain. The ported
                // traverse/ascend/descend/diagonal classes CAN — they carry the jump, the
                // overshoot off a lip and the head-bonk check — but nothing was handing them a
                // chase route. This does.
                boolean queued = false;
                if (kaptainwutax.tungsten.TungstenConfig.get().chaseUsesQueue
                        && !kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()) {
                    // THE CHASE ASKS FOR THE WHOLE ROUTE, whatever the global default is: that is
                    // the configuration the pairs above were measured in, and the navigator's
                    // build legs must not be dragged along with it.
                    queued = kaptainwutax.tungsten.path.movements.MovementQueue
                            .start(bfsPath, true) > 0;
                }
                if (!queued && !kaptainwutax.tungsten.path.movements.MovementQueue.isRunning()) {
                    BlockPathWalker.startBFS(bfsPath, true);
                }
                // Physics A* starts from BFS endpoint — don't waste time on
                // the segment the walker already covers
                // Root the physics search at the walker's endpoint ONLY while the
                // walker is actually getting there. If it just bailed (a 2-block
                // wall reads as "danger" and it cannot climb), a path rooted 17
                // blocks ahead is unreachable: it waits armed, the walker keeps
                // bailing, and the chase stands at the foot of the wall forever
                // (stand-measured on a mountainside). Rooting HERE instead lets
                // the physics engine solve the climb that blocks us right now.
                Vec3d bfsEnd = BlockPathWalker.getEndpoint();
                if (bfsEnd != null && !BlockPathWalker.wasStoppedByBail()) {
                    TungstenModDataContainer.PATHFINDER.overrideStartPos = bfsEnd;
                } else {
                    TungstenModDataContainer.PATHFINDER.overrideStartPos = null;
                }
            }

            // NB the fast route is NOT walked here. It is the physics search's
            // block-space guide (PathFinder.findBlockPath) — tungsten's advantage
            // is executing a route with real physics and jumps, and a second
            // navigator sprinting the same cells only fought the first one.
        }

        if (dist < 6 && hasLineOfSight(player, target)) {
            TungstenConfig.get().searchTimeoutMs      = 120L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 1;
            TungstenModDataContainer.PATHFINDER.minDistPath           = 0.1;
        } else if (dist < 12) {
            TungstenConfig.get().searchTimeoutMs      = 500L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 2;
            TungstenModDataContainer.PATHFINDER.minDistPath           = 0.3;
        } else if (dist < 25) {
            TungstenConfig.get().searchTimeoutMs      = 1500L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 3;
            TungstenModDataContainer.PATHFINDER.minDistPath           = 0.5;
        } else {
            TungstenConfig.get().searchTimeoutMs      = 3000L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 5;
            TungstenModDataContainer.PATHFINDER.minDistPath           = 0.8;
        }
        // HAND PHYSICS A LEG, NOT THE WHOLE CHASE. The physics search was being given the FULL
        // target — measured at 87 blocks — and this file's own history says what that costs: a
        // search that cannot finish burns its entire budget and restarts, forever. It is also
        // exactly what the navigator that keeps nav at 12/12 never does: FastNavigator cuts the
        // route into LEG_LENGTH pieces and only ever hands physics the piece in front of it.
        //
        // Measured after the walker watchdog was wired up, which unblocked replanning and left
        // this as the next blocker:
        //   FOLLOWGATE walker=false stopReq=false pf=true exec=false dist=87.7 tc=40
        // planning resumed, physics active, bot motionless, distance unchanged.
        //
        // So when a block route exists, aim physics at a point ALONG it rather than at the runner.
        Vec3d physicsGoal = target;
        if (bfsGuide != null && bfsGuide.size() >= 2) {
            int leg = Math.min(bfsGuide.size() - 1, PHYSICS_LEG_CELLS);
            var cell = bfsGuide.get(leg);
            physicsGoal = new Vec3d(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
        }
        TungstenModDataContainer.PATHFINDER.find(world, physicsGoal, player);
    }

    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * LEAP: PvP close-range movement — sprint forward + jump (crit hits).
     * NO camera rotation — altoclef controls aim and attacks.
     * Only used on flat ground with LOS to target.
     */
    private static void doLeap(ClientPlayerEntity player) {
        MinecraftClient mc = MinecraftClient.getInstance();
        // Movement only — camera is altoclef's responsibility
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.jumpKey.setPressed(player.isOnGround());
    }

    /** Release movement keys set by LEAP (does NOT touch camera/WindMouse). */
    private static void releaseLeapKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
    }

    /** Release all keys including WindMouse rotation (used by stop()). */
    private static void releaseKeys() {
        releaseLeapKeys();
        WindMouseRotation.INSTANCE.clearTarget();
    }

    /**
     * Quick check: safe to sprint-leap directly?
     * Flat ground between player and target — no voids, no lava, no walls.
     * Prevents LEAP on SkyWars edges, bridges, etc.
     */
    private static boolean isFlatGround(ClientPlayerEntity player, Vec3d targetPos) {
        if (!player.isOnGround()) return false;
        if (Math.abs(targetPos.y - player.getY()) > 1.5) return false;

        Vec3d pos = player.getEntityPos();
        double dx = targetPos.x - pos.x;
        double dz = targetPos.z - pos.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0) return true;

        dx /= len;
        dz /= len;
        int playerY = player.getBlockPos().getY();
        WorldView world = TungstenMod.mc.world;

        int steps = Math.min((int) len, 5);
        for (int i = 1; i <= steps; i++) {
            BlockPos check = new BlockPos(
                (int) Math.floor(pos.x + dx * i),
                playerY,
                (int) Math.floor(pos.z + dz * i));
            BlockPos below = check.down();
            // Ground must be solid (no voids, no lava below)
            BlockState ground = world.getBlockState(below);
            if (!ground.isSolidBlock(world, below)) return false;
            // Feet and head level must be passable (no walls)
            if (world.getBlockState(check).isSolidBlock(world, check)) return false;
            if (world.getBlockState(check.up()).isSolidBlock(world, check.up())) return false;
        }
        return true;
    }

    /**
     * Snap entity position to the nearest solid block below its feet.
     * Handles: sneaking on block edges (getBlockPos() returns air),
     * standing on fences/slabs, or any case where feet Y is above ground.
     */
    private static Vec3d snapToGround(WorldView world, Entity entity) {
        double x = entity.getX();
        double z = entity.getZ();
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int startY = (int) Math.floor(entity.getY());

        // Scan down up to 5 blocks to find solid ground
        for (int dy = 0; dy <= 5; dy++) {
            BlockPos check = new BlockPos(blockX, startY - dy, blockZ);
            BlockState state = world.getBlockState(check);
            if (!state.isAir() && state.isSolidBlock(world, check)) {
                // Target is on top of this block
                return new Vec3d(blockX + 0.5, check.getY() + 1.0, blockZ + 0.5);
            }
            // Also check for non-full blocks (fences, slabs) that have collision
            if (!state.getCollisionShape(world, check).isEmpty()) {
                return new Vec3d(blockX + 0.5, entity.getY(), blockZ + 0.5);
            }
        }
        // Fallback: use entity position as-is
        return new Vec3d(blockX + 0.5, entity.getY(), blockZ + 0.5);
    }

    /** True if no solid block obstructs the line from player eyes to targetPos. */
    static boolean hasLineOfSight(ClientPlayerEntity player, Vec3d targetPos) {
        Vec3d eyePos = player.getEyePos();
        RaycastContext ctx = new RaycastContext(eyePos, targetPos,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player);
        return TungstenMod.mc.world.raycast(ctx).getType() == HitResult.Type.MISS;
    }
}
