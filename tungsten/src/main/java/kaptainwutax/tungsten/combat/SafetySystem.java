package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import kaptainwutax.tungsten.render.Line;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Velocity visualization + edge/fall detection + anti-fall braking.
 *
 * Renders:
 *   - Player velocity vector (green) + predicted pos (green cube)
 *   - Enemy velocity vector (red) + predicted pos (red cube)
 *   - Fall danger marker (yellow/orange) at predicted pos if drop detected
 *
 * Anti-fall: when predicted position leads to a drop, counter-steer to brake.
 *   - Mild velocity → face away from edge, W+sprint to brake
 *   - Strong velocity → full reverse (face opposite of velocity vector, W+sprint)
 *   - Always: the worse the drop, the more aggressive the braking
 */
public class SafetySystem {

    private static final Color COL_PLAYER_VEL  = new Color(50, 220, 50);
    private static final Color COL_ENEMY_VEL   = new Color(220, 50, 50);
    private static final Color COL_DANGER       = new Color(255, 160, 0);
    private static final Color COL_VOID         = new Color(255, 0, 0);
    private static final Color COL_SAFE         = new Color(50, 200, 100);

    // how many ticks ahead to predict position
    private static final int PREDICT_TICKS = 10;
    // fall height thresholds
    private static final int FALL_WARN = 2;   // start braking
    private static final int FALL_DANGER = 5; // aggressive braking
    // velocity magnitude threshold for "strong" knockback
    private static final double STRONG_VEL = 0.4;

    private Vec3d prevEnemyPos = null;
    private Vec3d enemyVelocity = Vec3d.ZERO;

    // output: what keys the safety system wants pressed
    private boolean wantsForward = false;
    private boolean wantsBack = false;
    private boolean wantsLeft = false;
    private boolean wantsRight = false;
    private boolean wantsSprint = false;
    private boolean wantsSneak = false;
    private float brakeYaw = 0;
    private boolean braking = false;
    private boolean wasBraking = false; // track previous tick for key release

    /**
     * Tick: analyze velocities, check terrain, decide braking, render.
     * Call before legs/mouse in CombatController.
     */
    public void tick(ClientPlayerEntity player, Entity target, WorldView world) {
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
        wasBraking = braking;
        resetOutputs();

        Vec3d playerPos = player.getPos();
        Vec3d playerVel = player.getVelocity();
        Vec3d targetPos = target.getPos();

        // ── enemy velocity (manual tracking, entities don't expose getVelocity reliably) ──
        if (prevEnemyPos != null) {
            enemyVelocity = targetPos.subtract(prevEnemyPos);
        }
        prevEnemyPos = targetPos;

        // ── predicted positions ──
        Vec3d playerPredicted = playerPos.add(playerVel.multiply(PREDICT_TICKS));
        Vec3d enemyPredicted = targetPos.add(enemyVelocity.multiply(PREDICT_TICKS));

        // ── terrain analysis at predicted position ──
        int fallAtPredicted = VoidDetector.fallHeight(playerPredicted, world);
        int fallAtCurrent = VoidDetector.fallHeight(playerPos, world);

        // ── render: velocity vectors + predicted positions ──
        renderVelocity(playerPos, playerVel, playerPredicted, COL_PLAYER_VEL);
        renderVelocity(targetPos, enemyVelocity, enemyPredicted, COL_ENEMY_VEL);

        // fall danger marker at predicted pos
        if (fallAtPredicted >= FALL_WARN) {
            Color dangerCol = fallAtPredicted >= FALL_DANGER ? COL_VOID : COL_DANGER;
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    playerPredicted.subtract(0.4, 0, 0.4), new Vec3d(0.8, 0.1, 0.8), dangerCol));
        } else {
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    playerPredicted.subtract(0.2, 0, 0.2), new Vec3d(0.4, 0.1, 0.4), COL_SAFE));
        }

        // ── anti-fall braking ──
        double horizSpeed = Math.sqrt(playerVel.x * playerVel.x + playerVel.z * playerVel.z);

        if (fallAtPredicted >= FALL_WARN && horizSpeed > 0.01) {
            braking = true;

            // yaw opposite to velocity vector
            float velYaw = (float) Math.toDegrees(-Math.atan2(playerVel.x, playerVel.z));
            brakeYaw = velYaw + 180f; // face opposite

            if (horizSpeed > STRONG_VEL || fallAtPredicted >= FALL_DANGER) {
                // strong knockback or big drop: full reverse — face opposite, W+sprint
                wantsForward = true;
                wantsSprint = true;
                Debug.logMessage("SAFETY: HARD BRAKE (fall=" + fallAtPredicted
                        + " vel=" + String.format("%.2f", horizSpeed) + ")");
            } else {
                // mild: sneak to slow down + face opposite
                wantsSneak = true;
                wantsForward = true;
                Debug.logMessage("SAFETY: soft brake (fall=" + fallAtPredicted
                        + " vel=" + String.format("%.2f", horizSpeed) + ")");
            }
        } else if (fallAtCurrent >= FALL_DANGER && !player.isOnGround()
                && playerVel.y < -0.1 && horizSpeed > 0.01) {
            // actually falling (not just jumping) — try to move back onto solid ground
            braking = true;
            float velYaw = (float) Math.toDegrees(-Math.atan2(playerVel.x, playerVel.z));
            brakeYaw = velYaw + 180f;
            wantsForward = true;
            wantsSprint = true;
            Debug.logMessage("SAFETY: recovery (falling, fall=" + fallAtCurrent + ")");
        }
    }

    private void renderVelocity(Vec3d pos, Vec3d vel, Vec3d predicted, Color col) {
        // velocity line (scaled up for visibility)
        Vec3d velEnd = pos.add(vel.multiply(5));
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(
                pos.add(0, 0.5, 0), velEnd.add(0, 0.5, 0), col));
        // predicted pos cube
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                predicted.subtract(0.15, 0, 0.15), new Vec3d(0.3, 1.8, 0.3), col));
    }

    private void resetOutputs() {
        wantsForward = false;
        wantsBack = false;
        wantsLeft = false;
        wantsRight = false;
        wantsSprint = false;
        wantsSneak = false;
        braking = false;
    }

    // ── getters for CombatController ─────────────────────────────────────────

    public boolean isBraking()      { return braking; }
    public boolean wasBraking()     { return wasBraking; }
    public float getBrakeYaw()      { return brakeYaw; }
    public boolean wantsForward()   { return wantsForward; }
    public boolean wantsBack()      { return wantsBack; }
    public boolean wantsLeft()      { return wantsLeft; }
    public boolean wantsRight()     { return wantsRight; }
    public boolean wantsSprint()    { return wantsSprint; }
    public boolean wantsSneak()     { return wantsSneak; }

    public void reset() {
        prevEnemyPos = null;
        enemyVelocity = Vec3d.ZERO;
        resetOutputs();
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
    }
}
