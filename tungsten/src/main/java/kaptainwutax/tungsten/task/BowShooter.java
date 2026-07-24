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
            trackedVel = trackedVel.multiply(1 - VEL_EMA).add(inst.multiply(VEL_EMA));
        }
        lastTargetPos = curPos;
        Vec3d aimPoint = curPos.add(0, target.getHeight() * 0.6, 0);
        Vec3d leadVel = target.isOnGround()
                ? new Vec3d(trackedVel.x, 0, trackedVel.z) : trackedVel;

        double charge = Math.min(1.0, chargeTicks / 20.0);
        TrajectorySolver.Solution sol = TrajectorySolver.solve(
                player.getEyePos(), aimPoint, leadVel, Math.max(charge, 1.0)); // full-draw arc
        if (sol == null) {
            Debug.logMessage("Bow shot aborted (out of range)");
            stop();
            return;
        }

        // Humanized aim via WindMouse (mouse-pipeline) — never setYaw/setPitch,
        // anti-cheats flag instant rotation. WindMouse converges toward the
        // ballistic solution over frames like a real hand.
        kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.setTarget(sol.yaw, sol.pitch);
        float dYaw = MathHelper.wrapDegrees(sol.yaw - player.getYaw());
        float dPitch = MathHelper.wrapDegrees(sol.pitch - player.getPitch());

        // draw while tracking; release only at full charge AND on-solution aim
        mc.options.useKey.setPressed(true);
        chargeTicks++;
        if (chargeTicks >= CHARGE_TICKS
                && Math.abs(dYaw) < RELEASE_CONE && Math.abs(dPitch) < RELEASE_CONE) {
            mc.options.useKey.setPressed(false);
            shotsFired++;
            Debug.logMessage(String.format(
                    "Arrow released (flight ~%d ticks, lead %.1f blocks)",
                    sol.flightTicks,
                    sol.predictedTarget.distanceTo(target.getEntityPos())));
            stop();
        }
    }
}
