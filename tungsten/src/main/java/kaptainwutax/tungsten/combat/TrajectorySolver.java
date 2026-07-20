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
        public final Vec3d predictedTarget;

        Solution(float yaw, float pitch, int flightTicks, Vec3d predictedTarget) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.flightTicks = flightTicks;
            this.predictedTarget = predictedTarget;
        }
    }

    /** Solve for a moving entity: linear lead from its current velocity,
     *  clamped to the ground plane for grounded targets. */
    public static Solution solve(Vec3d shooterEye, Entity target, double charge) {
        Vec3d aimPoint = target.getEntityPos().add(0, target.getHeight() * 0.6, 0);
        Vec3d vel = target.getVelocity();
        if (target.isOnGround()) vel = new Vec3d(vel.x, 0, vel.z);
        return solve(shooterEye, aimPoint, vel, charge);
    }

    /** Solve for a target point moving with a constant velocity (may be zero). */
    public static Solution solve(Vec3d shooterEye, Vec3d targetPos, Vec3d targetVel, double charge) {
        double v0 = Math.max(0.1, charge) * FULL_CHARGE_SPEED;
        Vec3d predicted = targetPos;
        double flight = 0;

        for (int round = 0; round < 3; round++) {
            predicted = targetPos.add(targetVel.multiply(flight));
            double dx = Math.hypot(predicted.x - shooterEye.x, predicted.z - shooterEye.z);
            double dy = predicted.y - shooterEye.y;
            double[] sol = solvePitch(v0, dx, dy);
            if (sol == null) return null;
            flight = sol[1];
            if (round == 2) {
                float yaw = (float) Math.toDegrees(-Math.atan2(
                        predicted.x - shooterEye.x, predicted.z - shooterEye.z));
                return new Solution(yaw, (float) sol[0], (int) Math.ceil(flight), predicted);
            }
        }
        return null;
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
