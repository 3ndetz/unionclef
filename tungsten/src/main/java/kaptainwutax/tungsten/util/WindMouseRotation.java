package kaptainwutax.tungsten.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.Random;

/**
 * WindMouse-based rotation smoother for human-like yaw/pitch movement.
 *
 * Architecture:
 *   1. Game tick — setTarget(yaw, pitch): store desired facing.
 *   2. Render frame — applyRenderStep(player): compute WindMouse delta, convert to
 *      raw pixel deltas, accumulate in pendingPixelDX/DY.
 *   3. MixinMouse.updateMouse(HEAD) — consume pixel deltas, add to cursorDeltaX/Y.
 *      Vanilla pipeline does the rest: sensitivity scaling → changeLookDirection.
 *
 * Result: rotation goes through the full vanilla mouse pipeline.
 * Server sees rotation steps identical to a physical mouse.
 *
 * TODO: large-angle mouse lift pauses — when a big turn requires "picking up the mouse",
 *       add a brief pause + reduced precision to simulate repositioning.
 *
 * Singleton: WindMouseRotation.INSTANCE — shared across all tasks.
 */
public class WindMouseRotation {

    public static final WindMouseRotation INSTANCE = new WindMouseRotation();

    // WindMouse tuning — per render frame. Gravity = direct pull to target, wind = the
    // random orbit/spiral, maxStep = speed cap. Raised gravity + step and cut wind so the
    // aim converges FASTER and more DIRECTLY instead of slowly circling the target (user
    // live 2026-07-24). Still pixel-quantized + some wind -> humanized, not a robotic snap.
    private double gravity      = 6.0;
    private double wind         = 0.7;
    private double maxStep      = 8.5;
    private double windDist     = 20.0;
    private double doneThreshold = 0.3;
    private double flickScale   = 4.0;

    private static final double SQRT3 = Math.sqrt(3.0);
    private static final double SQRT5 = Math.sqrt(5.0);

    private final Random random = new Random();

    private double veloYaw   = 0, veloPitch = 0;
    private double windYaw   = 0, windPitch = 0;

    private float   targetYaw   = 0;
    private float   targetPitch = 0;
    private boolean hasTarget   = false;
    // FAST (navigation) turn: a human running SNAPS their view toward where they're going,
    // far quicker than a careful combat micro-adjustment. Combat/bow/mining keep the slow
    // humanized aim (anti-cheat surface); the walker's chase turn uses fast so the bot
    // stays aligned and doesn't stop to pivot on every bearing change (which halved chase
    // speed). Still pixel-quantized through the vanilla mouse pipeline — a quick flick, not
    // a teleport. Refreshed every setTarget call so it can't stick on.
    private boolean fastMode    = false;

    // Wall-clock of the last setTarget(). Active consumers (executor break, combat,
    // walker, bow, bridge, pillar) refresh the target every game tick (~50ms). If
    // nothing refreshes it for STALE_MS, the driving task is dead/stuck and the aim
    // auto-releases — otherwise a static singleton holding a stale target locks the
    // camera forever (and survives reconnect). This is the #29 root fix: a frozen
    // mine/combat aim can no longer persist just because a task forgot clearTarget().
    private long lastRefreshMs = 0L;
    private static final long STALE_MS = 600L;

    // accumulated raw pixel deltas for MixinMouse to consume
    private double pendingPixelDX = 0;
    private double pendingPixelDY = 0;

    // -------------------------------------------------------------------------

    public void setParams(double gravity, double wind, double maxStep,
                          double windDist, double doneThreshold, double flickScale) {
        this.gravity = gravity;
        this.wind = wind;
        this.maxStep = maxStep;
        this.windDist = windDist;
        this.doneThreshold = doneThreshold;
        this.flickScale = flickScale;
    }

    public void setTarget(float yaw, float pitch) {
        setTarget(yaw, pitch, false);
    }

    /** Navigation turn: snap toward the facing quickly (walker chase). See fastMode. */
    public void setTargetFast(float yaw, float pitch) {
        setTarget(yaw, pitch, true);
    }

    public void setTarget(float yaw, float pitch, boolean fast) {
        this.targetYaw   = yaw;
        this.targetPitch = pitch;
        this.hasTarget   = true;
        this.fastMode    = fast;
        this.lastRefreshMs = System.currentTimeMillis();
    }

    /**
     * Compute one WindMouse step and accumulate as raw pixel deltas.
     * Called per render frame from MixinInGameHud.
     * MixinMouse will inject these into cursorDeltaX/Y on next updateMouse().
     */
    public void applyRenderStep(ClientPlayerEntity player) {
        if (!hasTarget || player == null) return;

        // Auto-release a stale target: an active consumer refreshes setTarget every
        // game tick, so anything older than STALE_MS means the driving task is gone.
        // Releasing here is the #29 root fix — a frozen aim can never persist.
        if (System.currentTimeMillis() - lastRefreshMs > STALE_MS) {
            clearTarget();
            return;
        }

        float currentYaw   = player.getYaw();
        float currentPitch = player.getPitch();

        double dYaw   = wrapDelta(targetYaw - currentYaw);
        double dPitch = targetPitch - currentPitch;
        double dist   = Math.sqrt(dYaw * dYaw + dPitch * dPitch);

        if (dist < doneThreshold) {
            accumulatePixels(dYaw, dPitch);
            resetVelocity();
            return;
        }

        // Fast (nav) turn converges quicker: stronger pull, bigger cap, less random
        // slow-down — a running player's quick head-turn, not a careful combat micro-aim.
        double g  = fastMode ? gravity * 2.2 : gravity;
        double ms = fastMode ? maxStep * 2.6 : maxStep;
        double stepLo = fastMode ? 0.85 : 0.5;

        double W = Math.min(wind, dist);
        if (dist >= windDist) {
            windYaw   = windYaw   / SQRT3 + (random.nextDouble() * 2.0 - 1.0) * W / SQRT5;
            windPitch = windPitch / SQRT3 + (random.nextDouble() * 2.0 - 1.0) * W / SQRT5;
        } else {
            windYaw   /= SQRT3;
            windPitch /= SQRT3;
        }

        veloYaw   += windYaw   + g * dYaw   / dist;
        veloPitch += windPitch + g * dPitch / dist;

        double veloMag = Math.sqrt(veloYaw * veloYaw + veloPitch * veloPitch);
        double effectiveMaxStep = ms * Math.max(1.0, Math.min(flickScale, dist / 15.0));
        if (veloMag > effectiveMaxStep) {
            double scale = effectiveMaxStep * (stepLo + random.nextDouble() * (1.0 - stepLo)) / veloMag;
            veloYaw   *= scale;
            veloPitch *= scale;
        }

        accumulatePixels(veloYaw, veloPitch);
    }

    /**
     * Convert degree deltas to raw pixel deltas and accumulate.
     * MC pipeline: raw_pixels → * (f³*8) → changeLookDirection → * 0.15 → degrees.
     * Inverse: degrees / 0.15 / (f³*8) = raw_pixels.
     * Round to integer pixels for realism.
     */
    private void accumulatePixels(double deltaYawDeg, double deltaPitchDeg) {
        double sensScale = getSensitivityScale();
        double degreesPerPixel = sensScale * 0.15;

        long pixelsX = Math.round(deltaYawDeg / degreesPerPixel);
        long pixelsY = Math.round(deltaPitchDeg / degreesPerPixel);

        pendingPixelDX += pixelsX;
        pendingPixelDY += pixelsY;
    }

    /**
     * Called by MixinMouse at updateMouse(HEAD).
     * Returns accumulated raw pixel deltas and resets.
     */
    public double[] consumeRawPixelDeltas() {
        double dx = pendingPixelDX;
        double dy = pendingPixelDY;
        pendingPixelDX = 0;
        pendingPixelDY = 0;
        return new double[]{dx, dy};
    }

    private static double getSensitivityScale() {
        double sens = MinecraftClient.getInstance().options.getMouseSensitivity().getValue();
        double f = sens * 0.6 + 0.2;
        return f * f * f * 8.0;
    }

    public void clearTarget() {
        hasTarget = false;
        resetVelocity();
        pendingPixelDX = 0;
        pendingPixelDY = 0;
    }

    public boolean hasTarget() { return hasTarget; }

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

    /** Shortest signed angular difference in (-180,180]. Public so path-followers can
     *  gate movement on facing (don't walk until roughly pointed at the waypoint). */
    public static double wrapDelta(double delta) {
        delta = delta % 360.0;
        if (delta > 180.0)   delta -= 360.0;
        if (delta <= -180.0) delta += 360.0;
        return delta;
    }
}
