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

    private WeaponSelector() {}

    /** Melee score of a stack; 0 means "not a weapon". */
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
        return true;
    }

}
