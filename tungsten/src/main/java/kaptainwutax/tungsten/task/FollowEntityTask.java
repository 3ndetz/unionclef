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
    private static final int    WALKER_STUCK_TICKS = 30;   // 1.5s
    private static Vec3d walkerAnchor = null;
    private static int   walkerStuckTicks = 0;
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
        stopRequested      = false;
        leapActive         = false;
        steerCooldownTicks = 0;
        steerRequestedLastTick = false;
        trail.reset();
    }

    public static void stop() {
        active             = false;
        managed            = false;
        targetEntity       = null;
        lastKnownPos       = null;
        leapActive         = false;
        stopRequested      = false;
        stuckTicks         = 0;
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

        if (hasEntity && !leapActive && steerCooldownTicks == 0
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
        boolean walkerRunning   = BlockPathWalker.isRunning();
        tickCounter++;
        boolean executorRunning  = TungstenModDataContainer.isExecutorRunning();
        boolean pathfinderActive = TungstenModDataContainer.PATHFINDER.active.get();

        // THE BLOCK ROUTE MUST NOT WAIT FOR THE PHYSICS ENGINE TO FINISH. Every branch below
        // is gated on physics being idle, so during a chase — where the simulation search runs
        // almost continuously — the block route was computed only when physics happened to be
        // between searches. Measured on chase_terrain: four route walks in 180 seconds.
        // TODOS.md AC-2.3 puts it the other way round: the block route comes FIRST and physics
        // is the last resort, so plan and walk it on its own cadence while physics does whatever
        // it is doing. Only when the walker is idle — a running walker is already following one.
        if (!walkerRunning && !stopRequested
                && kaptainwutax.tungsten.TungstenConfig.get().followBlockPathFinderEnabled
                && effectiveDist > 6
                && (pathfinderActive || executorRunning)
                && tickCounter >= RECALC_TICKS) {
            tickCounter = 0;
            var res = kaptainwutax.tungsten.path.fast.FastPlanner.plan(
                    world, planStart(world, player),
                    net.minecraft.util.math.BlockPos.ofFloored(effectiveTarget),
                    kaptainwutax.tungsten.TungstenConfig.get().fastPlanBudgetMs);
            var cells = res.positions();
            if (cells.size() >= 2) BlockPathWalker.startBFS(cells);
        }

        if (walkerRunning) {
            // walker active — don't touch pathfinder, just let it compute
            stuckTicks = 0;
        } else if (!pathfinderActive && !executorRunning && !stopRequested) {
            stuckTicks = 0;
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

    private static void startFind(WorldView world, ClientPlayerEntity player, Vec3d target, double dist) {
        tickCounter   = 0;
        lastTargetPos = target;
        TungstenMod.TARGET = target;

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
            if (bfsPath.size() >= 2) {
                // IF WE HAVE A ROUTE, WALK THE ROUTE. A beeline is what you do when you have
                // no route, not what you do instead of one. `start(target, path)` begins in
                // DIRECT mode with the path merely as a fallback (BlockPathWalker.java:87), and
                // measured on chase_terrain that is what the chase actually did: over ~180 s the
                // log shows "Walker: direct" 3 times and "Walker: BFS" ZERO — a plan was
                // computed and then ignored while the bot sprinted at a runner it never caught
                // (contact=None, kills=0). AC-1.4 says the exact block route is run IMMEDIATELY.
                BlockPathWalker.startBFS(bfsPath);
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
        TungstenModDataContainer.PATHFINDER.find(world, target, player);
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
