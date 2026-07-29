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
import kaptainwutax.tungsten.helpers.DirectionHelper;
import net.minecraft.block.LadderBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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

    // Counters, not log lines: on the bounce course the physics search floods the chat and
    // the client drops messages ("Chat overflow"), so a missing log line proves nothing.
    // These are read over py4j and answer "did this code path run at all".
    public static volatile int bfsTicks = 0;
    public static volatile int slimeWpSeen = 0;
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
    /** The waypoint the walker is currently trying to reach (null if idle).
     *  This is what a jam must blacklist — never the cell we are standing in. */
    public static net.minecraft.util.math.BlockPos getCurrentWaypoint() {
        if (path == null || path.isEmpty()) return null;
        int i = Math.min(waypointIdx, path.size() - 1);
        return path.get(i);
    }

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

        // (Making the walker yield while a place/break queue exists was tried here and
        // measured WORSE — clicks fell from 2 in 28 in-range ticks to 1 in 35. The camera
        // contention is real but this is not the cure.)

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

        // check LOS to the target's BODY CENTRE, not its ground-snapped feet: a
        // ray to the feet clips terrain lips/steps in front and false-negatives on
        // any non-flat ground, so the live chase drops to BFS on rough terrain
        // (RW-9). +1.0 lifts the feet point to roughly mid-body.
        boolean hasLOS = FollowEntityTask.hasLineOfSight(player, directTarget.add(0, 1.0, 0));

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

        // check safety. For a LIVE chase we only guard the IMMEDIATE ground ahead (a few
        // blocks) — far hole/void avoidance is the BFS/physics job. Scanning the WHOLE line
        // to a 20-block-away target made DIRECT bail "danger" on nearly every tick, so the
        // drift-prone physics executor did all the moving (slow, never closed on a runner).
        double toX = directTarget.x - playerPos.x, toZ = directTarget.z - playerPos.z;
        double horiz = Math.sqrt(toX * toX + toZ * toZ);
        double aheadDist = Math.min(horiz, 4.0);
        BlockPos aheadCheck = (horiz < 0.5)
                ? BlockPos.ofFloored(directTarget)
                : BlockPos.ofFloored(new Vec3d(
                        playerPos.x + toX / horiz * aheadDist,
                        directTarget.y,
                        playerPos.z + toZ / horiz * aheadDist));
        boolean landingSafe = SafetySystem.isJumpLandingSafe(playerPos, player.getVelocity(), world);
        boolean pathSafe = !SafetySystem.hasHolesOnPath(playerPos, aheadCheck, world);
        // isWalkable needs a SOLID block under the feet — always false mid-air. Evaluating
        // it while airborne false-bailed the live chase at every bunny-hop apex ("danger ->
        // BFS") and armed the 2s steer cooldown, so the drift-prone physics executor did the
        // moving and never closed on a runner (RW-9). Only a danger when actually grounded.
        boolean groundSafe = !player.isOnGround()
                || CombatPathfinder.isWalkable(player.getBlockPos(), world);

        // bail to BFS if: no LOS, stalled, or IMMEDIATE danger
        if (!hasLOS || stalled || !pathSafe || !groundSafe) {
            if (DEBUG) Debug.logMessage(String.format(
                    "dirBAIL los%d stall%d path%d grnd%d d%.1f", hasLOS ? 1 : 0,
                    stalled ? 1 : 0, pathSafe ? 1 : 0, groundSafe ? 1 : 0, dist));
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

        // movement
        float yaw = AttackTiming.yawTo(playerPos, directTarget);
        // FAST nav turn: the 45deg face-before-move gate below only stays open if the
        // camera swings to the new bearing quickly. The slow humanized turn stalled sprint
        // on every bearing change of a moving target, halving chase speed (RW-9, dead
        // setTargetFast). Fast mode still goes through the mouse pipeline (anti-cheat safe).
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

        if (DEBUG && (dbgN++ % 2 == 0)) {
            Vec3d v = player.getVelocity();
            double spd = Math.sqrt(v.x * v.x + v.z * v.z);
            Debug.logMessage(String.format(
                "dir live%d d%.1f yawErr%.0f move%d grnd%d stuck%d spd%.2f los%d",
                liveMode ? 1 : 0, dist, yawErr, move ? 1 : 0, onGround ? 1 : 0,
                liveStuckTicks, spd, hasLOS ? 1 : 0));
        }
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

        // A SLIME PAD IS ONE MANOEUVRE, NOT A RUN OF WAYPOINTS. Hand the whole crossing to
        // the task that keeps heading and throttle across every bounce; the walker cannot,
        // because it re-decides each waypoint independently and bleeds the speed the last
        // bounce needs. Hand over the moment we are standing on the pad, aimed at the first
        // waypoint beyond it whose floor is NOT slime.
        bfsTicks++;
        if (kaptainwutax.tungsten.task.SlimeBounceTask.isActive()) return;
        // TRIGGER ON THE PLAN, NOT ON THE INSTANT. The first version of this asked whether we
        // were STANDING on slime, and it never once fired: on a bouncing pad the bot is
        // airborne almost every tick, so that window barely exists (measured — zero crossings
        // started across three runs). The route itself is the reliable signal: if the waypoint
        // we are heading for stands on slime, this is a crossing.
        if (TungstenConfig.get().slimeCrossing
                && path.get(waypointIdx) != null
                && player.getEntityWorld().getBlockState(path.get(waypointIdx).down())
                        .getBlock() instanceof net.minecraft.block.SlimeBlock) {
            slimeWpSeen++;
            BlockPos exit = null;
            for (int i = waypointIdx; i < path.size(); i++) {
                BlockPos c = path.get(i);
                if (!(player.getEntityWorld().getBlockState(c.down()).getBlock()
                        instanceof net.minecraft.block.SlimeBlock)) {
                    exit = c;
                    waypointIdx = i;
                    break;
                }
            }
            // THE LEG MAY END ON THE PAD. The walker is given a LEG, not the whole route, and
            // on this course the leg stops on the slime while the ledge belongs to the next
            // one — so "first waypoint past the pad" simply does not exist yet. Measured with
            // counters rather than logs, because the chat drops messages here: the trigger
            // condition was true 59 times in one run and a crossing still never started.
            // Aim at the far end of what we DO know; the navigator re-plans on arrival.
            if (exit == null && !path.isEmpty()) exit = path.get(path.size() - 1);
            if (exit != null) {
                kaptainwutax.tungsten.task.SlimeBounceTask.startTo(exit);
                return;
            }
        }

        BlockPos wp = path.get(waypointIdx);
        Vec3d wpPos = Vec3d.ofBottomCenter(wp);
        Vec3d playerPos = player.getEntityPos();
        double dist = horizontalDist(playerPos, wpPos);

        // advance waypoint. WHILE ON A LADDER, HORIZONTAL DISTANCE MEANS NOTHING: every cell
        // of the column shares one x/z, so this test is true from the first tick and would
        // consume the whole climb before a single rung is gained. On a ladder, arrival is a
        // VERTICAL question. (Only ladders are affected — off a ladder this is unchanged.)
        boolean onLadderNow = player.isClimbing();
        // FALLING PAST A LANDING IS NOT ARRIVING AT IT. Waypoints advance on horizontal
        // distance, so while airborne above a landing the walker ticked straight through it
        // and steered at the waypoints BEYOND. Arrival, while we are in the air and the
        // target is genuinely below us, is a vertical question — as on a ladder.
        //
        // This was briefly reverted on a FALSE regression signal: nav_gaps had gone flaky and
        // these walker changes looked responsible. An A/B on the same session settled it —
        // the last known-good build flakes on nav_gaps IDENTICALLY (1 pass in 3) on this
        // stand, which has drifted from ~15 fps to ~9 over a long session. The code was not
        // the cause. Measured effect of keeping this: nav_slime goes from 20.7 blocks short
        // with a void fall on every run, to 8.0-8.4 short with no falls at all, 3 runs of 3.
        // (Releasing the hold once we had flown PAST the waypoint was tried — the trace shows
        // the bot crossing its held waypoint five blocks up, steering back and walking off the
        // pad. Releasing measured WORSE, 3 failures in 3 against 1 landing in 3, so it is not
        // kept. Both behaviours are the same missing thing: the walker has no model of a
        // BOUNCING surface, and no rule bolted onto generic walking will give it one.)
        boolean fallingToward = !player.isOnGround() && (playerPos.y - wpPos.y) > 1.0;
        if (dist < 1.5 && (!onLadderNow || Math.abs(playerPos.y - wpPos.y) < 0.4)
                && !fallingToward) {
            waypointIdx++;
            if (waypointIdx >= path.size()) {
                stop();
                return;
            }
            wp = path.get(waypointIdx);
            wpPos = Vec3d.ofBottomCenter(wp);
        }

        // ── LADDER: A COLUMN OF WAYPOINTS HAS NO HORIZONTAL EXTENT ──────────────────
        // Waypoints advance on HORIZONTAL distance (see `dist` above), so a ladder column —
        // every cell sharing one x/z — is swallowed whole in a single tick without the bot
        // gaining a millimetre of height, and the walker then reports "arrived". The bearing
        // is no help either: a waypoint straight overhead has no meaningful yaw.
        // Vanilla climbs by holding forward INTO the ladder — that contact is what grants
        // climbing speed — so aim at the block the ladder hangs on, and advance on VERTICAL
        // arrival instead of horizontal.
        if (player.isClimbing()) {
            BlockPos cell = player.getBlockPos();
            var state = player.getEntityWorld().getBlockState(cell);
            if (state.getBlock() instanceof LadderBlock) {
                // FACING points AWAY from the block the ladder is fixed to, so push the
                // opposite way to press into it.
                Direction into = state.get(LadderBlock.FACING).getOpposite();
                Vec3d support = Vec3d.ofCenter(cell.offset(into));
                WindMouseRotation.INSTANCE.setTargetFast(
                        (float) DirectionHelper.calcYawFromVec3d(playerPos, support), 0);

                MinecraftClient lmc = MinecraftClient.getInstance();
                lmc.options.forwardKey.setPressed(true);
                lmc.options.sprintKey.setPressed(false);
                lmc.options.backKey.setPressed(false);
                lmc.options.leftKey.setPressed(false);
                lmc.options.rightKey.setPressed(false);
                lmc.options.sneakKey.setPressed(false);
                // Going up: jump as well as press in. Going down: contact alone is a slow,
                // controlled slide, which is what we want — never jump to descend.
                lmc.options.jumpKey.setPressed(wp.getY() > cell.getY());

                return;   // the advance above is already vertical-aware on a ladder
            }
        }

        float yaw = AttackTiming.yawTo(playerPos, wpPos);
        WindMouseRotation.INSTANCE.setTargetFast(yaw, 0);  // fast nav turn — keep the 45deg gate open

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
        // CLIMBING A STEP. A vanilla step-up is jump + FORWARD PRESSURE: jumping
        // without it just bounces on the spot. Right at the step the horizontal
        // distance is ~0, so the bearing is numerically unstable, `facing` flickers
        // and the forward key was released exactly when it was needed — the bot
        // hammered the jump key with X/Z frozen and Y oscillating for the rest of
        // the run (stand-measured at a 1-block ledge). When the waypoint is higher
        // and we are already on top of it horizontally, push forward regardless.
        boolean climbing = wp.getY() > player.getBlockPos().getY() && dist < 1.6;
        boolean move = facing || !onGround || climbing;
        // Do not keep adding speed once we are directly over the landing, or the arc carries
        // us past it. Known limit: on a bounce CHAIN this also throttles above every
        // intermediate hop and the bot bleeds speed, which is why the far ledge is still out
        // of reach. It is nonetheless the better of the two measured states — without it the
        // bot flies past the hop, turns back toward a waypoint now behind it and drops off
        // the pad. The real answer is a bounce chain the executor understands.
        // ...UNLESS that landing is a bouncy one. Cutting the throttle above every hop of a
        // bounce chain bled the bot from 0.25 to 0.00 blocks/tick (airborne trace), so it
        // could never carry speed to the far ledge; letting it run free instead made it fly
        // past the hop and drop off the pad. The world tells the two apart: land on SLIME and
        // you are going straight back up and still need the speed; land on solid ground and
        // you are stopping there.
        if (!onGround && (playerPos.y - wpPos.y) > 1.0 && dist < 1.0
                && !(player.getEntityWorld().getBlockState(wp.down()).getBlock()
                        instanceof net.minecraft.block.SlimeBlock)) {
            move = false;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(move);
        mc.options.sprintKey.setPressed(move && !climbing);   // sprint-jump overshoots a ledge
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        // DIAGNOSTIC: the harness samples roughly every 3 s because each sample is a py4j
        // round trip, which is far too coarse to see a fall — two samples showed the bot on
        // the lip and then 16 blocks away and 57 down, with no way to tell whether it ever
        // touched the pad in between. Print the actual flight, tick by tick.
        if (!onGround && TungstenConfig.get().verboseDebugLogging && (dbgN++ % 4 == 0)) {
            Vec3d vv = player.getVelocity();
            Debug.logMessage(String.format(
                    "AIR pos=(%.1f,%.1f,%.1f) vel=(%.2f,%.2f,%.2f) wp=(%d,%d,%d) idx=%d/%d",
                    playerPos.x, playerPos.y, playerPos.z, vv.x, vv.y, vv.z,
                    wp.getX(), wp.getY(), wp.getZ(), waypointIdx, path.size()));
        }

        boolean needJumpUp = wp.getY() > player.getBlockPos().getY();
        // YOU STEP OFF A HIGH LEDGE, YOU DO NOT LEAP OFF IT. A sprint-jump at a lip adds
        // height and forward momentum and stretches the arc far past the target — measured
        // on the bounce course at ~0.4 blocks/tick where a walk carries 0.28. Only a REAL
        // drop is meant here: a gap whose far side sits a block lower still has to be
        // jumped, so the line is drawn at two blocks, not at any descent at all.
        boolean droppingTo = wp.getY() < player.getBlockPos().getY() - 2;
        boolean canJump = (facing || climbing) && TungstenConfig.get().followJumpingEnabled
                && onGround && !droppingTo
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
