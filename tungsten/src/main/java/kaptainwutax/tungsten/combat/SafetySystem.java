package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import kaptainwutax.tungsten.render.Line;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Velocity analysis + edge detection + anti-fall braking + knockback prediction.
 *
 * Runs at RENDER FREQUENCY (~60 FPS) — reads live player/entity state each frame.
 * Client physics processes inputs continuously between ticks, so reacting at
 * render rate gives smoother braking curves than tick-rate decisions.
 *
 * tick() — called from game tick, only for enemy velocity tracking (needs fixed dt).
 * renderUpdate() — called every frame: full analysis, braking decisions, key outputs, viz.
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

    // enemy velocity — tracked per tick (fixed dt)
    private Vec3d prevEnemyPos = null;
    private Vec3d enemyVelocity = Vec3d.ZERO;

    // target entity reference (set by tick, read by renderUpdate)
    private Entity target = null;

    // KB analysis results (computed per frame)
    private Vec3d lastUsAfterKB = null;
    private int lastFallIfHit = 0;
    private Vec3d lastEnemyAfterKB = null;
    private int lastEnemyFallIfHit = 0;

    // output keys — updated every render frame
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
    // throttle debug logs to once per 20 frames (~1/sec)
    private int logCooldown = 0;

    // ── tick (20 TPS): enemy velocity tracking only ─────────────────────────

    public void tick(ClientPlayerEntity player, Entity target, WorldView world) {
        this.target = target;
        active = true;

        Vec3d targetPos = target.getPos();
        if (prevEnemyPos != null) {
            enemyVelocity = targetPos.subtract(prevEnemyPos);
        }
        prevEnemyPos = targetPos;
    }

    // ── render update (every frame): analysis + decisions + viz ──────────────

    public void renderUpdate(float tickDelta) {
        if (!active) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || target == null || target.isRemoved()) return;

        wasBraking = braking;
        resetOutputs();
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
        if (logCooldown > 0) logCooldown--;

        Vec3d playerPos = player.getPos();
        Vec3d playerVel = player.getVelocity();
        Vec3d targetPos = target.getPos();

        // predicted positions
        Vec3d playerPredicted = playerPos.add(playerVel.multiply(PREDICT_TICKS));
        Vec3d enemyPredicted = targetPos.add(enemyVelocity.multiply(PREDICT_TICKS));

        // terrain check (uses block grid — not affected by sub-tick precision)
        int fallAtPredicted = VoidDetector.fallHeight(playerPredicted, player.getWorld());
        int fallAtCurrent = VoidDetector.fallHeight(playerPos, player.getWorld());

        // ── render: velocity vectors + predicted positions ──
        renderVelocity(playerPos, playerVel, playerPredicted, COL_PLAYER_VEL);
        renderVelocity(targetPos, enemyVelocity, enemyPredicted, COL_ENEMY_VEL);

        // fall danger marker
        if (fallAtPredicted >= FALL_WARN) {
            Color dangerCol = fallAtPredicted >= FALL_DANGER ? COL_VOID : COL_DANGER;
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    playerPredicted.subtract(0.4, 0, 0.4), new Vec3d(0.8, 0.1, 0.8), dangerCol));
        } else {
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    playerPredicted.subtract(0.2, 0, 0.2), new Vec3d(0.4, 0.1, 0.4), COL_SAFE));
        }

        // ── knockback analysis + viz ──
        analyzeKnockback(playerPos, playerVel, targetPos, player.getWorld());

        if (lastUsAfterKB != null && lastFallIfHit >= KB_FALL_THRESHOLD) {
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(
                    playerPos.add(0, 1, 0), lastUsAfterKB.add(0, 1, 0), COL_KB_DANGER));
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    lastUsAfterKB.subtract(0.3, 0, 0.3), new Vec3d(0.6, 0.1, 0.6), COL_KB_DANGER));
        }
        if (lastEnemyAfterKB != null && lastEnemyFallIfHit >= KB_FALL_THRESHOLD) {
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(
                    targetPos.add(0, 1, 0), lastEnemyAfterKB.add(0, 1, 0), COL_KB_OPPORTUNITY));
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    lastEnemyAfterKB.subtract(0.3, 0, 0.3), new Vec3d(0.6, 0.1, 0.6), COL_KB_OPPORTUNITY));
        }

        // ── anti-fall braking decisions ──
        double horizSpeed = Math.sqrt(playerVel.x * playerVel.x + playerVel.z * playerVel.z);

        if (fallAtPredicted >= FALL_WARN && horizSpeed > 0.01) {
            braking = true;
            float velYaw = (float) Math.toDegrees(-Math.atan2(playerVel.x, playerVel.z));
            brakeYaw = velYaw + 180f;

            if (horizSpeed > STRONG_VEL || fallAtPredicted >= FALL_DANGER) {
                wantsForward = true;
                wantsSprint = true;
                if (logCooldown <= 0) {
                    Debug.logMessage("SAFETY: HARD BRAKE (fall=" + fallAtPredicted
                            + " vel=" + String.format("%.2f", horizSpeed) + ")");
                    logCooldown = 120;
                }
            } else {
                wantsSneak = true;
                wantsForward = true;
                if (logCooldown <= 0) {
                    Debug.logMessage("SAFETY: soft brake (fall=" + fallAtPredicted
                            + " vel=" + String.format("%.2f", horizSpeed) + ")");
                    logCooldown = 120;
                }
            }
        } else if (fallAtCurrent >= FALL_DANGER && !player.isOnGround()
                && playerVel.y < -0.1 && horizSpeed > 0.01) {
            braking = true;
            float velYaw = (float) Math.toDegrees(-Math.atan2(playerVel.x, playerVel.z));
            brakeYaw = velYaw + 180f;
            wantsForward = true;
            wantsSprint = true;
            if (logCooldown <= 0) {
                Debug.logMessage("SAFETY: recovery (falling, fall=" + fallAtCurrent + ")");
                logCooldown = 120;
            }
        }

        // apply keys immediately — client physics will process before next server sync
        if (braking) {
            mc.options.forwardKey.setPressed(wantsForward);
            mc.options.backKey.setPressed(wantsBack);
            mc.options.leftKey.setPressed(wantsLeft);
            mc.options.rightKey.setPressed(wantsRight);
            mc.options.sprintKey.setPressed(wantsSprint);
            mc.options.sneakKey.setPressed(wantsSneak);
            mc.options.jumpKey.setPressed(false);
        } else if (wasBraking) {
            // just stopped braking — release keys
            mc.options.forwardKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
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
        lastUsAfterKB = simulateKnockback(playerPos, playerVel, targetPos, true);
        lastFallIfHit = VoidDetector.fallHeight(lastUsAfterKB, world);

        lastEnemyAfterKB = simulateKnockback(targetPos, enemyVelocity, playerPos, true);
        lastEnemyFallIfHit = VoidDetector.fallHeight(lastEnemyAfterKB, world);
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

    public void reset() {
        prevEnemyPos = null;
        enemyVelocity = Vec3d.ZERO;
        target = null;
        active = false;
        resetOutputs();
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
    }
}
