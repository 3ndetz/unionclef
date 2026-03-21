package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.agent.AgentInput;
import kaptainwutax.tungsten.agent.TungstenPlayerInput;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import kaptainwutax.tungsten.render.Line;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fighter-style PvP combat controller.
 *
 * Rotation model:
 *   GROUND (1 tick): setYaw() directly for jump direction.
 *     WindMouse cleared — no conflict.
 *   AIR: WindMouse smoothly rotates toward aim point on render tick (60fps).
 *     DO NOT touch player yaw/pitch in game tick.
 *     Attack only when WindMouse arrived (crosshair on target).
 *
 * This means anti-cheat sees:
 *   - 1 instant yaw snap on landing (direction change, normal for mouse flick)
 *   - Smooth human-like rotation toward target during flight
 *   - Click happens when crosshair is genuinely on target
 */
public class CombatController {

    // ── constants ─────────────────────────────────────────────────────────────
    private static final int JUMP_SIM_TICKS   = 20;
    private static final int MAX_SAFE_FALL    = 4;
    private static final double CLOSE_RANGE   = 3.5;
    private static final double AIM_CLOSE_DEG = 6.0;  // degrees: WindMouse "arrived"

    // ── tactical state ────────────────────────────────────────────────────────
    private enum Tactic { ENGAGE, STRAFE, CHASE }
    private Tactic tactic = Tactic.ENGAGE;

    private boolean didHitThisJump = false;
    private boolean wasOnGround = true;
    private int airTicks = 0;
    private int strafeDir = 1;
    private int groundTicks = 0;

    // w-tap
    private int wtapCooldown = 0;
    private static final int WTAP_INTERVAL = 12;

    // attack key (1-tick press)
    private boolean attackKeyPressed = false;

    // target tracking
    private Vec3d prevTargetPos = null;

    // aim randomization
    private final Random rng = new Random();
    private Vec3d aimOffset = newAimOffset();

    // ── viz colors ────────────────────────────────────────────────────────────
    private static final Color COL_SAFE    = new Color(0, 220, 100);
    private static final Color COL_DANGER  = new Color(220, 60, 30);
    private static final Color COL_WALL    = new Color(200, 100, 0);
    private static final Color COL_LANDING = new Color(255, 200, 0);
    private static final Color COL_TARGET  = new Color(255, 50, 50);
    private static final Color COL_ENGAGE  = new Color(255, 80, 80);
    private static final Color COL_STRAFE  = new Color(80, 180, 255);
    private static final Color COL_CHASE   = new Color(255, 220, 0);

    // ───────────────────────────────────────────────────────────────────────────

    public boolean tick(ClientPlayerEntity player, Entity target, WorldView world) {
        if (target == null || target.isRemoved() || !target.isAlive()) return false;

        MinecraftClient mc = MinecraftClient.getInstance();

        // release attack key from previous tick
        if (attackKeyPressed) {
            mc.options.attackKey.setPressed(false);
            attackKeyPressed = false;
        }

        Vec3d playerPos = player.getPos();
        Vec3d targetPos = target.getPos();
        double dist = playerPos.distanceTo(targetPos);
        float toTargetYaw = AttackTiming.yawTo(playerPos, targetPos);
        boolean onGround = player.isOnGround();

        // random aim point on hitbox
        Vec3d aimPoint = targetPos.add(
            aimOffset.x * target.getWidth() * 0.4,
            aimOffset.y * target.getHeight(),
            aimOffset.z * target.getWidth() * 0.4);
        float aimYaw = AttackTiming.yawTo(playerPos, aimPoint);
        float aimPitch = AttackTiming.pitchTo(player.getEyePos(), aimPoint);

        if (wtapCooldown > 0) wtapCooldown--;

        // ── detect target behavior ────────────────────────────────────────────
        boolean targetRunningAway = false;
        boolean targetApproaching = false;
        if (prevTargetPos != null) {
            Vec3d targetVel = targetPos.subtract(prevTargetPos);
            double len = targetPos.subtract(playerPos).length();
            if (len > 0.1) {
                Vec3d toTarget = targetPos.subtract(playerPos).multiply(1.0 / len);
                double dot = targetVel.dotProduct(toTarget);
                targetRunningAway = dot > 0.05;
                targetApproaching = dot < -0.05;
            }
        }
        prevTargetPos = targetPos;

        boolean hasLOS = hasLineOfSight(player, targetPos);

        // ── GROUND: decision point ────────────────────────────────────────────
        if (onGround) {
            if (!wasOnGround) {
                groundTicks = 0;
                decideTactic(dist, targetRunningAway, targetApproaching, hasLOS);
                aimOffset = newAimOffset();
            }
            groundTicks++;

            // GROUND: clear WindMouse, set yaw directly for jump direction
            WindMouseRotation.INSTANCE.clearTarget();

            switch (tactic) {
                case ENGAGE, CHASE -> tickGroundEngage(player, mc, world, toTargetYaw, aimPitch, targetPos);
                case STRAFE -> tickGroundStrafe(player, mc, world, toTargetYaw, aimYaw, aimPitch, targetPos);
            }

            didHitThisJump = false;
            airTicks = 0;

        } else {
            // ── AIR: WindMouse controls rotation ──────────────────────────────
            airTicks++;

            // set WindMouse target — it will smoothly rotate on render tick
            WindMouseRotation.INSTANCE.setTarget(aimYaw, aimPitch);

            // DO NOT set player.setYaw/setPitch — WindMouse handles it on render tick

            // movement keys
            mc.options.forwardKey.setPressed(true);
            mc.options.sprintKey.setPressed(true);
            mc.options.jumpKey.setPressed(false);

            // strafe keys during initial air phase
            if (tactic == Tactic.STRAFE && airTicks <= 5) {
                mc.options.leftKey.setPressed(strafeDir > 0);
                mc.options.rightKey.setPressed(strafeDir < 0);
            } else {
                mc.options.leftKey.setPressed(false);
                mc.options.rightKey.setPressed(false);
            }
            mc.options.backKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);

            // ── ATTACK: only when WindMouse has aimed close enough ────────────
            if (airTicks >= 3) { // give WindMouse a few frames to start aiming
                tryAttack(player, target);
            }
        }

        wasOnGround = onGround;
        return true;
    }

    // ── ground ticks ──────────────────────────────────────────────────────────

    private void tickGroundEngage(ClientPlayerEntity player, MinecraftClient mc,
                                  WorldView world, float toTargetYaw,
                                  float aimPitch, Vec3d targetPos) {
        JumpResult jump = simulateJump(player, world, toTargetYaw, true, false);
        float safeYaw;

        if (jump.safe && !jump.hitWall) {
            safeYaw = toTargetYaw;
        } else {
            safeYaw = findSafeJumpYaw(player, world, toTargetYaw);
        }

        // w-tap on landing after hit
        boolean doWtap = wtapCooldown <= 0 && didHitThisJump && groundTicks == 1;
        if (doWtap) {
            mc.options.sprintKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            wtapCooldown = WTAP_INTERVAL;
        } else {
            mc.options.forwardKey.setPressed(true);
            mc.options.sprintKey.setPressed(true);
            mc.options.jumpKey.setPressed(true);
        }

        // direct yaw for jump direction (1 tick, then WindMouse takes over in air)
        player.setYaw(safeYaw);
        player.setPitch(aimPitch);

        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        renderJumpArc(player, safeYaw, world, targetPos, true, false);
    }

    private void tickGroundStrafe(ClientPlayerEntity player, MinecraftClient mc,
                                  WorldView world, float toTargetYaw,
                                  float aimYaw, float aimPitch, Vec3d targetPos) {
        // strafe: look at target + strafe keys
        JumpResult jump = simulateJump(player, world, toTargetYaw, true, strafeDir > 0);

        boolean canStrafe = jump.safe && !jump.hitWall;
        if (!canStrafe) {
            strafeDir = -strafeDir;
            jump = simulateJump(player, world, toTargetYaw, true, strafeDir > 0);
            canStrafe = jump.safe && !jump.hitWall;
        }

        if (!canStrafe) {
            tactic = Tactic.ENGAGE;
            tickGroundEngage(player, mc, world, toTargetYaw, aimPitch, targetPos);
            return;
        }

        // look at target (for attack), strafe with keys
        player.setYaw(aimYaw);
        player.setPitch(aimPitch);

        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.jumpKey.setPressed(true);
        mc.options.leftKey.setPressed(strafeDir > 0);
        mc.options.rightKey.setPressed(strafeDir < 0);
        mc.options.backKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        renderJumpArc(player, toTargetYaw, world, targetPos, true, strafeDir > 0);
    }

    // ── tactic decision ───────────────────────────────────────────────────────

    private void decideTactic(double dist, boolean targetRunning,
                              boolean targetApproaching, boolean hasLOS) {
        if (!hasLOS) {
            tactic = Tactic.ENGAGE;
        } else if (targetRunning) {
            tactic = Tactic.CHASE;
        } else if (dist < CLOSE_RANGE || targetApproaching || didHitThisJump) {
            tactic = Tactic.STRAFE;
            strafeDir = -strafeDir;
        } else {
            tactic = Tactic.ENGAGE;
        }
    }

    // ── attack ────────────────────────────────────────────────────────────────

    private void tryAttack(ClientPlayerEntity player, Entity target) {
        if (!AttackTiming.canAttack(player, target)) return;

        // only attack when WindMouse has aimed close to target
        double aimDist = WindMouseRotation.INSTANCE.distanceToTarget(player);
        if (aimDist > AIM_CLOSE_DEG) return;

        float cooldown = player.getAttackCooldownProgress(0.5f);
        boolean isCrit = AttackTiming.isCritState(player);

        // real LKM click
        if (isCrit || cooldown >= 1.0f) {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.options.attackKey.setPressed(true);
            attackKeyPressed = true;
            didHitThisJump = true;
            aimOffset = newAimOffset();
        }
    }

    // ── jump simulation ───────────────────────────────────────────────────────

    private JumpResult simulateJump(ClientPlayerEntity player, WorldView world,
                                    float yaw, boolean forward, boolean strafeLeft) {
        Agent sim = Agent.of(player);
        sim.yaw = yaw;
        sim.keyForward = forward;
        sim.keySprint = true;
        sim.keyJump = true;
        sim.keyBack = false;
        sim.keyLeft = strafeLeft;
        sim.keyRight = !strafeLeft && tactic == Tactic.STRAFE;
        sim.input = new AgentInput(sim);
        sim.input.playerInput = new TungstenPlayerInput(
            sim.keyForward, false, sim.keyLeft, sim.keyRight, true, false, true);

        List<Vec3d> arc = new ArrayList<>();
        arc.add(sim.getPos());
        double startY = sim.posY;
        boolean passedApex = false;
        boolean hitWall = false;

        for (int t = 0; t < JUMP_SIM_TICKS; t++) {
            sim.tick(world);
            arc.add(sim.getPos());

            if (t == 0) {
                sim.keyJump = false;
                sim.input.playerInput = new TungstenPlayerInput(
                    sim.keyForward, false, sim.keyLeft, sim.keyRight, false, false, true);
            }

            if (sim.horizontalCollision) hitWall = true;
            if (sim.velY < 0) passedApex = true;

            if (passedApex && sim.onGround && t > 2) {
                int fallH = (int) Math.max(0, startY - sim.posY);
                boolean safe = fallH <= MAX_SAFE_FALL
                    && VoidDetector.isSafe(sim.getPos(), world);
                return new JumpResult(safe, arc, sim.getPos(), fallH, hitWall);
            }

            if (passedApex && !VoidDetector.isSafe(sim.getPos(), world)) {
                return new JumpResult(false, arc, sim.getPos(), MAX_SAFE_FALL + 1, hitWall);
            }
        }

        int fallH = VoidDetector.fallHeight(sim.getPos(), world);
        double totalDrop = startY - sim.posY + fallH;
        boolean safe = totalDrop <= MAX_SAFE_FALL && fallH < 30;
        return new JumpResult(safe, arc, sim.getPos(), (int) totalDrop, hitWall);
    }

    private float findSafeJumpYaw(ClientPlayerEntity player, WorldView world,
                                   float toTargetYaw) {
        float[] offsets = {0, 30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180};

        for (float off : offsets) {
            float testYaw = toTargetYaw + off;
            JumpResult r = simulateJump(player, world, testYaw, true, false);
            if (r.safe && !r.hitWall) return testYaw;
        }

        for (float off : offsets) {
            float testYaw = toTargetYaw + off;
            JumpResult r = simulateJump(player, world, testYaw, true, false);
            if (r.safe) return testYaw;
        }

        MinecraftClient.getInstance().options.jumpKey.setPressed(false);
        return toTargetYaw;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Vec3d newAimOffset() {
        return new Vec3d(
            (rng.nextDouble() - 0.5) * 2.0,
            0.3 + rng.nextDouble() * 0.5,
            (rng.nextDouble() - 0.5) * 2.0);
    }

    private static boolean hasLineOfSight(ClientPlayerEntity player, Vec3d targetPos) {
        net.minecraft.util.hit.HitResult hit = player.getWorld().raycast(
            new net.minecraft.world.RaycastContext(
                player.getEyePos(), targetPos,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE, player));
        return hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS;
    }

    // ── visualization ─────────────────────────────────────────────────────────

    private void renderJumpArc(ClientPlayerEntity player, float yaw,
                               WorldView world, Vec3d targetPos,
                               boolean forward, boolean strafeLeft) {
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();

        JumpResult result = simulateJump(player, world, yaw, forward, strafeLeft);

        for (int i = 0; i < result.arc.size() - 1; i++) {
            Vec3d a = result.arc.get(i);
            Vec3d b = result.arc.get(i + 1);
            Color col;
            if (!VoidDetector.isSafe(b, world)) col = COL_DANGER;
            else if (result.hitWall) col = COL_WALL;
            else col = COL_SAFE;
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(a, b, col));
        }

        if (!result.arc.isEmpty()) {
            Vec3d end = result.arc.get(result.arc.size() - 1);
            Color landCol = result.safe ? COL_LANDING : COL_DANGER;
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
                new Cuboid(end.subtract(0.15, 0.15, 0.15), new Vec3d(0.3, 0.3, 0.3), landCol));
        }

        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
            new Cuboid(targetPos.subtract(0.3, 0, 0.3), new Vec3d(0.6, 1.8, 0.6), COL_TARGET));

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
        mc.options.attackKey.setPressed(false);
        attackKeyPressed = false;
        WindMouseRotation.INSTANCE.clearTarget();
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

    private record JumpResult(boolean safe, List<Vec3d> arc, Vec3d landingPos,
                               int fallHeight, boolean hitWall) {}
}
