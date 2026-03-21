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
 * Fighter PvP — sweep-hit style.
 *
 * Movement: W + A/D + sprint + jump. Strafe keys used WITH forward,
 * not instead of yaw control.
 *
 * Air aim pattern (per jump, 3 sweeps):
 *   Sweep 1: WindMouse overshoots PAST target (misses on purpose)
 *   Sweep 2: WindMouse corrects BACK past target (misses again)
 *   Sweep 3: WindMouse sweeps through target → CLICK mid-sweep
 *
 * The click happens during a pass-through, not at convergence.
 * Anti-cheat sees natural mouse correction pattern, not lock-on.
 *
 * On landing: full 180° turn toward target for next jump.
 */
public class CombatController {

    // ── constants ─────────────────────────────────────────────────────────────
    private static final int JUMP_SIM_TICKS = 20;
    private static final int MAX_SAFE_FALL  = 4;
    private static final double CLOSE_RANGE = 3.5;

    // sweep overshoot (degrees past target center)
    private static final float OVERSHOOT_MIN = 12f;
    private static final float OVERSHOOT_MAX = 25f;

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

    // target tracking
    private Vec3d prevTargetPos = null;

    // aim: sweep state
    private final Random rng = new Random();
    private int sweepPhase = 0;       // 0=sweep1(overshoot), 1=sweep2(back), 2=sweep3(hit)
    private float sweepYawOffset = 0; // current overshoot offset
    private float sweepPitchOffset = 0;
    private boolean clickArmed = false; // true when sweep3 started, waiting for crosshair pass
    private int clickDelayTicks = 0;   // ticks to wait after crosshair passes target

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
        if (clickDelayTicks > 0) clickDelayTicks--;

        // ── target behavior ───────────────────────────────────────────────────
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
                // just landed — new jump cycle
                groundTicks = 0;
                decideTactic(dist, targetRunningAway, targetApproaching, hasLOS);
                initSweepCycle();
            }
            groundTicks++;

            // WindMouse: turn toward target for next jump (this is the "разворот")
            WindMouseRotation.INSTANCE.setTarget(toTargetYaw, toTargetPitch);

            // movement: W + A/D toward target
            float safeYaw = computeGroundYaw(player, world, toTargetYaw);
            player.setYaw(safeYaw);

            // w-tap on first ground tick after hit
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

            // strafe: A/D with W for diagonal approach
            mc.options.leftKey.setPressed(strafeDir > 0);
            mc.options.rightKey.setPressed(strafeDir < 0);
            mc.options.backKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);

            didHitThisJump = false;
            airTicks = 0;

            renderJumpArc(player, safeYaw, world, targetPos, true,
                strafeDir > 0);

        } else {
            // ── AIR: sweep aiming ─────────────────────────────────────────────
            airTicks++;

            // compute sweep target (not the entity — an offset point!)
            float sweepTargetYaw = toTargetYaw + sweepYawOffset;
            float sweepTargetPitch = toTargetPitch + sweepPitchOffset;

            // advance sweep phases based on WindMouse proximity
            double distToSweepTarget = angleDist(player.getYaw(), player.getPitch(),
                sweepTargetYaw, sweepTargetPitch);
            advanceSweep(distToSweepTarget, toTargetYaw, toTargetPitch);

            // WindMouse aims at sweep point (not entity!)
            WindMouseRotation.INSTANCE.setTarget(
                toTargetYaw + sweepYawOffset,
                toTargetPitch + sweepPitchOffset);

            // movement: W + A/D + sprint (no jump, already airborne)
            mc.options.forwardKey.setPressed(true);
            mc.options.sprintKey.setPressed(true);
            mc.options.jumpKey.setPressed(false);
            mc.options.leftKey.setPressed(strafeDir > 0);
            mc.options.rightKey.setPressed(strafeDir < 0);
            mc.options.backKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);

            // attack: click during sweep3 when crosshair passes through target
            if (clickArmed && !didHitThisJump && clickDelayTicks <= 0) {
                // check if crosshair is currently near target
                double crosshairToTarget = angleDist(player.getYaw(), player.getPitch(),
                    toTargetYaw, toTargetPitch);
                if (crosshairToTarget < 15.0 && AttackTiming.canAttack(player, target)) {
                    mc.options.attackKey.setPressed(true);
                    attackKeyPressed = true;
                    didHitThisJump = true;
                    // DON'T stop WindMouse — let it continue sweep past target
                }
            }
        }

        wasOnGround = onGround;
        return true;
    }

    // ── sweep cycle ───────────────────────────────────────────────────────────

    private void initSweepCycle() {
        sweepPhase = 0;
        // sweep 1: overshoot past target
        float overshootYaw = OVERSHOOT_MIN + rng.nextFloat() * (OVERSHOOT_MAX - OVERSHOOT_MIN);
        float overshootPitch = (rng.nextFloat() - 0.5f) * 10f;
        int dir = rng.nextBoolean() ? 1 : -1;
        sweepYawOffset = overshootYaw * dir;
        sweepPitchOffset = overshootPitch;
        clickArmed = false;
        clickDelayTicks = 0;
    }

    private void advanceSweep(double distToCurrentTarget, float toTargetYaw, float toTargetPitch) {
        if (distToCurrentTarget > 5.0) return; // not close to current sweep target yet

        sweepPhase++;
        switch (sweepPhase) {
            case 1 -> {
                // sweep 2: overshoot in opposite direction (smaller)
                sweepYawOffset = -sweepYawOffset * 0.6f;
                sweepPitchOffset = (rng.nextFloat() - 0.5f) * 6f;
            }
            case 2 -> {
                // sweep 3: through target — arm the click
                sweepYawOffset = sweepYawOffset * -0.3f; // small offset past target
                sweepPitchOffset = (rng.nextFloat() - 0.5f) * 3f;
                clickArmed = true;
                clickDelayTicks = 1 + rng.nextInt(2); // 1-2 tick delay (reaction time)
            }
            default -> {
                // past sweep 3: small drift, no more attacks this jump
                sweepYawOffset = (rng.nextFloat() - 0.5f) * 8f;
                sweepPitchOffset = (rng.nextFloat() - 0.5f) * 4f;
                clickArmed = false;
            }
        }
    }

    // ── ground yaw ────────────────────────────────────────────────────────────

    private float computeGroundYaw(ClientPlayerEntity player, WorldView world,
                                    float toTargetYaw) {
        JumpResult jump = simulateJump(player, world, toTargetYaw, true,
            strafeDir > 0);

        if (jump.safe && !jump.hitWall) return toTargetYaw;

        return findSafeJumpYaw(player, world, toTargetYaw);
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
        int wallHitTick = -1;

        for (int t = 0; t < JUMP_SIM_TICKS; t++) {
            sim.tick(world);
            arc.add(sim.getPos());

            if (t == 0) {
                sim.keyJump = false;
                sim.input.playerInput = new TungstenPlayerInput(
                    sim.keyForward, false, sim.keyLeft, sim.keyRight, false, false, true);
            }

            if (sim.horizontalCollision && !hitWall) {
                hitWall = true;
                wallHitTick = t;
            }

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
        clickDelayTicks = 0;
        clickArmed = false;
        didHitThisJump = false;
        prevTargetPos = null;
        TungstenModRenderContainer.COMBAT_TRAJECTORY.clear();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private record JumpResult(boolean safe, List<Vec3d> arc, Vec3d landingPos,
                               int fallHeight, boolean hitWall) {}
}
