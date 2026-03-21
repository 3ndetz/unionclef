package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.agent.AgentInput;
import kaptainwutax.tungsten.agent.TungstenPlayerInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Model Predictive Control (MPC) combat controller.
 *
 * Each tick:
 *   1. Snapshot player state into Agent (includes knockback velocity)
 *   2. Generate candidate actions (yaw × jump × sprint combos)
 *   3. Forward-simulate each candidate 10-15 ticks via Agent.tick()
 *   4. Score trajectories (proximity, void safety, crit opportunity, edge safety)
 *   5. Execute best candidate for 1 tick
 *   6. Attack if conditions met (reach + cooldown + optionally crit)
 */
public class CombatController {

    // ── tuning constants ──────────────────────────────────────────────────────
    private static final int SIM_TICKS           = 12;
    private static final int YAW_CANDIDATES      = 12;
    private static final float YAW_SPREAD        = 120f; // degrees each side of target direction

    private static final double IDEAL_DIST       = 2.8;
    private static final double PROXIMITY_WEIGHT  = 12.0;
    private static final double VOID_PENALTY      = 1000.0;
    private static final double EDGE_WEIGHT       = 60.0;
    private static final double CRIT_BONUS        = 35.0;
    private static final double FACING_WEIGHT     = 8.0;
    private static final double VELOCITY_TOWARD_WEIGHT = 15.0;
    private static final double FALL_DAMAGE_PENALTY = 200.0;

    // ── w-tap state ───────────────────────────────────────────────────────────
    private int wtapCooldown = 0;
    private static final int WTAP_INTERVAL = 20; // ticks between w-tap resets
    private boolean wtapRelease = false;

    // ── last action (for smooth transitions) ──────────────────────────────────
    private float lastYaw = Float.NaN;

    // ───────────────────────────────────────────────────────────────────────────

    /**
     * Run one tick of MPC combat.
     * Called from PunkPlayerTask every game tick while in combat range.
     *
     * @return true if MPC produced a valid action, false if it couldn't (e.g. no target)
     */
    public boolean tick(ClientPlayerEntity player, Entity target, WorldView world) {
        if (target == null || target.isRemoved() || !target.isAlive()) return false;

        Vec3d playerPos = player.getPos();
        Vec3d targetPos = target.getPos();
        Vec3d targetCenter = target.getPos().add(0, target.getHeight() * 0.7, 0);
        double dist = playerPos.distanceTo(targetPos);

        float targetYaw = AttackTiming.yawTo(playerPos, targetPos);

        // ── generate & evaluate candidates ────────────────────────────────────
        Agent base = Agent.of(player);
        BestAction best = null;

        // yaw candidates: fan from -YAW_SPREAD to +YAW_SPREAD around target direction
        for (int yi = 0; yi < YAW_CANDIDATES; yi++) {
            float yaw = targetYaw + YAW_SPREAD * (2f * yi / (YAW_CANDIDATES - 1) - 1f);

            for (int jumpFlag = 0; jumpFlag <= 1; jumpFlag++) {
                boolean jump = jumpFlag == 1;

                // simulate forward
                Agent sim = base.copy();
                sim.yaw = yaw;
                sim.keyForward = true;
                sim.keySprint = true;
                sim.keyJump = jump;
                sim.keyBack = false;
                sim.keyLeft = false;
                sim.keyRight = false;
                // re-init input from keys
                sim.input = new AgentInput(sim);
                sim.input.playerInput = new TungstenPlayerInput(
                    true, false, false, false, jump, false, true);

                double score = simulateAndScore(sim, world, targetPos, dist);

                if (best == null || score > best.score) {
                    best = new BestAction(yaw, jump, score);
                }
            }
        }

        // also evaluate a "back off" action (when too close to edge / void)
        {
            float awayYaw = targetYaw + 180f;
            Agent sim = base.copy();
            sim.yaw = awayYaw;
            sim.keyForward = true;
            sim.keySprint = true;
            sim.keyJump = false;
            sim.keyBack = false;
            sim.input = new AgentInput(sim);
            sim.input.playerInput = new TungstenPlayerInput(
                true, false, false, false, false, false, true);

            double score = simulateAndScore(sim, world, targetPos, dist);
            // small penalty for retreating (we want to fight, not run)
            score -= 5.0;
            if (best == null || score > best.score) {
                best = new BestAction(awayYaw, false, score);
            }
        }

        if (best == null) return false;

        // ── apply best action ─────────────────────────────────────────────────
        applyAction(player, best, targetYaw);

        // ── w-tap logic (sprint reset for extra knockback on hit) ─────────────
        tickWtap(player, target, dist);

        // ── attack ────────────────────────────────────────────────────────────
        tickAttack(player, target, targetCenter);

        lastYaw = best.yaw;
        return true;
    }

    private double simulateAndScore(Agent sim, WorldView world, Vec3d targetPos, double initialDist) {
        double score = 0;

        // track if sim went through unsafe states
        boolean anyUnsafe = false;
        double worstEdge = 0;

        for (int t = 0; t < SIM_TICKS; t++) {
            sim.tick(world);

            Vec3d simPos = sim.getPos();

            // void check at each step
            if (!VoidDetector.isSafe(simPos, world)) {
                anyUnsafe = true;
            }

            // edge score at final position matters most, but track worst
            double edge = VoidDetector.edgeScore(simPos, world);
            if (edge > worstEdge) worstEdge = edge;
        }

        Vec3d finalPos = sim.getPos();
        double finalDist = finalPos.distanceTo(targetPos);

        // 1. Proximity: want to be near IDEAL_DIST from target
        score -= Math.abs(finalDist - IDEAL_DIST) * PROXIMITY_WEIGHT;

        // bonus for closing distance (approaching target)
        if (finalDist < initialDist) {
            score += (initialDist - finalDist) * 5.0;
        }

        // 2. Void safety
        if (anyUnsafe) score -= VOID_PENALTY;

        // 3. Edge proximity penalty
        score -= worstEdge * EDGE_WEIGHT;

        // 4. Crit opportunity: if we end up falling and close enough to hit
        if (!sim.onGround && sim.velY < 0 && finalDist < 3.5) {
            score += CRIT_BONUS;
        }

        // 5. Facing bonus: yaw alignment with target direction at endpoint
        float simYaw = sim.yaw;
        float idealYaw = AttackTiming.yawTo(finalPos, targetPos);
        float yawDiff = Math.abs(MathHelper.wrapDegrees(simYaw - idealYaw));
        score -= (yawDiff / 180.0) * FACING_WEIGHT;

        // 6. Velocity toward target
        Vec3d toTarget = targetPos.subtract(finalPos).normalize();
        Vec3d vel = new Vec3d(sim.velX, 0, sim.velZ);
        double velToward = vel.dotProduct(toTarget);
        score += velToward * VELOCITY_TOWARD_WEIGHT;

        // 7. Fall damage penalty
        if (sim.fallDistance > 3.0) {
            score -= FALL_DAMAGE_PENALTY;
        }

        return score;
    }

    private void applyAction(ClientPlayerEntity player, BestAction action, float targetYaw) {
        MinecraftClient mc = MinecraftClient.getInstance();

        // Smooth yaw transition: blend movement yaw toward chosen direction
        // but look at target for attacking (pitch + visual yaw)
        player.setYaw(action.yaw);

        // pitch: look at target height (for attacks to connect)
        // We set movement keys
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.jumpKey.setPressed(action.jump && player.isOnGround());
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    private void tickWtap(ClientPlayerEntity player, Entity target, double dist) {
        if (wtapCooldown > 0) {
            wtapCooldown--;
            return;
        }

        // W-tap: briefly release forward key right before an attack lands
        // This resets sprint, causing more knockback on the next hit
        if (dist < 3.2 && player.getAttackCooldownProgress(0.5f) > 0.85f && player.isOnGround()) {
            if (!wtapRelease) {
                // release sprint for 1 tick
                MinecraftClient.getInstance().options.sprintKey.setPressed(false);
                MinecraftClient.getInstance().options.forwardKey.setPressed(false);
                wtapRelease = true;
            } else {
                // re-engage
                MinecraftClient.getInstance().options.sprintKey.setPressed(true);
                MinecraftClient.getInstance().options.forwardKey.setPressed(true);
                wtapRelease = false;
                wtapCooldown = WTAP_INTERVAL;
            }
        }
    }

    private void tickAttack(ClientPlayerEntity player, Entity target, Vec3d targetCenter) {
        if (!AttackTiming.canAttack(player, target)) return;

        // Set pitch toward target for the attack to register
        Vec3d eyePos = player.getEyePos();
        float attackPitch = AttackTiming.pitchTo(eyePos, targetCenter);
        player.setPitch(attackPitch);

        // Prefer crit attacks (when falling), but don't skip non-crit if cooldown is full
        boolean isCrit = AttackTiming.isCritState(player);
        float cooldown = player.getAttackCooldownProgress(0.5f);

        // attack if: crit opportunity OR cooldown fully ready
        if (isCrit || cooldown >= 1.0f) {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.interactionManager.attackEntity(player, target);
            player.swingHand(Hand.MAIN_HAND);
        }
    }

    /** Release all keys. Called when combat stops. */
    public void releaseKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        lastYaw = Float.NaN;
        wtapCooldown = 0;
        wtapRelease = false;
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private static final class BestAction {
        final float yaw;
        final boolean jump;
        final double score;

        BestAction(float yaw, boolean jump, double score) {
            this.yaw = yaw;
            this.jump = jump;
            this.score = score;
        }
    }
}
