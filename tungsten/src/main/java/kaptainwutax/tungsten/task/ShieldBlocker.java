package kaptainwutax.tungsten.task;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Shield-block execution primitive: hold use for N ticks (shield must already
 * be in a hand — equipping it is the inventory side's job, e.g. altoclef's
 * forceEquipItemToOffhand). Blocking only covers the front — facing the
 * threat is the caller's aim decision.
 *
 * Yields to BowShooter (both drive the use key).
 */
public class ShieldBlocker {

    private static int holdTicks = 0;

    /** Raise (or extend) the shield for the given number of ticks. */
    /**
     * Hold the use key for {@code ticks}. NOTHING HERE CHECKS WHAT IS IN HAND — that is the
     * caller's job, and it matters: pressing use with food in the main hand eats it, with a
     * bow draws it, with a block places it. The combat engine checks for a shield in the OFF
     * hand before calling this.
     */
    public static synchronized void hold(int ticks) {
        holdTicks = Math.max(holdTicks, ticks);
    }

    public static synchronized void release() {
        holdTicks = 0;
        MinecraftClient.getInstance().options.useKey.setPressed(false);
    }

    public static boolean isBlocking() {
        return holdTicks > 0;
    }

    /** Called every game tick from MixinClientPlayerEntity. */
    public static void tick(ClientPlayerEntity player) {
        if (holdTicks <= 0) return;
        if (BowShooter.isActive()) return; // the bow owns the use key right now
        holdTicks--;
        MinecraftClient.getInstance().options.useKey.setPressed(true);
        if (holdTicks == 0) {
            MinecraftClient.getInstance().options.useKey.setPressed(false);
        }
    }
}
