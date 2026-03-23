package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.world.WorldView;

/**
 * PvP combat controller — clean skeleton.
 *
 * Two independent loops (to be implemented):
 *   LEGS  — movement: sprint-jump, strafe, edge avoidance, knockback recovery
 *   MOUSE — aiming: WindMouse rotation toward target, attack on crosshair hit
 *
 * Design principles:
 *   - Legit inputs only (WASD/Space/Click), no packet manipulation
 *   - Attack timing respects vanilla cooldown
 *   - Rotation via WindMouse (human-like, not instant snap)
 *   - Each subsystem (legs/mouse) is independently tickable
 */
public class CombatController {

    private final TriggerBot triggerBot = new TriggerBot();

    // ── tick ─────────────────────────────────────────────────────────────────

    /**
     * Main combat tick. Called every game tick while in combat mode.
     *
     * @return true if target is valid and combat is running, false if target lost
     */
    public boolean tick(ClientPlayerEntity player, Entity target, WorldView world) {
        if (target == null || target.isRemoved() || !target.isAlive()) return false;

        // TODO: tickLegs(player, target, world)
        // TODO: tickMouse(player, target)

        triggerBot.tick(player, target);

        return true;
    }

    // ── cleanup ──────────────────────────────────────────────────────────────

    /** Release all keys and reset state. Call when exiting combat. */
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
        WindMouseRotation.INSTANCE.clearTarget();
    }
}
