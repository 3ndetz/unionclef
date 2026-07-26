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
    public static Entity  getTarget() { return targetEntity; }
    public static boolean isManaged() { return managed; }

    // ─────────────────────────────────────────────────────────────────────────────

    /** Called every game tick from MixinClientPlayerEntity. */
    public static void tick(WorldView world, ClientPlayerEntity player) {
        if (!active) return;

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

        if (hasEntity && !leapActive && steerCooldownTicks == 0
                && effectiveDist > Math.max(closeEnough, 1.5)
                && hasLineOfSight(player, effectiveTarget.add(0, 1.0, 0))) {  // body-centre, not feet (terrain lips)
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

        // JAM WATCHDOG — engine-independent. Whichever component claims to be
        // driving, what matters is whether the BOT moves. The walker flickers
        // on/off while it bounces at an obstacle, so a counter living inside the
        // walker branch never reached its threshold and the bot bounced in the
        // same notch indefinitely. Judge by horizontal displacement alone.
        Vec3d hereNow = player.getEntityPos();
        if (jamAnchor == null
                || Math.hypot(hereNow.x - jamAnchor.x, hereNow.z - jamAnchor.z) > 0.5) {
            jamAnchor = hereNow;
            jamTicks = 0;
        } else if (++jamTicks >= WALKER_STUCK_TICKS) {
            Debug.logMessage("Jammed at " + player.getBlockPos().toShortString()
                    + " — blacklisting the cell and re-planning");
            jamTicks = 0;
            jamAnchor = null;
            kaptainwutax.tungsten.path.fast.FastPlanner.blockCell(player.getBlockPos());
            BlockPathWalker.stop();
            TungstenModDataContainer.PATHFINDER.overrideStartPos = null;
            TungstenModDataContainer.PATHFINDER.stop.set(true);
            stopRequested = true;
            tickCounter = 0;
            return;
        }

        if (walkerRunning) {
            // "The walker is running" is not the same as "the bot is moving". On
            // generated terrain it can hammer jump at an obstacle its waypoint sits
            // behind: X and Z frozen, Y oscillating, forever — and this branch used
            // to reset the stuck counter every tick, so the chase never recovered
            // (stand-measured: 27 blocks of progress, then dead for the rest of the
            // run). Watch HORIZONTAL displacement and force a re-plan when there is
            // none.
            Vec3d here = player.getEntityPos();
            if (walkerAnchor == null
                    || Math.hypot(here.x - walkerAnchor.x, here.z - walkerAnchor.z) > 0.5) {
                walkerAnchor = here;
                walkerStuckTicks = 0;
            } else if (++walkerStuckTicks >= WALKER_STUCK_TICKS) {
                Debug.logMessage("Walker jammed (no horizontal progress) — forcing a re-plan");
                walkerStuckTicks = 0;
                walkerAnchor = null;
                // and remember that this cell keeps failing, so the next plan
                // routes AROUND it instead of handing back the same dead end
                kaptainwutax.tungsten.path.fast.FastPlanner.blockCell(player.getBlockPos());
                BlockPathWalker.stop();
                TungstenModDataContainer.PATHFINDER.overrideStartPos = null;
                TungstenModDataContainer.PATHFINDER.stop.set(true);
                stopRequested = true;
                tickCounter = 0;
            }
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

    private static void startFind(WorldView world, ClientPlayerEntity player, Vec3d target, double dist) {
        tickCounter   = 0;
        lastTargetPos = target;
        TungstenMod.TARGET = target;

        // LOCAL BLOCKER FIRST. The bot stops in front of the thing the block
        // planner routed it into and that walking cannot do: usually a JUMP the
        // planner marked physics-required (a gap), sometimes a 2-block face. The
        // walked leg ends there by design — and then nobody executed the jump, so
        // it just stood there. Asking the physics engine for the FAR target does
        // not help either: it plans past the obstacle and the executor waits for a
        // root it can never reach. Give physics the SHORT problem instead — a
        // handful of blocks along the planned route, i.e. the jump itself — which
        // is exactly what its physics model is for. Long-range flow resumes after.
        // ...but it must NEVER replace the normal re-plan. An earlier version
        // returned here whenever the walker was idle — which is true forever once
        // it stops — so the long-range search was never issued again and the chase
        // died silently after its first successful leg (diagnostic: pathfinder,
        // block search, executor and walker all false for 60 s straight while the
        // fps stayed at 10). Fire the local leg at most once every few seconds and
        // fall through to the normal search.
        long nowMs = System.currentTimeMillis();
        if (!BlockPathWalker.isRunning()
                && nowMs - lastLocalLegMs > LOCAL_LEG_COOLDOWN_MS
                && kaptainwutax.tungsten.TungstenConfig.get().fastBlockFirst) {
            lastLocalLegMs = nowMs;
            kaptainwutax.tungsten.path.fast.FastPlanner.planAsync(
                    world, player.getBlockPos(),
                    net.minecraft.util.math.BlockPos.ofFloored(target), 250L,
                    res -> {
                        if (!active) return;
                        java.util.List<net.minecraft.util.math.BlockPos> cells = res.positions();
                        if (cells.size() < 2) return;
                        // Give physics a leg with real length: a target one cell
                        // away makes the bot inch forward a block per search (seen
                        // in the log: 194,123 -> 195,124 -> ...). Aim ~8 cells out,
                        // or the end of a short plan, so one physics solve actually
                        // carries the bot over the obstacle it is stuck on.
                        net.minecraft.util.math.BlockPos local =
                                cells.get(Math.min(8, cells.size() - 1));
                        TungstenConfig.get().searchTimeoutMs = 800L;
                        TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 1;
                        TungstenModDataContainer.PATHFINDER.minDistPath = 0.3;
                        TungstenModDataContainer.PATHFINDER.overrideStartPos = null;
                        Debug.logMessage("Local climb: physics leg to " + local);
                        TungstenModDataContainer.PATHFINDER.find(
                                world, Vec3d.ofBottomCenter(local), player);
                    });
            // NO return: the normal long-range flow below must keep running.
        }

        // ── Instant BFS for immediate movement while physics A* computes ──
        if (kaptainwutax.tungsten.TungstenConfig.get().followBlockPathFinderEnabled && dist > 6) {
            java.util.List<net.minecraft.util.math.BlockPos> bfsPath =
                    kaptainwutax.tungsten.combat.CombatPathfinder.findPath(
                            player.getBlockPos(),
                            net.minecraft.util.math.BlockPos.ofFloored(target),
                            world);
            if (bfsPath.size() >= 2) {
                // Direct sprint first, BFS as fallback
                BlockPathWalker.start(target, bfsPath);
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

            // The grid BFS above is flat-minded: it only knows "the two cells are
            // clear", so on generated terrain it dead-ends and the chase stalls in
            // front of a slope. Plan the real route as well (ascend/descend/parkour
            // moves, honest costs, real body clearance) and hand it to the walker.
            // Async: a terrain plan costs more than a tick.
            //
            // MEASURED, do not "improve" without re-running chase_terrain: feeding
            // this plan via startBFS instead of start(), or dropping the dist gate,
            // made the bot stop moving entirely (0 blocks in 180 s vs 50 with this
            // form) because every re-plan restarted the walker at waypoint 0.
            if (kaptainwutax.tungsten.TungstenConfig.get().fastBlockFirst) {
                final Vec3d fixedTarget = target;
                kaptainwutax.tungsten.path.fast.FastPlanner.planAsync(
                        world, player.getBlockPos(),
                        net.minecraft.util.math.BlockPos.ofFloored(target),
                        Math.min(200L, kaptainwutax.tungsten.TungstenConfig.get().fastPlanBudgetMs),
                        res -> {
                            if (!active) return;
                            java.util.List<net.minecraft.util.math.BlockPos> cells = res.positions();
                            int physics = res.firstPhysicsIndex();
                            if (physics > 1) cells = cells.subList(0, physics);
                            if (cells.size() >= 2) BlockPathWalker.start(fixedTarget, cells);
                        });
            }
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
