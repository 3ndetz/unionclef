package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.combat.AttackTiming;
import kaptainwutax.tungsten.combat.CombatPathfinder;
import kaptainwutax.tungsten.combat.SafetySystem;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.List;

/**
 * Immediate movement while physics A* computes.
 *
 * Priority chain:
 *   1. DIRECT — LOS + distance shrinking + safe → sprint straight at target
 *   2. BFS    — no LOS or danger detected → follow BFS waypoints
 *   3. (stop) — executor ready or path exhausted → hand off to A*
 *
 * Auto-stops when PathExecutor takes over.
 */
public class BlockPathWalker {

    private enum Mode { DIRECT, BFS }

    private static List<BlockPos> path = null;
    private static int waypointIdx = 0;
    private static boolean active = false;
    private static Mode mode = Mode.DIRECT;

    // progress tracking for direct-sprint
    private static double lastDistToTarget = Double.MAX_VALUE;
    private static int noProgressTicks = 0;
    private static final int NO_PROGRESS_LIMIT = 15; // ~0.75s without getting closer → switch to BFS
    private static final double MIN_APPROACH_SPEED = 0.03; // ~walk speed per tick

    private static Vec3d directTarget = null;

    // LIVE-STEER: continuously re-aim the DIRECT sprint at a MOVING target's CURRENT
    // position (fed each tick by FollowEntityTask). In this mode the "distance to
    // target must shrink" progress check is the WRONG signal — the target itself
    // moves — so a stall is detected by the BOT's own displacement instead (pressed
    // against a wall / not physically advancing).
    private static boolean liveMode = false;
    private static Vec3d liveStuckAnchor = null;
    private static int liveStuckTicks = 0;
    // true when the last DIRECT stop was a BAIL (no LOS / stall / danger) rather than a
    // success (reached the target). FollowEntityTask reads this so its live-steer cooldown
    // only fires on a real obstacle, not on close-success or an executor hand-off.
    private static boolean stoppedByBail = false;
    private static final int LIVE_STUCK_LIMIT = 20;    // ~1s of the bot not moving → BFS
    private static final double LIVE_STUCK_MOVE = 0.5; // min displacement to count as moving
    // Face-before-move gate (deg). Matches the movement gate so the stall detector's
    // "trying to move" agrees with when the bot actually sprints. A wider gate was tried
    // and reverted (the bot sprinted while mis-aimed and wandered off-course); the fast
    // nav turn (WindMouseRotation.setTargetFast) is what keeps this gate open instead.
    private static final double LIVE_MOVE_GATE = 45.0;

    // White-box climb instrumentation (off by default; toggled via py4j setWalkerDebug).
    // Logs the walker's per-tick decisions so a FAILING climb can be understood mechanism-
    // first instead of guessed from external position alone. Key signal: playerYaw vs the
    // target yaw the walker set (tests whether WindMouse easing lags during a sprint).
    public static volatile boolean DEBUG = false;
    private static int dbgN = 0;

    // ── public API ──────────────────────────────────────────────────────────

    /**
     * Start with direct-sprint toward target. BFS path is fallback.
     * @param target      the actual target position
     * @param blockPath   BFS path (fallback if direct fails), may be null
     */
    public static void start(Vec3d target, List<BlockPos> blockPath) {
        stop();
        directTarget = target;
        path = blockPath;
        waypointIdx = (blockPath != null && blockPath.size() > 1) ? 1 : 0;
        lastDistToTarget = Double.MAX_VALUE;
        noProgressTicks = 0;
        liveMode = false;
        liveStuckAnchor = null;
        liveStuckTicks = 0;
        mode = Mode.DIRECT;
        active = true;
        Debug.logMessage("Walker: direct→target" +
                (blockPath != null ? " (BFS fallback: " + blockPath.size() + " wp)" : ""));
    }

    /**
     * Live DIRECT-steer at a MOVING target: re-aim the drift-immune sprint at the
     * target's CURRENT position every tick, so the bot cuts across and CLOSES on a
     * runner instead of tracing a stale path snapshot ~30 blocks behind. No BFS
     * fallback stored here — if the straight line breaks (LOS / hole / ledge) or the
     * bot stalls against a wall, the walker stops and the caller (FollowEntityTask)
     * falls back to BFS + physics A*. Call every tick with the live target.
     */
    public static void steerLive(Vec3d target) {
        if (target == null) return;
        if (!active || mode != Mode.DIRECT) {
            start(target, null);   // (re)start a DIRECT sprint (resets liveMode=false)
        } else {
            directTarget = target; // keep progress/mode, just re-aim
        }
        liveMode = true;
    }

    /** Start BFS-only (no direct sprint). */
    public static void startBFS(List<BlockPos> blockPath) {
        if (blockPath == null || blockPath.size() < 2) return;
        stop();
        path = blockPath;
        waypointIdx = 1;
        mode = Mode.BFS;
        active = true;
        Debug.logMessage("Walker: BFS " + blockPath.size() + " wp");
    }

    public static void stop() {
        if (active) {
            releaseKeys();
        }
        active = false;
        path = null;
        directTarget = null;
        waypointIdx = 0;
        noProgressTicks = 0;
        lastDistToTarget = Double.MAX_VALUE;
        liveMode = false;
        liveStuckAnchor = null;
        liveStuckTicks = 0;
    }

    public static boolean isRunning() {
        return active;
    }

    /** True if the last DIRECT stop was a bail (no LOS / stall / danger), not a success. */
    public static boolean wasStoppedByBail() {
        return stoppedByBail;
    }

    /** BFS endpoint for A* start position. */
    public static Vec3d getEndpoint() {
        if (path == null || path.isEmpty()) return null;
        return Vec3d.ofBottomCenter(path.get(path.size() - 1));
    }

    // ── tick ─────────────────────────────────────────────────────────────────

    public static void tick(ClientPlayerEntity player) {
        if (!active) return;

        // auto-stop when executor takes over (NON-live only). In live-steer the walker
        // OWNS movement — FollowEntityTask has explicitly stopped the executor — so a
        // 1-tick transient "executor still running" must not yank the walker off.
        if (!liveMode && TungstenModDataContainer.isExecutorRunning()) {
            stop();
            return;
        }

        if (mode == Mode.DIRECT) {
            tickDirect(player);
        } else {
            tickBFS(player);
        }
    }

    // ── DIRECT: sprint straight at target ────────────────────────────────────

    private static void tickDirect(ClientPlayerEntity player) {
        if (directTarget == null) { switchToBFS(); return; }

        Vec3d playerPos = player.getEntityPos();
        WorldView world = player.getEntityWorld();
        double dist = horizontalDist(playerPos, directTarget);

        // check LOS
        boolean hasLOS = FollowEntityTask.hasLineOfSight(player, directTarget);

        // check progress. STATIC target: distance to it should shrink. LIVE (moving)
        // target: distance-shrink is the wrong signal — the target moves — so detect a
        // stall by the BOT's own displacement (pressed against a wall, not advancing).
        double progress = lastDistToTarget - dist;
        lastDistToTarget = dist;
        boolean stalled;
        if (liveMode) {
            // Count a "stuck" tick ONLY when the bot is actually TRYING to move (facing
            // the target / airborne) yet isn't displacing — i.e. pressed against a wall.
            // While it merely PIVOTS to face a target that jumped (face-before-move gates
            // forward), it is NOT stuck; counting that as stuck bails the chase to the 2s
            // physics cooldown and halves the effective speed (bot fell behind a runner).
            float yawNow = AttackTiming.yawTo(playerPos, directTarget);
            boolean facingNow = Math.abs(WindMouseRotation.wrapDelta(yawNow - player.getYaw())) < LIVE_MOVE_GATE;
            boolean tryingToMove = facingNow || !player.isOnGround();
            if (!tryingToMove || liveStuckAnchor == null
                    || playerPos.distanceTo(liveStuckAnchor) > LIVE_STUCK_MOVE) {
                liveStuckAnchor = playerPos;
                liveStuckTicks = 0;
            } else {
                liveStuckTicks++;
            }
            stalled = liveStuckTicks >= LIVE_STUCK_LIMIT;
        } else {
            if (progress < MIN_APPROACH_SPEED) noProgressTicks++; else noProgressTicks = 0;
            stalled = noProgressTicks >= NO_PROGRESS_LIMIT;
        }

        // check safety: landing safe + no holes on path to target
        BlockPos targetBlock = BlockPos.ofFloored(directTarget);
        boolean landingSafe = SafetySystem.isJumpLandingSafe(playerPos, player.getVelocity(), world);
        boolean pathSafe = !SafetySystem.hasHolesOnPath(playerPos, targetBlock, world);
        boolean groundSafe = CombatPathfinder.isWalkable(player.getBlockPos(), world);

        // bail to BFS if: no LOS, stalled, or danger
        if (!hasLOS || stalled || !pathSafe || !groundSafe) {
            if (!hasLOS) Debug.logMessage("Walker: no LOS → BFS");
            else if (stalled) Debug.logMessage("Walker: stalled → BFS");
            else Debug.logMessage("Walker: danger → BFS");
            stoppedByBail = true;
            switchToBFS();
            return;
        }

        // close enough — done (success, not a bail)
        if (dist < 1.5) {
            stoppedByBail = false;
            stop();
            return;
        }

        // movement — FAST nav turn (snap the view toward the chase target) so the bot
        // stays aligned and keeps sprinting instead of stopping to pivot; combat/mining/
        // bow keep the slow humanized aim. tickBFS (terrain) also stays humanized.
        float yaw = AttackTiming.yawTo(playerPos, directTarget);
        WindMouseRotation.INSTANCE.setTargetFast(yaw, 0);

        // FACE-BEFORE-MOVE (same fix as tickBFS): the humanized WindMouse yaw takes a few
        // frames to converge; pressing forward while it's still off makes the bot chase a
        // moving aim target and SPIN in a circle instead of approaching (the v0.44.0 walker
        // spin, in DIRECT mode). Gate movement on facing while onGround; keep momentum in
        // the air (a jump/leap sets its direction at take-off). Without this, enabling the
        // follow walker would make the combat approach circle the target.
        double yawErr = Math.abs(WindMouseRotation.wrapDelta(yaw - player.getYaw()));
        boolean onGround = player.isOnGround();
        // Keep the strict 45deg gate: a wider gate let the bot sprint while badly mis-aimed
        // and it wandered off-course (even off a ledge). Aligned-only sprint; the turn speed
        // (WindMouse) is what must be fast enough to keep the gate open — see setTargetFast.
        boolean move = (yawErr < 45.0) || !onGround;

        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(move);
        mc.options.sprintKey.setPressed(move);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        boolean canJump = (yawErr < 45.0) && TungstenConfig.get().followJumpingEnabled
                && onGround && landingSafe;
        mc.options.jumpKey.setPressed(canJump);
    }

    private static void switchToBFS() {
        if (path != null && path.size() >= 2) {
            mode = Mode.BFS;
            noProgressTicks = 0;
        } else {
            stop();
        }
    }

    // ── BFS: follow waypoints ────────────────────────────────────────────────

    private static void tickBFS(ClientPlayerEntity player) {
        if (path == null || waypointIdx >= path.size()) {
            stop();
            return;
        }

        BlockPos wp = path.get(waypointIdx);
        Vec3d wpPos = Vec3d.ofBottomCenter(wp);
        Vec3d playerPos = player.getEntityPos();
        double dist = horizontalDist(playerPos, wpPos);

        // advance waypoint
        if (dist < 1.5) {
            waypointIdx++;
            if (waypointIdx >= path.size()) {
                stop();
                return;
            }
            wp = path.get(waypointIdx);
            wpPos = Vec3d.ofBottomCenter(wp);
        }

        float yaw = AttackTiming.yawTo(playerPos, wpPos);
        WindMouseRotation.INSTANCE.setTarget(yaw, 0);

        // FACE-BEFORE-MOVE (on the ground only). The camera turns via WindMouse (humanized,
        // several frames to converge). Pressing forward while the yaw is still off makes the
        // bot walk in the WRONG direction, which shifts the waypoint bearing, which moves the
        // aim target — a feedback SPIN: the bot circles and never converges (white-box trace:
        // yaw swept ~680 deg, position spiralled, climb failed ~40%). So while ON THE GROUND,
        // gate movement on being roughly pointed at the waypoint: pivot in place first, then
        // walk straight. But NEVER cut movement while AIRBORNE — a gap jump / slime bounce
        // sets its direction at take-off, and releasing forward mid-arc kills the momentum
        // and drops the bot short (that killed parkour: course B, slime drop-bounce).
        double yawErr = Math.abs(WindMouseRotation.wrapDelta(yaw - player.getYaw()));
        boolean onGround = player.isOnGround();
        boolean facing = yawErr < 45.0;
        boolean move = facing || !onGround;     // face-before-move grounded; keep air momentum

        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(move);
        mc.options.sprintKey.setPressed(move);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        boolean needJumpUp = wp.getY() > player.getBlockPos().getY();
        boolean canJump = facing && TungstenConfig.get().followJumpingEnabled
                && onGround
                && (needJumpUp || SafetySystem.isJumpLandingSafe(
                        playerPos, player.getVelocity(), player.getEntityWorld()));
        mc.options.jumpKey.setPressed(canJump);

        if (DEBUG && (dbgN++ % 3 == 0)) {
            Vec3d v = player.getVelocity();
            Debug.logMessage(String.format(
                "wlk i%d/%d d%.1f wp(%d,%d,%d) g%d j%d yaw%.0f>%.0f v(%.2f,%.2f,%.2f) p(%.1f,%.1f,%.1f)",
                waypointIdx, path.size(), dist, wp.getX(), wp.getY(), wp.getZ(),
                player.isOnGround() ? 1 : 0, canJump ? 1 : 0,
                player.getYaw(), yaw, v.x, v.y, v.z,
                playerPos.x, playerPos.y, playerPos.z));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static double horizontalDist(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void releaseKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        WindMouseRotation.INSTANCE.clearTarget();
    }
}
