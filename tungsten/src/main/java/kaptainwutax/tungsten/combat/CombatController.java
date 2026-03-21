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
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.ArrayList;
import java.util.List;

/**
 * State-machine PvP combat controller.
 *
 * Phases:
 *   CHASE      — sprint toward target, close distance
 *   JUMP_IN    — sprint-jump toward target for crit attack
 *   AIRBORNE   — in air after jump, aim at target, wait for hit timing
 *   COOLDOWN   — landed after hit, wait for attack cooldown to reset
 *
 * Rotation: WindMouse (render-tick smooth, human-like flicks).
 * Movement yaw: direct toward target (game tick).
 * Attack: only when in reach + cooldown ready. Prefer crit (falling).
 *
 * MPC safety: forward-sim current trajectory to detect void → emergency strafe.
 */
public class CombatController {

    // ── distances ─────────────────────────────────────────────────────────────
    private static final double JUMP_RANGE     = 4.5;  // start jump attack within this
    private static final double REACH          = 3.0;  // attack reach
    private static final double TOO_CLOSE      = 1.2;  // back off below this

    // ── sim for void check ────────────────────────────────────────────────────
    private static final int VOID_SIM_TICKS    = 15;

    // ── state machine ─────────────────────────────────────────────────────────
    private enum Phase { CHASE, JUMP_IN, AIRBORNE, COOLDOWN }
    private Phase phase = Phase.CHASE;
    private int phaseTicks = 0;

    // ── w-tap ─────────────────────────────────────────────────────────────────
    private int wtapCooldown = 0;
    private static final int WTAP_INTERVAL = 15;

    // ── viz colors ────────────────────────────────────────────────────────────
    private static final Color TRAJ_SAFE   = new Color(0, 220, 100);
    private static final Color TRAJ_DANGER = new Color(220, 60, 30);
    private static final Color TRAJ_BEST   = new Color(255, 200, 0);
    private static final Color TARGET_BOX  = new Color(255, 50, 50);

    // ───────────────────────────────────────────────────────────────────────────

    public boolean tick(ClientPlayerEntity player, Entity target, WorldView world) {
        if (target == null || target.isRemoved() || !target.isAlive()) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d playerPos = player.getPos();
        Vec3d targetPos = target.getPos();
        Vec3d targetEyes = target.getPos().add(0, target.getHeight() * 0.7, 0);
        double dist = playerPos.distanceTo(targetPos);
        float toTargetYaw = AttackTiming.yawTo(playerPos, targetPos);

        phaseTicks++;
        if (wtapCooldown > 0) wtapCooldown--;

        // ── void safety check: forward-sim current velocity ───────────────────
        boolean voidAhead = isVoidAhead(player, world);

        // ── emergency: void ahead → strafe away ──────────────────────────────
        if (voidAhead) {
            float safeYaw = findSafeYaw(player, world, toTargetYaw);
            player.setYaw(safeYaw);
            mc.options.forwardKey.setPressed(true);
            mc.options.sprintKey.setPressed(true);
            mc.options.jumpKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            // still aim at target for potential attacks
            WindMouseRotation.INSTANCE.setTarget(toTargetYaw,
                AttackTiming.pitchTo(player.getEyePos(), targetEyes));
            tryAttack(player, target, targetEyes);
            renderTrajectory(playerPos, safeYaw, world, targetPos);
            return true;
        }

        // ── state machine ─────────────────────────────────────────────────────
        switch (phase) {
            case CHASE:
                tickChase(player, target, mc, dist, toTargetYaw, targetEyes, world, targetPos);
                break;
            case JUMP_IN:
                tickJumpIn(player, target, mc, dist, toTargetYaw, targetEyes, world, targetPos);
                break;
            case AIRBORNE:
                tickAirborne(player, target, mc, dist, toTargetYaw, targetEyes, world, targetPos);
                break;
            case COOLDOWN:
                tickCooldown(player, target, mc, dist, toTargetYaw, targetEyes, world, targetPos);
                break;
        }

        return true;
    }

    // ── CHASE: sprint toward target, transition to JUMP_IN when close ─────────

    private void tickChase(ClientPlayerEntity player, Entity target, MinecraftClient mc,
                           double dist, float toTargetYaw, Vec3d targetEyes,
                           WorldView world, Vec3d targetPos) {
        // movement: straight at target
        player.setYaw(toTargetYaw);
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.jumpKey.setPressed(false);
        mc.options.backKey.setPressed(false);

        // aim at target via WindMouse
        WindMouseRotation.INSTANCE.setTarget(toTargetYaw,
            AttackTiming.pitchTo(player.getEyePos(), targetEyes));

        // transition: close enough + on ground → jump attack
        if (dist < JUMP_RANGE && player.isOnGround()) {
            setPhase(Phase.JUMP_IN);
        }

        // if somehow already in reach, still attack
        tryAttack(player, target, targetEyes);
        renderTrajectory(player.getPos(), toTargetYaw, world, targetPos);
    }

    // ── JUMP_IN: sprint-jump toward target ────────────────────────────────────

    private void tickJumpIn(ClientPlayerEntity player, Entity target, MinecraftClient mc,
                            double dist, float toTargetYaw, Vec3d targetEyes,
                            WorldView world, Vec3d targetPos) {
        // movement: sprint + jump at target
        player.setYaw(toTargetYaw);
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.jumpKey.setPressed(player.isOnGround());
        mc.options.backKey.setPressed(false);

        // aim at target
        WindMouseRotation.INSTANCE.setTarget(toTargetYaw,
            AttackTiming.pitchTo(player.getEyePos(), targetEyes));

        // w-tap: release sprint for 1 tick right before jumping for extra KB
        if (phaseTicks == 1 && wtapCooldown <= 0) {
            mc.options.sprintKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
            wtapCooldown = WTAP_INTERVAL;
        }

        // transition: left ground → airborne
        if (!player.isOnGround() && phaseTicks > 1) {
            setPhase(Phase.AIRBORNE);
        }

        // timeout: if we can't jump for some reason
        if (phaseTicks > 5) {
            setPhase(Phase.CHASE);
        }

        renderTrajectory(player.getPos(), toTargetYaw, world, targetPos);
    }

    // ── AIRBORNE: in air, aim at target, hit when falling + in reach ──────────

    private void tickAirborne(ClientPlayerEntity player, Entity target, MinecraftClient mc,
                              double dist, float toTargetYaw, Vec3d targetEyes,
                              WorldView world, Vec3d targetPos) {
        // movement: keep sprinting forward toward target
        player.setYaw(toTargetYaw);
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.jumpKey.setPressed(false);
        mc.options.backKey.setPressed(false);

        // aim: WindMouse toward target
        WindMouseRotation.INSTANCE.setTarget(toTargetYaw,
            AttackTiming.pitchTo(player.getEyePos(), targetEyes));

        // attack: try to hit (crit if falling)
        tryAttack(player, target, targetEyes);

        // transition: landed → cooldown
        if (player.isOnGround() && phaseTicks > 2) {
            setPhase(Phase.COOLDOWN);
        }

        // timeout: been airborne too long (launched off edge?)
        if (phaseTicks > 30) {
            setPhase(Phase.CHASE);
        }

        renderTrajectory(player.getPos(), toTargetYaw, world, targetPos);
    }

    // ── COOLDOWN: landed, wait for attack cooldown, reposition ────────────────

    private void tickCooldown(ClientPlayerEntity player, Entity target, MinecraftClient mc,
                              double dist, float toTargetYaw, Vec3d targetEyes,
                              WorldView world, Vec3d targetPos) {
        // if too close, back off slightly (strafe)
        if (dist < TOO_CLOSE) {
            player.setYaw(toTargetYaw + 180f);
            mc.options.forwardKey.setPressed(true);
            mc.options.sprintKey.setPressed(true);
        } else {
            // keep approaching if target is moving away
            player.setYaw(toTargetYaw);
            mc.options.forwardKey.setPressed(dist > REACH);
            mc.options.sprintKey.setPressed(dist > REACH);
        }
        mc.options.jumpKey.setPressed(false);
        mc.options.backKey.setPressed(false);

        // aim at target
        WindMouseRotation.INSTANCE.setTarget(toTargetYaw,
            AttackTiming.pitchTo(player.getEyePos(), targetEyes));

        // still attack if in range + cooldown ready (non-crit hits while grounded)
        tryAttack(player, target, targetEyes);

        // transition: cooldown ready + on ground → jump again
        float cooldown = player.getAttackCooldownProgress(0.5f);
        if (cooldown >= 0.9f && player.isOnGround() && phaseTicks > 3) {
            if (dist < JUMP_RANGE) {
                setPhase(Phase.JUMP_IN);
            } else {
                setPhase(Phase.CHASE);
            }
        }

        // timeout
        if (phaseTicks > 20) {
            setPhase(Phase.CHASE);
        }

        renderTrajectory(player.getPos(), player.getYaw(), world, targetPos);
    }

    // ── attack logic ──────────────────────────────────────────────────────────

    private void tryAttack(ClientPlayerEntity player, Entity target, Vec3d targetEyes) {
        if (!AttackTiming.canAttack(player, target)) return;

        float cooldown = player.getAttackCooldownProgress(0.5f);
        boolean isCrit = AttackTiming.isCritState(player);

        // attack if full cooldown, or crit opportunity (even if slightly early)
        if (cooldown >= 1.0f || (isCrit && cooldown >= 0.9f)) {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.interactionManager.attackEntity(player, target);
            player.swingHand(Hand.MAIN_HAND);
        }
    }

    // ── void safety ───────────────────────────────────────────────────────────

    private boolean isVoidAhead(ClientPlayerEntity player, WorldView world) {
        Agent sim = Agent.of(player);
        sim.keyForward = true;
        sim.keySprint = true;
        sim.keyJump = false;
        sim.input = new AgentInput(sim);
        sim.input.playerInput = new TungstenPlayerInput(
            true, false, false, false, false, false, true);

        for (int t = 0; t < VOID_SIM_TICKS; t++) {
            sim.tick(world);
            if (!VoidDetector.isSafe(sim.getPos(), world)) return true;
        }
        return false;
    }

    private float findSafeYaw(ClientPlayerEntity player, WorldView world, float targetYaw) {
        // try yaws fanning out from target direction, pick first safe one
        float[] offsets = {0, 45, -45, 90, -90, 135, -135, 180};
        for (float off : offsets) {
            float testYaw = targetYaw + off;
            Agent sim = Agent.of(player);
            sim.yaw = testYaw;
            sim.keyForward = true;
            sim.keySprint = true;
            sim.input = new AgentInput(sim);
            sim.input.playerInput = new TungstenPlayerInput(
                true, false, false, false, false, false, true);

            boolean safe = true;
            for (int t = 0; t < VOID_SIM_TICKS; t++) {
                sim.tick(world);
                if (!VoidDetector.isSafe(sim.getPos(), world)) { safe = false; break; }
            }
            if (safe) return testYaw;
        }
        // no safe direction found, back away from target
        return targetYaw + 180f;
    }

    // ── visualization ─────────────────────────────────────────────────────────

    private void renderTrajectory(Vec3d startPos, float yaw, WorldView world, Vec3d targetPos) {
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();

        // forward-sim current direction to show predicted path
        Agent sim = new Agent();
        // minimal init for viz
        sim.posX = startPos.x; sim.posY = startPos.y; sim.posZ = startPos.z;
        sim.blockX = MathHelper.floor(startPos.x);
        sim.blockY = MathHelper.floor(startPos.y);
        sim.blockZ = MathHelper.floor(startPos.z);
        sim.box = Agent.STANDING_DIMENSIONS.getBoxAt(startPos);
        sim.dimensions = Agent.STANDING_DIMENSIONS;
        sim.standingEyeHeight = 1.62f;
        sim.onGround = true;
        sim.yaw = yaw;
        sim.keyForward = true;
        sim.keySprint = true;
        sim.movementSpeed = 0.1f;
        sim.airStrafingSpeed = 0.026f;
        sim.stepHeight = 0.6f;
        sim.input = new AgentInput(sim);
        sim.input.playerInput = new TungstenPlayerInput(
            true, false, false, false, false, false, true);

        List<Vec3d> points = new ArrayList<>();
        points.add(startPos);
        for (int t = 0; t < 20; t++) {
            sim.tick(world);
            points.add(sim.getPos());
        }

        // draw lines
        for (int i = 0; i < points.size() - 1; i++) {
            float t = (float) i / (points.size() - 1);
            int r = (int) (TRAJ_SAFE.getRed()   + t * (TRAJ_DANGER.getRed()   - TRAJ_SAFE.getRed()));
            int g = (int) (TRAJ_SAFE.getGreen() + t * (TRAJ_DANGER.getGreen() - TRAJ_SAFE.getGreen()));
            int b = (int) (TRAJ_SAFE.getBlue()  + t * (TRAJ_DANGER.getBlue()  - TRAJ_SAFE.getBlue()));
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
                new Line(points.get(i), points.get(i + 1), new Color(r, g, b)));
        }

        // endpoint
        Vec3d end = points.get(points.size() - 1);
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
            new Cuboid(end.subtract(0.1, 0.1, 0.1), new Vec3d(0.2, 0.2, 0.2), TRAJ_BEST));

        // target box
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
            new Cuboid(targetPos.subtract(0.3, 0, 0.3), new Vec3d(0.6, 1.8, 0.6), TARGET_BOX));

        // phase indicator: small cube at player pos, color = phase
        Color phaseColor = switch (phase) {
            case CHASE -> new Color(100, 100, 255);    // blue
            case JUMP_IN -> new Color(255, 255, 0);    // yellow
            case AIRBORNE -> new Color(255, 100, 0);   // orange
            case COOLDOWN -> new Color(150, 150, 150); // gray
        };
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(
            new Cuboid(startPos.add(-0.15, 1.9, -0.15), new Vec3d(0.3, 0.3, 0.3), phaseColor));
    }

    // ── phase management ──────────────────────────────────────────────────────

    private void setPhase(Phase newPhase) {
        this.phase = newPhase;
        this.phaseTicks = 0;
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
        WindMouseRotation.INSTANCE.clearTarget();
        phase = Phase.CHASE;
        phaseTicks = 0;
        wtapCooldown = 0;
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
    }
}
