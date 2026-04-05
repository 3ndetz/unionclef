package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
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
    private static final int    RECALC_TICKS       = 15;
    private static final double MIN_MOVE_DIST      = 1.5;
    private static final int    STUCK_TICKS        = 30;

    // ── state ───────────────────────────────────────────────────────────────────
    private static Entity  targetEntity    = null;
    private static Vec3d   lastKnownPos    = null;
    private static boolean active          = false;
    private static double  closeEnough     = DEFAULT_CLOSE_ENOUGH;
    private static boolean managed         = false; // true = FollowPlayerTask controls entity

    // ── LEAP mode (PvP close-range: sprint+jump, no camera — altoclef handles aim) ─
    private static boolean leapActive = false;

    // ── pathfinder state ────────────────────────────────────────────────────────
    private static Vec3d   lastTargetPos  = null;
    private static int     tickCounter    = 0;
    private static int     stuckTicks     = 0;
    private static boolean stopRequested  = false;

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
        } else if (managed && lastKnownPos != null) {
            // managed mode: survive entity removal, navigate to lastKnownPos
            targetPos = lastKnownPos;
            hasEntity = false;
        } else if (!managed) {
            // direct mode: entity gone → stop
            stop();
            return;
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

        // ── Tungsten A*: always runs as primary pathfinder ───────────────────
        // While BFS walker is running, suppress recalc — let it finish its segment.
        // A* is already computing from BFS endpoint; recalc would restart everything.
        boolean walkerRunning   = BlockPathWalker.isRunning();
        tickCounter++;
        boolean executorRunning  = TungstenModDataContainer.isExecutorRunning();
        boolean pathfinderActive = TungstenModDataContainer.PATHFINDER.active.get();

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
                && effectiveTarget.distanceTo(lastTargetPos) > MIN_MOVE_DIST) {
            // Stop pathfinder but keep executor running — bot continues along
            // current path while we recalculate a new one for the moved target
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
                Vec3d bfsEnd = BlockPathWalker.getEndpoint();
                if (bfsEnd != null) {
                    TungstenModDataContainer.PATHFINDER.overrideStartPos = bfsEnd;
                }
            }
        }

        if (dist < 6 && hasLineOfSight(player, target)) {
            TungstenModDataContainer.PATHFINDER.searchTimeoutMs      = 120L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 1;
            TungstenModDataContainer.PATHFINDER.minDistPath           = 0.1;
        } else if (dist < 12) {
            TungstenModDataContainer.PATHFINDER.searchTimeoutMs      = 500L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 2;
            TungstenModDataContainer.PATHFINDER.minDistPath           = 0.3;
        } else if (dist < 25) {
            TungstenModDataContainer.PATHFINDER.searchTimeoutMs      = 1500L;
            TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 3;
            TungstenModDataContainer.PATHFINDER.minDistPath           = 0.5;
        } else {
            TungstenModDataContainer.PATHFINDER.searchTimeoutMs      = 3000L;
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
