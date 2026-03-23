package kaptainwutax.tungsten.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;

/**
 * Trigger bot — bare minimum: if our target is under crosshair and cooldown ready, click.
 */
public class TriggerBot {

    private static final float COOLDOWN_THRESHOLD = 0.9f;

    public void tick(ClientPlayerEntity player, Entity target) {
        MinecraftClient mc = MinecraftClient.getInstance();

        // mc.targetedEntity is the actual entity under crosshair, set by GameRenderer
        Entity underCrosshair = mc.targetedEntity;

        if (underCrosshair == target
                && player.getAttackCooldownProgress(0.5f) >= COOLDOWN_THRESHOLD) {
            mc.options.attackKey.setPressed(true);
        } else {
            mc.options.attackKey.setPressed(false);
        }
    }

    public void reset() {
        MinecraftClient.getInstance().options.attackKey.setPressed(false);
    }
}
