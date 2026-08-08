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
    private static final float AIM_STEP = 18.0f;   // deg per tick toward the solution
    private static final float RELEASE_CONE = 3.5f; // max aim error at release (deg)
    private static final int TIMEOUT_TICKS = 100;

    private static final double VEL_EMA = 0.5;      // smooth packet jitter in the lead
    /** Above this per-tick jump the sample is a teleport/fall, not running. */
    private static final double MAX_STEP_PER_TICK = 1.5;
    /** Hard cap on the lead velocity: sprint-jumping tops out near 0.4 b/t. */
    private static final double MAX_LEAD_SPEED = 0.5;

    private static Entity target = null;
    private static int chargeTicks = 0;
    private static int totalTicks = 0;
    private static boolean active = false;
    private static int shotsFired = 0;
    private static Vec3d lastTargetPos = null;       // for position-delta velocity
    private static Vec3d trackedVel = Vec3d.ZERO;

    public static synchronized boolean shootAt(Entity entity) {
        if (entity == null) return false;
        target = entity;
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
     */
    private static final int AIM_LOCK_TICKS = 6;

    public static boolean isAimCritical() {
        return active && chargeTicks >= CHARGE_TICKS - AIM_LOCK_TICKS;
    }

    /** Zero the shot tally so a bench run measures its own shots, not the stand's history.
     *  Called from resetRunCounters alongside every other per-run counter. */
    public static void resetShotsFired() { shotsFired = 0; }

    public static void stop() {
        active = false;
        target = null;
        MinecraftClient.getInstance().options.useKey.setPressed(false);
        kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
    }

    /** Called every game tick from MixinClientPlayerEntity. */
    public static void tick(ClientPlayerEntity player) {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();

        if (target == null || target.isRemoved() || ++totalTicks > TIMEOUT_TICKS) {
            Debug.logMessage("Bow shot aborted");
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
        // claims the camera for the last AIM_LOCK_TICKS while the body keeps running its escape
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

        // draw while tracking; release only at full charge AND on-solution aim
        mc.options.useKey.setPressed(true);
        chargeTicks++;
        if (chargeTicks >= CHARGE_TICKS
                && Math.abs(dYaw) < RELEASE_CONE && Math.abs(dPitch) < RELEASE_CONE) {
            mc.options.useKey.setPressed(false);
            kaptainwutax.tungsten.TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
            shotsFired++;
            Debug.logMessage(String.format(
                    "Arrow released (flight ~%d ticks, lead %.1f blocks)",
                    sol.flightTicks,
                    sol.predictedTarget.distanceTo(target.getEntityPos())));
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
