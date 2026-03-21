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
 * Jump-based PvP combat controller.
 *
 * Core principle: sprint-jump IS the movement. Bot is always jumping.
 * Hits happen mid-flight when target is in reach.
 *
 * Each game tick:
 *   1. Pick yaw: toward target (or safe direction if void ahead)
 *   2. Always sprint + forward
 *   3. Jump on every ground contact (sprint-jump chain)
 *   4. Attack when: in reach + cooldown ready (prefer crit = falling)
 *   5. Yaw is set directly (movement direction), no WindMouse needed —
 *      the constant jumping justifies any rotation between jumps.
 *
 * Landing = new jump = natural moment for direction change.
 * In air = committed to trajectory = no weird spinning.
 */
public class CombatController {

    // ── distances ─────────────────────────────────────────────────────────────
    private static final double REACH = 3.0;

    // ── void sim ──────────────────────────────────────────────────────────────
    private static final int VOID_SIM_TICKS = 15;

    // ── w-tap ─────────────────────────────────────────────────────────────────
    private int wtapCooldown = 0;
    private static final int WTAP_INTERVAL = 12;
    private boolean didAttackThisJump = false;

    // ── state ─────────────────────────────────────────────────────────────────
    private boolean wasOnGround = true;
    private float jumpYaw = 0; // yaw locked at takeoff, committed during flight
    private int airTicks = 0;

    // ── viz ────────────────────────────────────────────────────────────────────
    private static final Color TRAJ_SAFE   = new Color(0, 220, 100);
    private static final Color TRAJ_DANGER = new Color(220, 60, 30);
    private static final Color TRAJ_END    = new Color(255, 200, 0);
    private static final Color TARGET_BOX  = new Color(255, 50, 50);
    private static final Color GROUND_COL  = new Color(80, 180, 255);
    private static final Color AIR_COL     = new Color(255, 140, 0);

    // ───────────────────────────────────────────────────────────────────────────

    public boolean tick(ClientPlayerEntity player, Entity target, WorldView world) {
        if (target == null || target.isRemoved() || !target.isAlive()) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d playerPos = player.getPos();
        Vec3d targetPos = target.getPos();
        Vec3d targetEyes = target.getPos().add(0, target.getHeight() * 0.7, 0);
        double dist = playerPos.distanceTo(targetPos);
        float toTargetYaw = AttackTiming.yawTo(playerPos, targetPos);

        if (wtapCooldown > 0) wtapCooldown--;

        boolean onGround = player.isOnGround();

        // ── GROUND CONTACT: pick direction for next jump ──────────────────────
        if (onGround) {
            airTicks = 0;

            // pick yaw: toward target, but check void safety
            float chosenYaw = pickSafeYaw(player, world, toTargetYaw, targetPos, dist);

            // w-tap: release sprint for 1 tick on landing before re-jumping
            // (resets sprint → next hit does more knockback)
            boolean doWtap = wtapCooldown <= 0 && dist < 5.0 && didAttackThisJump;
            if (doWtap) {
                mc.options.sprintKey.setPressed(false);
                mc.options.forwardKey.setPressed(false);
                wtapCooldown = WTAP_INTERVAL;
            } else {
                mc.options.forwardKey.setPressed(true);
                mc.options.sprintKey.setPressed(true);
            }

            // ALWAYS jump on ground contact → sprint-jump chain
            mc.options.jumpKey.setPressed(true);

            // set yaw for the jump (direction chosen on ground)
            player.setYaw(chosenYaw);
            jumpYaw = chosenYaw;

            // pitch toward target (for attack registration)
            float desiredPitch = AttackTiming.pitchTo(player.getEyePos(), targetEyes);
            player.setPitch(desiredPitch);

            if (!wasOnGround) {
                // just landed → reset per-jump state
                didAttackThisJump = false;
            }

            renderTrajectory(player, chosenYaw, world, targetPos);

        } else {
            // ── IN AIR: committed to jump trajectory ──────────────────────────
            airTicks++;

            mc.options.forwardKey.setPressed(true);
            mc.options.sprintKey.setPressed(true);
            mc.options.jumpKey.setPressed(false); // already airborne

            // keep movement yaw locked to jump direction (committed trajectory)
            // but allow gradual steering toward target for air control
            float airYaw;
            if (airTicks <= 3) {
                // first few ticks: committed to jump direction
                airYaw = jumpYaw;
            } else {
                // after that: blend toward target (air strafing, subtle)
                float blend = Math.min(1.0f, (airTicks - 3) / 8.0f);
                airYaw = lerpAngle(jumpYaw, toTargetYaw, blend * 0.4f);
            }
            player.setYaw(airYaw);

            // pitch tracks target smoothly during flight
            float desiredPitch = AttackTiming.pitchTo(player.getEyePos(), targetEyes);
            // smooth pitch: blend toward target
            float currentPitch = player.getPitch();
            float pitchBlend = Math.min(1.0f, airTicks / 5.0f);
            player.setPitch(lerpAngle(currentPitch, desiredPitch, pitchBlend * 0.6f));
        }

        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        wasOnGround = onGround;

        // ── ATTACK: hit when in reach + cooldown ready ────────────────────────
        tryAttack(player, target, targetEyes);

        return true;
    }

    // ── yaw selection with void safety ─────────────────────────────────────────

    private float pickSafeYaw(ClientPlayerEntity player, WorldView world,
                              float toTargetYaw, Vec3d targetPos, double dist) {
        // first check: is direct path to target safe?
        if (isYawSafe(player, world, toTargetYaw, true)) {
            return toTargetYaw;
        }

        // target direction has void → find safest yaw that still approaches target
        float bestYaw = toTargetYaw + 180f; // fallback: away from target
        double bestScore = Double.NEGATIVE_INFINITY;

        float[] offsets = {30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180};
        for (float off : offsets) {
            float testYaw = toTargetYaw + off;
            if (isYawSafe(player, world, testYaw, true)) {
                // score: prefer yaws closer to target direction
                double score = -Math.abs(off);
                if (score > bestScore) {
                    bestScore = score;
                    bestYaw = testYaw;
                }
            }
        }
        return bestYaw;
    }

    private boolean isYawSafe(ClientPlayerEntity player, WorldView world,
                              float yaw, boolean withJump) {
        Agent sim = Agent.of(player);
        sim.yaw = yaw;
        sim.keyForward = true;
        sim.keySprint = true;
        sim.keyJump = withJump && player.isOnGround();
        sim.keyBack = false;
        sim.keyLeft = false;
        sim.keyRight = false;
        sim.input = new AgentInput(sim);
        sim.input.playerInput = new TungstenPlayerInput(
            true, false, false, false, sim.keyJump, false, true);

        for (int t = 0; t < VOID_SIM_TICKS; t++) {
            sim.tick(world);
            if (!VoidDetector.isSafe(sim.getPos(), world)) return false;
            // also check: did we fall too far? (off an edge)
            if (sim.fallDistance > 4.0) return false;
        }
        return true;
    }

    // ── attack ────────────────────────────────────────────────────────────────

    private void tryAttack(ClientPlayerEntity player, Entity target, Vec3d targetEyes) {
        if (!AttackTiming.canAttack(player, target)) return;

        float cooldown = player.getAttackCooldownProgress(0.5f);
        boolean isCrit = AttackTiming.isCritState(player);

        // attack: crit (falling) or full cooldown
        if (isCrit || cooldown >= 1.0f) {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.interactionManager.attackEntity(player, target);
            player.swingHand(Hand.MAIN_HAND);
            didAttackThisJump = true;
        }
    }

    // ── visualization ─────────────────────────────────────────────────────────

    private void renderTrajectory(ClientPlayerEntity player, float yaw,
                                  WorldView world, Vec3d targetPos) {
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();

        // simulate sprint-jump trajectory
        Agent sim = Agent.of(player);
        sim.yaw = yaw;
        sim.keyForward = true;
        sim.keySprint = true;
        sim.keyJump = player.isOnGround();
        sim.keyBack = false;
        sim.input = new AgentInput(sim);
        sim.input.playerInput = new TungstenPlayerInput(
            true, false, false, false, sim.keyJump, false, true);

        List<Vec3d> points = new ArrayList<>();
        points.add(sim.getPos());

        for (int t = 0; t < 25; t++) {
            sim.tick(world);
            points.add(sim.getPos());
            // after first tick, stop jumping (already airborne)
            if (t == 0) {
                sim.keyJump = false;
                sim.input.playerInput = new TungstenPlayerInput(
                    true, false, false, false, false, false, true);
            }
        }

        // draw trajectory lines with safety coloring
        for (int i = 0; i < points.size() - 1; i++) {
            Vec3d a = points.get(i);
            Vec3d b = points.get(i + 1);
            boolean safe = VoidDetector.isSafe(b, world);
            Color col = safe ? TRAJ_SAFE : TRAJ_DANGER;
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(a, b, col));
        }

        // endpoint cube
        Vec3d end = points.get(points.size() - 1);
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
            new Cuboid(end.subtract(0.1, 0.1, 0.1), new Vec3d(0.2, 0.2, 0.2), TRAJ_END));

        // target marker
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
            new Cuboid(targetPos.subtract(0.3, 0, 0.3), new Vec3d(0.6, 1.8, 0.6), TARGET_BOX));

        // state indicator above player
        Color stateCol = player.isOnGround() ? GROUND_COL : AIR_COL;
        Vec3d head = player.getPos().add(-0.15, 2.0, -0.15);
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
            new Cuboid(head, new Vec3d(0.3, 0.3, 0.3), stateCol));
    }

    // ── cleanup ───────────────────────────────────────────────────────────────

    public void releaseKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        wasOnGround = true;
        airTicks = 0;
        wtapCooldown = 0;
        didAttackThisJump = false;
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
    }

    // ── math helpers ──────────────────────────────────────────────────────────

    private static float lerpAngle(float from, float to, float t) {
        float diff = MathHelper.wrapDegrees(to - from);
        return from + diff * t;
    }
}
