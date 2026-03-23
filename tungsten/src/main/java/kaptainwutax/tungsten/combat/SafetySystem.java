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
 * Velocity analysis + edge detection + anti-fall braking + knockback prediction.
 *
 * Two-phase architecture:
 *   tick()         — game tick (20 TPS): analyze, decide braking, log
 *   renderUpdate() — render frame (~60 FPS): smooth interpolated visualization
 *
 * Movement keys are tick-based (MC physics is 20 TPS).
 * Rotation is continuous via WindMouse (render frequency).
 * Visualization is continuous for smooth display.
 */
public class SafetySystem {

    private static final Color COL_PLAYER_VEL     = new Color(50, 220, 50);
    private static final Color COL_ENEMY_VEL      = new Color(220, 50, 50);
    private static final Color COL_DANGER          = new Color(255, 160, 0);
    private static final Color COL_VOID            = new Color(255, 0, 0);
    private static final Color COL_SAFE            = new Color(50, 200, 100);
    private static final Color COL_KB_DANGER       = new Color(255, 80, 200);
    private static final Color COL_KB_OPPORTUNITY  = new Color(0, 255, 255);

    private static final int PREDICT_TICKS = 10;
    private static final int FALL_WARN = 2;
    private static final int FALL_DANGER = 5;
    private static final double STRONG_VEL = 0.4;

    // vanilla knockback
    private static final double KB_BASE = 0.4;
    private static final double KB_SPRINT_BONUS = 0.4;
    private static final double KB_UP = 0.4;
    private static final int KB_PREDICT_TICKS = 15;
    private static final int KB_FALL_THRESHOLD = 2;

    // ── tick state (updated at 20 TPS) ──────────────────────────────────────
    private Vec3d prevEnemyPos = null;
    private Vec3d enemyVelocity = Vec3d.ZERO;

    // snapshot from last tick — used by renderUpdate for interpolation
    private Vec3d lastPlayerPos = Vec3d.ZERO;
    private Vec3d lastPlayerVel = Vec3d.ZERO;
    private Vec3d lastTargetPos = Vec3d.ZERO;
    private Vec3d lastEnemyVel = Vec3d.ZERO;
    private int lastFallAtPredicted = 0;
    // KB analysis results
    private Vec3d lastUsAfterKB = null;
    private int lastFallIfHit = 0;
    private Vec3d lastEnemyAfterKB = null;
    private int lastEnemyFallIfHit = 0;

    // output keys
    private boolean wantsForward = false;
    private boolean wantsBack = false;
    private boolean wantsLeft = false;
    private boolean wantsRight = false;
    private boolean wantsSprint = false;
    private boolean wantsSneak = false;
    private float brakeYaw = 0;
    private boolean braking = false;
    private boolean wasBraking = false;

    private boolean active = false;

    // ── tick (20 TPS): analysis + decisions ──────────────────────────────────

    public void tick(ClientPlayerEntity player, Entity target, WorldView world) {
        wasBraking = braking;
        resetOutputs();
        active = true;

        Vec3d playerPos = player.getPos();
        Vec3d playerVel = player.getVelocity();
        Vec3d targetPos = target.getPos();

        // enemy velocity tracking
        if (prevEnemyPos != null) {
            enemyVelocity = targetPos.subtract(prevEnemyPos);
        }
        prevEnemyPos = targetPos;

        Vec3d playerPredicted = playerPos.add(playerVel.multiply(PREDICT_TICKS));
        int fallAtPredicted = VoidDetector.fallHeight(playerPredicted, world);
        int fallAtCurrent = VoidDetector.fallHeight(playerPos, world);

        // save snapshot for render interpolation
        lastPlayerPos = playerPos;
        lastPlayerVel = playerVel;
        lastTargetPos = targetPos;
        lastEnemyVel = enemyVelocity;
        lastFallAtPredicted = fallAtPredicted;

        // ── knockback analysis ──
        analyzeKnockback(playerPos, playerVel, targetPos, world);

        // ── anti-fall braking ──
        double horizSpeed = Math.sqrt(playerVel.x * playerVel.x + playerVel.z * playerVel.z);

        if (fallAtPredicted >= FALL_WARN && horizSpeed > 0.01) {
            braking = true;
            float velYaw = (float) Math.toDegrees(-Math.atan2(playerVel.x, playerVel.z));
            brakeYaw = velYaw + 180f;

            if (horizSpeed > STRONG_VEL || fallAtPredicted >= FALL_DANGER) {
                wantsForward = true;
                wantsSprint = true;
                Debug.logMessage("SAFETY: HARD BRAKE (fall=" + fallAtPredicted
                        + " vel=" + String.format("%.2f", horizSpeed) + ")");
            } else {
                wantsSneak = true;
                wantsForward = true;
                Debug.logMessage("SAFETY: soft brake (fall=" + fallAtPredicted
                        + " vel=" + String.format("%.2f", horizSpeed) + ")");
            }
        } else if (fallAtCurrent >= FALL_DANGER && !player.isOnGround()
                && playerVel.y < -0.1 && horizSpeed > 0.01) {
            braking = true;
            float velYaw = (float) Math.toDegrees(-Math.atan2(playerVel.x, playerVel.z));
            brakeYaw = velYaw + 180f;
            wantsForward = true;
            wantsSprint = true;
            Debug.logMessage("SAFETY: recovery (falling, fall=" + fallAtCurrent + ")");
        }
    }

    // ── render update (every frame): smooth visualization ────────────────────

    /**
     * Called every render frame from MixinInGameHud.
     * Interpolates positions using tickDelta for smooth display.
     */
    public void renderUpdate(float tickDelta) {
        if (!active) return;

        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();

        // interpolate current positions between last tick and next
        Vec3d playerPos = lastPlayerPos.add(lastPlayerVel.multiply(tickDelta));
        Vec3d targetPos = lastTargetPos.add(lastEnemyVel.multiply(tickDelta));
        Vec3d playerPredicted = playerPos.add(lastPlayerVel.multiply(PREDICT_TICKS));
        Vec3d enemyPredicted = targetPos.add(lastEnemyVel.multiply(PREDICT_TICKS));

        // velocity vectors + predicted positions
        renderVelocity(playerPos, lastPlayerVel, playerPredicted, COL_PLAYER_VEL);
        renderVelocity(targetPos, lastEnemyVel, enemyPredicted, COL_ENEMY_VEL);

        // fall danger marker
        if (lastFallAtPredicted >= FALL_WARN) {
            Color dangerCol = lastFallAtPredicted >= FALL_DANGER ? COL_VOID : COL_DANGER;
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    playerPredicted.subtract(0.4, 0, 0.4), new Vec3d(0.8, 0.1, 0.8), dangerCol));
        } else {
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    playerPredicted.subtract(0.2, 0, 0.2), new Vec3d(0.4, 0.1, 0.4), COL_SAFE));
        }

        // KB danger visualization (defensive)
        if (lastUsAfterKB != null && lastFallIfHit >= KB_FALL_THRESHOLD) {
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(
                    playerPos.add(0, 1, 0), lastUsAfterKB.add(0, 1, 0), COL_KB_DANGER));
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    lastUsAfterKB.subtract(0.3, 0, 0.3), new Vec3d(0.6, 0.1, 0.6), COL_KB_DANGER));
        }

        // KB opportunity visualization (offensive)
        if (lastEnemyAfterKB != null && lastEnemyFallIfHit >= KB_FALL_THRESHOLD) {
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(
                    targetPos.add(0, 1, 0), lastEnemyAfterKB.add(0, 1, 0), COL_KB_OPPORTUNITY));
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    lastEnemyAfterKB.subtract(0.3, 0, 0.3), new Vec3d(0.6, 0.1, 0.6), COL_KB_OPPORTUNITY));
        }
    }

    // ── knockback simulation ─────────────────────────────────────────────────

    private Vec3d simulateKnockback(Vec3d victimPos, Vec3d victimVel,
                                     Vec3d attackerPos, boolean sprintHit) {
        double dx = victimPos.x - attackerPos.x;
        double dz = victimPos.z - attackerPos.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return victimPos;

        double nx = dx / len;
        double nz = dz / len;
        double kbStrength = KB_BASE + (sprintHit ? KB_SPRINT_BONUS : 0);

        double vx = victimVel.x * 0.5 + nx * kbStrength;
        double vy = KB_UP;
        double vz = victimVel.z * 0.5 + nz * kbStrength;

        double px = victimPos.x, py = victimPos.y, pz = victimPos.z;
        for (int t = 0; t < KB_PREDICT_TICKS; t++) {
            px += vx; py += vy; pz += vz;
            vx *= 0.91;
            vy = (vy - 0.08) * 0.98;
            vz *= 0.91;
        }
        return new Vec3d(px, py, pz);
    }

    private void analyzeKnockback(Vec3d playerPos, Vec3d playerVel,
                                   Vec3d targetPos, WorldView world) {
        // DEFENSIVE: enemy sprint-hits us
        lastUsAfterKB = simulateKnockback(playerPos, playerVel, targetPos, true);
        lastFallIfHit = VoidDetector.fallHeight(lastUsAfterKB, world);
        if (lastFallIfHit >= KB_FALL_THRESHOLD) {
            Debug.logMessage("§cSAFETY: incoming hit → fall " + lastFallIfHit + " blocks!");
        }

        // OFFENSIVE: we sprint-hit enemy
        lastEnemyAfterKB = simulateKnockback(targetPos, enemyVelocity, playerPos, true);
        lastEnemyFallIfHit = VoidDetector.fallHeight(lastEnemyAfterKB, world);
        if (lastEnemyFallIfHit >= KB_FALL_THRESHOLD) {
            Debug.logMessage("§bSAFETY: hit now → enemy falls " + lastEnemyFallIfHit + " blocks!");
        }
    }

    // ── render helpers ───────────────────────────────────────────────────────

    private void renderVelocity(Vec3d pos, Vec3d vel, Vec3d predicted, Color col) {
        Vec3d velEnd = pos.add(vel.multiply(5));
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(
                pos.add(0, 0.5, 0), velEnd.add(0, 0.5, 0), col));
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

    // ── getters ──────────────────────────────────────────────────────────────

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
        active = false;
        resetOutputs();
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
    }
}
