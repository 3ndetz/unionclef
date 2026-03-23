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

    private final TriggerBot triggerBot = new TriggerBot();
    public static final SafetySystem safety = new SafetySystem();

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
            } else if (safety.isMovementActive() && player.squaredDistanceTo(target) > 9.0) {
                // moving along BFS path — face path direction (W goes where we look)
                // only when far from target; close range = aim at target for hits
                WindMouseRotation.INSTANCE.setParams(
                        cfg.combatWindMouseGravity,
                        cfg.combatWindMouseWind,
                        cfg.combatWindMouseMaxStep,
                        cfg.combatWindMouseWindDist,
                        cfg.combatWindMouseDoneThreshold,
                        cfg.combatWindMouseFlickScale
                );
                WindMouseRotation.INSTANCE.setTarget(safety.getMovementYaw(), 0);
            } else {
                // close range or no movement: aim at predicted target position
                WindMouseRotation.INSTANCE.setParams(
                        cfg.combatWindMouseGravity,
                        cfg.combatWindMouseWind,
                        cfg.combatWindMouseMaxStep,
                        cfg.combatWindMouseWindDist,
                        cfg.combatWindMouseDoneThreshold,
                        cfg.combatWindMouseFlickScale
                );
                WindMouseRotation.INSTANCE.setTarget(safety.getAimYaw(), safety.getAimPitch());
            }
        }

        if (cfg.combatTriggerBotEnabled) {
            triggerBot.tick(player, target);
        }

        return true;
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
