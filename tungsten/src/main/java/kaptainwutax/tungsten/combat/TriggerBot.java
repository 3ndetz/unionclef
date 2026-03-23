package kaptainwutax.tungsten.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;

/**
 * Trigger bot — if our target is under crosshair and cooldown ready, click.
 * Uses KeyBinding.onKeyPressed() to register a real key event that MC sees
 * via wasPressed() in handleInputEvents().
 */
public class TriggerBot {

    private static final float COOLDOWN_THRESHOLD = 0.9f;

    // prevent double-clicking on same cooldown cycle
    private boolean clickedThisCycle = false;
    // release the key on the tick after pressing
    private boolean needsRelease = false;

    public void tick(ClientPlayerEntity player, Entity target) {
        MinecraftClient mc = MinecraftClient.getInstance();

        // always release from previous tick's click
        if (needsRelease) {
            // only release if player isn't manually holding attack
            if (!mc.options.attackKey.isPressed() || clickedThisCycle) {
                mc.options.attackKey.setPressed(false);
            }
            needsRelease = false;
        }

        Entity underCrosshair = mc.targetedEntity;
        float cooldown = player.getAttackCooldownProgress(0.5f);

        // reset click lock when cooldown resets (new cycle)
        if (cooldown < COOLDOWN_THRESHOLD) {
            clickedThisCycle = false;
        }

        if (underCrosshair == target
                && cooldown >= COOLDOWN_THRESHOLD
                && !clickedThisCycle) {
            mc.options.attackKey.setPressed(true);
            KeyBinding.onKeyPressed(mc.options.attackKey.getDefaultKey());
            clickedThisCycle = true;
            needsRelease = true;
        }
    }

    public void reset() {
        clickedThisCycle = false;
        if (needsRelease) {
            MinecraftClient.getInstance().options.attackKey.setPressed(false);
        }
        needsRelease = false;
    }
}
