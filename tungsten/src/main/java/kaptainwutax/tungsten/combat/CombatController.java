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
 * Simple fighter PvP.
 *
 * Ground: set jump direction directly (NO WindMouse, no tracking).
 * Air: WindMouse sweeps PAST target. Click when crosshair crosses hitbox.
 * Between jumps: no rotation at all (pause).
 */
public class CombatController {

    private static final int JUMP_SIM_TICKS = 20;
    private static final int MAX_SAFE_FALL  = 4;
    private static final double CLOSE_RANGE = 3.5;

    // ── state ─────────────────────────────────────────────────────────────────
    private enum Tactic { ENGAGE, STRAFE, CHASE }
    private Tactic tactic = Tactic.ENGAGE;

    private boolean didHitThisJump = false;
    private boolean wasOnGround = true;
    private int airTicks = 0;
    private int strafeDir = 1;
    private int groundTicks = 0;

    private int wtapCooldown = 0;
    private boolean attackKeyPressed = false;
    private Vec3d prevTargetPos = null;
    private final Random rng = new Random();

    // sweep: one point PAST target that WindMouse aims at
    private float sweepTargetYaw = 0;
    private float sweepTargetPitch = 0;
    private boolean hasCrossedTarget = false; // crosshair passed through target zone
    private int ticksSinceCross = 0;

    // viz
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
            double len = targetPos.subtract(playerPos).length();
            if (len > 0.1) {
                double dot = targetVel.dotProduct(targetPos.subtract(playerPos).multiply(1.0 / len));
                targetRunningAway = dot > 0.05;
                targetApproaching = dot < -0.05;
            }
        }
        prevTargetPos = targetPos;

        boolean hasLOS = hasLineOfSight(player, targetPos);

        // ── GROUND ────────────────────────────────────────────────────────────
        if (onGround) {
            if (!wasOnGround) {
                groundTicks = 0;
                decideTactic(dist, targetRunningAway, targetApproaching, hasLOS);
            }
            groundTicks++;

            // NO WindMouse on ground — no constant tracking
            WindMouseRotation.INSTANCE.clearTarget();

            // set jump direction directly
            float jumpYaw = computeGroundYaw(player, world, toTargetYaw);
            player.setYaw(jumpYaw);
            // pitch: roughly toward target (not precise)
            player.setPitch(toTargetPitch + (rng.nextFloat() - 0.5f) * 10f);

            // compute sweep target for the upcoming air phase:
            // a point 15-30° PAST target (WindMouse will sweep through target toward it)
            float overshoot = 15f + rng.nextFloat() * 15f;
            int side = rng.nextBoolean() ? 1 : -1;
            sweepTargetYaw = toTargetYaw + overshoot * side;
            sweepTargetPitch = toTargetPitch + (rng.nextFloat() - 0.5f) * 8f;
            hasCrossedTarget = false;
            ticksSinceCross = 0;

            // w-tap
            boolean doWtap = wtapCooldown <= 0 && didHitThisJump && groundTicks == 1;
            if (doWtap) {
                mc.options.sprintKey.setPressed(false);
                mc.options.forwardKey.setPressed(false);
                mc.options.jumpKey.setPressed(false);
                wtapCooldown = 12;
            } else {
                mc.options.forwardKey.setPressed(true);
                mc.options.sprintKey.setPressed(true);
                mc.options.jumpKey.setPressed(true);
            }

            // A/D strafe with W
            mc.options.leftKey.setPressed(strafeDir > 0);
            mc.options.rightKey.setPressed(strafeDir < 0);
            mc.options.backKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);

            didHitThisJump = false;
            airTicks = 0;

            renderJumpArc(player, jumpYaw, world, targetPos);

        } else {
            // ── AIR ───────────────────────────────────────────────────────────
            airTicks++;

            if (airTicks <= 2) {
                // first 2 ticks: no rotation, natural drift from jump
                WindMouseRotation.INSTANCE.clearTarget();
            } else if (!didHitThisJump) {
                // WindMouse sweeps toward the OVERSHOOT point (past target)
                // crosshair will naturally pass through target on the way
                WindMouseRotation.INSTANCE.setTarget(sweepTargetYaw, sweepTargetPitch);
            } else {
                // after hit: stop aiming, let crosshair drift
                WindMouseRotation.INSTANCE.clearTarget();
            }

            mc.options.forwardKey.setPressed(true);
            mc.options.sprintKey.setPressed(true);
            mc.options.jumpKey.setPressed(false);
            mc.options.leftKey.setPressed(strafeDir > 0);
            mc.options.rightKey.setPressed(strafeDir < 0);
            mc.options.backKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);

            // ── click detection: did crosshair pass through target? ───────────
            if (!didHitThisJump && airTicks > 2) {
                double crosshairToTarget = angleDist(
                    player.getYaw(), player.getPitch(),
                    toTargetYaw, toTargetPitch);

                if (crosshairToTarget < 18.0) {
                    if (!hasCrossedTarget) {
                        hasCrossedTarget = true;
                        ticksSinceCross = 0;
                    }
                }

                if (hasCrossedTarget) {
                    ticksSinceCross++;

                    // click 1-2 ticks after entering target zone (reaction time)
                    // NOT immediately — that's suspicious
                    if (ticksSinceCross >= 1 + rng.nextInt(2)) {
                        if (AttackTiming.canAttack(player, target)) {
                            mc.options.attackKey.setPressed(true);
                            attackKeyPressed = true;
                            didHitThisJump = true;
                        }
                    }
                }
            }
        }

        wasOnGround = onGround;
        return true;
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

    // ── jump simulation ───────────────────────────────────────────────────────

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

    private static double angleDist(float yaw1, float pitch1, float yaw2, float pitch2) {
        float dy = Math.abs(MathHelper.wrapDegrees(yaw1 - yaw2));
        float dp = Math.abs(pitch1 - pitch2);
        return Math.sqrt(dy * dy + dp * dp);
    }

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
        tactic = Tactic.ENGAGE;
        wasOnGround = true;
        airTicks = 0;
        groundTicks = 0;
        wtapCooldown = 0;
        didHitThisJump = false;
        hasCrossedTarget = false;
        ticksSinceCross = 0;
        prevTargetPos = null;
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
    }

    private record JumpResult(boolean safe, List<Vec3d> arc, Vec3d landingPos,
                               int fallHeight, boolean hitWall) {}
}
