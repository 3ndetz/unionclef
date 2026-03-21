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
 * Fighter PvP with human-like aiming.
 *
 * Aim cycle per jump:
 *   1. JUMP (ground) — snap yaw approximately toward target (±15° spread)
 *   2. DRIFT (air, first ticks) — no aim correction, crosshair drifts naturally
 *   3. AIM (air, mid-flight) — WindMouse starts moving toward target
 *   4. ATTACK (air, WindMouse close enough) — LKM click with random delay
 *   5. RELEASE (after hit) — WindMouse cleared, crosshair drifts away
 *
 * Not aiming at target 100% of the time. Approximate, with gaps.
 */
public class CombatController {

    // ── constants ─────────────────────────────────────────────────────────────
    private static final int JUMP_SIM_TICKS   = 20;
    private static final int MAX_SAFE_FALL    = 4;
    private static final double CLOSE_RANGE   = 3.5;
    private static final double AIM_CLOSE_DEG = 8.0;

    // aim timing (in air ticks, 20tps)
    private static final int DRIFT_TICKS      = 3;   // no aiming after jump
    private static final int AIM_START_TICK    = 4;   // WindMouse starts here

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

    // attack
    private boolean attackKeyPressed = false;
    private int attackDelay = 0; // random delay before clicking

    // target tracking
    private Vec3d prevTargetPos = null;

    // aim
    private final Random rng = new Random();
    private Vec3d aimOffset = newAimOffset();
    private float jumpSpread = 0; // random yaw offset for this jump

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

        // aim point on hitbox (randomized per jump)
        Vec3d aimPoint = targetPos.add(
            aimOffset.x * target.getWidth() * 0.4,
            aimOffset.y * target.getHeight(),
            aimOffset.z * target.getWidth() * 0.4);
        float aimYaw = AttackTiming.yawTo(playerPos, aimPoint);
        float aimPitch = AttackTiming.pitchTo(player.getEyePos(), aimPoint);

        if (wtapCooldown > 0) wtapCooldown--;
        if (attackDelay > 0) attackDelay--;

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

        // ── GROUND ────────────────────────────────────────────────────────────
        if (onGround) {
            if (!wasOnGround) {
                // just landed
                groundTicks = 0;
                decideTactic(dist, targetRunningAway, targetApproaching, hasLOS);
                aimOffset = newAimOffset();
                // random spread: don't aim perfectly at target each jump
                jumpSpread = (rng.nextFloat() - 0.5f) * 30f; // ±15°
                // random delay before next attack (1-4 ticks after aim starts)
                attackDelay = 1 + rng.nextInt(4);
            }
            groundTicks++;

            // GROUND: WindMouse released (drift between jumps)
            WindMouseRotation.INSTANCE.clearTarget();

            switch (tactic) {
                case ENGAGE, CHASE -> tickGroundEngage(player, mc, world,
                    toTargetYaw, aimPitch, targetPos);
                case STRAFE -> tickGroundStrafe(player, mc, world,
                    toTargetYaw, aimYaw, aimPitch, targetPos);
            }

            didHitThisJump = false;
            airTicks = 0;

        } else {
            // ── AIR ───────────────────────────────────────────────────────────
            airTicks++;

            // aim phases:
            if (airTicks <= DRIFT_TICKS) {
                // DRIFT: no aim correction, crosshair naturally moves with momentum
                // WindMouse stays cleared from ground phase
            } else if (!didHitThisJump) {
                // AIM: WindMouse starts targeting
                WindMouseRotation.INSTANCE.setTarget(aimYaw, aimPitch);
            } else {
                // RELEASE: after hit, stop aiming — crosshair drifts
                WindMouseRotation.INSTANCE.clearTarget();
            }

            // movement
            mc.options.forwardKey.setPressed(true);
            mc.options.sprintKey.setPressed(true);
            mc.options.jumpKey.setPressed(false);

            if (tactic == Tactic.STRAFE && airTicks <= 5) {
                mc.options.leftKey.setPressed(strafeDir > 0);
                mc.options.rightKey.setPressed(strafeDir < 0);
            } else {
                mc.options.leftKey.setPressed(false);
                mc.options.rightKey.setPressed(false);
            }
            mc.options.backKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);

            // ATTACK: only after aim phase started + delay expired + close enough
            if (airTicks >= AIM_START_TICK && attackDelay <= 0 && !didHitThisJump) {
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
        // approximate jump direction: target ± spread
        float jumpYaw = toTargetYaw + jumpSpread;

        JumpResult jump = simulateJump(player, world, jumpYaw, true, false);
        float safeYaw;

        if (jump.safe && !jump.hitWall) {
            safeYaw = jumpYaw;
        } else {
            // spread direction unsafe, try exact target direction
            jump = simulateJump(player, world, toTargetYaw, true, false);
            if (jump.safe && !jump.hitWall) {
                safeYaw = toTargetYaw;
            } else {
                safeYaw = findSafeJumpYaw(player, world, toTargetYaw);
            }
        }

        // w-tap
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

        player.setYaw(aimYaw + jumpSpread * 0.5f); // approximate look
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

    // ── tactic ────────────────────────────────────────────────────────────────

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

        // only when aimed close enough
        double aimDist = WindMouseRotation.INSTANCE.distanceToTarget(player);
        if (aimDist > AIM_CLOSE_DEG) return;

        float cooldown = player.getAttackCooldownProgress(0.5f);
        boolean isCrit = AttackTiming.isCritState(player);

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
        // Agent.of(player) captures real velocity — including knockback
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
        int wallHitTick = -1;

        for (int t = 0; t < JUMP_SIM_TICKS; t++) {
            sim.tick(world);
            arc.add(sim.getPos());

            if (t == 0) {
                sim.keyJump = false;
                sim.input.playerInput = new TungstenPlayerInput(
                    sim.keyForward, false, sim.keyLeft, sim.keyRight, false, false, true);
            }

            // wall: mark first collision tick
            if (sim.horizontalCollision && !hitWall) {
                hitWall = true;
                wallHitTick = t;
            }

            // early bail: hit wall in first 3 ticks = blocked path
            if (hitWall && wallHitTick <= 3) {
                return new JumpResult(false, arc, sim.getPos(), 0, true);
            }

            if (sim.velY < 0) passedApex = true;

            if (passedApex && sim.onGround && t > 2) {
                int fallH = (int) Math.max(0, startY - sim.posY);
                boolean safe = fallH <= MAX_SAFE_FALL
                    && VoidDetector.isSafe(sim.getPos(), world);
                return new JumpResult(safe, arc, sim.getPos(), fallH, hitWall);
            }

            // void during arc
            if (passedApex && !VoidDetector.isSafe(sim.getPos(), world)) {
                return new JumpResult(false, arc, sim.getPos(), MAX_SAFE_FALL + 1, hitWall);
            }
        }

        // didn't land
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

        // nothing safe — don't jump
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
        attackDelay = 0;
        didHitThisJump = false;
        prevTargetPos = null;
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private record JumpResult(boolean safe, List<Vec3d> arc, Vec3d landingPos,
                               int fallHeight, boolean hitWall) {}
}
