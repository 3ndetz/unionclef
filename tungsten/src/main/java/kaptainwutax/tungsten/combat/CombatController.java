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
 * Fighter-style PvP combat controller.
 *
 * Tactics:
 *   ENGAGE  — sprint-jump toward target, attack in flight
 *   STRAFE  — after hit or if target is close/stationary, jump sideways
 *             to reposition for next attack run
 *   CHASE   — target is running away, sprint-jump directly after them
 *
 * Safety:
 *   Before every jump, simulate the full arc. Check:
 *   - Is there ground under the landing point?
 *   - How far would we fall? (>4 blocks = refuse)
 *   - If no safe direction toward target, pick safest alternative.
 *
 * Ground = decision point. Air = committed to trajectory.
 */
public class CombatController {

    // ── constants ─────────────────────────────────────────────────────────────
    private static final int JUMP_SIM_TICKS   = 20;  // simulate jump arc this many ticks
    private static final int MAX_SAFE_FALL    = 4;   // max acceptable fall height in blocks
    private static final double ATTACK_RANGE  = 3.0;
    private static final double CLOSE_RANGE   = 3.5; // target is "close" → strafe instead of charge

    // ── tactical state ────────────────────────────────────────────────────────
    private enum Tactic { ENGAGE, STRAFE, CHASE }
    private Tactic tactic = Tactic.ENGAGE;

    private boolean didHitThisJump = false;
    private boolean wasOnGround = true;
    private float jumpYaw = 0;
    private int airTicks = 0;
    private int strafeDir = 1; // +1 or -1, alternates for varied angles
    private int groundTicks = 0;

    // w-tap
    private int wtapCooldown = 0;
    private static final int WTAP_INTERVAL = 12;

    // target tracking
    private Vec3d prevTargetPos = null;

    // ── viz colors ────────────────────────────────────────────────────────────
    private static final Color COL_SAFE    = new Color(0, 220, 100);
    private static final Color COL_DANGER  = new Color(220, 60, 30);
    private static final Color COL_LANDING = new Color(255, 200, 0);
    private static final Color COL_TARGET  = new Color(255, 50, 50);
    private static final Color COL_ENGAGE  = new Color(255, 80, 80);
    private static final Color COL_STRAFE  = new Color(80, 180, 255);
    private static final Color COL_CHASE   = new Color(255, 220, 0);

    // ───────────────────────────────────────────────────────────────────────────

    public boolean tick(ClientPlayerEntity player, Entity target, WorldView world) {
        if (target == null || target.isRemoved() || !target.isAlive()) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d playerPos = player.getPos();
        Vec3d targetPos = target.getPos();
        Vec3d targetEyes = target.getPos().add(0, target.getHeight() * 0.7, 0);
        double dist = playerPos.distanceTo(targetPos);
        float toTargetYaw = AttackTiming.yawTo(playerPos, targetPos);
        boolean onGround = player.isOnGround();

        if (wtapCooldown > 0) wtapCooldown--;

        // ── detect target behavior ────────────────────────────────────────────
        boolean targetRunningAway = false;
        boolean targetApproaching = false;
        if (prevTargetPos != null) {
            Vec3d targetVel = targetPos.subtract(prevTargetPos);
            Vec3d toTarget = targetPos.subtract(playerPos).normalize();
            double dot = targetVel.dotProduct(toTarget);
            targetRunningAway = dot > 0.05;   // moving away from us
            targetApproaching = dot < -0.05;  // moving toward us
        }
        prevTargetPos = targetPos;

        // ── GROUND: decision point ────────────────────────────────────────────
        if (onGround) {
            if (!wasOnGround) {
                // just landed — decide next tactic
                groundTicks = 0;
                decideTactic(dist, targetRunningAway, targetApproaching);
            }
            groundTicks++;

            // pick yaw based on tactic
            float desiredYaw = computeTacticalYaw(toTargetYaw, dist);

            // find safe yaw (simulate jump arc, check landing)
            JumpResult jump = simulateJump(player, world, desiredYaw);
            float safeYaw;

            if (jump.safe) {
                safeYaw = desiredYaw;
            } else {
                // desired direction unsafe → search for safe alternative
                safeYaw = findSafeJumpYaw(player, world, toTargetYaw, desiredYaw);
            }

            // w-tap: release sprint for 1 tick after hitting (sprint reset for KB)
            boolean doWtap = wtapCooldown <= 0 && didHitThisJump;
            if (doWtap && groundTicks == 1) {
                mc.options.sprintKey.setPressed(false);
                mc.options.forwardKey.setPressed(false);
                mc.options.jumpKey.setPressed(false);
                wtapCooldown = WTAP_INTERVAL;
            } else {
                mc.options.forwardKey.setPressed(true);
                mc.options.sprintKey.setPressed(true);
                mc.options.jumpKey.setPressed(true); // jump!
            }

            player.setYaw(safeYaw);
            jumpYaw = safeYaw;

            // pitch: toward target for attack
            player.setPitch(AttackTiming.pitchTo(player.getEyePos(), targetEyes));

            didHitThisJump = false;
            airTicks = 0;

            renderJumpArc(player, safeYaw, world, targetPos);

        } else {
            // ── AIR: committed to trajectory ──────────────────────────────────
            airTicks++;

            mc.options.forwardKey.setPressed(true);
            mc.options.sprintKey.setPressed(true);
            mc.options.jumpKey.setPressed(false);

            // movement yaw: mostly committed, slight air-strafing toward target
            float airYaw;
            if (airTicks <= 2) {
                airYaw = jumpYaw;
            } else {
                float blend = Math.min(0.3f, (airTicks - 2) / 10.0f);
                airYaw = lerpAngle(jumpYaw, toTargetYaw, blend);
            }
            player.setYaw(airYaw);

            // pitch: smooth blend toward target
            float desiredPitch = AttackTiming.pitchTo(player.getEyePos(), targetEyes);
            float pitchBlend = Math.min(0.5f, airTicks / 6.0f);
            player.setPitch(lerpAngle(player.getPitch(), desiredPitch, pitchBlend));
        }

        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        wasOnGround = onGround;

        // ── ATTACK ────────────────────────────────────────────────────────────
        tryAttack(player, target);

        return true;
    }

    // ── tactic decision ───────────────────────────────────────────────────────

    private void decideTactic(double dist, boolean targetRunning, boolean targetApproaching) {
        if (targetRunning) {
            // target fleeing → chase directly
            tactic = Tactic.CHASE;
        } else if (dist < CLOSE_RANGE || targetApproaching || didHitThisJump) {
            // close combat or just hit → strafe to reposition
            tactic = Tactic.STRAFE;
            strafeDir = -strafeDir; // alternate sides
        } else {
            // default: engage (jump at target)
            tactic = Tactic.ENGAGE;
        }
    }

    private float computeTacticalYaw(float toTargetYaw, double dist) {
        return switch (tactic) {
            case ENGAGE -> toTargetYaw;
            case CHASE  -> toTargetYaw;
            case STRAFE -> {
                // jump sideways: 60-100° off target direction
                float strafeAngle = 70 + (float)(Math.random() * 30);
                yield toTargetYaw + strafeAngle * strafeDir;
            }
        };
    }

    // ── jump simulation & safety ──────────────────────────────────────────────

    private JumpResult simulateJump(ClientPlayerEntity player, WorldView world, float yaw) {
        Agent sim = Agent.of(player);
        sim.yaw = yaw;
        sim.keyForward = true;
        sim.keySprint = true;
        sim.keyJump = true;
        sim.keyBack = false;
        sim.keyLeft = false;
        sim.keyRight = false;
        sim.input = new AgentInput(sim);
        sim.input.playerInput = new TungstenPlayerInput(
            true, false, false, false, true, false, true);

        List<Vec3d> arc = new ArrayList<>();
        arc.add(sim.getPos());
        double startY = sim.posY;
        boolean passedApex = false;

        for (int t = 0; t < JUMP_SIM_TICKS; t++) {
            sim.tick(world);
            arc.add(sim.getPos());

            // after first tick, release jump
            if (t == 0) {
                sim.keyJump = false;
                sim.input.playerInput = new TungstenPlayerInput(
                    true, false, false, false, false, false, true);
            }

            if (sim.velY < 0) passedApex = true;

            // check: did we land?
            if (passedApex && sim.onGround && t > 2) {
                int fallH = (int) Math.max(0, startY - sim.posY);
                boolean safe = fallH <= MAX_SAFE_FALL
                    && VoidDetector.isSafe(sim.getPos(), world);
                return new JumpResult(safe, arc, sim.getPos(), fallH);
            }

            // check: falling into void during arc
            if (passedApex && !VoidDetector.isSafe(sim.getPos(), world)) {
                return new JumpResult(false, arc, sim.getPos(), MAX_SAFE_FALL + 1);
            }
        }

        // didn't land in sim window — check landing zone
        int fallH = VoidDetector.fallHeight(sim.getPos(), world);
        double totalDrop = startY - sim.posY + fallH;
        boolean safe = totalDrop <= MAX_SAFE_FALL && fallH < 30;
        return new JumpResult(safe, arc, sim.getPos(), (int) totalDrop);
    }

    private float findSafeJumpYaw(ClientPlayerEntity player, WorldView world,
                                   float toTargetYaw, float preferredYaw) {
        // test angles fanning from preferred, then from target, then opposites
        float[] offsets = {
            0, 30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180
        };

        // first pass: near preferred direction
        for (float off : offsets) {
            float testYaw = preferredYaw + off;
            JumpResult r = simulateJump(player, world, testYaw);
            if (r.safe) return testYaw;
        }

        // second pass: near target direction (if different)
        if (Math.abs(MathHelper.wrapDegrees(preferredYaw - toTargetYaw)) > 30) {
            for (float off : offsets) {
                float testYaw = toTargetYaw + off;
                JumpResult r = simulateJump(player, world, testYaw);
                if (r.safe) return testYaw;
            }
        }

        // nothing safe: don't jump, stay put
        MinecraftClient.getInstance().options.jumpKey.setPressed(false);
        return toTargetYaw; // face target, don't jump
    }

    // ── attack ────────────────────────────────────────────────────────────────

    private void tryAttack(ClientPlayerEntity player, Entity target) {
        if (!AttackTiming.canAttack(player, target)) return;

        float cooldown = player.getAttackCooldownProgress(0.5f);
        boolean isCrit = AttackTiming.isCritState(player);

        if (isCrit || cooldown >= 1.0f) {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.interactionManager.attackEntity(player, target);
            player.swingHand(Hand.MAIN_HAND);
            didHitThisJump = true;
        }
    }

    // ── visualization ─────────────────────────────────────────────────────────

    private void renderJumpArc(ClientPlayerEntity player, float yaw,
                               WorldView world, Vec3d targetPos) {
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();

        JumpResult result = simulateJump(player, world, yaw);

        // draw arc
        for (int i = 0; i < result.arc.size() - 1; i++) {
            Vec3d a = result.arc.get(i);
            Vec3d b = result.arc.get(i + 1);
            boolean segSafe = VoidDetector.fallHeight(b, world) <= MAX_SAFE_FALL;
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
                new Line(a, b, segSafe ? COL_SAFE : COL_DANGER));
        }

        // landing point
        if (!result.arc.isEmpty()) {
            Vec3d end = result.arc.get(result.arc.size() - 1);
            Color landCol = result.safe ? COL_LANDING : COL_DANGER;
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
                new Cuboid(end.subtract(0.15, 0.15, 0.15), new Vec3d(0.3, 0.3, 0.3), landCol));
        }

        // target
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
            new Cuboid(targetPos.subtract(0.3, 0, 0.3), new Vec3d(0.6, 1.8, 0.6), COL_TARGET));

        // tactic indicator above head
        Color tacCol = switch (tactic) {
            case ENGAGE -> COL_ENGAGE;
            case STRAFE -> COL_STRAFE;
            case CHASE  -> COL_CHASE;
        };
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
            new Cuboid(player.getPos().add(-0.15, 2.0, -0.15),
                new Vec3d(0.3, 0.3, 0.3), tacCol));
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
        tactic = Tactic.ENGAGE;
        wasOnGround = true;
        airTicks = 0;
        groundTicks = 0;
        wtapCooldown = 0;
        didHitThisJump = false;
        prevTargetPos = null;
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private static float lerpAngle(float from, float to, float t) {
        float diff = MathHelper.wrapDegrees(to - from);
        return from + diff * t;
    }

    private record JumpResult(boolean safe, List<Vec3d> arc, Vec3d landingPos, int fallHeight) {}
}
