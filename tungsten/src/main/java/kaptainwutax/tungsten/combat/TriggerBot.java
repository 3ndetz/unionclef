package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.Debug;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Trigger bot — bare minimum: if our target is under crosshair and cooldown ready, click.
 * No jitter, no reaction delay, no hold duration. Just click.
 */
public class TriggerBot {

    private static final float COOLDOWN_THRESHOLD = 0.9f;

    /**
     * Call every game tick.
     * Checks mc.crosshairTarget against the actual target entity (not tungsten goal).
     * If match + cooldown ready → press attack. Otherwise → release.
     */
    public void tick(ClientPlayerEntity player, Entity target) {
        MinecraftClient mc = MinecraftClient.getInstance();

        Entity underCrosshair = getCrosshairEntity(mc);

        if (underCrosshair == target
                && player.getAttackCooldownProgress(0.5f) >= COOLDOWN_THRESHOLD) {
            mc.options.attackKey.setPressed(true);
            Debug.logMessage("TRIGGER: click on " + target.getName().getString());
        } else {
            mc.options.attackKey.setPressed(false);
        }
    }

    private static Entity getCrosshairEntity(MinecraftClient mc) {
        if (mc.crosshairTarget == null) return null;
        if (mc.crosshairTarget.getType() != HitResult.Type.ENTITY) return null;
        return ((EntityHitResult) mc.crosshairTarget).getEntity();
    }

    public void reset() {
        MinecraftClient.getInstance().options.attackKey.setPressed(false);
    }
}
