package kaptainwutax.tungsten.util;

import net.minecraft.client.network.ClientPlayerEntity;

import java.util.Random;

/**
 * WindMouse-based rotation smoother for human-like yaw/pitch movement.
 *
 * Architecture (two-phase):
 *   1. Game tick  — setTarget(yaw, pitch): store desired facing, do NOT touch player.
 *   2. Render frame — applyRenderStep(player): one WindMouse step → player.setYaw/setPitch.
 *
 * Running at render frequency (~60 FPS vs 20 TPS) makes the rotation look genuinely
 * smooth instead of snapping every 50 ms, which anti-cheats detect.
 *
 * Singleton: WindMouseRotation.INSTANCE — shared across all follow tasks.
 */
public class WindMouseRotation {

    /** Shared singleton used by follow tasks and the render mixin. */
    public static final WindMouseRotation INSTANCE = new WindMouseRotation();

    // WindMouse tuning — per render frame at ~60 FPS
    // These are defaults; can be overridden per-instance via setParams()
    private double gravity      = 3.5;   // pull toward target per frame
    private double wind         = 1.2;   // max wind magnitude per frame
    private double maxStep      = 5.0;   // max degrees moved per frame
    private double windDist     = 20.0;  // degrees distance below which wind decays
    private double doneThreshold = 0.3;  // snap when this close
    private double flickScale   = 4.0;   // max multiplier for far-angle flicks

    private static final double SQRT3 = Math.sqrt(3.0);
    private static final double SQRT5 = Math.sqrt(5.0);

    private final Random random = new Random();

    private double veloYaw   = 0, veloPitch = 0;
    private double windYaw   = 0, windPitch = 0;

    private float   targetYaw   = 0;
    private float   targetPitch = 0;
    private boolean hasTarget   = false;

    // -------------------------------------------------------------------------

    /** Override WindMouse parameters. Call before setTarget if needed. */
    public void setParams(double gravity, double wind, double maxStep,
                          double windDist, double doneThreshold, double flickScale) {
        this.gravity = gravity;
        this.wind = wind;
        this.maxStep = maxStep;
        this.windDist = windDist;
        this.doneThreshold = doneThreshold;
        this.flickScale = flickScale;
    }

    /**
     * Set the desired rotation. Call once per game tick.
     * Does NOT immediately move the player.
     */
    public void setTarget(float yaw, float pitch) {
        this.targetYaw   = yaw;
        this.targetPitch = pitch;
        this.hasTarget   = true;
    }

    /**
     * Apply one WindMouse step toward target. Call once per render frame from MixinInGameHud.
     * Does nothing if no target is set.
     */
    public void applyRenderStep(ClientPlayerEntity player) {
        if (!hasTarget || player == null) return;

        float currentYaw   = player.getYaw();
        float currentPitch = player.getPitch();

        double dYaw   = wrapDelta(targetYaw - currentYaw);
        double dPitch = targetPitch - currentPitch;
        double dist   = Math.sqrt(dYaw * dYaw + dPitch * dPitch);

        if (dist < doneThreshold) {
            player.setYaw(targetYaw);
            player.setPitch(targetPitch);
            resetVelocity();
            return;
        }

        double W = Math.min(wind, dist);
        if (dist >= windDist) {
            windYaw   = windYaw   / SQRT3 + (random.nextDouble() * 2.0 - 1.0) * W / SQRT5;
            windPitch = windPitch / SQRT3 + (random.nextDouble() * 2.0 - 1.0) * W / SQRT5;
        } else {
            windYaw   /= SQRT3;
            windPitch /= SQRT3;
        }

        veloYaw   += windYaw   + gravity * dYaw   / dist;
        veloPitch += windPitch + gravity * dPitch / dist;

        double veloMag = Math.sqrt(veloYaw * veloYaw + veloPitch * veloPitch);
        double effectiveMaxStep = maxStep * Math.max(1.0, Math.min(flickScale, dist / 15.0));
        if (veloMag > effectiveMaxStep) {
            double scale = effectiveMaxStep * (0.5 + random.nextDouble() * 0.5) / veloMag;
            veloYaw   *= scale;
            veloPitch *= scale;
        }

        player.setYaw((float) (currentYaw + veloYaw));
        player.setPitch((float) Math.max(-90.0, Math.min(90.0, currentPitch + veloPitch)));
    }

    /** Clear target and reset velocity/wind state. Call from releaseKeys(). */
    public void clearTarget() {
        hasTarget = false;
        resetVelocity();
    }

    public boolean hasTarget() { return hasTarget; }

    /** Angular distance (degrees) from player's current facing to target. */
    public double distanceToTarget(ClientPlayerEntity player) {
        if (!hasTarget || player == null) return 999;
        double dYaw   = wrapDelta(targetYaw - player.getYaw());
        double dPitch = targetPitch - player.getPitch();
        return Math.sqrt(dYaw * dYaw + dPitch * dPitch);
    }

    private void resetVelocity() {
        veloYaw = 0; veloPitch = 0;
        windYaw = 0; windPitch = 0;
    }

    private static double wrapDelta(double delta) {
        delta = delta % 360.0;
        if (delta > 180.0)   delta -= 360.0;
        if (delta <= -180.0) delta += 360.0;
        return delta;
    }
}
