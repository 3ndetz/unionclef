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
    // hit tracking for progress detection
    private int totalHits = 0;
    private int lastCheckHits = 0;
    private int ticksSinceLastHit = 0;

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

        ticksSinceLastHit++;

        if (underCrosshair == target
                && cooldown >= COOLDOWN_THRESHOLD
                && !clickedThisCycle) {
            mc.options.attackKey.setPressed(true);
            KeyBinding.onKeyPressed(mc.options.attackKey.getDefaultKey());
            clickedThisCycle = true;
            needsRelease = true;
            totalHits++;
            ticksSinceLastHit = 0;
        }
    }

    /** True if no hits landed in the last N ticks. */
    public boolean hasNoProgress(int tickThreshold) {
        return ticksSinceLastHit > tickThreshold;
    }

    public int getTotalHits() { return totalHits; }
    public int getTicksSinceLastHit() { return ticksSinceLastHit; }

    public void reset() {
        clickedThisCycle = false;
        if (needsRelease) {
            MinecraftClient.getInstance().options.attackKey.setPressed(false);
        }
        needsRelease = false;
        totalHits = 0;
        lastCheckHits = 0;
        ticksSinceLastHit = 0;
    }
}
