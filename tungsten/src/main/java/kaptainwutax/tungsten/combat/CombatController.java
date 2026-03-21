package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.agent.AgentInput;
import kaptainwutax.tungsten.agent.TungstenPlayerInput;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import kaptainwutax.tungsten.render.Line;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.ArrayList;
import java.util.List;

/**
 * Model Predictive Control (MPC) combat controller.
 *
 * Each tick: snapshot state → generate candidates → forward-sim → score → apply best.
 * Yaw/pitch change smoothly (max degrees/tick), no instant snapping.
 */
public class CombatController {

    // ── simulation ────────────────────────────────────────────────────────────
    private static final int SIM_TICKS      = 12;
    private static final int YAW_CANDIDATES = 10;
    private static final float YAW_SPREAD   = 90f;  // degrees each side of target

    // ── rotation limits (degrees per tick) ────────────────────────────────────
    private static final float MAX_YAW_SPEED   = 18f;  // ~360°/sec, fast but not instant
    private static final float MAX_PITCH_SPEED = 12f;

    // ── jump control ──────────────────────────────────────────────────────────
    private static final int JUMP_COOLDOWN_TICKS = 12; // min ticks between jumps
    private int jumpCooldown = 0;

    // ── scoring weights ───────────────────────────────────────────────────────
    private static final double IDEAL_DIST           = 2.8;
    private static final double PROXIMITY_WEIGHT     = 12.0;
    private static final double VOID_PENALTY         = 1000.0;
    private static final double EDGE_WEIGHT          = 60.0;
    private static final double CRIT_BONUS           = 25.0;
    private static final double FACING_WEIGHT        = 8.0;
    private static final double VELOCITY_TOWARD_WEIGHT = 15.0;
    private static final double FALL_DAMAGE_PENALTY  = 200.0;
    private static final double JUMP_PENALTY         = 8.0; // discourage gratuitous jumping
    private static final double YAW_CHANGE_PENALTY   = 3.0; // prefer consistent direction

    // ── w-tap state ───────────────────────────────────────────────────────────
    private int wtapCooldown = 0;
    private static final int WTAP_INTERVAL = 20;
    private boolean wtapRelease = false;

    // ── smoothed state ────────────────────────────────────────────────────────
    private float currentYaw  = Float.NaN;
    private float currentPitch = Float.NaN;

    // ── visualization ─────────────────────────────────────────────────────────
    private static final Color TRAJ_SAFE   = new Color(0, 220, 100);   // green
    private static final Color TRAJ_DANGER = new Color(220, 60, 30);   // red
    private static final Color TRAJ_BEST   = new Color(255, 200, 0);   // yellow/gold
    private static final Color TARGET_BOX  = new Color(255, 50, 50);   // red target marker

    // ───────────────────────────────────────────────────────────────────────────

    public boolean tick(ClientPlayerEntity player, Entity target, WorldView world) {
        if (target == null || target.isRemoved() || !target.isAlive()) return false;

        Vec3d playerPos = player.getPos();
        Vec3d targetPos = target.getPos();
        Vec3d targetCenter = target.getPos().add(0, target.getHeight() * 0.7, 0);
        double dist = playerPos.distanceTo(targetPos);

        float targetYaw = AttackTiming.yawTo(playerPos, targetPos);

        // init smoothed yaw/pitch on first tick
        if (Float.isNaN(currentYaw))  currentYaw  = player.getYaw();
        if (Float.isNaN(currentPitch)) currentPitch = player.getPitch();

        if (jumpCooldown > 0) jumpCooldown--;

        // ── generate & evaluate candidates ────────────────────────────────────
        Agent base = Agent.of(player);
        BestCandidate best = null;

        for (int yi = 0; yi < YAW_CANDIDATES; yi++) {
            float candidateYaw = targetYaw + YAW_SPREAD * (2f * yi / (YAW_CANDIDATES - 1) - 1f);

            // clamp candidate yaw to what we can actually reach this tick
            float clampedYaw = clampYawToward(currentYaw, candidateYaw, MAX_YAW_SPEED);

            for (int jumpFlag = 0; jumpFlag <= 1; jumpFlag++) {
                boolean jump = jumpFlag == 1;

                // skip jump candidates if on cooldown or already airborne
                if (jump && (jumpCooldown > 0 || !player.isOnGround())) continue;

                Agent sim = base.copy();
                sim.yaw = clampedYaw;
                sim.keyForward = true;
                sim.keySprint = true;
                sim.keyJump = jump;
                sim.keyBack = false;
                sim.keyLeft = false;
                sim.keyRight = false;
                sim.input = new AgentInput(sim);
                sim.input.playerInput = new TungstenPlayerInput(
                    true, false, false, false, jump, false, true);

                List<Vec3d> trajectory = new ArrayList<>(SIM_TICKS + 1);
                trajectory.add(sim.getPos());
                double score = simulateAndScore(sim, world, targetPos, dist, trajectory);

                // penalize jumping when not needed
                if (jump) score -= JUMP_PENALTY;

                // penalize large yaw change from last tick (consistency)
                if (!Float.isNaN(currentYaw)) {
                    float yawDelta = Math.abs(MathHelper.wrapDegrees(clampedYaw - currentYaw));
                    score -= yawDelta / 180.0 * YAW_CHANGE_PENALTY;
                }

                if (best == null || score > best.score) {
                    best = new BestCandidate(clampedYaw, jump, score, trajectory);
                }
            }
        }

        // "back off" candidate
        {
            float awayYaw = clampYawToward(currentYaw, targetYaw + 180f, MAX_YAW_SPEED);
            Agent sim = base.copy();
            sim.yaw = awayYaw;
            sim.keyForward = true;
            sim.keySprint = true;
            sim.keyJump = false;
            sim.keyBack = false;
            sim.input = new AgentInput(sim);
            sim.input.playerInput = new TungstenPlayerInput(
                true, false, false, false, false, false, true);

            List<Vec3d> trajectory = new ArrayList<>(SIM_TICKS + 1);
            trajectory.add(sim.getPos());
            double score = simulateAndScore(sim, world, targetPos, dist, trajectory) - 5.0;

            if (best == null || score > best.score) {
                best = new BestCandidate(awayYaw, false, score, trajectory);
            }
        }

        if (best == null) return false;

        // ── apply best action with smooth rotation ────────────────────────────
        applyAction(player, best, targetCenter);

        // ── render best trajectory ────────────────────────────────────────────
        renderTrajectory(best.trajectory, targetPos);

        // ── w-tap ─────────────────────────────────────────────────────────────
        tickWtap(player, target, dist);

        // ── attack ────────────────────────────────────────────────────────────
        tickAttack(player, target, targetCenter);

        return true;
    }

    private double simulateAndScore(Agent sim, WorldView world, Vec3d targetPos,
                                     double initialDist, List<Vec3d> trajectory) {
        double score = 0;
        boolean anyUnsafe = false;
        double worstEdge = 0;

        for (int t = 0; t < SIM_TICKS; t++) {
            sim.tick(world);
            Vec3d simPos = sim.getPos();
            trajectory.add(simPos);

            if (!VoidDetector.isSafe(simPos, world)) anyUnsafe = true;

            double edge = VoidDetector.edgeScore(simPos, world);
            if (edge > worstEdge) worstEdge = edge;
        }

        Vec3d finalPos = sim.getPos();
        double finalDist = finalPos.distanceTo(targetPos);

        // proximity
        score -= Math.abs(finalDist - IDEAL_DIST) * PROXIMITY_WEIGHT;
        if (finalDist < initialDist) score += (initialDist - finalDist) * 5.0;

        // void
        if (anyUnsafe) score -= VOID_PENALTY;

        // edge
        score -= worstEdge * EDGE_WEIGHT;

        // crit opportunity
        if (!sim.onGround && sim.velY < 0 && finalDist < 3.5) score += CRIT_BONUS;

        // facing
        float idealYaw = AttackTiming.yawTo(finalPos, targetPos);
        float yawDiff = Math.abs(MathHelper.wrapDegrees(sim.yaw - idealYaw));
        score -= (yawDiff / 180.0) * FACING_WEIGHT;

        // velocity toward target
        Vec3d toTarget = targetPos.subtract(finalPos);
        double toTargetLen = toTarget.length();
        if (toTargetLen > 0.001) {
            Vec3d vel = new Vec3d(sim.velX, 0, sim.velZ);
            score += vel.dotProduct(toTarget.multiply(1.0 / toTargetLen)) * VELOCITY_TOWARD_WEIGHT;
        }

        // fall damage
        if (sim.fallDistance > 3.0) score -= FALL_DAMAGE_PENALTY;

        return score;
    }

    private void applyAction(ClientPlayerEntity player, BestCandidate action, Vec3d targetCenter) {
        MinecraftClient mc = MinecraftClient.getInstance();

        // smooth yaw: move toward chosen direction, capped by max speed
        currentYaw = action.yaw; // already clamped during candidate generation
        player.setYaw(currentYaw);

        // smooth pitch: gradually look at target center
        Vec3d eyePos = player.getEyePos();
        float desiredPitch = AttackTiming.pitchTo(eyePos, targetCenter);
        currentPitch = smoothAngle(currentPitch, desiredPitch, MAX_PITCH_SPEED);
        player.setPitch(currentPitch);

        // keys
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.jumpKey.setPressed(action.jump && player.isOnGround());
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        if (action.jump && player.isOnGround()) {
            jumpCooldown = JUMP_COOLDOWN_TICKS;
        }
    }

    private void tickWtap(ClientPlayerEntity player, Entity target, double dist) {
        if (wtapCooldown > 0) { wtapCooldown--; return; }

        if (dist < 3.2 && player.getAttackCooldownProgress(0.5f) > 0.85f && player.isOnGround()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!wtapRelease) {
                mc.options.sprintKey.setPressed(false);
                mc.options.forwardKey.setPressed(false);
                wtapRelease = true;
            } else {
                mc.options.sprintKey.setPressed(true);
                mc.options.forwardKey.setPressed(true);
                wtapRelease = false;
                wtapCooldown = WTAP_INTERVAL;
            }
        }
    }

    private void tickAttack(ClientPlayerEntity player, Entity target, Vec3d targetCenter) {
        if (!AttackTiming.canAttack(player, target)) return;

        boolean isCrit = AttackTiming.isCritState(player);
        float cooldown = player.getAttackCooldownProgress(0.5f);

        if (isCrit || cooldown >= 1.0f) {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.interactionManager.attackEntity(player, target);
            player.swingHand(Hand.MAIN_HAND);
        }
    }

    // ── visualization ─────────────────────────────────────────────────────────

    private void renderTrajectory(List<Vec3d> trajectory, Vec3d targetPos) {
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();

        if (trajectory == null || trajectory.size() < 2) return;

        for (int i = 0; i < trajectory.size() - 1; i++) {
            Vec3d a = trajectory.get(i);
            Vec3d b = trajectory.get(i + 1);

            // color: interpolate green→yellow→red along trajectory
            float t = (float) i / (trajectory.size() - 1);
            int r = (int) (TRAJ_SAFE.getRed()   + t * (TRAJ_DANGER.getRed()   - TRAJ_SAFE.getRed()));
            int g = (int) (TRAJ_SAFE.getGreen() + t * (TRAJ_DANGER.getGreen() - TRAJ_SAFE.getGreen()));
            int bl = (int) (TRAJ_SAFE.getBlue()  + t * (TRAJ_DANGER.getBlue()  - TRAJ_SAFE.getBlue()));
            Color segColor = new Color(r, g, bl);

            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(a, b, segColor));
        }

        // small cube at trajectory end
        Vec3d end = trajectory.get(trajectory.size() - 1);
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
            new Cuboid(end.subtract(0.05, 0.05, 0.05), new Vec3d(0.1, 0.1, 0.1), TRAJ_BEST));

        // target marker
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
            new Cuboid(targetPos.subtract(0.3, 0, 0.3), new Vec3d(0.6, 1.8, 0.6), TARGET_BOX));
    }

    /** Clear trajectory render when combat stops. */
    private void clearRender() {
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
    }

    // ── rotation helpers ──────────────────────────────────────────────────────

    /** Clamp candidateYaw so it's at most maxDelta degrees from currentYaw. */
    private static float clampYawToward(float current, float target, float maxDelta) {
        float diff = MathHelper.wrapDegrees(target - current);
        diff = MathHelper.clamp(diff, -maxDelta, maxDelta);
        return current + diff;
    }

    /** Smoothly move angle toward target by at most maxDelta per tick. */
    private static float smoothAngle(float current, float target, float maxDelta) {
        return clampYawToward(current, target, maxDelta);
    }

    public void releaseKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        currentYaw = Float.NaN;
        currentPitch = Float.NaN;
        wtapCooldown = 0;
        wtapRelease = false;
        jumpCooldown = 0;
        clearRender();
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private static final class BestCandidate {
        final float yaw;
        final boolean jump;
        final double score;
        final List<Vec3d> trajectory;

        BestCandidate(float yaw, boolean jump, double score, List<Vec3d> trajectory) {
            this.yaw = yaw;
            this.jump = jump;
            this.score = score;
            this.trajectory = trajectory;
        }
    }
}
