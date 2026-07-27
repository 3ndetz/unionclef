package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.world.WorldView;

/**
 * PvP combat controller.
 *
 * Subsystems:
 *   SAFETY  — render-freq: stage machine, viz, braking, aim prediction
 *   MOUSE   — render-freq via WindMouse: rotation toward predicted aim point
 *   TRIGGER — tick-freq: auto-click when crosshair lands on target
 */
public class CombatController {

    public static final TriggerBot triggerBot = new TriggerBot();
    public static final SafetySystem safety = new SafetySystem();

    /**
     * Distance (eye -> nearest hitbox point) the bot tries to hold in a melee fight.
     * Derived from {@link TriggerBot#REACH} so the mover can never again settle at a
     * distance the attack gate refuses to fire at: we sit a comfortable margin INSIDE
     * reach, so normal jitter/knockback still leaves the swing legal.
     */
    public static final double STRIKE_DISTANCE = TriggerBot.REACH - 0.6;   // 2.4
    /** Below this we are inside the opponent's swing and lose angle — drift back out. */
    public static final double TOO_CLOSE_DISTANCE = TriggerBot.REACH - 1.4; // 1.6

    // ── dynamic combat movement state (circle-strafe + range + crit-jumps) ──────
    private int strafeDir = 1;              // +1 = left, -1 = right
    private long lastStrafeSwitch = 0;
    private long strafeInterval = 800;
    private long lastJump = 0;
    private long jumpInterval = 900;

    /** The resolved request actually written to the keys this tick. */
    private final CombatMoveIntent resolved = new CombatMoveIntent();

    public boolean tick(ClientPlayerEntity player, Entity target, WorldView world) {
        if (target == null || target.isRemoved() || !target.isAlive()) return false;

        TungstenConfig cfg = TungstenConfig.get();

        // safety: velocity tracking always, braking/viz only if enabled
        safety.tick(player, target, world);

        if (cfg.combatRotatesEnabled) {
            if (cfg.combatSaverEnabled && safety.isBraking()) {
                // DANGER_IMMINENT: face opposite velocity
                WindMouseRotation.INSTANCE.setParams(
                        cfg.combatWindMouseGravity * 2,
                        cfg.combatWindMouseWind * 0.3,
                        cfg.combatWindMouseMaxStep * 2.5,
                        cfg.combatWindMouseWindDist,
                        cfg.combatWindMouseDoneThreshold,
                        cfg.combatWindMouseFlickScale
                );
                WindMouseRotation.INSTANCE.setTarget(safety.getBrakeYaw(), 0);
            } else if (cfg.combatSaverEnabled && safety.isRepositioning()) {
                // DANGER_BATTLE: face retreat waypoint (faster turn, still fighting)
                WindMouseRotation.INSTANCE.setParams(
                        cfg.combatWindMouseGravity * 1.5,
                        cfg.combatWindMouseWind * 0.5,
                        cfg.combatWindMouseMaxStep * 1.5,
                        cfg.combatWindMouseWindDist,
                        cfg.combatWindMouseDoneThreshold,
                        cfg.combatWindMouseFlickScale
                );
                WindMouseRotation.INSTANCE.setTarget(safety.getBrakeYaw(), 0);
            } else if (safety.hasLOS()) {
                // LOS to target: aim at predicted target position for hits
                WindMouseRotation.INSTANCE.setParams(
                        cfg.combatWindMouseGravity,
                        cfg.combatWindMouseWind,
                        cfg.combatWindMouseMaxStep,
                        cfg.combatWindMouseWindDist,
                        cfg.combatWindMouseDoneThreshold,
                        cfg.combatWindMouseFlickScale
                );
                WindMouseRotation.INSTANCE.setTarget(safety.getAimYaw(), safety.getAimPitch());
            } else if (safety.isMovementActive()) {
                // no LOS: face BFS path direction to navigate around walls
                WindMouseRotation.INSTANCE.setParams(
                        cfg.combatWindMouseGravity,
                        cfg.combatWindMouseWind,
                        cfg.combatWindMouseMaxStep,
                        cfg.combatWindMouseWindDist,
                        cfg.combatWindMouseDoneThreshold,
                        cfg.combatWindMouseFlickScale
                );
                WindMouseRotation.INSTANCE.setTarget(safety.getMovementYaw(), 0);
            }
        }

        if (cfg.combatTriggerBotEnabled) {
            triggerBot.tick(player, target);
        }

        // LEGS: resolve ONE movement request and write it ONCE.
        //
        // Previously two subsystems pressed the keys independently: the SafetySystem stage
        // machine (per RENDER FRAME) and combatMove (per CLIENT TICK). Whichever ran last
        // before vanilla sampled the keyboard won, so movement was framerate-dependent —
        // which is why stand results never matched live behaviour — and the per-tick writer
        // bypassed the VoidGuard clamp completely. Now: safety has priority (it handles
        // falling, bridges and retreat), close-quarters combat fills in when safety does not
        // want the legs, VoidGuard vetoes the result, and the keys are written exactly once.
        if (cfg.combatMovementsEnabled) {
            resolved.clear();

            CombatMoveIntent safetyIntent = safety.getIntent();
            boolean safetyFresh = safety.consumeIntentFresh();
            if (cfg.combatSaverEnabled && safetyFresh && safetyIntent.active) {
                resolved.copyFrom(safetyIntent);
            } else {
                closeQuarters(player, target, world, resolved);
            }

            if (cfg.combatSaverEnabled) {
                VoidGuard.apply(resolved, player, player.getEntityPos(),
                        player.getVelocity(), world);
            }
            resolved.writeKeys(MinecraftClient.getInstance());
        }

        return true;
    }

    /**
     * Close-quarters movement: circle-strafe around the target while holding strike
     * distance, crit-hop on a randomised cadence. Only runs when the safety stage machine
     * does not want the legs (i.e. PURSUE / DELICATE_BATTLE — no fall, no bridge, no retreat).
     *
     * <p>THE BUG THIS REPLACES. The old version pressed forward only at {@code dist > 3.4}
     * and back only at {@code dist < 2.0}, both measured CENTRE-TO-CENTRE. Between those two
     * numbers it pressed no forward and no back at all — and 2.0-3.4 centre-to-centre is
     * exactly melee range. The only motion left was the circle-strafe, which was itself
     * suppressed near any drop by setting BOTH strafe keys false, so on a ledge or a 1-wide
     * bridge the bot pressed literally nothing and stood there. That is the "стоит и смотрит,
     * почти не двигается когда цель рядом" the user kept reporting. Worse, 3.4
     * centre-to-centre is ~3.1 eye-to-hitbox, i.e. OUTSIDE {@link TriggerBot#REACH}, so the
     * bot's chosen hold distance was one at which it could never land a hit.
     *
     * <p>Now: distance uses the same eye-to-hitbox metric as the attack gate, the band is
     * derived from {@link TriggerBot#REACH}, and there is no state in which the bot presses
     * nothing — if a strafe side is unsafe it takes the other side, and if both are unsafe it
     * still micro-adjusts range instead of freezing.
     */
    private void closeQuarters(ClientPlayerEntity player, Entity target, WorldView world,
                               CombatMoveIntent out) {
        // No line of sight: the target is occluded (a wall, or another entity in the way).
        // Don't just spin and click — walk toward the route so we flank around it. The aim
        // branch already points us at getMovementYaw().
        if (!safety.hasLOS()) {
            if (safety.isMovementActive()) {
                out.set(true, false, false, false, true, false, false);
            }
            return;
        }

        long now = System.currentTimeMillis();
        double dist = TriggerBot.eyeToHitbox(player, target);

        // Range control (we face the target, so forward = toward it).
        boolean tooFar = dist > STRIKE_DISTANCE;
        boolean tooClose = dist < TOO_CLOSE_DISTANCE;
        out.active = true;
        out.forward = tooFar;
        out.back = tooClose;
        out.sprint = tooFar && dist > STRIKE_DISTANCE + 1.0; // sprint only for a real approach

        // Circle-strafe: orbit the target, flipping direction on a randomised cadence
        // (unpredictable, keeps flanking). If the chosen side is a drop, take the OTHER
        // side rather than standing still; only when BOTH sides are unsafe do we skip the
        // strafe, and even then the range control above keeps the bot moving.
        if (now - lastStrafeSwitch > strafeInterval) {
            strafeDir = -strafeDir;
            lastStrafeSwitch = now;
            strafeInterval = 500 + (long) (Math.random() * 700);
        }
        if (!strafeSideSafe(player, world, strafeDir)) {
            strafeDir = -strafeDir;               // try the other side immediately
            lastStrafeSwitch = now;
        }
        boolean canStrafe = strafeSideSafe(player, world, strafeDir);
        out.left = canStrafe && strafeDir > 0;
        out.right = canStrafe && strafeDir < 0;

        // If neither side is strafeable and we are already at strike distance, jitter the
        // range instead of freezing — a professional never stands still in a duel.
        if (!canStrafe && !tooFar && !tooClose) {
            out.forward = ((now / 300) % 2) == 0;
            out.back = !out.forward;
        }

        // BUNNY-HOP: jump on a fast cadence so the bot is ALWAYS moving/juking around the
        // target, for crits + a harder-to-hit profile. Only from the ground and only when the
        // landing isn't a drop (a jump can never launch us into the void).
        if (player.isOnGround() && now - lastJump > jumpInterval
                && SafetySystem.isJumpLandingSafe(player.getEntityPos(), player.getVelocity(), world)) {
            out.jump = true;
            lastJump = now;
            var jcfg = kaptainwutax.tungsten.TungstenConfig.get();
            jumpInterval = jcfg.combatBunnyHopMinMs + (long) (Math.random() * jcfg.combatBunnyHopRandMs);
        }
    }

    /** Is stepping sideways in {@code dir} (+1 left / -1 right) clear of a drop? */
    private boolean strafeSideSafe(ClientPlayerEntity player, WorldView world, int dir) {
        double rad = Math.toRadians(player.getYaw());
        double sdx = dir * Math.cos(rad);   // world dir of the strafe (MC input transform)
        double sdz = dir * Math.sin(rad);
        return !VoidDetector.edgeAhead(player.getEntityPos(), sdx, sdz, world, 3, 1.5);
    }

    public void releaseKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        resolved.clear();
        safety.getIntent().clear();
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        triggerBot.reset();
        safety.reset();
        WindMouseRotation.INSTANCE.clearTarget();
    }
}
