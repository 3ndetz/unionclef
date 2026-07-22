package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.combat.VoidDetector;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Flee a named player, keeping at least {@code keepDistance} blocks. The mirror
 * of {@link FollowEntityTask}: instead of pathing TO the threat it paths to the
 * safest reachable point directly AWAY from it, re-planning as the threat moves.
 *
 * Void-aware: a flee waypoint is only accepted on solid ground that isn't over a
 * serious drop, so keeping distance never means backing off the island. When the
 * straight-away direction is blocked by a wall or the void it samples angled
 * fallbacks and takes whichever safe point sits furthest from the threat.
 *
 * Primitive, not policy — the agent decides WHEN to flee and from WHOM; the mod
 * just executes the keep-distance retreat. Ticked every game tick from
 * MixinClientPlayerEntity.
 */
public class RunAwayTask {

    private static final int    RECALC = 10;   // re-plan cadence (ticks)
    private static final double STEP   = 8.0;  // how far ahead to aim the flee point

    private static boolean active       = false;
    private static String  threatName   = null;
    private static double  keepDistance = 8.0;
    private static PlayerEntity threat  = null;
    private static int     tickCounter  = 0;

    /** Start fleeing {@code name}, keeping at least {@code dist} blocks (min 3). */
    public static void start(String name, double dist) {
        stop();
        PunkPlayerTask.stop();            // can't hunt and flee at once
        threatName   = name;
        keepDistance = Math.max(3.0, dist);
        active       = true;
        Debug.logMessage("Run away from " + name + " (keep " + (int) keepDistance + ")");
    }

    public static void stop() {
        if (active) {
            TungstenModDataContainer.PATHFINDER.stop.set(true);
            if (TungstenModDataContainer.EXECUTOR != null) TungstenModDataContainer.EXECUTOR.stop = true;
            releaseKeys();
        }
        active = false; threatName = null; threat = null; tickCounter = 0;
    }

    public static boolean isActive()      { return active; }
    public static String  getThreatName() { return threatName; }

    /** Name of the threat currently tracked (null if not visible). */
    public static String getCurrentThreat() {
        return (threat != null && threat.isAlive() && !threat.isRemoved())
                ? threat.getName().getString() : null;
    }

    // ── tick ─────────────────────────────────────────────────────────────────

    public static void tick(WorldView world, ClientPlayerEntity player) {
        if (!active) return;

        threat = resolve(player);
        if (threat == null) return; // threat gone / out of view — idle; agent decides next

        double dist = player.getEntityPos().distanceTo(threat.getEntityPos());
        if (dist >= keepDistance + 1.5) {
            // far enough — stop pathing, hold position
            if (TungstenModDataContainer.PATHFINDER.active.get()) {
                TungstenModDataContainer.PATHFINDER.stop.set(true);
            }
            return;
        }

        tickCounter++;
        boolean pathing = TungstenModDataContainer.PATHFINDER.active.get()
                || TungstenModDataContainer.isExecutorRunning();
        if (!pathing || tickCounter >= RECALC) {
            tickCounter = 0;
            Vec3d flee = safeFleePoint(world, player);
            if (flee != null) {
                TungstenModDataContainer.PATHFINDER.stop.set(true);
                TungstenConfig.get().searchTimeoutMs = 400L;
                TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 1;
                TungstenModDataContainer.PATHFINDER.minDistPath = 0.3;
                TungstenModDataContainer.PATHFINDER.find(world, flee, player);
            }
        }
    }

    /**
     * Best solid, void-safe standable point ~STEP blocks away from the threat.
     * Straight-away first, then angled fallbacks so a wall/void doesn't trap the
     * flee; picks the safe candidate furthest from the threat. Null if none safe.
     */
    private static Vec3d safeFleePoint(WorldView world, ClientPlayerEntity player) {
        Vec3d p = player.getEntityPos();
        Vec3d away = p.subtract(threat.getEntityPos());
        away = new Vec3d(away.x, 0, away.z);
        if (away.lengthSquared() < 1e-4) away = new Vec3d(1, 0, 0);
        away = away.normalize();

        Vec3d best = null;
        double bestScore = -1;
        double[] angles = {0, 30, -30, 60, -60, 90, -90};
        for (double a : angles) {
            double rad = Math.toRadians(a);
            double cos = Math.cos(rad), sin = Math.sin(rad);
            double dx = away.x * cos - away.z * sin;
            double dz = away.x * sin + away.z * cos;
            // don't even consider a direction that steps off a drop right away
            if (VoidDetector.edgeAhead(p, dx, dz, world, 3, 1.6)) continue;
            Vec3d cand = new Vec3d(p.x + dx * STEP, p.y, p.z + dz * STEP);
            Vec3d ground = snapGround(world, cand);
            if (ground == null) continue;
            double score = ground.distanceTo(threat.getEntityPos());
            if (score > bestScore) { bestScore = score; best = ground; }
        }
        return best;
    }

    /** Nearest standable, not-over-a-drop cell around {@code pos} (scan a few
     *  blocks up/down); null if none. */
    private static Vec3d snapGround(WorldView world, Vec3d pos) {
        int x = (int) Math.floor(pos.x), z = (int) Math.floor(pos.z), y0 = (int) Math.floor(pos.y);
        for (int dy = 2; dy >= -4; dy--) {
            BlockPos bp = new BlockPos(x, y0 + dy, z);
            boolean solid = !world.getBlockState(bp).getCollisionShape(world, bp).isEmpty();
            boolean airAbove = world.getBlockState(bp.up()).getCollisionShape(world, bp.up()).isEmpty()
                    && world.getBlockState(bp.up(2)).getCollisionShape(world, bp.up(2)).isEmpty();
            if (solid && airAbove) {
                Vec3d stand = new Vec3d(x + 0.5, bp.getY() + 1.0, z + 0.5);
                return VoidDetector.fallHeight(stand, world) <= 3 ? stand : null;
            }
        }
        return null;
    }

    private static PlayerEntity resolve(ClientPlayerEntity player) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || threatName == null) return null;
        if (threat != null && !threat.isRemoved() && threat.isAlive()
                && mc.world.getEntityById(threat.getId()) == threat) {
            return threat;
        }
        for (PlayerEntity pl : mc.world.getPlayers()) {
            if (pl != player && pl.getName().getString().equalsIgnoreCase(threatName)) return pl;
        }
        return null;
    }

    private static void releaseKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }
}
