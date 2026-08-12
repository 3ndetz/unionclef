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
    // 2 blocks tripped on any minor terrain step and stalled the approach;
    // real knockback danger starts around fall-damage height
    private static final int KB_FALL_THRESHOLD = 4;

    // ── state ───────────────────────────────────────────────────────────────
    private Vec3d prevEnemyPos = null;
    private Vec3d enemyVelocity = Vec3d.ZERO;
    private Entity target = null;

    private final CombatPathfinder pathfinder = new CombatPathfinder();
    private final KnockbackEstimator kbEstimator = new KnockbackEstimator();
    private final CombatExecutor executor = new CombatExecutor();

    /**
     * What the stage machine wants the legs to do. Filled here, resolved and written
     * ONCE per client tick by CombatController — see {@link CombatMoveIntent} for why
     * this is no longer allowed to press keys directly.
     */
    private final CombatMoveIntent intent = new CombatMoveIntent();

    /** The stage machine's movement request for this frame (never null). */
    public CombatMoveIntent getIntent() { return intent; }

    /** Set when renderUpdate has produced a request since the last tick consumed one. */
    private boolean intentFresh = false;

    /**
     * Consume the freshness flag. The stage machine runs on the RENDER loop but the keys are
     * written on the TICK loop, so the tick must be able to tell "this request was computed
     * for the current situation" from "rendering stalled and this is last frame's leftovers".
     * A tick that finds no fresh request falls back to its own close-quarters movement rather
     * than replaying stale keys.
     */
    public boolean consumeIntentFresh() {
        boolean f = intentFresh;
        intentFresh = false;
        return f;
    }

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
    /**
     * WHICH STAGE IS ACTUALLY RETREATING. isRepositioning() is set from THREE different places —
     * NARROW_BATTLE (path-following on a ledge), DANGER_BATTLE (knockback would drop us) and ESCAPE
     * (low HP, break contact) — and CombatController's aimReposition counter adds them all up. A
     * whole pass was spent predicting how "the reposition share" would move on the assumption it
     * meant DANGER_BATTLE; it rose, and the number could not say which of the three did it, so the
     * result settled nothing either way. Count them apart and the branch becomes measurable.
     */
    public static volatile int rpNarrow = 0, rpDanger = 0, rpEscape = 0;
    /**
     * NARROW_BATTLE turned out to be a SYMPTOM: it is pinned for 200 frames whenever DANGER_IMMINENT
     * fires 3 times in a 120-frame window (:467, :479). Measured on allround: narrow=323 against
     * danger=18 and escape=0, on a flat walled platform where edgeScore is 0. So the question is why
     * the imminent stage panics, and these two count it — how often it triggers, and how often that
     * escalates into a forced narrow.
     */
    public static volatile int rpImminent = 0, rpForcedNarrow = 0;
    /**
     * The forced-narrow countdown itself, published live. imm/forced froze at 12/4 while narrow kept
     * climbing 324 -> 478, and 4 escalations x 200 frames is only ~32 s of a 120 s fight at 26 fps —
     * so either the lockouts overlap far more than that arithmetic allows, or something keeps NARROW
     * pinned after this timer expires. Watching the timer's own value across a fight answers both,
     * and costs nothing.
     */
    public static volatile int rpForcedTimer = 0;
    /**
     * WHY hasLOS IS FALSE ALL FIGHT. closeQuarters returns on its first line when !hasLOS
     * (CombatController:363), so everything below it is dead code in a fight — proven by ctlTotal=0
     * against lowHpTicks=149. The raycast itself (hasCleanLOS) reads correctly, so the cause is
     * above it: either findBestAimPoint never runs, leaving hasLOS stuck at its clear, or it runs
     * and every sample is blocked. losCalls separates those in one run; losClosest/losSample say
     * which path succeeded, and losNone counts the give-up that clears the flag.
     */
    public static volatile int losCalls = 0, losClosest = 0, losSample = 0, losNone = 0;
    private boolean wasBrakingLastFrame = false;
    private boolean wasRepositioningLastFrame = false;
    private boolean wantsJump = false;

    // movement output — legs direction from BFS path
    private float movementYaw = 0;
    private boolean movementActive = false;

    // post-imminent cooldown: block movement for N frames after braking ends
    private int postImminentCooldown = 0;
    private static final int POST_IMMINENT_COOLDOWN_FRAMES = 40; // ~0.7 sec

    // edge sneak: hold sneak briefly when landing near block edge with momentum
    private int edgeSneakTicks = 0;
    private static final int EDGE_SNEAK_DURATION = 10;

    // imminent spam detection → force NARROW_BATTLE mode
    private int imminentCount = 0;       // how many times IMMINENT triggered recently
    private long imminentDecayUntil = 0L;  // wall-clock deadline for the spam window
    private static final int IMMINENT_SPAM_THRESHOLD = 3;  // 3 times in window → narrow mode
    // ⛔ THESE WERE FRAME COUNTS ON A LOOP THAT RUNS AT THE FRAME RATE, SO THEY MEANT DIFFERENT
    // DURATIONS ON DIFFERENT MACHINES. 120 and 200 frames were written for ~60 fps, i.e. ~2 s and
    // ~3.3 s. This stand measures 28-30 fps, so the forced-narrow pin actually lasted about SEVEN
    // seconds — more than double its intent — and every second of it is a second the bot does not
    // fight, because the safety stage claims the legs and closeQuarters never runs
    // (CombatController:294). The worse the frame rate, the longer the bot stands there.
    // A behaviour that changes with frame rate is wrong on any machine, not just a slow one, so
    // these are now wall-clock and mean what they say.
    private static final long IMMINENT_DECAY_MS = 2000;    // 2 s window
    private static final long FORCED_NARROW_MS  = 3300;    // 3.3 s forced narrow
    private long forcedNarrowUntil = 0L;

    private boolean active = false;
    private int logCooldown = 0;

    // ── tick (20 TPS): enemy velocity tracking ──────────────────────────────

    public void tick(ClientPlayerEntity player, Entity target, WorldView world) {
        this.target = target;
        active = true;

        Vec3d targetPos = target.getEntityPos();
        if (prevEnemyPos != null) {
            // The raw per-tick position delta is NOISY for a packet-moving player (position
            // packets arrive irregularly -> velocity spikes to big values then zero), which
            // jitters the lead prediction and is a root cause of the aim SHAKE. Smooth it
            // with an EMA so the lead (and therefore the aim) stays stable while tracking.
            Vec3d rawVel = targetPos.subtract(prevEnemyPos);
            double vs = kaptainwutax.tungsten.TungstenConfig.get().combatVelSmoothing;
            enemyVelocity = enemyVelocity.multiply(1.0 - vs).add(rawVel.multiply(vs));
        }
        prevEnemyPos = targetPos;

        // KB estimator: track enemy sprint state + enchants
        kbEstimator.tick(target, enemyVelocity);

        // pathfinder updates every N ticks
        pathfinder.tick(player.getBlockPos(), target.getBlockPos(), enemyVelocity, world);

        // combat executor: pre-compute jump+attack timeline
        executor.tick(player, target, world);
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
        // A fresh request every frame: anything not re-asserted below is released by
        // CombatController's single write, so a stale press cannot survive.
        intent.clear();
        if (postImminentCooldown > 0) postImminentCooldown--;
        if (edgeSneakTicks > 0) edgeSneakTicks--;
        rpForcedTimer = (int) Math.max(0, forcedNarrowUntil - System.currentTimeMillis());
        if (System.currentTimeMillis() > imminentDecayUntil) imminentCount = 0;
        TungstenModRenderContainer.COMBAT_TRAJECTORY =
                java.util.Collections.synchronizedCollection(new java.util.ArrayList<>());
        if (logCooldown > 0) logCooldown--;

        // tick-accurate positions for logic (block grid checks)
        Vec3d playerVel = player.getVelocity();
        Vec3d playerPosTick = player.getEntityPos();
        Vec3d targetPosTick = target.getEntityPos();
        double horizSpeed = Math.sqrt(playerVel.x * playerVel.x + playerVel.z * playerVel.z);

        // interpolated positions for smooth visualization
        Vec3d playerPos = playerPosTick.add(playerVel.multiply(tickDelta));
        Vec3d targetPos = targetPosTick.add(enemyVelocity.multiply(tickDelta));

        // predicted positions (from interpolated for smooth viz)
        Vec3d playerPredicted = playerPos.add(playerVel.multiply(PREDICT_TICKS));
        Vec3d enemyPredicted = targetPos.add(enemyVelocity.multiply(PREDICT_TICKS));

        // terrain checks use tick positions (block grid)
        // ⛔ THIS EXTRAPOLATED THE BOT THROUGH THE FLOOR AND CALLED IT A CLIFF.
        // It was pos + velocity*10 with the VERTICAL component included, no gravity, no ground.
        // Descending from a crit hop the bot carries vy about -0.3, so the predicted point landed
        // ~3 blocks BELOW its feet — under the platform — and fallHeight() started its scan down
        // there, found nothing and returned MAX_SCAN_DEPTH=30. fromFallHeight(30) is HEIGHT_DEATH,
        // so dangerPredicted.isSerious() was true on EVERY crit hop, which fired DANGER_IMMINENT
        // (:490), and three of those inside 120 frames pinned NARROW_BATTLE for 200 — measured as a
        // self-sustaining loop (timer 92 -> 67 -> 4 -> re-armed to 120, imm 3 -> 6) that cost about
        // thirty seconds of retreat in a two-minute duel.
        //
        // The question this look-ahead exists to answer is "if I keep going this way, is there floor
        // ahead of me" — that is the GROUND TRACK, so take the horizontal velocity only. The vertical
        // guess was never meaningful anyway without gravity or collision.
        Vec3d playerPredictedTick = playerPosTick.add(
                playerVel.x * PREDICT_TICKS, 0, playerVel.z * PREDICT_TICKS);
        int fallAtPredicted = VoidDetector.fallHeight(playerPredictedTick, player.getEntityWorld());
        int fallAtCurrent = VoidDetector.fallHeight(playerPosTick, player.getEntityWorld());
        DangerLevel dangerPredicted = DangerLevel.fromFallHeight(fallAtPredicted);
        DangerLevel dangerCurrent = DangerLevel.fromFallHeight(fallAtCurrent);

        // edge score: how surrounded by dangerous drops (5+ blocks) we are
        double currentEdgeScore = VoidDetector.edgeScoreWithFallThreshold(playerPosTick, player.getEntityWorld(), 5);

        // KB analysis uses tick positions
        analyzeKnockback(playerPosTick, playerVel, targetPosTick, player.getEntityWorld());

        // ── evaluate stage ───────────────────────────────────────────────
        CombatStage newStage = evaluateStage(player, playerVel, horizSpeed,
                dangerPredicted, dangerCurrent, currentEdgeScore);
        if (newStage != stage) {
            stage = newStage;
            if (prevStage != stage && logCooldown <= 0) {
                final String msg = stage.chatColor() + "COMBAT: → " + stage.name();
                mc.execute(() -> Debug.logMessage(msg));
                prevStage = stage;
                logCooldown = 60; // ~1 sec between stage change logs
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

                    // use strafe-based braking relative to look direction
                    // (safe on bridges — won't walk sideways off edge)
                    float lookYaw = player.getYaw();
                    float deltaYaw = brakeYaw - lookYaw;
                    deltaYaw = ((deltaYaw % 360) + 540) % 360 - 180;

                    boolean w = deltaYaw > -70 && deltaYaw < 70;
                    boolean s = deltaYaw > 110 || deltaYaw < -110;
                    boolean a = deltaYaw > 20 && deltaYaw < 160;
                    boolean d = deltaYaw < -20 && deltaYaw > -160;

                    if (horizSpeed > 0.05 && player.isOnGround() && currentEdgeScore < 0.5) {
                        // only jump to brake if not on narrow terrain
                        wantsJump = true;
                    }
                    intent.set(w, s, a, d, true, wantsJump, false);
                }
                case NARROW_BATTLE -> {
                    // bridge/narrow: face along path, W only. NO STRAFE (instant death on 1-wide).
                    // camera faces path direction, triggerbot clicks when target crosses crosshair.
                    java.util.List<net.minecraft.util.math.BlockPos> atkPath = pathfinder.getAttackPath();
                    if (atkPath.size() >= 2) {
                        net.minecraft.util.math.BlockPos nextWp = atkPath.get(Math.min(1, atkPath.size() - 1));
                        movementYaw = AttackTiming.yawTo(playerPosTick, Vec3d.ofBottomCenter(nextWp));
                        movementActive = true;
                        // override aim to path direction (NOT target) — safety first
                        brakeYaw = movementYaw;
                        repositioning = true; // tells CombatController to use brakeYaw for aim
                        rpNarrow++;

                        intent.set(true, false, false, false, false, false, false);
                    }
                }
                case DANGER_BATTLE -> {
                    // reposition toward retreat path if KB fall is serious
                    DangerLevel kbDanger = DangerLevel.fromFallHeight(lastFallIfHit);
                    java.util.List<net.minecraft.util.math.BlockPos> retreat = pathfinder.getRetreatPath();
                    if (kbDanger.isSerious() && retreat.size() >= 2) {
                        repositioning = true;
                        rpDanger++;
                        net.minecraft.util.math.BlockPos waypoint = retreat.get(Math.min(2, retreat.size() - 1));
                        Vec3d wpPos = Vec3d.ofBottomCenter(waypoint);
                        brakeYaw = AttackTiming.yawTo(playerPosTick, wpPos);

                        // THE GAZE USED TO BE THE STEERING, AND THAT IS WHY THE BOT COULD NOT FIGHT
                        // WHILE RETREATING. This branch used to press FORWARD and rely on the camera
                        // being pointed at the waypoint (CombatController read brakeYaw for aim), so
                        // "retreat" and "look away from the opponent" were the same instruction. It
                        // owns 63% of a duel's ticks, which is exactly why the angle gate refused on
                        // 40% of them and the bot lost 9-14 while looking the wrong way.
                        // Measured when the aim was pointed at the enemy while this still pressed
                        // forward: the retreat became an ADVANCE — reach refusals 57% -> 33% (closer),
                        // kills 9 -> 11 (more time in range), deaths 14 -> 17 (walking into it).
                        //
                        // So resolve travel against the head instead of assuming they agree. Minecraft
                        // movement keys are relative to the look direction, and yaw increases turning
                        // right, so a positive difference puts the waypoint to the player's right.
                        // The bot can now back away and strafe while its crosshair stays on the enemy,
                        // which is what a fighting retreat actually is.
                        float rel = net.minecraft.util.math.MathHelper.wrapDegrees(
                                brakeYaw - player.getYaw());
                        float a = Math.abs(rel);
                        boolean goFwd   = a <= 67.5f;
                        boolean goBack  = a >= 112.5f;
                        boolean goRight = rel > 22.5f && rel < 157.5f;
                        boolean goLeft  = rel < -22.5f && rel > -157.5f;

                        // walk, no jump, allow jump ONLY if stuck (below waypoint Y)
                        boolean needJumpUp = waypoint.getY() > playerPosTick.y + 0.5 && player.isOnGround();
                        intent.set(goFwd, goBack, goLeft, goRight, true, needJumpUp, false);
                    }
                }
                case ESCAPE -> {
                    // disengage: run along retreat path, sprint-jump away
                    java.util.List<net.minecraft.util.math.BlockPos> retreatEsc = pathfinder.getRetreatPath();
                    if (retreatEsc.size() >= 2) {
                        net.minecraft.util.math.BlockPos wp = retreatEsc.get(Math.min(2, retreatEsc.size() - 1));
                        movementYaw = AttackTiming.yawTo(playerPosTick, Vec3d.ofBottomCenter(wp));
                        movementActive = true;
                        repositioning = true; // use movementYaw for camera via brakeYaw
                        rpEscape++;
                        brakeYaw = movementYaw;

                        // jump if on ground and not on narrow terrain
                        intent.set(true, false, false, false, true,
                                player.isOnGround() && currentEdgeScore < 0.4, false);
                    }
                }
                case PURSUE, DELICATE_BATTLE -> {
                    // no key override from saver
                }
            }
        }

        // ── edge sneak: hold sneak when on block edge with momentum ──────
        if (player.isOnGround() && horizSpeed > 0.03 && currentEdgeScore > 0.2) {
            // check sub-block position: how close to edge of current block
            double fracX = playerPosTick.x - Math.floor(playerPosTick.x);
            double fracZ = playerPosTick.z - Math.floor(playerPosTick.z);
            double edgeDist = Math.min(Math.min(fracX, 1 - fracX), Math.min(fracZ, 1 - fracZ));
            if (edgeDist < 0.3) {
                edgeSneakTicks = EDGE_SNEAK_DURATION;
            }
        }
        if (edgeSneakTicks > 0 && !braking) {
            // additive: claim the legs (sneak-only) if nothing else has
            intent.active = true;
            intent.sneak = true;
        }

        // ── movement: follow BFS attack path (if enabled + not braking/repositioning/cooldown) ──
        boolean movementsEnabled = kaptainwutax.tungsten.TungstenConfig.get().combatMovementsEnabled;
        // don't move toward target if we're in danger zone (DANGER_BATTLE = KB would kill us)
        if (movementsEnabled && !braking && !repositioning && postImminentCooldown <= 0
                && stage != CombatStage.DANGER_BATTLE) {
            // Near the island rim: walk, never sprint or jump. Sprint momentum
            // (~0.28/tick) and jump arcs overshoot the edge faster than the
            // reactive edge-clamp can arrest them. A drop within 3 blocks means
            // any sprint could carry us off before we stop — walk instead so the
            // clamp/sneak plant us on solid ground. (On this void map fallHeight
            // bottoms out ~4 blocks down, so the radius-3 scan is cheap.)
            boolean nearEdge = VoidDetector.voidWithin(playerPosTick, player.getEntityWorld(), 3, 3);
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

                // check if next waypoint is safe — block itself AND surroundings
                int wpFall = VoidDetector.fallHeight(Vec3d.ofBottomCenter(nextWp), player.getEntityWorld());
                DangerLevel wpDanger = DangerLevel.fromFallHeight(wpFall);

                if (!wpDanger.isSerious()) {
                    movementYaw = AttackTiming.yawTo(playerPosTick, Vec3d.ofBottomCenter(nextWp));
                    movementActive = true;

                    // jump only if landing zone is safe AND we're not on the rim
                    // check 3-4 blocks ahead in velocity direction for drops
                    boolean safeToJump = isJumpLandingSafe(playerPosTick, playerVel, player.getEntityWorld());
                    intent.set(true, false, false, false, !nearEdge,
                            player.isOnGround() && safeToJump && !nearEdge, false);
                }
                // if waypoint is dangerous, don't move — stay and fight
            }

            // Close the last half-block: the BFS waypoint tolerance (1.5)
            // leaves the bot hovering just outside the 3.0 attack reach
            // (observed 3.06 — staring at the enemy, never swinging).
            if (!movementActive && !braking && !repositioning) {
                net.minecraft.util.math.Box tb = target.getBoundingBox();
                Vec3d eye = player.getEyePos();
                Vec3d closest = new Vec3d(
                        net.minecraft.util.math.MathHelper.clamp(eye.x, tb.minX, tb.maxX),
                        net.minecraft.util.math.MathHelper.clamp(eye.y, tb.minY, tb.maxY),
                        net.minecraft.util.math.MathHelper.clamp(eye.z, tb.minZ, tb.maxZ));
                if (eye.distanceTo(closest) > CombatController.STRIKE_DISTANCE) {
                    movementYaw = AttackTiming.yawTo(playerPosTick, target.getEntityPos());
                    movementActive = true;
                    intent.set(true, false, false, false, !nearEdge, false, false);
                }
            }
        }

        // No explicit "release" pass is needed any more: the intent is cleared at the top
        // of every frame and CombatController writes the FULL key set once per tick, so an
        // unclaimed key is released by construction rather than by a bookkeeping branch.
        //
        // The VoidGuard clamp also moved out of here. It used to run at the end of this
        // RENDER-frequency method, which meant the per-TICK combat mover bypassed it
        // entirely; it is now applied by CombatController to the resolved intent, so every
        // combat movement — whatever produced it — passes the same void clamp exactly once.

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

        intentFresh = true;

        // combat paths visualization
        pathfinder.renderUpdate(tickDelta);

        // executor: planned trajectory visualization
        executor.renderUpdate();
    }

    // ── stage evaluation ─────────────────────────────────────────────────────

    private CombatStage evaluateStage(ClientPlayerEntity player, Vec3d playerVel,
                                       double horizSpeed, DangerLevel dangerPredicted,
                                       DangerLevel dangerCurrent, double edgeScore) {
        boolean onNarrowTerrain = edgeScore >= 0.4 && player.isOnGround();

        // forced NARROW from imminent spam
        if (System.currentTimeMillis() < forcedNarrowUntil) {
            onNarrowTerrain = true;
        }

        // DANGER_IMMINENT: actually falling or about to fall off
        if (!onNarrowTerrain) {
            if (dangerPredicted.isSerious() && horizSpeed > 0.02
                    && (dangerCurrent != DangerLevel.NONE || playerVel.y < -0.3)) {
                imminentCount++;
                rpImminent++;
                imminentDecayUntil = System.currentTimeMillis() + IMMINENT_DECAY_MS;
                if (imminentCount >= IMMINENT_SPAM_THRESHOLD) {
                    // too many imminents → force narrow mode
                    forcedNarrowUntil = System.currentTimeMillis() + FORCED_NARROW_MS;
                    rpForcedNarrow++;
                    imminentCount = 0;
                    if (logCooldown <= 0) {
                        MinecraftClient.getInstance().execute(() -> Debug.logMessage("§9COMBAT: imminent spam → forced NARROW_BATTLE"));
                        logCooldown = 120;
                    }
                    return CombatStage.NARROW_BATTLE;
                }
                return CombatStage.DANGER_IMMINENT;
            }
        }
        // always: already falling hard into serious danger
        if (dangerCurrent.isSerious() && !player.isOnGround() && playerVel.y < -0.3) {
            return CombatStage.DANGER_IMMINENT;
        }

        // NARROW_BATTLE: bridge, dyrjavy floor, or forced after imminent spam
        if (onNarrowTerrain) {
            return CombatStage.NARROW_BATTLE;
        }

        // DANGER_BATTLE: next enemy hit would knock us off
        if (lastFallIfHit >= KB_FALL_THRESHOLD) {
            return CombatStage.DANGER_BATTLE;
        }

        // No progress: no hits for 5 seconds
        if (CombatController.triggerBot.hasNoProgress(100)) {
            if (logCooldown <= 0) {
                MinecraftClient.getInstance().execute(() -> Debug.logMessage("§eCOMBAT: no hits — need closer approach"));
                logCooldown = 120;
            }
        }

        // NOTE: the old "ESCAPE while weapon on cooldown" rule fired for the
        // first half of EVERY cooldown cycle — the bot sprinted away and looked
        // away after every single hit. That was the main source of passivity.
        // Disengage decisions belong to real danger stages above, not cooldown.

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

        float rawYaw   = AttackTiming.yawTo(player.getEntityPos(), aimPoint);
        float rawPitch = AttackTiming.pitchTo(eyePos, aimPoint);
        // LOW-PASS the aim target so a packet-jittery enemy (velocity/lead noise, flipping
        // best-aim-point) doesn't make the camera SHAKE (user: "прицел трясёт как не в себя")
        // and stay outside the trigger's 40deg window -> never hits. Snap on a big jump (first
        // aim / target teleport / target switch), else blend halfway toward the raw aim — kills
        // the high-frequency jitter while still tracking a moving target so the angle gate holds.
        float dYaw = net.minecraft.util.math.MathHelper.wrapDegrees(rawYaw - aimYaw);
        float aimS = (float) kaptainwutax.tungsten.TungstenConfig.get().combatAimSmoothing;
        if (Math.abs(dYaw) > 55f) {
            aimYaw = rawYaw;
            aimPitch = rawPitch;
        } else {
            aimYaw = net.minecraft.util.math.MathHelper.wrapDegrees(aimYaw + dYaw * aimS);
            aimPitch = aimPitch + (rawPitch - aimPitch) * aimS;
        }
    }

    /**
     * Check if sprint-jump landing zone is safe.
     * Scans blocks 1-4 ahead in velocity direction for serious drops.
     * If any landing spot has fall 4+, don't jump.
     */
    public static boolean isJumpLandingSafe(Vec3d pos, Vec3d vel, net.minecraft.world.WorldView world) {
        double horizSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (horizSpeed < 0.01) return true; // standing still, jump is safe

        double nx = vel.x / horizSpeed;
        double nz = vel.z / horizSpeed;
        int y = net.minecraft.util.math.MathHelper.floor(pos.y);

        // check blocks 1, 2, 3, 4 ahead in velocity direction
        for (int dist = 1; dist <= 4; dist++) {
            int x = net.minecraft.util.math.MathHelper.floor(pos.x + nx * dist);
            int z = net.minecraft.util.math.MathHelper.floor(pos.z + nz * dist);
            int fall = VoidDetector.fallHeight(new Vec3d(x + 0.5, y, z + 0.5), world);
            if (fall >= 4) return false;
        }
        return true;
    }

    /** Check if there are holes (fall 3+ blocks) on the straight line between player and waypoint. */
    public static boolean hasHolesOnPath(Vec3d from, net.minecraft.util.math.BlockPos to, net.minecraft.world.WorldView world) {
        double dx = to.getX() + 0.5 - from.x;
        double dz = to.getZ() + 0.5 - from.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        int steps = (int) Math.ceil(dist * 2);
        if (steps <= 0) return false;

        int fromY = net.minecraft.util.math.MathHelper.floor(from.y);
        for (int s = 1; s < steps; s++) {
            double t = (double) s / steps;
            int x = net.minecraft.util.math.MathHelper.floor(from.x + dx * t);
            int z = net.minecraft.util.math.MathHelper.floor(from.z + dz * t);
            // check if block below feet is air (hole)
            net.minecraft.util.math.BlockPos below = new net.minecraft.util.math.BlockPos(x, fromY - 1, z);
            if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                // no solid below → check fall depth
                int fall = VoidDetector.fallHeight(new Vec3d(x + 0.5, fromY, z + 0.5), world);
                if (fall >= 3) return true;
            }
        }
        return false;
    }

    /**
     * Find best visible point on target hitbox.
     * Priority: closest point on box → if blocked, try hitbox sample points.
     */
    private Vec3d findBestAimPoint(ClientPlayerEntity player, Vec3d eyePos,
                                    net.minecraft.util.math.Box box, Vec3d targetPos, double height) {
        // closest point on bounding box to our eyes
        losCalls++;
        Vec3d closest = closestPointOnBox(eyePos, box);

        if (hasCleanLOS(player, eyePos, closest)) {
            losClosest++;
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
            losSample++;
            hasLOS = true;
            return bestVisible;
        }

        // no visible point — aim at predicted center anyway (WindMouse will track)
        losNone++;
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
        net.minecraft.util.hit.HitResult hit = player.getEntityWorld().raycast(
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
        double degreesPerTick = kaptainwutax.tungsten.TungstenConfig.get().combatWindMouseMaxStep * 3.0;
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
                                     Vec3d attackerPos, boolean asEnemy, WorldView world) {
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
            // ⛔ THE BODY MUST LAND. Without this the integration falls through the floor for the
            // whole 15 ticks: vy starts at 0.4, peaks the arc at +1.15 around t=4 and ends at
            // START MINUS 2.33 — a point below any surface the fighter was standing on. The caller
            // then measures VoidDetector.fallHeight FROM THAT SUNKEN POINT, and DANGER_BATTLE
            // fires at fallHeight >= 4.
            //
            // On ordinary terrain the sunken point sits INSIDE solid blocks, fallHeight reads 0,
            // and the bug is invisible — which is why it was recorded for a long time as the
            // estimate merely being "inflated on flat ground". On a THIN platform it is total:
            // 2.33 blocks below the slab is open air, fallHeight returns MAX_SCAN_DEPTH, and the
            // stage engages EVERY TICK no matter where the fighter stands. The whole mob bench is
            // a floating island, so `danger` dominated `reposition` in every run (69/144/170/222/
            // 244, and 1984 in a long one) including runs measured ten blocks clear of any edge.
            //
            // This is not the caution being removed — removing it was measured harmful twice
            // (deaths 16 -> 23 and 15 -> 19) and that result stands. Being knocked over a real
            // ledge still leaves the simulated body in open air with nothing beneath it, so the
            // stage still fires exactly there. What stops is the stage firing on solid ground.
            if (vy < 0 && world != null
                    && VoidDetector.fallHeight(new Vec3d(px, py, pz), world) <= 1) {
                break;
            }
            vx *= 0.91;
            vy = (vy - 0.08) * 0.98;
            vz *= 0.91;
        }
        return new Vec3d(px, py, pz);
    }

    private void analyzeKnockback(Vec3d playerPos, Vec3d playerVel,
                                   Vec3d targetPos, WorldView world) {
        lastUsAfterKB = simulateKnockback(playerPos, playerVel, targetPos, true, world);
        lastFallIfHit = VoidDetector.fallHeight(lastUsAfterKB, world);

        lastEnemyAfterKB = simulateKnockback(targetPos, enemyVelocity, playerPos, false, world);
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
        executor.reset();
        TungstenModRenderContainer.COMBAT_TRAJECTORY =
                java.util.Collections.synchronizedCollection(new java.util.ArrayList<>());
    }
}
