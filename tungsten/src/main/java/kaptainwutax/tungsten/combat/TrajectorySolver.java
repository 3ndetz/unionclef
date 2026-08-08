package kaptainwutax.tungsten.combat;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Arrow ballistics on vanilla physics: per tick pos += vel; vel *= 0.99;
 * vel.y -= 0.05. There is no closed form with drag, so pitch is solved by
 * bisection over simulated flights, and target lead is fixed-point iterated
 * (flight time -> predicted target -> new flight time, 3 rounds).
 *
 * Pure math — no client state. The combat-primitives API exposes this to
 * altoclef's bow logic (which keeps weapon selection and the decision WHEN
 * to shoot).
 */
public final class TrajectorySolver {

    /** Full-charge arrow speed (blocks/tick). Charge scales it down. */
    public static final double FULL_CHARGE_SPEED = 3.0;
    private static final double GRAVITY = 0.05;
    private static final double DRAG = 0.99;
    private static final int MAX_FLIGHT_TICKS = 120;

    public static final class Solution {
        public final float yaw;
        public final float pitch;
        public final int flightTicks;
        /** Where the TARGET is expected to be at impact — the point to draw a marker on. */
        public final Vec3d predictedTarget;
        /** The VIRTUAL point the barrel points at: {@link #predictedTarget} shifted back by the
         *  shooter's inherited velocity. Not a place anything will ever be — see {@link #solve}. */
        public final Vec3d aimPoint;

        Solution(float yaw, float pitch, int flightTicks, Vec3d predictedTarget, Vec3d aimPoint) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.flightTicks = flightTicks;
            this.predictedTarget = predictedTarget;
            this.aimPoint = aimPoint;
        }
    }

    /**
     * Solve for a moving entity: linear lead from its current velocity, clamped to the ground
     * plane for grounded targets.
     *
     * <p>Takes the SHOOTER as an entity rather than a bare eye position because the shot depends on
     * the shooter's own movement, and that term is too easy to forget — it was missing here for the
     * whole life of this class. See {@link #solve(Vec3d, Vec3d, Vec3d, Vec3d, double)}.
     */
    public static Solution solve(Entity shooter, Entity target, double charge) {
        Vec3d aimPoint = target.getEntityPos().add(0, target.getHeight() * 0.6, 0);
        Vec3d vel = target.getVelocity();
        if (target.isOnGround()) vel = new Vec3d(vel.x, 0, vel.z);
        return solve(shooter.getEyePos(), shooterVelocity(shooter), aimPoint, vel, charge);
    }

    /**
     * The velocity vanilla will ADD to an arrow this shooter looses, matching
     * {@code ProjectileEntity.setVelocity(Entity, ...)}: horizontal always, vertical only while
     * airborne ({@code shooter.isOnGround() ? 0.0 : movement.y}).
     */
    public static Vec3d shooterVelocity(Entity shooter) {
        Vec3d mov = shooter.getMovement();
        return shooter.isOnGround() ? new Vec3d(mov.x, 0, mov.z) : mov;
    }

    /**
     * How far AHEAD of the flight time the lead has to reach, in ticks.
     *
     * <p>Two lags stack, and both point the same way, so the arrow always passes BEHIND a moving
     * target:
     *
     * <ul>
     *   <li>the position we solve against is the CLIENT-INTERPOLATED one. A remote player moves by
     *       position packets with a 3-step lerp restarted every tick, whose steady-state lag is
     *       three ticks of velocity — the same model this repo already documents where it explains
     *       why {@code getVelocity()} reads ~0 for a walking remote player;</li>
     *   <li>the release is one to two ticks later than the solve: the solve runs at the head of
     *       the client tick, and dropping the use key is only acted on by {@code
     *       handleInputEvents()} on the NEXT tick.</li>
     * </ul>
     *
     * <p>Measured cost of ignoring it, on a 25-block lane against a sprinting target (the effective
     * lateral window is about ±0.6 blocks including vanilla's hit margin): 0 ticks of lag puts the
     * arrow 0.38 behind, three ticks puts it 1.30 behind. Leading by {@code flight + 4} puts it
     * +0.08 — a centre hit — and stays a hit at lag 2, 3 and 4 alike, which is what makes a fixed
     * term defensible rather than a tuning knob.
     */
    private static final double LATENCY_TICKS = 4.0;

    /**
     * Solve for a target point moving with a constant velocity (may be zero), fired by a shooter
     * itself moving at {@code shooterVel} (may be zero).
     *
     * <h3>Why the shooter's own velocity belongs in a BALLISTICS solver</h3>
     *
     * Vanilla's {@code ProjectileEntity.setVelocity(Entity shooter, ...)} does not launch the arrow
     * at the aim direction alone — it ADDS the shooter's movement:
     *
     * <pre>this.setVelocity(this.getVelocity().add(mov.x, shooter.isOnGround() ? 0.0 : mov.y, mov.z))</pre>
     *
     * (read off the 1.21.11 bytecode, and {@code BowItem.shoot} reaches exactly that overload).
     *
     * <h3>WHICH motion this actually costs — measured, because the obvious answer is wrong</h3>
     *
     * The tempting story is "the bot sprints away, so the arrow leaves slower and lands short".
     * That story is worth about 0.14 blocks and is NOT why shots miss. Velocity pointing along the
     * shot is nearly COLLINEAR with the aim: it changes the flight TIME, not the line, and all that
     * survives is a little extra gravity drop.
     *
     * <p>The whole cost lives in the component ACROSS the shot. Simulated against vanilla ballistics
     * with a swept-segment hit test and a ±0.6-block window (stationary target, uncorrected solver):
     *
     * <pre>
     *   shooter strafing sideways : worst miss 2.33 blocks  (12 of 20 range/speed cases MISS)
     *   shooter fleeing backwards : worst miss 0.14 blocks  (every case still a hit)
     * </pre>
     *
     * With the correction below, the strafing cases come back to ≤0.25 blocks — all hits. This
     * matters here because the bow shoots while KITING: the camera locks onto the target for the
     * last few ticks of the draw while the body keeps travelling its escape path, so the shooter's
     * velocity is exactly the across-the-shot kind.
     *
     * <h3>Why one subtraction is the exact answer and not an approximation</h3>
     *
     * The flight is LINEAR in the initial velocity: with {@code v(k+1) = v(k)*DRAG - g*ŷ} and
     * {@code pos += v} each tick,
     *
     * <pre>pos(t) = pos0 + A(t)*vInit - B(t)*ŷ,    A(t) = sum(DRAG^k, k &lt; t)</pre>
     *
     * and {@code vInit = aimDir*v0 + shooterVel}. Both parts are multiplied by the SAME {@code A(t)},
     * so aiming from a moving shooter at a point P is identical to aiming from a standing shooter at
     * {@code P - A(t)*shooterVel}. No extra simulation and no second root-find: the shift drops
     * straight into the flight-time fixed point that the target lead already iterates.
     *
     * @param shooterVel what vanilla will add to the arrow — use {@link #shooterVelocity} rather
     *                   than a raw velocity, so the on-ground vertical rule is not lost.
     */
    public static Solution solve(Vec3d shooterEye, Vec3d shooterVel,
                                 Vec3d targetPos, Vec3d targetVel, double charge) {
        double v0 = Math.max(0.1, charge) * FULL_CHARGE_SPEED;
        Vec3d predicted = targetPos;
        Vec3d aim = targetPos;
        double flight = 0;

        for (int round = 0; round < 3; round++) {
            predicted = targetPos.add(targetVel.multiply(flight + LATENCY_TICKS));
            aim = predicted.subtract(shooterVel.multiply(dragSum(flight)));
            double dx = Math.hypot(aim.x - shooterEye.x, aim.z - shooterEye.z);
            double dy = aim.y - shooterEye.y;
            double[] sol = solvePitch(v0, dx, dy);
            if (sol == null) return null;
            flight = sol[1];
            if (round == 2) {
                float yaw = (float) Math.toDegrees(-Math.atan2(
                        aim.x - shooterEye.x, aim.z - shooterEye.z));
                return new Solution(yaw, (float) sol[0], (int) Math.ceil(flight), predicted, aim);
            }
        }
        return null;
    }

    /** {@code A(t) = sum(DRAG^k, k &lt; t)} — the multiplier vanilla applies to the WHOLE initial
     *  velocity over a flight of {@code ticks}, which is what makes the shooter-velocity shift exact. */
    private static double dragSum(double ticks) {
        if (ticks <= 0) return 0;
        return (1 - Math.pow(DRAG, ticks)) / (1 - DRAG);
    }

    /** Bisection for the FLAT (low) trajectory: [pitch, flightTicks] or null.
     *  Convention: negative pitch aims up (Minecraft). */
    private static double[] solvePitch(double v0, double dx, double dy) {
        if (dx < 0.5) {
            // (nearly) straight up/down — direct angle
            double pitch = Math.toDegrees(-Math.atan2(dy, dx));
            return new double[]{MathHelper.clamp(pitch, -89, 89), Math.abs(dy) / Math.max(0.5, v0)};
        }
        // heightAt grows monotonically as aim rises through the flat branch
        double lo = -89, hi = 89, bestPitch = Double.NaN, bestTicks = 0;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            double[] res = heightAt(v0, mid, dx);
            if (res == null) {
                // fell short of dx — must aim higher (more negative pitch)
                hi = mid;
                continue;
            }
            double miss = res[0] - dy;
            if (Math.abs(miss) < 0.05) {
                bestPitch = mid;
                bestTicks = res[1];
                break;
            }
            if (miss > 0) lo = mid;   // hit above the target — aim lower
            else hi = mid;            // below — aim higher
            bestPitch = mid;
            bestTicks = res[1];
        }
        if (Double.isNaN(bestPitch)) return null;
        double[] check = heightAt(v0, bestPitch, dx);
        if (check == null || Math.abs(check[0] - dy) > 0.75) return null; // out of range
        return new double[]{bestPitch, bestTicks};
    }

    /** Simulate a flight: [heightAtDx, ticks] when horizontal travel reaches dx,
     *  null if the arrow never gets there (out of range). */
    private static double[] heightAt(double v0, double pitchDeg, double dx) {
        double rad = Math.toRadians(pitchDeg);
        double vh = v0 * Math.cos(rad);
        double vy = -v0 * Math.sin(rad);
        double x = 0, y = 0;
        for (int t = 1; t <= MAX_FLIGHT_TICKS; t++) {
            double prevX = x, prevY = y;
            x += vh;
            y += vy;
            vh *= DRAG;
            vy = vy * DRAG - GRAVITY;
            if (x >= dx) {
                double f = (dx - prevX) / Math.max(1e-6, x - prevX);
                return new double[]{prevY + (y - prevY) * f, t};
            }
        }
        return null;
    }
}
