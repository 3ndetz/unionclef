package kaptainwutax.tungsten.combat;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Picks the melee weapon the combat primitive swings with.
 *
 * Execution detail, not strategy: the punk engine used to swing WHATEVER was in
 * hand, so a bot that had just fired a bow kept "fighting" with the bow (1 dmg
 * per hit) while a sword sat in the hotbar — it lost fights it should have won
 * (user 2026-07-24). The brain (altoclef / the agent) still decides WHEN to
 * fight and may force a slot; this only guarantees we are not swinging a tool
 * when a real weapon is one hotbar slot away.
 *
 * Scored from an explicit item table rather than `instanceof SwordItem`: the
 * item-class hierarchy is version-dependent (1.21.2+ moved swords onto data
 * components and the class is gone), the item constants are not.
 *
 * Hotbar only — pulling from the deep inventory is inventory management, which
 * belongs to altoclef.
 */
public final class WeaponSelector {

    /** Re-check cadence in ticks: cheap, but no reason to scan every tick. */
    private static final int RECHECK_TICKS = 20;
    private static int cooldown = 0;

    /**
     * Force the next {@link #equipBestMelee} call to actually look, instead of spending up to
     * RECHECK_TICKS coasting on a decision made before the world changed under it.
     *
     * <p>MEASURED: on allround the mean melee score of what the bot was HOLDING as it swung came
     * back 59.21 against an iron_sword's 75.0, i.e. about one swing in five went out with a score
     * of zero -- a bow, worth roughly one damage where the sword is six. The course hands the bot
     * a bow for its ranged phase and restores the sword only when the driver's rcon poll sees the
     * range close (scenarios_pvp:693), and the bot dies seventeen times a course, so seventeen
     * lives BEGIN holding the bow while this cadence is mid-count. PunkPlayerTask's own comment
     * already records the failure this produces: "kept fighting with the bow (2 dmg/hit) while a
     * sword sat in the hotbar and it lost the fight".
     *
     * <p>The cadence itself is right -- switching slots resets the attack cooldown, so re-checking
     * every tick would cost more than it saves. What is wrong is carrying a stale count across the
     * moment combat STARTS, which is exactly when the hand is most likely to hold the wrong thing.
     */
    public static void forceRecheck() {
        cooldown = 0;
    }

    /**
     * Is the hotbar holding something strictly better than what is in the hand right now?
     *
     * <p>Exists so the swing itself can decline. Forcing a re-check when combat starts took the
     * bow swings from 21% of all swings down to 9% but not to zero, because a slot switch is not
     * instantaneous: {@code equipBestMelee} sets the selected slot and the swing can still go out
     * the same tick with the old item in hand. Rate-limiting the CHECK cannot fix that; only the
     * attack declining can.
     *
     * <p>Strictly better, and only ever consulted when the hand is empty of a real weapon, so a bot
     * with nothing but its fists still swings rather than standing there waiting for a sword that
     * does not exist.
     */
    public static boolean hasBetterThanHeld(ClientPlayerEntity player) {
        if (player == null) return false;
        double held = meleeScore(player.getInventory().getStack(
                player.getInventory().getSelectedSlot()));
        for (int slot = 0; slot < 9; slot++) {
            if (meleeScore(player.getInventory().getStack(slot)) > held) return true;
        }
        return false;
    }

    /** Melee value of each weapon (vanilla attack damage, ties broken by speed). */
    private static final Map<Item, Double> MELEE_SCORE = new HashMap<>();
    static {
        MELEE_SCORE.put(Items.NETHERITE_SWORD, 100.0);
        MELEE_SCORE.put(Items.DIAMOND_SWORD,    90.0);
        MELEE_SCORE.put(Items.NETHERITE_AXE,    88.0);
        MELEE_SCORE.put(Items.DIAMOND_AXE,      82.0);
        MELEE_SCORE.put(Items.IRON_SWORD,       75.0);
        MELEE_SCORE.put(Items.IRON_AXE,         70.0);
        MELEE_SCORE.put(Items.STONE_SWORD,      60.0);
        MELEE_SCORE.put(Items.STONE_AXE,        55.0);
        MELEE_SCORE.put(Items.GOLDEN_SWORD,     50.0);
        MELEE_SCORE.put(Items.WOODEN_SWORD,     45.0);
        MELEE_SCORE.put(Items.GOLDEN_AXE,       42.0);
        MELEE_SCORE.put(Items.WOODEN_AXE,       40.0);
        MELEE_SCORE.put(Items.TRIDENT,          78.0);
        // mace exists from 1.21; guarded so older mappings still compile
        try {
            MELEE_SCORE.put(Items.MACE, 85.0);
        } catch (Throwable ignored) {
        }
    }

    /** Counts as ammunition for a bow or a crossbow. */
    private static final java.util.Set<Item> ARROWS =
            java.util.Set.of(Items.ARROW, Items.SPECTRAL_ARROW, Items.TIPPED_ARROW);

    /**
     * Is there any way to turn DISTANCE into DAMAGE — a launcher, and something to put in it?
     *
     * <p>Asked before backing a wounded bot out of a fight. That retreat is justified in
     * CombatController by "out past reach the bow is the weapon", and the justification holds
     * exactly when a bow exists. On a sword-only kit it does not: the bot walks out of range,
     * deals nothing, cannot heal (no regeneration, no food) and hands over the initiative. That
     * is the shape of the two red courses — melee_basic and narrow_bridge, both KIT_SWORD, both
     * losing the exchange while every other check passes — against edge_duel, which is a 5x5
     * platform with nowhere to retreat TO and wins 4/4 on the same jar.
     *
     * <p>The two halves are scanned differently, on purpose. The LAUNCHER has to be reachable to
     * be fired and this class only ever equips from the hotbar, so hotbar plus offhand is the
     * honest question. The AMMUNITION is searched across the whole inventory because that is what
     * vanilla does when the string is released — asking a narrower question would have made the
     * answer depend on where a `/give` happened to drop the arrows, and the retreat would then
     * disappear from a bow course silently, the first time a kit filled the hotbar first.
     *
     * <p>A charged crossbow can fire once with no ammunition. Not modelled: it would change the
     * answer only for that single loaded shot, and no course produces one.
     *
     * <p>⚠️ WHAT THIS PREDICATE DOES NOT COVER, for whoever extends it. It answers "can I convert
     * distance into damage", which is the only justification the retreat currently claims. It is
     * NOT the right question for a retreat that means to HEAL: on the bench that distinction is
     * invisible because regeneration is off and no kit carries food, but in a real world a wounded
     * bot could break contact and recover. Nothing in tungsten does that today — the module never
     * eats and never waits out a regen — so removing the retreat removes nothing. If a
     * disengage-to-heal behaviour is ever added, it needs its OWN predicate and must not be hung
     * on this one, or it will be gated on owning a bow for no reason.
     */
    public static boolean hasRangedOption(ClientPlayerEntity player) {
        if (player == null) return false;
        boolean launcher = false;
        for (int slot = 0; slot < 9 && !launcher; slot++) {
            Item it = player.getInventory().getStack(slot).getItem();
            if (it == Items.BOW || it == Items.CROSSBOW) launcher = true;
        }
        if (!launcher) {
            Item off = player.getOffHandStack().getItem();
            launcher = off == Items.BOW || off == Items.CROSSBOW;
        }
        if (!launcher) return false;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            if (ARROWS.contains(player.getInventory().getStack(slot).getItem())) return true;
        }
        return ARROWS.contains(player.getOffHandStack().getItem());
    }

    private WeaponSelector() {}

    /** Melee score of a stack; 0 means "not a weapon". */
    /** Slot changes we told the server about; reads 0 with the flag off. */
    public static volatile int slotSyncSent = 0;

    /**
     * Send the slot change NOW instead of waiting for vanilla's per-tick sync.
     *
     * <p>Cheap, idempotent and ORDERED, which is the point: the server applies packets in the
     * order they arrive, so a swing queued after this one can no longer be resolved with the
     * item we just put away. Vanilla sends its own copy on the next tick and it changes nothing.
     */
    public static void syncSlot(net.minecraft.client.network.ClientPlayerEntity player, int slot) {
        if (!kaptainwutax.tungsten.TungstenConfig.get().syncSlotToServer) return;
        try {
            if (player.networkHandler == null) return;
            player.networkHandler.sendPacket(
                    new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(slot));
            slotSyncSent++;
        } catch (Exception ignored) {
            // a sync must never be the thing that breaks a fight
        }
    }

    public static double meleeScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        Double score = MELEE_SCORE.get(stack.getItem());
        return score == null ? 0 : score;
    }

    /**
     * Ensure the best hotbar melee weapon is held. No-op when the held item is
     * already the best one, or when the hotbar holds no weapon at all (bare
     * fists still beat swinging nothing).
     *
     * @return true if the selected slot was changed
     */
    public static boolean equipBestMelee(ClientPlayerEntity player) {
        if (player == null) return false;
        if (cooldown > 0) { cooldown--; return false; }
        cooldown = RECHECK_TICKS;

        int bestSlot = -1;
        double bestScore = 0;
        for (int slot = 0; slot < 9; slot++) {
            double score = meleeScore(player.getInventory().getStack(slot));
            if (score > bestScore) { bestScore = score; bestSlot = slot; }
        }
        if (bestSlot < 0) return false;                       // nothing to switch to

        int selected = player.getInventory().getSelectedSlot();
        if (selected == bestSlot) return false;
        if (meleeScore(player.getInventory().getStack(selected)) >= bestScore) return false;

        player.getInventory().setSelectedSlot(bestSlot);
        syncSlot(player, bestSlot);
        return true;
    }

}
