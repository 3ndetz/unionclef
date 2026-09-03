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

    /** Frame time the per-frame tuning above was written against: 20 fps, the tick rate. */
    private static final double REF_FRAME_MS = 50.0;
    /** Most a single starved frame may claim, so a hitch cannot become a teleport. */
    private static final double MAX_CATCHUP = 4.0;
    /** When the last aim step ran, for measuring the frame in milliseconds. */
    private long lastStepMs = 0L;

    // WindMouse tuning — per render frame. Gravity = direct pull to target, wind = the
    // random orbit/spiral, maxStep = speed cap. Raised gravity + step and cut wind so the
    // aim converges FASTER and more DIRECTLY instead of slowly circling the target (user
    // live 2026-07-24, complained TWICE). Still pixel-quantized -> humanized, not a teleport.
    private double gravity      = 6.5;
    private double wind         = 0.30;
    private double maxStep      = 9.0;
    private double windDist     = 20.0;
    private double doneThreshold = 0.5;
    private double flickScale   = 4.0;

    // Close-range direct settle: within this many degrees of the target we DROP the
    // WindMouse momentum + wind entirely and move a fixed fraction of the remaining angle
    // straight at the target each frame. The slow "circling" the user saw is the classic
    // WindMouse orbit — accumulated velocity + wind carries the aim PAST the target and it
    // spirals in. Killing both inside the close zone makes the camera LAND sharply instead
    // of orbiting. (user 2026-07-24, twice). Far-range approach still uses WindMouse (with
    // velocity damping) so long turns stay smooth/humanized.
    private static final double CLOSE_DEG   = 7.0;
    private double closeFrac     = 0.55;   // combat/careful: fraction of remaining per frame
    private static final double VELO_DAMP = 0.72; // far-range momentum damping (anti-orbit)

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

        // TODOS.md C6.11: this accumulated pixel deltas every frame regardless of screen state,
        // while MixinMouse's own injection point refuses to drain the buffer while ANY Screen is
        // open (chat, inventory, pause) -- it early-returns WITHOUT calling
        // consumeRawPixelDeltas(), so nothing resets it either. The backlog built unboundedly for
        // as long as the screen stayed open and landed in a single frame the instant it closed:
        // a camera snap. Mirror the consumer's guard here so nothing is queued that cannot be
        // applied yet. lastStepMs is deliberately left unadvanced by this early return -- the
        // resuming frame sees one large frameMs, which the existing MAX_CATCHUP clamp below
        // already handles the same way it handles an ordinary hitch.
        if (MinecraftClient.getInstance().currentScreen != null) return;

        // Auto-release a stale target: an active consumer refreshes setTarget every
        // game tick, so anything older than STALE_MS means the driving task is gone.
        // Releasing here is the #29 root fix — a frozen aim can never persist.
        if (System.currentTimeMillis() - lastRefreshMs > STALE_MS) {
            clearTarget();
            return;
        }

        // AN AIM THAT ADVANCES PER FRAME TURNS SLOWER ON A SLOWER MACHINE.
        // Every step below is a fixed fraction or cap PER RENDER FRAME, so at 10 fps the head
        // turns at half the angular rate it does at 20 -- while the world, the mobs and the
        // block-placing all still run at 20 ticks a second. Anything that needs the crosshair on
        // a target before it acts is starved exactly when the host is loaded.
        // Measured on nav_bridge: 16.3 fps -> reaches the goal in 11.0s; 9.9 fps (the same course
        // WITH the recorder running) -> the bot stands at the lip of the gap and never places a
        // block. Same build, same course, different frame rate. The user's word for it was that
        // it must work "железно стабильно" -- and a behaviour that depends on frame rate is not
        // that.
        // So the step becomes a rate in TIME rather than per frame: a frame that took twice as
        // long is allowed twice the angular movement. Clamped at 1.0 below so nothing changes at
        // or above the reference rate -- this only ever gives back what a starved frame lost --
        // and capped above so a hitch cannot become a teleport.
        long nowMs = System.currentTimeMillis();
        double frameMs = lastStepMs == 0L ? REF_FRAME_MS : (nowMs - lastStepMs);
        lastStepMs = nowMs;
        double rate = Math.max(1.0, Math.min(MAX_CATCHUP, frameMs / REF_FRAME_MS));

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

        // --- Close-range DIRECT settle: no wind, no momentum — kills the orbit. ---
        // Move a large fraction of the remaining angle straight at the target. At render
        // FPS this converges in a few frames = a sharp, crisp land, not a slow spiral.
        if (dist < CLOSE_DEG) {
            // The fraction is per frame, so a long frame gets a bigger bite -- capped at 0.98
            // because taking the whole remainder in one step is a teleport, not a mouse.
            double frac = Math.min(0.98, (fastMode ? 0.9 : closeFrac) * rate);
            double stepYaw   = dYaw   * frac;
            double stepPitch = dPitch * frac;
            // clamp so an extreme sensitivity can't overshoot in one frame
            double stepMag = Math.sqrt(stepYaw * stepYaw + stepPitch * stepPitch);
            double capClose = (fastMode ? maxStep * 2.6 : maxStep) * rate;
            if (stepMag > capClose) {
                double s = capClose / stepMag;
                stepYaw *= s; stepPitch *= s;
            }
            resetVelocity();
            accumulatePixels(stepYaw, stepPitch);
            return;
        }

        // Fast (nav) turn converges quicker: stronger pull, bigger cap, less random
        // slow-down — a running player's quick head-turn, not a careful combat micro-aim.
        double g  = (fastMode ? gravity * 2.2 : gravity) * rate;
        double ms = (fastMode ? maxStep * 2.6 : maxStep) * rate;
        double stepLo = fastMode ? 0.85 : 0.6;

        double W = Math.min(wind, dist);
        if (dist >= windDist) {
            windYaw   = windYaw   / SQRT3 + (random.nextDouble() * 2.0 - 1.0) * W / SQRT5;
            windPitch = windPitch / SQRT3 + (random.nextDouble() * 2.0 - 1.0) * W / SQRT5;
        } else {
            windYaw   /= SQRT3;
            windPitch /= SQRT3;
        }

        // Damp accumulated momentum BEFORE adding this frame's pull — a plain WindMouse
        // integrator lets velocity build up and overshoot (the orbit). Damping keeps the
        // approach smooth but converging, not spiralling.
        veloYaw   = veloYaw   * VELO_DAMP + windYaw   + g * dYaw   / dist;
        veloPitch = veloPitch * VELO_DAMP + windPitch + g * dPitch / dist;

        double veloMag = Math.sqrt(veloYaw * veloYaw + veloPitch * veloPitch);
        double effectiveMaxStep = ms * Math.max(1.0, Math.min(flickScale, dist / 15.0));
        if (veloMag > effectiveMaxStep) {
            double scale = effectiveMaxStep * (stepLo + random.nextDouble() * (1.0 - stepLo)) / veloMag;
            veloYaw   *= scale;
            veloPitch *= scale;
        }

        // NEVER STEP FURTHER THAN THE ANGLE THAT IS LEFT.
        //
        // Measured on a live duel (sampled while punkStats reported combat=82): the head moves on
        // 26% of ticks, mean 13.56 deg, MAX 175.35 deg — a full spin in one tick while the target
        // sat 79 deg away. A step larger than the remaining angle cannot land; it can only cross
        // to the other side, and the momentum term then carries it back again. That is the orbit
        // this integrator's damping was meant to prevent, happening one scale up.
        //
        // It also explains why the close-range settle never helped: that zone only engages within
        // CLOSE_DEG = 7 deg, and a head travelling 13.6 deg a tick steps straight over it — from
        // outside the zone on one side to outside it on the other, never inside.
        //
        // Clamping to `dist` is the arithmetic fix rather than another tuned constant: the step
        // keeps its wind, its momentum and its direction, and simply stops at the target instead
        // of past it. Overshooting by 175 deg is not more human than landing — it is the shake the
        // user complained about ("прицел трясёт как не в себя").
        double stepMag = Math.sqrt(veloYaw * veloYaw + veloPitch * veloPitch);
        if (stepMag > dist && stepMag > 1.0E-6) {
            double s = dist / stepMag;
            veloYaw *= s;
            veloPitch *= s;
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
