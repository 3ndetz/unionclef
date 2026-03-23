package kaptainwutax.tungsten.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;

/**
 * Trigger bot — if our target is under crosshair and cooldown ready, click.
 * Uses attackKey press/release cycle: MC needs a false→true transition
 * (wasPressed) to register as a click.
 */
public class TriggerBot {

    private static final float COOLDOWN_THRESHOLD = 0.9f;

    // true = we pressed attack last tick, need to release this tick
    private boolean pressedLastTick = false;

    public void tick(ClientPlayerEntity player, Entity target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean alreadyPressed = mc.options.attackKey.isPressed();

        // after a press, release for one tick so MC sees the full click cycle
        if (pressedLastTick) {
            if (!alreadyPressed) {
                mc.options.attackKey.setPressed(false);
            }
            pressedLastTick = false;
            return;
        }

        // if player is already holding attack, don't interfere
        if (alreadyPressed) return;

        Entity underCrosshair = mc.targetedEntity;

        if (underCrosshair == target
                && player.getAttackCooldownProgress(0.5f) >= COOLDOWN_THRESHOLD) {
            mc.options.attackKey.setPressed(true);
            pressedLastTick = true;
        }
    }

    public void reset() {
        pressedLastTick = false;
    }
}
