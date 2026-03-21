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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Two independent cycles:
 *
 * LEGS CYCLE (sprint-jump chain):
 *   On ground → W + A/D + sprint + jump → airborne → land → repeat.
 *   Jump direction set by yaw on ground tick. Own rhythm.
 *
 * MOUSE CYCLE (aim pattern, runs in parallel):
 *   1. FLICK: fast turn roughly toward target (overshoot 20-40°). Few ticks.
 *   2. CORRECT: WindMouse corrects back, sweeping through target hitbox.
 *      If crosshair actually touches target (MC crosshairTarget) → click.
 *   3. PAUSE: brief rest (2-5 ticks). No rotation.
 *   4. Back to FLICK in opposite direction.
 *
 * Click: only when mc.crosshairTarget IS the target entity.
 * This is the actual client raycast, same as manual mouse click.
 */
public class CombatController {

    private static final int JUMP_SIM_TICKS = 20;
    private static final int MAX_SAFE_FALL  = 4;
    private static final double CLOSE_RANGE = 3.5;

    // ── legs state ────────────────────────────────────────────────────────────
    private boolean wasOnGround = true;
    private int strafeDir = 1;
    private int wtapCooldown = 0;
    private boolean didHitThisJump = false;
    private Vec3d prevTargetPos = null;

    private enum Tactic { ENGAGE, STRAFE, CHASE }
    private Tactic tactic = Tactic.ENGAGE;

    // ── mouse state ───────────────────────────────────────────────────────────
    private enum MousePhase { FLICK, CORRECT, PAUSE }
    private MousePhase mousePhase = MousePhase.FLICK;
    private int mousePhaseTicks = 0;
    private float flickDirection = 1; // +1 or -1, alternates
    private boolean attackKeyPressed = false;

    private final Random rng = new Random();

    // ── viz ────────────────────────────────────────────────────────────────────
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
        Vec3d targetCenter = target.getPos().add(0, target.getHeight() * 0.5, 0);
        double dist = playerPos.distanceTo(targetPos);
        float toTargetYaw = AttackTiming.yawTo(playerPos, targetPos);
        float toTargetPitch = AttackTiming.pitchTo(player.getEyePos(), targetCenter);
        boolean onGround = player.isOnGround();

        if (wtapCooldown > 0) wtapCooldown--;

        // target behavior
        boolean targetRunningAway = false;
        boolean targetApproaching = false;
        if (prevTargetPos != null) {
            Vec3d targetVel = targetPos.subtract(prevTargetPos);
            double len = dist;
            if (len > 0.1) {
                double dot = targetVel.dotProduct(targetPos.subtract(playerPos).multiply(1.0 / len));
                targetRunningAway = dot > 0.05;
                targetApproaching = dot < -0.05;
            }
        }
        prevTargetPos = targetPos;

        boolean hasLOS = hasLineOfSight(player, targetPos);

        // ══════════════════════════════════════════════════════════════════════
        // LEGS CYCLE — independent from mouse
        // ══════════════════════════════════════════════════════════════════════
        tickLegs(player, mc, world, toTargetYaw, onGround,
            dist, targetRunningAway, targetApproaching, hasLOS, targetPos);

        // ══════════════════════════════════════════════════════════════════════
        // MOUSE CYCLE — independent from legs
        // ══════════════════════════════════════════════════════════════════════
        tickMouse(player, mc, target, toTargetYaw, toTargetPitch, dist);

        wasOnGround = onGround;
        return true;
    }

    // ── LEGS ──────────────────────────────────────────────────────────────────

    private void tickLegs(ClientPlayerEntity player, MinecraftClient mc,
                          WorldView world, float toTargetYaw, boolean onGround,
                          double dist, boolean targetRunning, boolean targetApproaching,
                          boolean hasLOS, Vec3d targetPos) {
        if (onGround) {
            if (!wasOnGround) {
                // just landed
                decideTactic(dist, targetRunning, targetApproaching, hasLOS);
                didHitThisJump = false;
            }

            float jumpYaw = computeGroundYaw(player, world, toTargetYaw);

            // DON'T set player.setYaw here — mouse cycle controls yaw
            // Instead, we use forward + strafe keys relative to current facing

            // w-tap on first ground tick after hit
            if (wtapCooldown <= 0 && didHitThisJump && !wasOnGround) {
                mc.options.sprintKey.setPressed(false);
                mc.options.forwardKey.setPressed(false);
                mc.options.jumpKey.setPressed(false);
                wtapCooldown = 12;
            } else {
                mc.options.forwardKey.setPressed(true);
                mc.options.sprintKey.setPressed(true);
                mc.options.jumpKey.setPressed(true);
            }

            mc.options.leftKey.setPressed(strafeDir > 0);
            mc.options.rightKey.setPressed(strafeDir < 0);
            mc.options.backKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);

            renderJumpArc(player, jumpYaw, world, targetPos);
        } else {
            // airborne — keep movement going
            mc.options.forwardKey.setPressed(true);
            mc.options.sprintKey.setPressed(true);
            mc.options.jumpKey.setPressed(false);
            mc.options.leftKey.setPressed(strafeDir > 0);
            mc.options.rightKey.setPressed(strafeDir < 0);
            mc.options.backKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
        }
    }

    // ── MOUSE ─────────────────────────────────────────────────────────────────

    private void tickMouse(ClientPlayerEntity player, MinecraftClient mc,
                           Entity target, float toTargetYaw, float toTargetPitch,
                           double dist) {
        mousePhaseTicks++;

        switch (mousePhase) {
            case FLICK -> {
                // fast turn: set a point 20-40° past target
                float overshoot = 20f + rng.nextFloat() * 20f;
                float flickYaw = toTargetYaw + overshoot * flickDirection;
                float flickPitch = toTargetPitch + (rng.nextFloat() - 0.5f) * 10f;
                WindMouseRotation.INSTANCE.setTarget(flickYaw, flickPitch);

                // transition when we're roughly past target (or time limit)
                if (mousePhaseTicks >= 2 + rng.nextInt(3)) {
                    enterMousePhase(MousePhase.CORRECT);
                }
            }
            case CORRECT -> {
                // WindMouse corrects back through target toward opposite side
                float correction = 5f + rng.nextFloat() * 10f;
                float correctYaw = toTargetYaw - correction * flickDirection;
                float correctPitch = toTargetPitch + (rng.nextFloat() - 0.5f) * 5f;
                WindMouseRotation.INSTANCE.setTarget(correctYaw, correctPitch);

                // CLICK: check MC's own crosshair raycast
                if (isLookingAtEntity(mc, target) && AttackTiming.canAttack(player, target)) {
                    mc.options.attackKey.setPressed(true);
                    attackKeyPressed = true;
                    didHitThisJump = true;
                }

                if (mousePhaseTicks >= 3 + rng.nextInt(3)) {
                    enterMousePhase(MousePhase.PAUSE);
                }
            }
            case PAUSE -> {
                // no rotation — human rests between mouse movements
                WindMouseRotation.INSTANCE.clearTarget();

                if (mousePhaseTicks >= 2 + rng.nextInt(4)) {
                    flickDirection = -flickDirection; // alternate sides
                    enterMousePhase(MousePhase.FLICK);
                }
            }
        }
    }

    private void enterMousePhase(MousePhase phase) {
        mousePhase = phase;
        mousePhaseTicks = 0;
    }

    /**
     * Check if MC's own crosshair raycast is targeting this entity.
     * This is the SAME check that a real mouse click uses.
     */
    private static boolean isLookingAtEntity(MinecraftClient mc, Entity target) {
        if (mc.crosshairTarget == null) return false;
        if (mc.crosshairTarget.getType() != net.minecraft.util.hit.HitResult.Type.ENTITY) return false;
        net.minecraft.util.hit.EntityHitResult ehr = (net.minecraft.util.hit.EntityHitResult) mc.crosshairTarget;
        return ehr.getEntity() == target;
    }

    // ── ground yaw with safety ────────────────────────────────────────────────

    private float computeGroundYaw(ClientPlayerEntity player, WorldView world,
                                    float toTargetYaw) {
        JumpResult jump = simulateJump(player, world, toTargetYaw);
        if (jump.safe && !jump.hitWall) return toTargetYaw;
        return findSafeJumpYaw(player, world, toTargetYaw);
    }

    private void decideTactic(double dist, boolean targetRunning,
                              boolean targetApproaching, boolean hasLOS) {
        if (!hasLOS) tactic = Tactic.ENGAGE;
        else if (targetRunning) tactic = Tactic.CHASE;
        else if (dist < CLOSE_RANGE || targetApproaching || didHitThisJump) {
            tactic = Tactic.STRAFE;
            strafeDir = -strafeDir;
        } else tactic = Tactic.ENGAGE;
    }

    // ── jump sim ──────────────────────────────────────────────────────────────

    private JumpResult simulateJump(ClientPlayerEntity player, WorldView world, float yaw) {
        Agent sim = Agent.of(player);
        sim.yaw = yaw;
        sim.keyForward = true;
        sim.keySprint = true;
        sim.keyJump = true;
        sim.keyBack = false;
        sim.keyLeft = strafeDir > 0;
        sim.keyRight = strafeDir < 0;
        sim.input = new AgentInput(sim);
        sim.input.playerInput = new TungstenPlayerInput(
            true, false, sim.keyLeft, sim.keyRight, true, false, true);

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
                    true, false, sim.keyLeft, sim.keyRight, false, false, true);
            }
            if (sim.horizontalCollision && !hitWall) {
                hitWall = true;
                if (t <= 3) return new JumpResult(false, arc, sim.getPos(), 0, true);
            }
            if (sim.velY < 0) passedApex = true;
            if (passedApex && sim.onGround && t > 2) {
                int fallH = (int) Math.max(0, startY - sim.posY);
                boolean safe = fallH <= MAX_SAFE_FALL && VoidDetector.isSafe(sim.getPos(), world);
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

    private float findSafeJumpYaw(ClientPlayerEntity player, WorldView world, float toTargetYaw) {
        float[] offsets = {0, 30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180};
        for (float off : offsets) {
            JumpResult r = simulateJump(player, world, toTargetYaw + off);
            if (r.safe && !r.hitWall) return toTargetYaw + off;
        }
        for (float off : offsets) {
            JumpResult r = simulateJump(player, world, toTargetYaw + off);
            if (r.safe) return toTargetYaw + off;
        }
        MinecraftClient.getInstance().options.jumpKey.setPressed(false);
        return toTargetYaw;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean hasLineOfSight(ClientPlayerEntity player, Vec3d targetPos) {
        net.minecraft.util.hit.HitResult hit = player.getWorld().raycast(
            new net.minecraft.world.RaycastContext(
                player.getEyePos(), targetPos,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE, player));
        return hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS;
    }

    // ── viz ────────────────────────────────────────────────────────────────────

    private void renderJumpArc(ClientPlayerEntity player, float yaw,
                               WorldView world, Vec3d targetPos) {
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
        JumpResult result = simulateJump(player, world, yaw);

        for (int i = 0; i < result.arc.size() - 1; i++) {
            Vec3d a = result.arc.get(i);
            Vec3d b = result.arc.get(i + 1);
            Color col = !VoidDetector.isSafe(b, world) ? COL_DANGER
                : result.hitWall ? COL_WALL : COL_SAFE;
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(a, b, col));
        }

        if (!result.arc.isEmpty()) {
            Vec3d end = result.arc.get(result.arc.size() - 1);
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                end.subtract(0.15, 0.15, 0.15), new Vec3d(0.3, 0.3, 0.3),
                result.safe ? COL_LANDING : COL_DANGER));
        }

        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
            targetPos.subtract(0.3, 0, 0.3), new Vec3d(0.6, 1.8, 0.6), COL_TARGET));

        Color tacCol = switch (tactic) {
            case ENGAGE -> COL_ENGAGE; case STRAFE -> COL_STRAFE; case CHASE -> COL_CHASE;
        };
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
            player.getPos().add(-0.15, 2.0, -0.15), new Vec3d(0.3, 0.3, 0.3), tacCol));
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
        mousePhase = MousePhase.FLICK;
        mousePhaseTicks = 0;
        tactic = Tactic.ENGAGE;
        wasOnGround = true;
        wtapCooldown = 0;
        didHitThisJump = false;
        prevTargetPos = null;
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
    }

    private record JumpResult(boolean safe, List<Vec3d> arc, Vec3d landingPos,
                               int fallHeight, boolean hitWall) {}
}
