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

    // ── dynamic combat movement state (circle-strafe + range + crit-jumps) ──────
    private int strafeDir = 1;              // +1 = left, -1 = right
    private long lastStrafeSwitch = 0;
    private long strafeInterval = 800;
    private long lastJump = 0;
    private long jumpInterval = 900;

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

        // LEGS: dynamic combat movement. A fight is dynamic — you strafe, kite to
        // reach, and jump for crits, you don't stand and click. This was entirely
        // missing (only aim + trigger ran), so the bot rooted in place.
        if (cfg.combatMovementsEnabled) {
            combatMove(player, target, world);
        }

        return true;
    }

    /**
     * Dynamic movement while fighting. LOS + safe: circle-strafe around the target,
     * hold ~melee reach (close when far, back off when too close), crit-jump on a
     * randomised cadence. No LOS: walk toward the pathfinder's route (the aim already
     * faces it) to get around whatever occludes the target. Danger (safety braking/
     * repositioning): release the legs and let the safety system own motion. All
     * moves are void-checked so a strafe/jump can't walk us off a ledge.
     */
    private void combatMove(ClientPlayerEntity player, Entity target, WorldView world) {
        MinecraftClient mc = MinecraftClient.getInstance();

        // Danger: safety owns motion (braking away from a drop / repositioning).
        if (safety.isBraking() || safety.isRepositioning()) {
            releaseLegs(mc);
            return;
        }

        // No line of sight: the target is occluded (a wall, or another entity in the
        // way). Don't just spin and click — walk toward the route so we flank around
        // it. The aim branch already points us at getMovementYaw().
        if (!safety.hasLOS()) {
            if (safety.isMovementActive()) {
                mc.options.forwardKey.setPressed(true);
                mc.options.sprintKey.setPressed(true);
                mc.options.backKey.setPressed(false);
                mc.options.leftKey.setPressed(false);
                mc.options.rightKey.setPressed(false);
            } else {
                releaseLegs(mc);
            }
            return;
        }

        long now = System.currentTimeMillis();
        double dist = player.getEntityPos().distanceTo(target.getEntityPos());

        // Range control (we face the target, so forward = toward it): stay at melee
        // reach — close in when out of range, back off when too close to keep angle.
        boolean tooFar = dist > 3.4;
        boolean tooClose = dist < 2.0;
        mc.options.forwardKey.setPressed(tooFar);
        mc.options.backKey.setPressed(tooClose);
        mc.options.sprintKey.setPressed(tooFar);

        // Circle-strafe: orbit the target, flipping direction on a randomised cadence
        // (unpredictable, keeps flanking) — unless that side is a drop.
        if (now - lastStrafeSwitch > strafeInterval) {
            strafeDir = -strafeDir;
            lastStrafeSwitch = now;
            strafeInterval = 500 + (long) (Math.random() * 700);
        }
        double rad = Math.toRadians(player.getYaw());
        double sdx = strafeDir * Math.cos(rad);   // world dir of the strafe (MC input transform)
        double sdz = strafeDir * Math.sin(rad);
        boolean strafeSafe = !VoidDetector.edgeAhead(player.getEntityPos(), sdx, sdz, world, 3, 1.5);
        if (!strafeSafe) {                          // flip away from the edge next tick
            strafeDir = -strafeDir;
            lastStrafeSwitch = now;
        }
        mc.options.leftKey.setPressed(strafeSafe && strafeDir > 0);
        mc.options.rightKey.setPressed(strafeSafe && strafeDir < 0);

        // Crit / juke jumps on a randomised cadence — only from the ground and only
        // when the landing isn't a drop (so a jump can't launch us into the void).
        if (player.isOnGround() && now - lastJump > jumpInterval
                && SafetySystem.isJumpLandingSafe(player.getEntityPos(), player.getVelocity(), world)) {
            mc.options.jumpKey.setPressed(true);
            lastJump = now;
            jumpInterval = 650 + (long) (Math.random() * 700);
        } else if (!player.isOnGround()) {
            mc.options.jumpKey.setPressed(false); // don't hold jump airborne
        }
    }

    private void releaseLegs(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
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
        mc.options.attackKey.setPressed(false);
        triggerBot.reset();
        safety.reset();
        WindMouseRotation.INSTANCE.clearTarget();
    }
}
