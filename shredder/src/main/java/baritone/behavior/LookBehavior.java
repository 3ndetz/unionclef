/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.behavior.ILookBehavior;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.*;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.behavior.look.ForkableRandom;
import java.util.Optional;
import java.util.Random;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.MathHelper;

public final class LookBehavior extends Behavior implements ILookBehavior {

    /**
     * The current look target, may be {@code null}.
     */
    private Target target;

    /**
     * The rotation known to the server. Returned by {@link #getEffectiveRotation()} for use in {@link IPlayerContext}.
     */
    private Rotation serverRotation;

    /**
     * The last player rotation. Used to restore the player's angle when using free look.
     *
     * @see Settings#freeLook
     */
    private Rotation prevRotation;

    private final AimProcessor processor;

    // Render-frame smooth rotation state
    private float smoothYaw;
    private float smoothPitch;
    private float renderTargetYaw;
    private float renderTargetPitch;
    private long lastSmoothNanos;
    private boolean smoothActive;
    private boolean hadTargetThisTick;

    // WindMouse state (render-frame physics)
    private final Random wmRandom = new Random();
    private double wmVeloYaw, wmVeloPitch;
    private double wmWindYaw, wmWindPitch;

    public LookBehavior(Baritone baritone) {
        super(baritone);
        this.processor = new AimProcessor(baritone.getPlayerContext());
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract) {
        this.target = new Target(rotation, Target.Mode.resolve(ctx, blockInteract), blockInteract);
    }

    @Override
    public IAimProcessor getAimProcessor() {
        return this.processor;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN) {
            this.processor.tick();
            // If baritone had no target last tick, stop overriding player rotation
            if (!hadTargetThisTick && smoothActive) {
                smoothActive = false;
            }
            hadTargetThisTick = false;
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {

        if (this.target == null) {
            return;
        }

        switch (event.getState()) {
            case PRE: {
                if (this.target.mode == Target.Mode.NONE) {
                    // Just return for PRE, we still want to set target to null on POST
                    return;
                }

                this.prevRotation = new Rotation(ctx.player().getYaw(), ctx.player().getPitch());
                final Rotation actual = this.processor.peekRotation(this.target.rotation);
                ctx.player().setYaw(actual.getYaw());
                ctx.player().setPitch(actual.getPitch());

                // Update render-frame smooth target
                this.hadTargetThisTick = true;
                this.renderTargetYaw = this.target.rotation.getYaw();
                this.renderTargetPitch = this.target.rotation.getPitch();
                if (!this.smoothActive) {
                    // First frame: snap to target
                    this.smoothYaw = this.renderTargetYaw;
                    this.smoothPitch = this.renderTargetPitch;
                    this.lastSmoothNanos = System.nanoTime();
                    this.smoothActive = true;
                    // Reset WindMouse physics so we don't carry stale velocity
                    this.wmVeloYaw = 0; this.wmVeloPitch = 0;
                    this.wmWindYaw = 0; this.wmWindPitch = 0;
                } else if (this.target.blockInteract) {
                    // Block interaction needs objectMouseOver to see the correct face immediately.
                    // getYaw() mixin returns smoothYaw, which lags behind actual — snap it so
                    // the raycast hits the right face on this tick, not several ticks later.
                    this.smoothYaw = actual.getYaw();
                    this.smoothPitch = actual.getPitch();
                    this.wmVeloYaw = 0; this.wmVeloPitch = 0;
                    this.wmWindYaw = 0; this.wmWindPitch = 0;
                }
                break;
            }
            case POST: {
                if (this.prevRotation != null) {
                    if (this.target.mode == Target.Mode.SERVER) {
                        // freeLook: restore original yaw so player's visual doesn't snap.
                        // Render-frame mixin handles smooth display via getSmoothedYaw.
                        ctx.player().setYaw(this.prevRotation.getYaw());
                        ctx.player().setPitch(this.prevRotation.getPitch());
                    }
                    // CLIENT mode: PRE already set yaw to peekRotation for packets.
                    // Render-frame mixin overrides getYaw(tickDelta) with smooth value.
                    this.prevRotation = null;
                }
                this.target = null;
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onSendPacket(PacketEvent event) {
        if (!(event.getPacket() instanceof PlayerMoveC2SPacket)) {
            return;
        }

        final PlayerMoveC2SPacket packet = (PlayerMoveC2SPacket) event.getPacket();
        if (packet instanceof PlayerMoveC2SPacket.LookAndOnGround || packet instanceof PlayerMoveC2SPacket.Full) {
            this.serverRotation = new Rotation(packet.getYaw(0.0f), packet.getPitch(0.0f));
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        this.serverRotation = null;
        this.target = null;
        this.smoothActive = false;
        this.lastSmoothNanos = 0;
        this.wmVeloYaw = 0; this.wmVeloPitch = 0;
        this.wmWindYaw = 0; this.wmWindPitch = 0;
    }

    /**
     * Called every render frame from mixin. Returns smoothly interpolated yaw.
     */
    public float getSmoothedYaw(float defaultYaw) {
        if (!smoothActive) {
            return defaultYaw;
        }
        updateSmoothRotation();
        return smoothYaw;
    }

    /**
     * Called every render frame from mixin. Returns smoothly interpolated pitch.
     */
    public float getSmoothedPitch(float defaultPitch) {
        if (!smoothActive) {
            return defaultPitch;
        }
        return smoothPitch;
    }

    private static final double WM_SQRT3 = Math.sqrt(3.0);
    private static final double WM_SQRT5 = Math.sqrt(5.0);
    private static final double WM_DONE_THRESHOLD = 0.3;
    private static final double WM_WIND_DIST = 20.0;

    private void updateSmoothRotation() {
        long now = System.nanoTime();
        if (lastSmoothNanos == 0) {
            lastSmoothNanos = now;
            return;
        }
        float dtSeconds = (now - lastSmoothNanos) / 1_000_000_000f;
        lastSmoothNanos = now;
        dtSeconds = Math.min(dtSeconds, 0.1f);

        if (Baritone.settings().windMouseLook.value) {
            updateWindMouse(dtSeconds);
        } else {
            updateExpDecay(dtSeconds);
        }
    }

    private void updateWindMouse(float dt) {
        double dYaw = MathHelper.wrapDegrees(renderTargetYaw - smoothYaw);
        double dPitch = (double) renderTargetPitch - smoothPitch;
        double dist = Math.sqrt(dYaw * dYaw + dPitch * dPitch);

        if (dist < WM_DONE_THRESHOLD) {
            smoothYaw = renderTargetYaw;
            smoothPitch = renderTargetPitch;
            wmVeloYaw = 0; wmVeloPitch = 0;
            wmWindYaw = 0; wmWindPitch = 0;
            return;
        }

        // smoothLookTicks controls overall speed: 5 = baseline, higher = slower, lower = faster
        int ticks = Math.max(Baritone.settings().smoothLookTicks.value, 1);
        double speedFactor = 5.0 / ticks; // ticks=5 → 1.0, ticks=10 → 0.5, ticks=3 → 1.67

        // Scale physics by frame time relative to 60 FPS baseline
        double frameScale = dt * 60.0 * speedFactor;

        double gravity = Baritone.settings().windMouseGravity.value;
        double windMag = Math.min(Baritone.settings().windMouseWind.value, dist);
        double maxStep = Baritone.settings().windMouseMaxStep.value;

        // Wind: random perturbation, decays when close to target
        if (dist >= WM_WIND_DIST) {
            wmWindYaw   = wmWindYaw   / WM_SQRT3 + (wmRandom.nextDouble() * 2.0 - 1.0) * windMag / WM_SQRT5;
            wmWindPitch = wmWindPitch / WM_SQRT3 + (wmRandom.nextDouble() * 2.0 - 1.0) * windMag / WM_SQRT5;
        } else {
            wmWindYaw   /= WM_SQRT3;
            wmWindPitch /= WM_SQRT3;
        }

        // Accumulate velocity: wind + gravity pull toward target
        wmVeloYaw   += (wmWindYaw   + gravity * dYaw   / dist) * frameScale;
        wmVeloPitch += (wmWindPitch + gravity * dPitch / dist) * frameScale;

        // Velocity damping — prevents snappy convergence, makes movement more human
        double damping = Math.pow(0.85, frameScale);
        wmVeloYaw   *= damping;
        wmVeloPitch *= damping;

        // Clamp velocity magnitude; scale max step with distance (human-like fast flick for large angles)
        double veloMag = Math.sqrt(wmVeloYaw * wmVeloYaw + wmVeloPitch * wmVeloPitch);
        double effectiveMaxStep = maxStep * Math.max(1.0, Math.min(4.0, dist / 15.0)) * frameScale;
        if (veloMag > effectiveMaxStep) {
            double scale = effectiveMaxStep * (0.5 + wmRandom.nextDouble() * 0.5) / veloMag;
            wmVeloYaw   *= scale;
            wmVeloPitch *= scale;
        }

        smoothYaw  += (float) wmVeloYaw;
        smoothPitch = (float) Math.max(-90.0, Math.min(90.0, smoothPitch + wmVeloPitch));
    }

    private void updateExpDecay(float dtSeconds) {
        float tickSpeed = 2.0f / Math.max(Baritone.settings().smoothLookTicks.value, 1);
        tickSpeed = Math.min(tickSpeed, 1.0f);
        float frameSpeed = 1.0f - (float) Math.pow(1.0f - tickSpeed, dtSeconds * 20.0f);

        float deltaYaw = MathHelper.wrapDegrees(renderTargetYaw - smoothYaw);
        smoothYaw += deltaYaw * frameSpeed;
        float deltaPitch = renderTargetPitch - smoothPitch;
        smoothPitch += deltaPitch * frameSpeed;
    }

    public void pig() {
        if (this.target != null) {
            final Rotation actual = this.processor.peekRotation(this.target.rotation);
            ctx.player().setYaw(actual.getYaw());
        }
    }

    public Optional<Rotation> getEffectiveRotation() {
        if (Baritone.settings().freeLook.value) {
            return Optional.ofNullable(this.serverRotation);
        }
        // If freeLook isn't on, just defer to the player's actual rotations
        return Optional.empty();
    }

    @Override
    public void onPlayerRotationMove(RotationMoveEvent event) {
        if (this.target != null) {
            final Rotation actual = this.processor.peekRotation(this.target.rotation);
            event.setYaw(actual.getYaw());
            event.setPitch(actual.getPitch());
        }
    }

    private static final class AimProcessor extends AbstractAimProcessor {

        public AimProcessor(final IPlayerContext ctx) {
            super(ctx);
        }

        @Override
        protected Rotation getPrevRotation() {
            // Implementation will use LookBehavior.serverRotation
            return ctx.playerRotations();
        }
    }

    private static abstract class AbstractAimProcessor implements ITickableAimProcessor {

        protected final IPlayerContext ctx;
        private final ForkableRandom rand;
        private double randomYawOffset;
        private double randomPitchOffset;

        public AbstractAimProcessor(IPlayerContext ctx) {
            this.ctx = ctx;
            this.rand = new ForkableRandom();
        }

        private AbstractAimProcessor(final AbstractAimProcessor source) {
            this.ctx = source.ctx;
            this.rand = source.rand.fork();
            this.randomYawOffset = source.randomYawOffset;
            this.randomPitchOffset = source.randomPitchOffset;
        }

        @Override
        public final Rotation peekRotation(final Rotation rotation) {
            final Rotation prev = this.getPrevRotation();

            float desiredYaw = rotation.getYaw();
            float desiredPitch = rotation.getPitch();

            // In other words, the target doesn't care about the pitch, so it used playerRotations().getPitch()
            // and it's safe to adjust it to a normal level
            if (desiredPitch == prev.getPitch()) {
                desiredPitch = nudgeToLevel(desiredPitch);
            }

            desiredYaw += this.randomYawOffset;
            desiredPitch += this.randomPitchOffset;

            return new Rotation(
                    this.calculateMouseMove(prev.getYaw(), desiredYaw),
                    this.calculateMouseMove(prev.getPitch(), desiredPitch)
            ).clamp();
        }

        @Override
        public final void tick() {
            // randomLooking
            this.randomYawOffset = (this.rand.nextDouble() - 0.5) * Baritone.settings().randomLooking.value;
            this.randomPitchOffset = (this.rand.nextDouble() - 0.5) * Baritone.settings().randomLooking.value;

            // randomLooking113
            double random = this.rand.nextDouble() - 0.5;
            if (Math.abs(random) < 0.1) {
                random *= 4;
            }
            this.randomYawOffset += random * Baritone.settings().randomLooking113.value;
        }

        @Override
        public final void advance(int ticks) {
            for (int i = 0; i < ticks; i++) {
                this.tick();
            }
        }

        @Override
        public Rotation nextRotation(final Rotation rotation) {
            final Rotation actual = this.peekRotation(rotation);
            this.tick();
            return actual;
        }

        @Override
        public final ITickableAimProcessor fork() {
            return new AbstractAimProcessor(this) {

                private Rotation prev = AbstractAimProcessor.this.getPrevRotation();

                @Override
                public Rotation nextRotation(final Rotation rotation) {
                    return (this.prev = super.nextRotation(rotation));
                }

                @Override
                protected Rotation getPrevRotation() {
                    return this.prev;
                }
            };
        }

        protected abstract Rotation getPrevRotation();

        /**
         * Nudges the player's pitch to a regular level. (Between {@code -20} and {@code 10}, increments are by {@code 1})
         */
        private float nudgeToLevel(float pitch) {
            if (pitch < -20) {
                return pitch + 1;
            } else if (pitch > 10) {
                return pitch - 1;
            }
            return pitch;
        }

        private float calculateMouseMove(float current, float target) {
            final float delta = target - current;
            final double deltaPx = angleToMouse(delta); // yes, even the mouse movements use double
            return current + mouseToAngle(deltaPx);
        }

        private double angleToMouse(float angleDelta) {
            final float minAngleChange = mouseToAngle(1);
            return Math.round(angleDelta / minAngleChange);
        }

        private float mouseToAngle(double mouseDelta) {
            // casting float literals to double gets us the precise values used by mc
            final double f = ctx.minecraft().options.getMouseSensitivity().getValue() * (double) 0.6f + (double) 0.2f;
            return (float) (mouseDelta * f * f * f * 8.0d) * 0.15f; // yes, one double and one float scaling factor
        }
    }

    private static class Target {

        public final Rotation rotation;
        public final Mode mode;
        public final boolean blockInteract;

        public Target(Rotation rotation, Mode mode, boolean blockInteract) {
            this.rotation = rotation;
            this.mode = mode;
            this.blockInteract = blockInteract;
        }

        enum Mode {
            /**
             * Rotation will be set client-side and is visual to the player
             */
            CLIENT,

            /**
             * Rotation will be set server-side and is silent to the player
             */
            SERVER,

            /**
             * Rotation will remain unaffected on both the client and server
             */
            NONE;

            static Mode resolve(IPlayerContext ctx, boolean blockInteract) {
                final Settings settings = Baritone.settings();
                final boolean antiCheat = settings.antiCheatCompatibility.value;
                final boolean blockFreeLook = settings.blockFreeLook.value;

                if (ctx.player().isFallFlying()) {
                    // always need to set angles while flying
                    return settings.elytraFreeLook.value ? SERVER : CLIENT;
                } else if (settings.freeLook.value) {
                    // Regardless of if antiCheatCompatibility is enabled, if a blockInteract is requested then the player
                    // rotation needs to be set somehow, otherwise Baritone will halt since objectMouseOver() will just be
                    // whatever the player is mousing over visually. Let's just settle for setting it silently.
                    if (blockInteract) {
                        return blockFreeLook ? SERVER : CLIENT;
                    }
                    return antiCheat ? SERVER : NONE;
                }

                // all freeLook settings are disabled so set the angles
                return CLIENT;
            }
        }
    }
}
