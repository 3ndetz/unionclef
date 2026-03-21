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
import java.util.Random;

/**
 * Fighter-style PvP combat controller.
 *
 * Key insight: in Minecraft, yaw controls BOTH camera and movement.
 * So strafe = look at target + left/right keys, not yaw away.
 *
 * Tactics:
 *   ENGAGE — sprint-jump toward target, attack in flight
 *   STRAFE — look at target + jump with left/right keys to circle
 *   CHASE  — target running, sprint-jump straight at them
 *
 * Attack: only when crosshair is near target hitbox. Random aim point.
 * Safety: full arc simulation, fall height, wall collision, LOS.
 */
public class CombatController {

    // ── constants ─────────────────────────────────────────────────────────────
    private static final int JUMP_SIM_TICKS   = 20;
    private static final int MAX_SAFE_FALL    = 4;
    private static final double CLOSE_RANGE   = 3.5;
    private static final float AIM_TOLERANCE  = 25f;  // max degrees off target to attack

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

    // target tracking
    private Vec3d prevTargetPos = null;

    // aim randomization — pick a new point on hitbox per attack attempt
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
        Vec3d playerPos = player.getPos();
        Vec3d targetPos = target.getPos();
        double dist = playerPos.distanceTo(targetPos);
        float toTargetYaw = AttackTiming.yawTo(playerPos, targetPos);
        boolean onGround = player.isOnGround();

        // random aim point on target hitbox
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

        // ── check LOS to target ──────────────────────────────────────────────
        boolean hasLOS = hasLineOfSight(player, targetPos);

        // ── GROUND: decision point ────────────────────────────────────────────
        if (onGround) {
            if (!wasOnGround) {
                groundTicks = 0;
                decideTactic(dist, targetRunningAway, targetApproaching, hasLOS);
                aimOffset = newAimOffset(); // new aim point each jump
            }
            groundTicks++;

            switch (tactic) {
                case ENGAGE, CHASE -> tickGroundEngage(player, mc, world, toTargetYaw,
                    aimYaw, aimPitch, targetPos);
                case STRAFE -> tickGroundStrafe(player, mc, world, toTargetYaw,
                    aimYaw, aimPitch, targetPos);
            }

            didHitThisJump = false;
            airTicks = 0;

        } else {
            // ── AIR: committed to trajectory ──────────────────────────────────
            airTicks++;
            tickAir(player, mc, toTargetYaw, aimYaw, aimPitch);
        }

        wasOnGround = onGround;

        // ── ATTACK: only when facing target ──────────────────────────────────
        tryAttack(player, target, aimYaw, aimPitch);

        return true;
    }

    // ── ground ticks ──────────────────────────────────────────────────────────

    private void tickGroundEngage(ClientPlayerEntity player, MinecraftClient mc,
                                  WorldView world, float toTargetYaw,
                                  float aimYaw, float aimPitch, Vec3d targetPos) {
        // simulate jump toward target
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

        // yaw: movement direction = toward target (or safe alt)
        player.setYaw(safeYaw);

        // pitch: aim at random hitbox point
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
        // strafe: look at target + move sideways
        // yaw stays toward target (for attacking), movement via strafe keys

        // simulate strafe-jump to check safety
        JumpResult jump = simulateJump(player, world, toTargetYaw,
            true, strafeDir > 0);

        boolean canStrafe = jump.safe && !jump.hitWall;
        if (!canStrafe) {
            // try other direction
            strafeDir = -strafeDir;
            jump = simulateJump(player, world, toTargetYaw,
                true, strafeDir > 0);
            canStrafe = jump.safe && !jump.hitWall;
        }

        if (!canStrafe) {
            // strafe unsafe both sides → fall back to engage
            tactic = Tactic.ENGAGE;
            tickGroundEngage(player, mc, world, toTargetYaw, aimYaw, aimPitch, targetPos);
            return;
        }

        // look at target
        player.setYaw(aimYaw);
        player.setPitch(aimPitch);

        // strafe-jump
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.jumpKey.setPressed(true);
        mc.options.leftKey.setPressed(strafeDir > 0);
        mc.options.rightKey.setPressed(strafeDir < 0);
        mc.options.backKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        renderJumpArc(player, toTargetYaw, world, targetPos, true, strafeDir > 0);
    }

    private void tickAir(ClientPlayerEntity player, MinecraftClient mc,
                         float toTargetYaw, float aimYaw, float aimPitch) {
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.jumpKey.setPressed(false);

        // yaw: blend toward target (for aim + air control)
        float currentYaw = player.getYaw();
        float blend = Math.min(0.5f, airTicks / 5.0f);
        player.setYaw(lerpAngle(currentYaw, aimYaw, blend));

        // pitch: blend toward aim point
        float pitchBlend = Math.min(0.6f, airTicks / 4.0f);
        player.setPitch(lerpAngle(player.getPitch(), aimPitch, pitchBlend));

        // keep strafe keys from ground phase if strafing
        if (tactic == Tactic.STRAFE && airTicks <= 4) {
            mc.options.leftKey.setPressed(strafeDir > 0);
            mc.options.rightKey.setPressed(strafeDir < 0);
        } else {
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
        }
        mc.options.backKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    // ── tactic decision ───────────────────────────────────────────────────────

    private void decideTactic(double dist, boolean targetRunning,
                              boolean targetApproaching, boolean hasLOS) {
        if (!hasLOS) {
            // can't see target → engage (go around)
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

            // wall detection
            if (sim.horizontalCollision) {
                hitWall = true;
            }

            if (sim.velY < 0) passedApex = true;

            // landed?
            if (passedApex && sim.onGround && t > 2) {
                int fallH = (int) Math.max(0, startY - sim.posY);
                boolean safe = fallH <= MAX_SAFE_FALL
                    && VoidDetector.isSafe(sim.getPos(), world);
                return new JumpResult(safe, arc, sim.getPos(), fallH, hitWall);
            }

            // void during arc?
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

        // safe but with wall is better than unsafe
        for (float off : offsets) {
            float testYaw = toTargetYaw + off;
            JumpResult r = simulateJump(player, world, testYaw, true, false);
            if (r.safe) return testYaw;
        }

        // nothing safe → don't jump
        MinecraftClient.getInstance().options.jumpKey.setPressed(false);
        return toTargetYaw;
    }

    // ── attack ────────────────────────────────────────────────────────────────

    private void tryAttack(ClientPlayerEntity player, Entity target,
                           float aimYaw, float aimPitch) {
        if (!AttackTiming.canAttack(player, target)) return;

        // only attack when actually facing the target (within tolerance)
        float yawDiff = Math.abs(MathHelper.wrapDegrees(player.getYaw() - aimYaw));
        float pitchDiff = Math.abs(player.getPitch() - aimPitch);
        if (yawDiff > AIM_TOLERANCE || pitchDiff > AIM_TOLERANCE) return;

        float cooldown = player.getAttackCooldownProgress(0.5f);
        boolean isCrit = AttackTiming.isCritState(player);

        if (isCrit || cooldown >= 1.0f) {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.interactionManager.attackEntity(player, target);
            player.swingHand(Hand.MAIN_HAND);
            didHitThisJump = true;
            aimOffset = newAimOffset(); // new random point for next attack
        }
    }

    // ── aim randomization ─────────────────────────────────────────────────────

    private Vec3d newAimOffset() {
        return new Vec3d(
            (rng.nextDouble() - 0.5) * 2.0,  // -1..1 (width)
            0.3 + rng.nextDouble() * 0.5,     // 0.3..0.8 (height: torso area)
            (rng.nextDouble() - 0.5) * 2.0    // -1..1 (depth)
        );
    }

    // ── LOS check ─────────────────────────────────────────────────────────────

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

    private record JumpResult(boolean safe, List<Vec3d> arc, Vec3d landingPos,
                               int fallHeight, boolean hitWall) {}
}
