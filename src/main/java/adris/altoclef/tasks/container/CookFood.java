package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Which block to cook food in: a smoker when one is at hand or cheap to make, otherwise a furnace.
 *
 * <p>⛔ FOOD WAS COOKED ONLY IN A SMOKER (full58, 2026-09-30). A smoker is a furnace plus four logs.
 * At y=9, with a furnace a few blocks away that had just smelted gold, the bot spent 42 minutes in
 * "Doing stuff in [smoker] container: cooked_chicken x4 -- Getting container item": no logs
 * underground, and no other way to cook. A furnace cooks the same food at half the speed, and half
 * the speed of four pieces of chicken is twenty seconds.
 */
public final class CookFood {

    /** Within this distance a placed smoker or furnace counts as at hand. */
    private static final double AT_HAND = 48;
    /** Logs a smoker needs on top of a furnace. */
    private static final int SMOKER_LOGS = 4;
    /** Cobblestone-like blocks a new furnace needs. */
    private static final int FURNACE_STONE = 8;

    /** Cooking tasks this class handed out, so a caller can tell them from ore smelting. */
    private static final Set<Task> COOKING = Collections.newSetFromMap(new WeakHashMap<>());
    public static volatile int smokerChosen, furnaceChosen;

    private CookFood() {}

    public static Task task(AltoClef mod, SmeltTarget target, boolean ignoreMaterials) {
        var inv = mod.getItemStorage();
        var scanner = mod.getBlockScanner();
        boolean smokerAtHand = inv.hasItem(Items.SMOKER) || scanner.anyFoundWithinDistance(AT_HAND, Blocks.SMOKER);
        boolean furnaceAtHand = inv.hasItem(Items.FURNACE) || scanner.anyFoundWithinDistance(AT_HAND, Blocks.FURNACE);
        boolean logsForSmoker = inv.getItemCount(ItemHelper.LOG) >= SMOKER_LOGS;
        boolean stoneForFurnace = inv.getItemCount(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE) >= FURNACE_STONE;

        Task t;
        if (smokerAtHand || (logsForSmoker && (furnaceAtHand || stoneForFurnace)) || !(furnaceAtHand || stoneForFurnace)) {
            // A smoker is here or cheap -- or neither can be made from what is carried, in which
            // case the smoker path is the old default and gathers what it needs.
            SmeltInSmokerTask s = new SmeltInSmokerTask(target);
            if (ignoreMaterials) s.ignoreMaterials();
            t = s;
            smokerChosen++;
        } else {
            SmeltInFurnaceTask f = new SmeltInFurnaceTask(target);
            if (ignoreMaterials) f.ignoreMaterials();
            t = f;
            furnaceChosen++;
        }
        COOKING.add(t);
        return t;
    }

    /** Was this task handed out here, to cook food (and not, say, to smelt iron)? */
    public static boolean isCooking(Task t) {
        return t != null && COOKING.contains(t);
    }
}
