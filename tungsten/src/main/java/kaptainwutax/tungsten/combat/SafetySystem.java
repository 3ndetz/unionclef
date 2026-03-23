package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import kaptainwutax.tungsten.render.Line;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Combat safety + stage machine + aim prediction.
 *
 * Runs at RENDER FREQUENCY (~60 FPS).
 * tick() — enemy velocity tracking (fixed dt).
 * renderUpdate() — stage evaluation, braking, viz, aim prediction output.
 */
public class SafetySystem {

    // ── colors ──────────────────────────────────────────────────────────────
    private static final Color COL_PLAYER_VEL     = new Color(50, 220, 50);
    private static final Color COL_ENEMY_VEL      = new Color(220, 50, 50);
    private static final Color COL_DANGER          = new Color(255, 160, 0);
    private static final Color COL_VOID            = new Color(255, 0, 0);
    private static final Color COL_SAFE            = new Color(50, 200, 100);
    private static final Color COL_KB_DANGER       = new Color(255, 80, 200);
    private static final Color COL_KB_OPPORTUNITY  = new Color(0, 255, 255);
    private static final Color COL_AIM_PREDICT     = new Color(255, 255, 100);

    // ── constants ───────────────────────────────────────────────────────────
    private static final int PREDICT_TICKS = 10;
    private static final int FALL_WARN = 2;
    private static final int FALL_DANGER = 5;

    private static final double KB_BASE = 0.4;
    private static final double KB_SPRINT_BONUS = 0.4;
    private static final double KB_UP = 0.4;
    private static final int KB_PREDICT_TICKS = 15;
    private static final int KB_FALL_THRESHOLD = 2;

    // ── state ───────────────────────────────────────────────────────────────
    private Vec3d prevEnemyPos = null;
    private Vec3d enemyVelocity = Vec3d.ZERO;
    private Entity target = null;

    private CombatStage stage = CombatStage.PURSUE;
    private CombatStage prevStage = null;

    // KB analysis
    private Vec3d lastUsAfterKB = null;
    private int lastFallIfHit = 0;
    private Vec3d lastEnemyAfterKB = null;
    private int lastEnemyFallIfHit = 0;

    // aim prediction output — read by CombatController
    private float aimYaw = 0;
    private float aimPitch = 0;

    // braking output
    private float brakeYaw = 0;
    private boolean braking = false;
    private boolean wantsJump = false;

    private boolean active = false;
    private int logCooldown = 0;

    // ── tick (20 TPS): enemy velocity tracking ──────────────────────────────

    public void tick(ClientPlayerEntity player, Entity target, WorldView world) {
        this.target = target;
        active = true;

        Vec3d targetPos = target.getPos();
        if (prevEnemyPos != null) {
            enemyVelocity = targetPos.subtract(prevEnemyPos);
        }
        prevEnemyPos = targetPos;
    }

    // ── render update (~60 FPS): stage + decisions + viz ─────────────────────

    public void renderUpdate(float tickDelta) {
        if (!active) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || target == null || target.isRemoved()) return;

        braking = false;
        wantsJump = false;
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
        if (logCooldown > 0) logCooldown--;

        // tick-accurate positions for logic (block grid checks)
        Vec3d playerVel = player.getVelocity();
        Vec3d playerPosTick = player.getPos();
        Vec3d targetPosTick = target.getPos();
        double horizSpeed = Math.sqrt(playerVel.x * playerVel.x + playerVel.z * playerVel.z);

        // interpolated positions for smooth visualization
        Vec3d playerPos = playerPosTick.add(playerVel.multiply(tickDelta));
        Vec3d targetPos = targetPosTick.add(enemyVelocity.multiply(tickDelta));

        // predicted positions (from interpolated for smooth viz)
        Vec3d playerPredicted = playerPos.add(playerVel.multiply(PREDICT_TICKS));
        Vec3d enemyPredicted = targetPos.add(enemyVelocity.multiply(PREDICT_TICKS));

        // terrain checks use tick positions (block grid)
        Vec3d playerPredictedTick = playerPosTick.add(playerVel.multiply(PREDICT_TICKS));
        int fallAtPredicted = VoidDetector.fallHeight(playerPredictedTick, player.getWorld());
        int fallAtCurrent = VoidDetector.fallHeight(playerPosTick, player.getWorld());

        // KB analysis uses tick positions
        analyzeKnockback(playerPosTick, playerVel, targetPosTick, player.getWorld());

        // ── evaluate stage ───────────────────────────────────────────────
        CombatStage newStage = evaluateStage(player, playerVel, horizSpeed,
                fallAtPredicted, fallAtCurrent);
        if (newStage != stage) {
            stage = newStage;
            if (prevStage != stage) {
                Debug.logMessage(stage.chatColor() + "COMBAT: → " + stage.name());
                prevStage = stage;
            }
        }

        // ── aim prediction (for mouse subsystem) ────────────────────────
        computeAimPrediction(player, targetPos);

        // ── stage-specific behavior ─────────────────────────────────────
        switch (stage) {
            case DANGER_IMMINENT -> {
                braking = true;
                float velYaw = (float) Math.toDegrees(-Math.atan2(playerVel.x, playerVel.z));
                brakeYaw = velYaw + 180f;

                // sprint + W + jump opposite to velocity
                if (horizSpeed > 0.05 && player.isOnGround()) {
                    wantsJump = true;
                }

                mc.options.forwardKey.setPressed(true);
                mc.options.sprintKey.setPressed(true);
                mc.options.sneakKey.setPressed(false);
                mc.options.jumpKey.setPressed(wantsJump);
                mc.options.backKey.setPressed(false);
                mc.options.leftKey.setPressed(false);
                mc.options.rightKey.setPressed(false);
            }
            case DANGER_BATTLE -> {
                // no key override yet — just awareness stage
                // future: reposition away from edge while fighting
            }
            case PURSUE, ESCAPE, DELICATE_BATTLE -> {
                // no key override — normal combat
            }
        }

        // release keys when leaving braking stages
        if (!braking && (prevStage == CombatStage.DANGER_IMMINENT)) {
            mc.options.forwardKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
        }

        // ── visualization ────────────────────────────────────────────────
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

        // KB viz
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

        // aim prediction marker
        Vec3d aimTarget = targetPos.add(0, target.getHeight() * 0.5, 0)
                .add(enemyVelocity.multiply(getAimLeadTicks()));
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                aimTarget.subtract(0.1, 0.1, 0.1), new Vec3d(0.2, 0.2, 0.2), COL_AIM_PREDICT));
    }

    // ── stage evaluation ─────────────────────────────────────────────────────

    private CombatStage evaluateStage(ClientPlayerEntity player, Vec3d playerVel,
                                       double horizSpeed, int fallAtPredicted, int fallAtCurrent) {
        // DANGER_IMMINENT: our velocity leads into a fall, or already falling
        if ((fallAtPredicted >= FALL_WARN && horizSpeed > 0.01)
                || (fallAtCurrent >= FALL_DANGER && !player.isOnGround()
                    && playerVel.y < -0.1 && horizSpeed > 0.01)) {
            return CombatStage.DANGER_IMMINENT;
        }

        // DANGER_BATTLE: next enemy hit would knock us off
        if (lastFallIfHit >= KB_FALL_THRESHOLD) {
            return CombatStage.DANGER_BATTLE;
        }

        // TODO: ESCAPE — target just hit (immunity frames), or mutual edge danger, or low HP
        // TODO: DELICATE_BATTLE — low HP careful play

        return CombatStage.PURSUE;
    }

    // ── aim prediction ───────────────────────────────────────────────────────

    /**
     * Compute predicted aim point: target center + velocity * lead ticks.
     * Lead ticks = how long WindMouse needs to converge to target angle.
     */
    private void computeAimPrediction(ClientPlayerEntity player, Vec3d targetPos) {
        int leadTicks = getAimLeadTicks();
        Vec3d predictedCenter = targetPos.add(0, target.getHeight() * 0.5, 0)
                .add(enemyVelocity.multiply(leadTicks));

        aimYaw = AttackTiming.yawTo(player.getPos(), predictedCenter);
        aimPitch = AttackTiming.pitchTo(player.getEyePos(), predictedCenter);
    }

    /**
     * Estimate how many ticks WindMouse needs to reach target.
     * Based on current angular distance / effective step rate.
     * Clamped to [1, 5] — we don't predict further than 5 ticks for aiming.
     */
    private int getAimLeadTicks() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 2;

        double angDist = WindMouseRotation.INSTANCE.distanceToTarget(mc.player);
        // rough: WindMouse moves ~maxStep degrees per frame, ~3 frames per tick
        double degreesPerTick = 4.0 * 3.0; // default maxStep * ~frames/tick
        int ticks = (int) Math.ceil(angDist / degreesPerTick);
        return Math.max(1, Math.min(5, ticks));
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

    // ── getters ──────────────────────────────────────────────────────────────

    public CombatStage getStage()   { return stage; }
    public boolean isBraking()      { return braking; }
    public float getBrakeYaw()      { return brakeYaw; }
    public float getAimYaw()        { return aimYaw; }
    public float getAimPitch()      { return aimPitch; }

    public void reset() {
        prevEnemyPos = null;
        enemyVelocity = Vec3d.ZERO;
        target = null;
        active = false;
        stage = CombatStage.PURSUE;
        prevStage = null;
        braking = false;
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
    }
}
