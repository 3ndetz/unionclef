package kaptainwutax.tungsten.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.SwordItem;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.Random;

/**
 * Trigger bot — clicks attack when crosshair is on a valid target.
 *
 * Two modes based on held item:
 *   FAST  — fist / no-cooldown (1.8-style): click as fast as possible
 *   TIMED — sword/axe: wait for cooldown + random human delay
 *
 * Human-like behavior:
 *   - Reaction delay: won't click until target has been under crosshair for 1-3 ticks
 *   - Post-cooldown jitter: random 1-3 tick delay after cooldown is ready
 *   - Click duration: holds attack key for 1-2 ticks (not instant release)
 */
public class TriggerBot {

    private static final float COOLDOWN_THRESHOLD = 0.9f;

    // reaction: how many ticks target must be under crosshair before we click
    private static final int REACTION_MIN = 1;
    private static final int REACTION_MAX = 3;

    // post-cooldown jitter: random delay after cooldown is ready
    private static final int JITTER_MIN = 1;
    private static final int JITTER_MAX = 3;

    // click hold duration
    private static final int HOLD_MIN = 1;
    private static final int HOLD_MAX = 2;

    private final Random rng = new Random();

    // how many ticks the current target has been under crosshair
    private int crosshairTicks = 0;
    // reaction threshold for current "acquisition" (randomized per acquisition)
    private int reactionThreshold = REACTION_MIN;

    // ticks to wait after cooldown before clicking
    private int jitterWait = 0;
    // ticks remaining with attack key held down
    private int holdRemaining = 0;

    // last entity under crosshair (to detect new acquisition)
    private Entity lastCrosshairEntity = null;

    // ── tick ─────────────────────────────────────────────────────────────────

    /**
     * Call every game tick. Manages attack key press/release.
     *
     * @param player the local player
     * @param target the combat target (for validation — only clicks on this entity)
     */
    public void tick(ClientPlayerEntity player, Entity target) {
        MinecraftClient mc = MinecraftClient.getInstance();

        // if still holding from a previous click, count down and release
        if (holdRemaining > 0) {
            holdRemaining--;
            if (holdRemaining <= 0) {
                mc.options.attackKey.setPressed(false);
            }
            return;
        }

        // check what's under crosshair right now
        Entity underCrosshair = getCrosshairEntity(mc);

        // is it our target?
        if (underCrosshair != null && underCrosshair == target) {
            // new acquisition? reset reaction
            if (underCrosshair != lastCrosshairEntity) {
                crosshairTicks = 0;
                reactionThreshold = REACTION_MIN + rng.nextInt(REACTION_MAX - REACTION_MIN + 1);
                jitterWait = 0;
            }
            lastCrosshairEntity = underCrosshair;
            crosshairTicks++;

            // haven't "reacted" yet
            if (crosshairTicks < reactionThreshold) return;

            // cooldown check
            boolean fast = isFastMode(player);
            if (!fast) {
                float cooldown = player.getAttackCooldownProgress(0.5f);
                if (cooldown < COOLDOWN_THRESHOLD) {
                    jitterWait = 0;
                    return;
                }
                // cooldown ready — apply jitter
                if (jitterWait <= 0) {
                    jitterWait = JITTER_MIN + rng.nextInt(JITTER_MAX - JITTER_MIN + 1);
                }
                jitterWait--;
                if (jitterWait > 0) return;
            }

            // click
            mc.options.attackKey.setPressed(true);
            holdRemaining = HOLD_MIN + rng.nextInt(HOLD_MAX - HOLD_MIN + 1);
        } else {
            // target lost from crosshair
            lastCrosshairEntity = underCrosshair;
            crosshairTicks = 0;
            jitterWait = 0;
            mc.options.attackKey.setPressed(false);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Returns the entity under MC's crosshair raycast, or null. */
    private static Entity getCrosshairEntity(MinecraftClient mc) {
        if (mc.crosshairTarget == null) return null;
        if (mc.crosshairTarget.getType() != HitResult.Type.ENTITY) return null;
        return ((EntityHitResult) mc.crosshairTarget).getEntity();
    }

    /** True if player holds fist or a non-cooldown item (fast click mode). */
    private static boolean isFastMode(ClientPlayerEntity player) {
        var stack = player.getMainHandStack();
        if (stack.isEmpty()) return true;
        var item = stack.getItem();
        return !(item instanceof SwordItem) && !(item instanceof AxeItem);
    }

    /** Reset all state. Call when exiting combat. */
    public void reset() {
        crosshairTicks = 0;
        reactionThreshold = REACTION_MIN;
        jitterWait = 0;
        holdRemaining = 0;
        lastCrosshairEntity = null;
        MinecraftClient.getInstance().options.attackKey.setPressed(false);
    }
}
