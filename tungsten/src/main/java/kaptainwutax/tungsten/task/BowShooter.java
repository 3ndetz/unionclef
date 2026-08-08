package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.combat.TrajectorySolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Bow-shot execution primitive: aim by TrajectorySolver, hold use to charge,
 * keep tracking the (moving) target while charging, release at full draw.
 *
 * A primitive by design: expects a bow already in hand and does not decide
 * WHEN to shoot — weapon selection and tactics stay on the altoclef side
 * (its bow logic just calls shootAt / py4j shootArrowAt drives it in tests).
 * Ticked from MixinClientPlayerEntity on the client thread.
 */
public class BowShooter {

    private static final int CHARGE_TICKS = 22;    // full draw is 20
    /**
     * How near the solution the aim must be before the bow is DRAWN at all.
     *
     * <p>Coarse on purpose. It is not an accuracy gate — {@link TrajectorySolver#predictedMiss}
     * decides accuracy at release — it only stops the draw from starting while the camera is still
     * swinging round, because a drawn bow cannot sprint. Wide enough that the remaining slew fits
     * comfortably inside the draw, tight enough that the draw is not begun facing the wrong way.
     */
    private static final float DRAW_START_CONE = 12.0f;
    /** Target half-width to aim inside, including vanilla's hit margin. */
    private static final double HIT_RADIUS = 0.55;
    private static final int TIMEOUT_TICKS = 100;

    private static final double VEL_EMA = 0.5;      // smooth packet jitter in the lead
    /** Above this per-tick jump the sample is a teleport/fall, not running. */
    private static final double MAX_STEP_PER_TICK = 1.5;
    /** Hard cap on the lead velocity: sprint-jumping tops out near 0.4 b/t. */
    private static final double MAX_LEAD_SPEED = 0.5;

    private static Entity target = null;
    /** False while still turning onto the solution, true once the bow is actually being drawn. */
    private static boolean drawing = false;
    private static int chargeTicks = 0;
    private static int totalTicks = 0;
    private static boolean active = false;
    private static int shotsFired = 0;
    private static int wildShots = 0;
    private static Vec3d lastTargetPos = null;       // for position-delta velocity
    private static Vec3d trackedVel = Vec3d.ZERO;

    /** Draw length past which vanilla fires on key release — see {@link #getWildShots()}. */
    private static final int VANILLA_FIRES_AFTER = 3;

    /**
     * Which HALF of the shot ran out of time — recorded because a red course cannot otherwise tell
     * these two apart, and they want opposite fixes.
     *
     * <ul>
     *   <li>{@code aimTimeouts}: the turn never got within {@link #DRAW_START_CONE}, so the bow was
     *       never drawn. The camera is losing its fight with movement — the TURN is the problem.</li>
     *   <li>{@code drawTimeouts}: the bow was drawn and fully charged, but no tick ever predicted an
     *       impact inside {@link #HIT_RADIUS}. The RELEASE GATE is the problem — at 24.5 blocks it
     *       tolerates about 1.26 degrees, and if the aim cannot hold that, shots stop entirely and a
     *       course that used to pass goes red for a reason that looks nothing like its cause.</li>
     * </ul>
     *
     * <p>Written BEFORE the change that creates the risk, so the next run answers the question
     * instead of me guessing at it afterwards.
     */
    private static int aimTimeouts = 0;
    private static int drawTimeouts = 0;
    /**
     * Ticks spent with a shot in progress — i.e. ticks the bot is FACING ITS TARGET.
     *
     * <p>This is the quantity that decides whether a kiting bot can hold its distance, and nothing
     * was counting it. Vanilla will not sprint unless the movement input has a forward component
     * ({@code canStartSprinting()} requires {@code input.hasForwardMovement()}), and
     * {@code getDirectionalMovementSpeedMultiplier} penalises non-forward travel on top. A bot
     * turned around to shoot is therefore walking backwards, and on bow_flee the trace shows the
     * gap closing at 1.47 blocks/second — which is what a backwards-walking target loses to a
     * sprinting chaser, even one the course has afflicted with slowness.
     *
     * <p>So the cost of shooting is measured in TICKS SPENT FACING, and the course requests a shot
     * every 3 seconds. If this lands near half the run, the bot cannot win that footrace no matter
     * how good its aim is, and the fix is the shot CYCLE, not the shot.
     */
    private static int facingTicks = 0;
    /** Closest predicted impact (blocks) seen during the last draw — how near the gate we got. */
    private static double bestMiss = -1;

    public static synchronized boolean shootAt(Entity entity) {
        if (entity == null) return false;
        target = entity;
        drawing = false;
        bestMiss = -1;
        chargeTicks = 0;
        totalTicks = 0;
        active = true;
        lastTargetPos = null;
        trackedVel = Vec3d.ZERO;
        return true;
    }

    public static boolean isActive() { return active; }
    public static int getShotsFired() { return shotsFired; }

    /**
     * Arrows thrown away by an ABORT rather than aimed — and they are real arrows.
     *
     * <p>{@link #stop()} drops the use key, and in vanilla dropping the use key on a drawn bow IS
     * the shot: {@code BowItem.onStoppedUsing} fires whenever {@code getPullProgress(useTicks)}
     * reaches 0.1, which {@code (t/20)^2 + 2(t/20) > 0.3} puts at about 2.8 ticks of draw. All
     * three abort paths — timeout, target removed, out of range — are reached far past that.
     *
     * <p>So an aborted draw is not a cancelled shot, it is an arrow loosed wherever the camera
     * happened to point, usually mid-slew. {@code shotsFired} never saw them, which makes
     * "bowShots=6 requested~20" mean "6 AIMED, the rest thrown", not "14 never happened" — the
     * same shape of lie as the landed_swings undercount.
     */
    public static int getWildShots() { return wildShots; }

    /** Ticks spent facing the target for a shot — see the field docs; this is the kiting cost. */
    public static int getFacingTicks() { return facingTicks; }

    /** Draws that never turned onto the solution / never predicted a hit — see the field docs. */
    public static int getAimTimeouts() { return aimTimeouts; }
    public static int getDrawTimeouts() { return drawTimeouts; }
    /** Closest predicted impact of the last draw, in blocks; -1 if nothing was ever evaluated. */
    public static double getBestMiss() { return bestMiss; }

    /**
     * Is the draw close enough to release that the aim should own the camera?
     *
     * <p>WHY THIS IS NARROWER THAN {@link #isActive()}. PathExecutor hands the camera to the aim
     * while a shot is in progress, because movement otherwise overwrites the yaw every tick and the
     * arrow is never loosed. That works — shots went from 1 in 20 to 5 — but facing the target
     * means travelling on the strafe and back keys, and VANILLA ONLY SPRINTS WHILE MOVING FORWARD.
     * So the whole flight was being run at walking pace to buy a shot once a second:
     *
     *     pre-fix   avg_dist 7.17   bowShots 1
     *     post-fix  avg_dist 4.95 / 6.51 / 6.02   bowShots 5 / 5 / 3
     *
     * <p>The draw takes {@link #CHARGE_TICKS} ticks and only the last of them need the crosshair on
     * the solution. Before that the bot may as well be sprinting away with its back turned, which
     * is what keeps the gap open. So the camera is claimed only for the final stretch, and the cost
     * is paid for a fraction of a second per arrow instead of for the entire flight.
     *
     * <p>UPDATED with the aim-first split. The camera is now claimed for the WHOLE shot, aiming
     * included — the turn cannot finish while movement rewrites the yaw every tick, and it is the
     * turn that decides when the bow may be drawn. That sounds like the regression this comment
     * was written to prevent, and it is not, because the two costs are different:
     *
     * <ul>
     *   <li>the camera claim costs the SPRINT DIRECTION — facing the target means travelling on
     *       strafe and back, and vanilla only sprints forward;</li>
     *   <li>the DRAW costs sprinting outright — {@code isBlockedFromSprinting()} is
     *       {@code isUsingItem() && !USE_EFFECTS.canSprint()}.</li>
     * </ul>
     *
     * <p>Before, a draw that could not satisfy its release gate ran to the 100-tick timeout paying
     * BOTH. Now the bow stays down until the shot is roughly on-solution, so an impossible shot
     * costs the camera only and the bot keeps sprinting; and a shot that does happen pays the draw
     * for the ~22 ticks it actually needs.
     */
    public static boolean isAimCritical() {
        return active;
    }

    /** Zero the shot tally so a bench run measures its own shots, not the stand's history.
     *  Called from resetRunCounters alongside every other per-run counter. */
    public static void resetShotsFired() {
        shotsFired = 0;
        wildShots = 0;
        aimTimeouts = 0;
        drawTimeouts = 0;
        facingTicks = 0;
        bestMiss = -1;
    }

    public static void stop() {
        // Count the arrow this release is about to throw. Ordered BEFORE active is cleared, since
        // TungstenMod's global stop() calls this with nothing drawn and that must not count.
        if (active && chargeTicks >= VANILLA_FIRES_AFTER) wildShots++;
        active = false;
        drawing = false;
        target = null;
        MinecraftClient.getInstance().options.useKey.setPressed(false);
        kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
    }

    /** Called every game tick from MixinClientPlayerEntity. */
    public static void tick(ClientPlayerEntity player) {
        if (!active) return;
        facingTicks++;      // the camera is claimed for the whole shot — this IS the kiting cost
        MinecraftClient mc = MinecraftClient.getInstance();

        if (target == null || target.isRemoved() || ++totalTicks > TIMEOUT_TICKS) {
            if (totalTicks > TIMEOUT_TICKS) {
                if (drawing) drawTimeouts++; else aimTimeouts++;
                Debug.logMessage(String.format("Bow shot timed out (%s, best miss %.2f)",
                        drawing ? "drawn but never on target" : "never finished turning", bestMiss));
            } else {
                Debug.logMessage("Bow shot aborted");
            }
            stop();
            return;
        }

        // Lead prediction from PER-TICK POSITION DELTAS, not target.getVelocity():
        // a walking remote player reports ~0 getVelocity() on the client (it moves via
        // position packets), so the Entity-overload solve had no lead on real players
        // (RW-6 / audit F6) — bow whiffed every moving target. Track velocity here and
        // feed the explicit-velocity solver.
        Vec3d curPos = target.getEntityPos();
        if (lastTargetPos != null) {
            Vec3d inst = curPos.subtract(lastTargetPos);   // blocks/tick, arrow-sim units
            // Reject teleports//tp/respawn jumps and clamp to a physically possible
            // player speed. Without this a single position spike (a falling or
            // teleported target) poisoned the EMA and the solver aimed 57 blocks
            // ahead — straight into the ground (user 2026-07-24).
            if (inst.length() <= MAX_STEP_PER_TICK) {
                trackedVel = trackedVel.multiply(1 - VEL_EMA).add(inst.multiply(VEL_EMA));
            } else {
                trackedVel = Vec3d.ZERO;                   // discontinuity: no lead
            }
            if (trackedVel.length() > MAX_LEAD_SPEED) {
                trackedVel = trackedVel.normalize().multiply(MAX_LEAD_SPEED);
            }
        }
        lastTargetPos = curPos;
        Vec3d aimPoint = curPos.add(0, target.getHeight() * 0.6, 0);
        Vec3d leadVel = target.isOnGround()
                ? new Vec3d(trackedVel.x, 0, trackedVel.z) : trackedVel;

        double charge = Math.min(1.0, chargeTicks / 20.0);
        // The arrow inherits the SHOOTER's movement (vanilla adds it in setVelocity). It costs
        // almost nothing when running straight away from the target (0.14 blocks — collinear), and
        // up to 2.33 blocks when the motion is ACROSS the shot. Kiting is the second kind: the aim
        // claims the camera while the body keeps running its escape
        // path, so the body is moving sideways relative to where the bow points.
        Vec3d shooterVel = TrajectorySolver.shooterVelocity(player);
        TrajectorySolver.Solution sol = TrajectorySolver.solve(
                player.getEyePos(), shooterVel, aimPoint, leadVel, Math.max(charge, 1.0)); // full-draw arc
        if (sol == null) {
            Debug.logMessage("Bow shot aborted (out of range)");
            stop();
            return;
        }

        // Humanized aim via WindMouse (mouse-pipeline) — never setYaw/setPitch,
        // anti-cheats flag instant rotation. FAST mode: a real archer flicks onto
        // the target and holds; the slow glide made every shot take seconds
        // (user 2026-07-24: "стрелял ОЧЕНЬ МЕДЛЕННО").
        kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.setTargetFast(sol.yaw, sol.pitch);
        // VISUALIZE the ballistic solution — the user must SEE the arc the solver
        // chose (RW-6 / "где траектории при стрельбе из лука").
        renderTrajectory(player, sol, Math.max(charge, 1.0));
        float dYaw = MathHelper.wrapDegrees(sol.yaw - player.getYaw());
        float dPitch = MathHelper.wrapDegrees(sol.pitch - player.getPitch());

        // AIM FIRST, DRAW SECOND — the draw is not free, it costs the sprint.
        //
        // Vanilla: isBlockedFromSprinting() is `isUsingItem() && !USE_EFFECTS.canSprint()`, and
        // canStartSprinting() requires !isBlockedFromSprinting(); the movement input is scaled by
        // getActiveItemSpeedMultiplier() on top. A vanilla bow carries no override, so EVERY TICK
        // THE BOW IS DRAWN IS A TICK THE BOT CANNOT SPRINT.
        //
        // The old code pressed the use key on tick 0 and kept it pressed while the camera was
        // still slewing onto the solution. If the aim never settled the draw did not end at
        // CHARGE_TICKS, it ran to the TIMEOUT_TICKS abort — up to 100 ticks of no sprint, against
        // a caller asking for a shot every 60. So the kiting bot walked, and bow_flee, ordered to
        // hold 12 blocks, averaged 6.66.
        //
        // Slewing costs the camera but NOT the sprint, so do it with the bow down. Only start
        // drawing once the shot is roughly on-solution; the draw then almost always completes on
        // time, and the no-sprint window is the ~22 ticks it genuinely needs.
        boolean onSolution = Math.abs(dYaw) < DRAW_START_CONE && Math.abs(dPitch) < DRAW_START_CONE;
        if (!drawing) {
            if (!onSolution) return;                  // still turning — bow stays down
            drawing = true;
        }
        mc.options.useKey.setPressed(true);
        chargeTicks++;

        // RELEASE ON PREDICTED IMPACT, NOT ON AN ANGLE. What matters is where the arrow lands, and
        // the same simulator that solved the shot can answer that from the CURRENT aim. An angular
        // cone cannot: the lateral miss it permits grows with range, so 3.5 degrees was 0.37 blocks
        // at 6 and 1.53 at 25 against a target ~0.6 wide. This also needs no separate allowance for
        // the shooter's inherited velocity — the simulation already carries it.
        double miss = TrajectorySolver.predictedMiss(player.getEyePos(), shooterVel,
                player.getYaw(), player.getPitch(), 1.0, sol.predictedTarget);
        if (bestMiss < 0 || miss < bestMiss) bestMiss = miss;
        if (chargeTicks >= CHARGE_TICKS && miss <= HIT_RADIUS) {
            mc.options.useKey.setPressed(false);
            kaptainwutax.tungsten.TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
            shotsFired++;
            Debug.logMessage(String.format(
                    "Arrow released (flight ~%d ticks, predicted miss %.2f blocks)",
                    sol.flightTicks, miss));
            stop();
        }
    }

    /**
     * Draw the SIMULATED arrow flight (vanilla ballistics: pos += vel; vel *= 0.99;
     * vel.y -= 0.05) plus a marker on the predicted impact point. Rebuilt every tick
     * while drawing, so the arc visibly re-aims as the target moves. Gated by
     * renderCombat / renderVisualization in the debug-renderer mixin.
     */
    private static void renderTrajectory(ClientPlayerEntity player,
                                         TrajectorySolver.Solution sol, double charge) {
        if (!kaptainwutax.tungsten.TungstenConfig.get().renderVisualization
                || !kaptainwutax.tungsten.TungstenConfig.get().renderCombat) return;
        kaptainwutax.tungsten.TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();

        double v0 = Math.max(0.1, charge) * TrajectorySolver.FULL_CHARGE_SPEED;
        double yawRad = Math.toRadians(sol.yaw);
        double pitchRad = Math.toRadians(sol.pitch);
        double horiz = v0 * Math.cos(pitchRad);
        // Same inherited-movement term the solver corrects for. Drawing the aim direction alone
        // would show an arc through the crosshair, which is NOT the arc the arrow flies while the
        // bot is kiting — the picture has to match the physics or it is worse than no picture.
        Vec3d vel = new Vec3d(-Math.sin(yawRad) * horiz,
                              -v0 * Math.sin(pitchRad),
                               Math.cos(yawRad) * horiz)
                .add(TrajectorySolver.shooterVelocity(player));
        Vec3d pos = player.getEyePos();
        kaptainwutax.tungsten.render.Color arc =
                new kaptainwutax.tungsten.render.Color(80, 220, 255);   // cyan flight arc
        for (int t = 0; t < Math.max(20, sol.flightTicks + 6); t++) {
            Vec3d next = pos.add(vel);
            kaptainwutax.tungsten.TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
                    new kaptainwutax.tungsten.render.Line(pos, next, arc));
            pos = next;
            vel = new Vec3d(vel.x * 0.99, vel.y * 0.99 - 0.05, vel.z * 0.99);
        }
        Vec3d p = sol.predictedTarget;                     // where the lead expects the target
        kaptainwutax.tungsten.TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
                new kaptainwutax.tungsten.render.Cuboid(
                        p.subtract(0.3, 0.3, 0.3), new Vec3d(0.6, 0.6, 0.6),
                        new kaptainwutax.tungsten.render.Color(255, 80, 80)));
    }
}
