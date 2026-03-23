package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * PvP combat controller.
 *
 * Subsystems:
 *   SAFETY  — render-frequency: viz, edge detection, braking (manages own keys)
 *   MOUSE   — render-frequency via WindMouse: rotation toward target
 *   TRIGGER — tick-frequency: auto-click when crosshair lands on target
 *
 * TODO: LEGS — movement (sprint-jump, strafe)
 */
public class CombatController {

    private final TriggerBot triggerBot = new TriggerBot();
    public static final SafetySystem safety = new SafetySystem();

    public boolean tick(ClientPlayerEntity player, Entity target, WorldView world) {
        if (target == null || target.isRemoved() || !target.isAlive()) return false;

        TungstenConfig cfg = TungstenConfig.get();

        // safety tick: enemy velocity tracking (needs fixed dt)
        safety.tick(player, target, world);

        if (cfg.combatRotatesEnabled) {
            // safety braking overrides aim
            if (safety.isBraking()) {
                WindMouseRotation.INSTANCE.setParams(
                        cfg.combatWindMouseGravity * 2,
                        cfg.combatWindMouseWind * 0.5,
                        cfg.combatWindMouseMaxStep * 2,
                        cfg.combatWindMouseWindDist,
                        cfg.combatWindMouseDoneThreshold,
                        cfg.combatWindMouseFlickScale
                );
                WindMouseRotation.INSTANCE.setTarget(safety.getBrakeYaw(), 0);

                if (cfg.combatTriggerBotEnabled) {
                    triggerBot.tick(player, target);
                }
                return true;
            }

            // ── mouse: aim at target ─────────────────────────────────────
            Vec3d targetCenter = target.getPos().add(0, target.getHeight() * 0.5, 0);
            float yaw = AttackTiming.yawTo(player.getPos(), target.getPos());
            float pitch = AttackTiming.pitchTo(player.getEyePos(), targetCenter);

            WindMouseRotation.INSTANCE.setParams(
                    cfg.combatWindMouseGravity,
                    cfg.combatWindMouseWind,
                    cfg.combatWindMouseMaxStep,
                    cfg.combatWindMouseWindDist,
                    cfg.combatWindMouseDoneThreshold,
                    cfg.combatWindMouseFlickScale
            );
            WindMouseRotation.INSTANCE.setTarget(yaw, pitch);
        }

        // ── trigger bot ──────────────────────────────────────────────────
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
