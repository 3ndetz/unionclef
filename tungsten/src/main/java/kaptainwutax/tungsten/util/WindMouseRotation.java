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
    private static final double GRAVITY   = 3.5;   // pull toward target per frame
    private static final double WIND      = 1.2;   // max wind magnitude per frame
    private static final double MAX_STEP  = 5.0;   // max degrees moved per frame
    private static final double WIND_DIST = 20.0;  // degrees distance below which wind decays
    private static final double DONE_THRESHOLD = 0.3; // snap when this close

    private static final double SQRT3 = Math.sqrt(3.0);
    private static final double SQRT5 = Math.sqrt(5.0);

    private final Random random = new Random();

    private double veloYaw   = 0, veloPitch = 0;
    private double windYaw   = 0, windPitch = 0;

    private float   targetYaw   = 0;
    private float   targetPitch = 0;
    private boolean hasTarget   = false;

    // -------------------------------------------------------------------------

    /**
     * Set the desired rotation. Call once per game tick from doDirectSprint().
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

        if (dist < DONE_THRESHOLD) {
            player.setYaw(targetYaw);
            player.setPitch(targetPitch);
            resetVelocity();
            return;
        }

        double W = Math.min(WIND, dist);
        if (dist >= WIND_DIST) {
            windYaw   = windYaw   / SQRT3 + (random.nextDouble() * 2.0 - 1.0) * W / SQRT5;
            windPitch = windPitch / SQRT3 + (random.nextDouble() * 2.0 - 1.0) * W / SQRT5;
        } else {
            windYaw   /= SQRT3;
            windPitch /= SQRT3;
        }

        veloYaw   += windYaw   + GRAVITY * dYaw   / dist;
        veloPitch += windPitch + GRAVITY * dPitch / dist;

        double veloMag = Math.sqrt(veloYaw * veloYaw + veloPitch * veloPitch);
        // Scale max step with distance: large angles turn faster (human-like fast flick)
        double effectiveMaxStep = MAX_STEP * Math.max(1.0, Math.min(4.0, dist / 15.0));
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
