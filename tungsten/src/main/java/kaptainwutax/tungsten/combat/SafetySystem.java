package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import kaptainwutax.tungsten.render.Line;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * Combat safety + stage machine + aim prediction.
 *
 * Runs at RENDER FREQUENCY (~60 FPS).
 * tick() — enemy velocity tracking (fixed dt).
 * renderUpdate() — stage evaluation, braking, viz, aim prediction output.
 */
public class SafetySystem {

    // ── colors ──────────────────────────────────────────────────────────────
    private static final Color COL_PLAYER_VEL     = new Color(50, 220, 50);
    private static final Color COL_ENEMY_VEL      = new Color(220, 50, 50);
    private static final Color COL_DANGER          = new Color(255, 160, 0);
    private static final Color COL_VOID            = new Color(255, 0, 0);
    private static final Color COL_SAFE            = new Color(50, 200, 100);
    private static final Color COL_KB_DANGER       = new Color(255, 80, 200);
    private static final Color COL_KB_OPPORTUNITY  = new Color(0, 255, 255);
    private static final Color COL_AIM_PREDICT     = new Color(255, 255, 100);

    // ── constants ───────────────────────────────────────────────────────────
    private static final int PREDICT_TICKS = 10;

    private static final int KB_PREDICT_TICKS = 15;
    private static final int KB_FALL_THRESHOLD = 2;

    // ── state ───────────────────────────────────────────────────────────────
    private Vec3d prevEnemyPos = null;
    private Vec3d enemyVelocity = Vec3d.ZERO;
    private Entity target = null;

    private final CombatPathfinder pathfinder = new CombatPathfinder();
    private final KnockbackEstimator kbEstimator = new KnockbackEstimator();

    private CombatStage stage = CombatStage.PURSUE;
    private CombatStage prevStage = null;

    // KB analysis
    private Vec3d lastUsAfterKB = null;
    private int lastFallIfHit = 0;
    private Vec3d lastEnemyAfterKB = null;
    private int lastEnemyFallIfHit = 0;

    // aim prediction output — read by CombatController
    private float aimYaw = 0;
    private float aimPitch = 0;

    // braking/repositioning output
    private float brakeYaw = 0;
    private boolean braking = false;
    private boolean repositioning = false;
    private boolean wasBrakingLastFrame = false;
    private boolean wasRepositioningLastFrame = false;
    private boolean wantsJump = false;

    // movement output — legs direction from BFS path
    private float movementYaw = 0;
    private boolean movementActive = false;

    // post-imminent cooldown: block movement for N frames after braking ends
    private int postImminentCooldown = 0;
    private static final int POST_IMMINENT_COOLDOWN_FRAMES = 40; // ~0.7 sec

    private boolean active = false;
    private int logCooldown = 0;

    // ── tick (20 TPS): enemy velocity tracking ──────────────────────────────

    public void tick(ClientPlayerEntity player, Entity target, WorldView world) {
        this.target = target;
        active = true;

        Vec3d targetPos = target.getPos();
        if (prevEnemyPos != null) {
            enemyVelocity = targetPos.subtract(prevEnemyPos);
        }
        prevEnemyPos = targetPos;

        // KB estimator: track enemy sprint state + enchants
        kbEstimator.tick(target, enemyVelocity);

        // pathfinder updates every N ticks
        pathfinder.tick(player.getBlockPos(), target.getBlockPos(), enemyVelocity, world);
    }

    // ── render update (~60 FPS): stage + decisions + viz ─────────────────────

    public void renderUpdate(float tickDelta) {
        if (!active) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || target == null || target.isRemoved()) return;

        wasBrakingLastFrame = braking;
        wasRepositioningLastFrame = repositioning;
        braking = false;
        movementActive = false;
        repositioning = false;
        wantsJump = false;
        if (postImminentCooldown > 0) postImminentCooldown--;
        TungstenModRenderContainer.COMBAT_TRAJECTORY =
                java.util.Collections.synchronizedCollection(new java.util.ArrayList<>());
        if (logCooldown > 0) logCooldown--;

        // tick-accurate positions for logic (block grid checks)
        Vec3d playerVel = player.getVelocity();
        Vec3d playerPosTick = player.getPos();
        Vec3d targetPosTick = target.getPos();
        double horizSpeed = Math.sqrt(playerVel.x * playerVel.x + playerVel.z * playerVel.z);

        // interpolated positions for smooth visualization
        Vec3d playerPos = playerPosTick.add(playerVel.multiply(tickDelta));
        Vec3d targetPos = targetPosTick.add(enemyVelocity.multiply(tickDelta));

        // predicted positions (from interpolated for smooth viz)
        Vec3d playerPredicted = playerPos.add(playerVel.multiply(PREDICT_TICKS));
        Vec3d enemyPredicted = targetPos.add(enemyVelocity.multiply(PREDICT_TICKS));

        // terrain checks use tick positions (block grid)
        Vec3d playerPredictedTick = playerPosTick.add(playerVel.multiply(PREDICT_TICKS));
        int fallAtPredicted = VoidDetector.fallHeight(playerPredictedTick, player.getWorld());
        int fallAtCurrent = VoidDetector.fallHeight(playerPosTick, player.getWorld());
        DangerLevel dangerPredicted = DangerLevel.fromFallHeight(fallAtPredicted);
        DangerLevel dangerCurrent = DangerLevel.fromFallHeight(fallAtCurrent);

        // KB analysis uses tick positions
        analyzeKnockback(playerPosTick, playerVel, targetPosTick, player.getWorld());

        // ── evaluate stage ───────────────────────────────────────────────
        CombatStage newStage = evaluateStage(player, playerVel, horizSpeed,
                dangerPredicted, dangerCurrent);
        if (newStage != stage) {
            stage = newStage;
            if (prevStage != stage) {
                Debug.logMessage(stage.chatColor() + "COMBAT: → " + stage.name());
                prevStage = stage;
            }
        }

        // ── aim prediction (for mouse subsystem) ────────────────────────
        computeAimPrediction(player, targetPos);

        // ── stage-specific behavior (only if saver enabled) ──────────────
        boolean saverEnabled = kaptainwutax.tungsten.TungstenConfig.get().combatSaverEnabled;

        if (saverEnabled) {
            switch (stage) {
                case DANGER_IMMINENT -> {
                    braking = true;
                    postImminentCooldown = POST_IMMINENT_COOLDOWN_FRAMES;
                    float velYaw = (float) Math.toDegrees(-Math.atan2(playerVel.x, playerVel.z));
                    brakeYaw = velYaw + 180f;

                    // sprint + W + jump opposite to velocity
                    if (horizSpeed > 0.05 && player.isOnGround()) {
                        wantsJump = true;
                    }

                    mc.options.forwardKey.setPressed(true);
                    mc.options.sprintKey.setPressed(true);
                    mc.options.sneakKey.setPressed(false);
                    mc.options.jumpKey.setPressed(wantsJump);
                    mc.options.backKey.setPressed(false);
                    mc.options.leftKey.setPressed(false);
                    mc.options.rightKey.setPressed(false);
                }
                case DANGER_BATTLE -> {
                    // reposition toward retreat path if KB fall is serious
                    DangerLevel kbDanger = DangerLevel.fromFallHeight(lastFallIfHit);
                    java.util.List<net.minecraft.util.math.BlockPos> retreat = pathfinder.getRetreatPath();
                    if (kbDanger.isSerious() && retreat.size() >= 2) {
                        repositioning = true;
                        // aim legs toward first retreat waypoint (not first = our pos)
                        net.minecraft.util.math.BlockPos waypoint = retreat.get(Math.min(2, retreat.size() - 1));
                        Vec3d wpPos = Vec3d.ofBottomCenter(waypoint);
                        brakeYaw = AttackTiming.yawTo(playerPosTick, wpPos);

                        // walk (no jump — risky near edges), sprint
                        mc.options.forwardKey.setPressed(true);
                        mc.options.sprintKey.setPressed(true);
                        mc.options.jumpKey.setPressed(false);
                        mc.options.backKey.setPressed(false);
                        mc.options.leftKey.setPressed(false);
                        mc.options.rightKey.setPressed(false);
                        mc.options.sneakKey.setPressed(false);
                    }
                    // if no retreat path — stay and fight, don't panic
                }
                case PURSUE, ESCAPE, DELICATE_BATTLE -> {
                    // no key override from saver
                }
            }
        }

        // ── movement: follow BFS attack path (if enabled + not braking/repositioning/cooldown) ──
        boolean movementsEnabled = kaptainwutax.tungsten.TungstenConfig.get().combatMovementsEnabled;
        if (movementsEnabled && !braking && !repositioning && postImminentCooldown <= 0) {
            java.util.List<net.minecraft.util.math.BlockPos> attackPath = pathfinder.getAttackPath();
            if (attackPath.size() >= 2) {
                // find next waypoint we haven't reached yet
                net.minecraft.util.math.BlockPos nextWp = null;
                for (int i = 1; i < attackPath.size(); i++) {
                    if (playerPosTick.squaredDistanceTo(Vec3d.ofBottomCenter(attackPath.get(i))) > 1.5) {
                        nextWp = attackPath.get(i);
                        break;
                    }
                }
                if (nextWp == null) nextWp = attackPath.get(attackPath.size() - 1);

                // check if next waypoint is safe
                int wpFall = VoidDetector.fallHeight(Vec3d.ofBottomCenter(nextWp), player.getWorld());
                DangerLevel wpDanger = DangerLevel.fromFallHeight(wpFall);

                if (!wpDanger.isSerious()) {
                    // face toward waypoint for movement (legs direction)
                    // WindMouse handles mouse aim separately — W goes where player faces
                    // So we set a movement yaw that CombatController can use
                    movementYaw = AttackTiming.yawTo(playerPosTick, Vec3d.ofBottomCenter(nextWp));
                    movementActive = true;

                    mc.options.forwardKey.setPressed(true);
                    mc.options.sprintKey.setPressed(true);
                    mc.options.backKey.setPressed(false);
                    mc.options.leftKey.setPressed(false);
                    mc.options.rightKey.setPressed(false);
                    mc.options.sneakKey.setPressed(false);
                    if (player.isOnGround()) {
                        mc.options.jumpKey.setPressed(true);
                    } else {
                        mc.options.jumpKey.setPressed(false);
                    }
                }
                // if waypoint is dangerous, don't move — stay and fight
            }
        }

        // release keys when braking/repositioning ended THIS frame
        if ((!braking && wasBrakingLastFrame || !repositioning && wasRepositioningLastFrame)
                && !kaptainwutax.tungsten.TungstenConfig.get().combatMovementsEnabled) {
            mc.options.forwardKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
        }

        // ── visualization ────────────────────────────────────────────────
        renderVelocity(playerPos, playerVel, playerPredicted, COL_PLAYER_VEL);
        renderVelocity(targetPos, enemyVelocity, enemyPredicted, COL_ENEMY_VEL);

        // fall danger marker
        if (dangerPredicted != DangerLevel.NONE) {
            Color dangerCol = dangerPredicted.isSerious() ? COL_VOID : COL_DANGER;
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    playerPredicted.subtract(0.4, 0, 0.4), new Vec3d(0.8, 0.1, 0.8), dangerCol));
        } else {
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    playerPredicted.subtract(0.2, 0, 0.2), new Vec3d(0.4, 0.1, 0.4), COL_SAFE));
        }

        // KB viz
        if (lastUsAfterKB != null && lastFallIfHit >= KB_FALL_THRESHOLD) {
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(
                    playerPos.add(0, 1, 0), lastUsAfterKB.add(0, 1, 0), COL_KB_DANGER));
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    lastUsAfterKB.subtract(0.3, 0, 0.3), new Vec3d(0.6, 0.1, 0.6), COL_KB_DANGER));
        }
        if (lastEnemyAfterKB != null && lastEnemyFallIfHit >= KB_FALL_THRESHOLD) {
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(
                    targetPos.add(0, 1, 0), lastEnemyAfterKB.add(0, 1, 0), COL_KB_OPPORTUNITY));
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    lastEnemyAfterKB.subtract(0.3, 0, 0.3), new Vec3d(0.6, 0.1, 0.6), COL_KB_OPPORTUNITY));
        }

        // aim prediction marker
        Vec3d aimTarget = targetPos.add(0, target.getHeight() * 0.5, 0)
                .add(enemyVelocity.multiply(getAimLeadTicks()));
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                aimTarget.subtract(0.1, 0.1, 0.1), new Vec3d(0.2, 0.2, 0.2), COL_AIM_PREDICT));

        // combat paths visualization
        pathfinder.renderUpdate(tickDelta);
    }

    // ── stage evaluation ─────────────────────────────────────────────────────

    private CombatStage evaluateStage(ClientPlayerEntity player, Vec3d playerVel,
                                       double horizSpeed, DangerLevel dangerPredicted, DangerLevel dangerCurrent) {
        // DANGER_IMMINENT: predicted serious fall, BUT only if we're actually
        // in danger — not during a normal jump that will land safely.
        // Require: current pos also not safe, OR we're falling hard (velY < -0.3)
        if (dangerPredicted.isSerious() && horizSpeed > 0.02
                && (dangerCurrent != DangerLevel.NONE || playerVel.y < -0.3)) {
            return CombatStage.DANGER_IMMINENT;
        }
        // Already falling into serious danger (velY < -0.3 = past jump apex, actually falling)
        if (dangerCurrent.isSerious() && !player.isOnGround()
                && playerVel.y < -0.3) {
            return CombatStage.DANGER_IMMINENT;
        }

        // DANGER_BATTLE: next enemy hit would knock us off
        if (lastFallIfHit >= KB_FALL_THRESHOLD) {
            return CombatStage.DANGER_BATTLE;
        }

        // TODO: ESCAPE — target just hit (immunity frames), or mutual edge danger, or low HP
        // TODO: DELICATE_BATTLE — low HP careful play

        return CombatStage.PURSUE;
    }

    // ── aim prediction ───────────────────────────────────────────────────────

    // LOS check result — public for CombatController to know if we have LOS
    private boolean hasLOS = false;

    /**
     * Compute predicted aim point with smart hitbox targeting.
     *
     * 1. Predict target position N ticks ahead (N = WindMouse convergence time)
     * 2. Build predicted bounding box at that position
     * 3. Find closest point on predicted hitbox to our eye pos
     * 4. Raycast to that point — if blocked, sample hitbox corners for any visible point
     * 5. Aim at the best visible point, or predicted center as fallback
     */
    private void computeAimPrediction(ClientPlayerEntity player, Vec3d targetPos) {
        int leadTicks = getAimLeadTicks();
        Vec3d eyePos = player.getEyePos();

        // predicted target position
        Vec3d predictedPos = targetPos.add(enemyVelocity.multiply(leadTicks));
        // build predicted bounding box
        double hw = target.getWidth() / 2.0;
        double h = target.getHeight();
        net.minecraft.util.math.Box predictedBox = new net.minecraft.util.math.Box(
                predictedPos.x - hw, predictedPos.y, predictedPos.z - hw,
                predictedPos.x + hw, predictedPos.y + h, predictedPos.z + hw);

        // find best aim point on hitbox
        Vec3d aimPoint = findBestAimPoint(player, eyePos, predictedBox, predictedPos, h);

        aimYaw = AttackTiming.yawTo(player.getPos(), aimPoint);
        aimPitch = AttackTiming.pitchTo(eyePos, aimPoint);
    }

    /**
     * Find best visible point on target hitbox.
     * Priority: closest point on box → if blocked, try hitbox sample points.
     */
    private Vec3d findBestAimPoint(ClientPlayerEntity player, Vec3d eyePos,
                                    net.minecraft.util.math.Box box, Vec3d targetPos, double height) {
        // closest point on bounding box to our eyes
        Vec3d closest = closestPointOnBox(eyePos, box);

        if (hasCleanLOS(player, eyePos, closest)) {
            hasLOS = true;
            return closest;
        }

        // sample hitbox points: center, top, bottom, corners
        Vec3d center = targetPos.add(0, height * 0.5, 0);
        double hw = (box.maxX - box.minX) / 2.0;
        Vec3d[] samples = {
            center,
            targetPos.add(0, height * 0.85, 0),   // head
            targetPos.add(0, height * 0.15, 0),    // feet
            targetPos.add(hw, height * 0.5, 0),    // sides
            targetPos.add(-hw, height * 0.5, 0),
            targetPos.add(0, height * 0.5, hw),
            targetPos.add(0, height * 0.5, -hw),
        };

        Vec3d bestVisible = null;
        double bestDist = Double.MAX_VALUE;
        for (Vec3d sample : samples) {
            if (hasCleanLOS(player, eyePos, sample)) {
                double dist = eyePos.squaredDistanceTo(sample);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestVisible = sample;
                }
            }
        }

        if (bestVisible != null) {
            hasLOS = true;
            return bestVisible;
        }

        // no visible point — aim at predicted center anyway (WindMouse will track)
        hasLOS = false;
        return center;
    }

    private static Vec3d closestPointOnBox(Vec3d point, net.minecraft.util.math.Box box) {
        return new Vec3d(
                Math.max(box.minX, Math.min(box.maxX, point.x)),
                Math.max(box.minY, Math.min(box.maxY, point.y)),
                Math.max(box.minZ, Math.min(box.maxZ, point.z))
        );
    }

    private static boolean hasCleanLOS(ClientPlayerEntity player, Vec3d from, Vec3d to) {
        net.minecraft.util.hit.HitResult hit = player.getWorld().raycast(
                new net.minecraft.world.RaycastContext(from, to,
                        net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                        net.minecraft.world.RaycastContext.FluidHandling.NONE, player));
        return hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS
                || from.squaredDistanceTo(hit.getPos()) >= from.squaredDistanceTo(to) * 0.95;
    }

    /**
     * Estimate how many ticks WindMouse needs to reach target.
     * Based on current angular distance / effective step rate.
     * Clamped to [1, 5] — we don't predict further than 5 ticks for aiming.
     */
    private int getAimLeadTicks() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 2;

        double angDist = WindMouseRotation.INSTANCE.distanceToTarget(mc.player);
        // rough: WindMouse moves ~maxStep degrees per frame, ~3 frames per tick
        double degreesPerTick = 4.0 * 3.0; // default maxStep * ~frames/tick
        int ticks = (int) Math.ceil(angDist / degreesPerTick);
        return Math.max(1, Math.min(5, ticks));
    }

    // ── knockback simulation ─────────────────────────────────────────────────

    /**
     * Simulate KB using current kbEstimator values for enemy hitting us,
     * or fixed base values for us hitting enemy.
     * @param asEnemy true = simulate enemy hitting victim (use estimator), false = us hitting
     */
    private Vec3d simulateKnockback(Vec3d victimPos, Vec3d victimVel,
                                     Vec3d attackerPos, boolean asEnemy) {
        double dx = victimPos.x - attackerPos.x;
        double dz = victimPos.z - attackerPos.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return victimPos;

        double nx = dx / len;
        double nz = dz / len;
        double kbStrength = asEnemy ? kbEstimator.getHorizontalStrength() : 0.8; // us: assume sprint hit
        double kbUp = asEnemy ? kbEstimator.getVerticalStrength() : 0.4;

        double vx = victimVel.x * 0.5 + nx * kbStrength;
        double vy = kbUp;
        double vz = victimVel.z * 0.5 + nz * kbStrength;

        double px = victimPos.x, py = victimPos.y, pz = victimPos.z;
        for (int t = 0; t < KB_PREDICT_TICKS; t++) {
            px += vx; py += vy; pz += vz;
            vx *= 0.91;
            vy = (vy - 0.08) * 0.98;
            vz *= 0.91;
        }
        return new Vec3d(px, py, pz);
    }

    private void analyzeKnockback(Vec3d playerPos, Vec3d playerVel,
                                   Vec3d targetPos, WorldView world) {
        lastUsAfterKB = simulateKnockback(playerPos, playerVel, targetPos, true);
        lastFallIfHit = VoidDetector.fallHeight(lastUsAfterKB, world);

        lastEnemyAfterKB = simulateKnockback(targetPos, enemyVelocity, playerPos, false);
        lastEnemyFallIfHit = VoidDetector.fallHeight(lastEnemyAfterKB, world);
    }

    // ── render helpers ───────────────────────────────────────────────────────

    private void renderVelocity(Vec3d pos, Vec3d vel, Vec3d predicted, Color col) {
        Vec3d velEnd = pos.add(vel.multiply(5));
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(
                pos.add(0, 0.5, 0), velEnd.add(0, 0.5, 0), col));
        TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                predicted.subtract(0.15, 0, 0.15), new Vec3d(0.3, 1.8, 0.3), col));
    }

    // ── getters ──────────────────────────────────────────────────────────────

    public CombatStage getStage()   { return stage; }
    public boolean isBraking()         { return braking; }
    public boolean isRepositioning()   { return repositioning; }
    public boolean isMovementActive()  { return movementActive; }
    public float getMovementYaw()      { return movementYaw; }
    public float getBrakeYaw()      { return brakeYaw; }
    public float getAimYaw()        { return aimYaw; }
    public float getAimPitch()      { return aimPitch; }
    public boolean hasLOS()         { return hasLOS; }

    public void reset() {
        prevEnemyPos = null;
        enemyVelocity = Vec3d.ZERO;
        target = null;
        active = false;
        stage = CombatStage.PURSUE;
        prevStage = null;
        braking = false;
        pathfinder.reset();
        kbEstimator.reset();
        TungstenModRenderContainer.COMBAT_TRAJECTORY =
                java.util.Collections.synchronizedCollection(new java.util.ArrayList<>());
    }
}
