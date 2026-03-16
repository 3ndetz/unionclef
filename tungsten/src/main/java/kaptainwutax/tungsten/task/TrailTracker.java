package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.TungstenMod;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.ArrayList;
import java.util.List;

/**
 * Records target movement trail and provides waypoints for TRAILING mode.
 *
 * When the target "escapes" (dist>20, height diff>5, or pathfinder stalled),
 * the follower navigates to the nearest recorded waypoint and then follows
 * the chain toward the target's current position.
 */
public class TrailTracker {

    private static final int    MAX_WAYPOINTS      = 50;
    private static final int    RECORD_INTERVAL    = 2;    // ticks between recordings
    private static final double MIN_RECORD_DIST    = 1.5;  // skip if target barely moved
    private static final long   MAX_AGE_MS         = 30_000; // discard points older than 30s
    private static final double ESCAPE_DIST        = 20.0;
    private static final double ESCAPE_HEIGHT_DIFF = 5.0;
    private static final double TRAIL_EXIT_DIST    = 12.0; // exit trailing when this close
    private static final double WAYPOINT_REACH_DIST = 3.0; // consider waypoint "reached"
    private static final int    STALL_TICKS        = 100;  // 5 sec without progress → stalled

    private final List<TrailPoint> trail = new ArrayList<>();
    private int recordTick = 0;
    private int waypointIndex = -1;
    private boolean trailing = false;

    // stall detection
    private double lastProgressDist = Double.MAX_VALUE;
    private int    stallCounter     = 0;

    private final String label; // for log messages

    public TrailTracker(String label) {
        this.label = label;
    }

    public void reset() {
        trail.clear();
        recordTick = 0;
        waypointIndex = -1;
        trailing = false;
        stallCounter = 0;
        lastProgressDist = Double.MAX_VALUE;
    }

    /** Call every game tick with target's current position. Only records ground positions. */
    public void recordPosition(Vec3d targetPos) {
        if (targetPos == null) return;
        recordTick++;
        if (recordTick < RECORD_INTERVAL) return;
        recordTick = 0;

        // Snap to ground: find solid block below target (max 5 blocks down)
        WorldView world = TungstenMod.mc.world;
        if (world == null) return;
        Vec3d groundPos = snapToGround(world, targetPos);
        if (groundPos == null) return; // no ground found — skip (void, mid-air over nothing)

        // skip if target barely moved since last recording
        if (!trail.isEmpty()) {
            TrailPoint last = trail.get(trail.size() - 1);
            if (last.pos.squaredDistanceTo(groundPos) < MIN_RECORD_DIST * MIN_RECORD_DIST) return;
        }

        trail.add(new TrailPoint(groundPos));

        // trim oldest
        while (trail.size() > MAX_WAYPOINTS) trail.remove(0);

        // remove stale entries
        long now = System.currentTimeMillis();
        while (!trail.isEmpty() && now - trail.get(0).timestamp > MAX_AGE_MS) trail.remove(0);
    }

    /**
     * Call every tick to update trailing state.
     * @return true if trailing mode is active after this update
     */
    public boolean update(Vec3d playerPos, Vec3d targetPos) {
        if (playerPos == null || targetPos == null) return trailing;

        double dist = playerPos.distanceTo(targetPos);
        double heightDiff = Math.abs(playerPos.y - targetPos.y);

        // stall detection: if dist to target hasn't decreased over STALL_TICKS
        if (dist < lastProgressDist - 0.5) {
            lastProgressDist = dist;
            stallCounter = 0;
        } else {
            stallCounter++;
        }
        boolean stalled = stallCounter >= STALL_TICKS;

        if (!trailing) {
            // enter trailing if target escaped AND we have enough trail
            boolean escaped = dist > ESCAPE_DIST || heightDiff > ESCAPE_HEIGHT_DIFF || stalled;
            if (escaped && trail.size() >= 3) {
                trailing = true;
                waypointIndex = findNearestIndex(playerPos);
                stallCounter = 0;
                lastProgressDist = Double.MAX_VALUE;
                TungstenMod.LOG.info("[" + label + "] TRAILING activated (dist="
                        + String.format("%.1f", dist) + " height=" + String.format("%.1f", heightDiff)
                        + " stalled=" + stalled + " trail=" + trail.size() + " pts)");
            }
        } else {
            // exit trailing when close enough or trail exhausted
            if (dist < TRAIL_EXIT_DIST || trail.isEmpty()) {
                trailing = false;
                waypointIndex = -1;
                TungstenMod.LOG.info("[" + label + "] TRAILING mode ended (dist=" + String.format("%.1f", dist) + ")");
            }
        }

        return trailing;
    }

    public boolean isTrailing() { return trailing; }

    /**
     * Returns the current waypoint to navigate to, or null if not trailing.
     * Advances along the chain when waypoints are reached.
     */
    public Vec3d getWaypoint(Vec3d playerPos) {
        if (!trailing || trail.isEmpty()) return null;

        // clamp index
        if (waypointIndex < 0 || waypointIndex >= trail.size()) {
            waypointIndex = findNearestIndex(playerPos);
        }

        Vec3d wp = trail.get(waypointIndex).pos;

        // check if reached current waypoint
        if (playerPos.squaredDistanceTo(wp) < WAYPOINT_REACH_DIST * WAYPOINT_REACH_DIST) {
            waypointIndex++;
            if (waypointIndex >= trail.size()) {
                // reached end of trail — exit trailing
                trailing = false;
                waypointIndex = -1;
                TungstenMod.LOG.info("[" + label + "] TRAILING complete — end of trail reached");
                return null;
            }
            wp = trail.get(waypointIndex).pos;
        }

        return wp;
    }

    public int getTrailSize() { return trail.size(); }

    public int getWaypointIndex() { return waypointIndex; }

    private int findNearestIndex(Vec3d pos) {
        int nearest = 0;
        double nearestDist = Double.MAX_VALUE;
        for (int i = 0; i < trail.size(); i++) {
            double d = pos.squaredDistanceTo(trail.get(i).pos);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = i;
            }
        }
        return nearest;
    }

    /**
     * Snap a position to ground if within 2 blocks below (parkour-safe).
     * Returns position on top of solid block, or null if too far from ground.
     */
    private static Vec3d snapToGround(WorldView world, Vec3d pos) {
        BlockPos feet = BlockPos.ofFloored(pos.x, pos.y, pos.z);
        // Check feet level and 1 block down (max ~1.9 blocks gap)
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos below = feet.down(dy + 1);
            if (world.getBlockState(below).isSolidBlock(world, below)) {
                return new Vec3d(pos.x, below.getY() + 1, pos.z);
            }
        }
        return null; // too far from ground — skip
    }

    private static class TrailPoint {
        final Vec3d pos;
        final long  timestamp;

        TrailPoint(Vec3d pos) {
            this.pos = pos;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
