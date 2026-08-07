package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Optional;

/**
 * Assuming a crafting screen is open, crafts a recipe.
 * <p>
 * Not useful for custom tasks.
 */
/*
 * IMPLEMENTS ITaskUsesCraftingGrid, AND THAT ONE WORD IS THE WHOLE BUG.
 *
 * ResourceTask.onTick (line ~98) asks "does anything under me claim the crafting grid?" and, if
 * nothing does, treats an item in the CURSOR that matches its targets as a stray to be put away --
 * "Moving from cursor", click, back in the pack. The marker interface is how a craft says "that
 * item in my hand is mine, I am mid-placement".
 *
 * CraftGenericWithRecipeBooksTask declares it. This task -- the MANUAL one, which became the
 * default when the recipe book was turned off because servers disable it -- did not. So every time
 * the mover picked an ingredient up, the parent took it away again, and the craft could never place
 * a single item.
 *
 * The evidence, in the order it was found: mc=1854/0/0/0/0 (asking for a move every tick and
 * nothing else), the click trace showing pick-up-from-36 then straight back into 36, no
 * MOVEMISMATCH line (so the ingredient was correct all along), and finally
 * "CURSORBACK onResourceStop holding=minecraft:oak_log interrupt=EnsureFreePlayerCraftingGridTask",
 * which named both the hand that returned it and the task that interrupted the craft.
 */
public class CraftGenericManuallyTask extends Task implements adris.altoclef.tasksystem.ITaskUsesCraftingGrid {

    /**
     * WHERE A MANUAL CRAFT ACTUALLY GOES. Read as mc=fill/short/out/wait/invalid.
     *
     * <p>This task had no counters at all, and it is where the playthrough now stops: two identical
     * chain snapshots at the end of a run, six levels deep, ending at "Getting planks x 2" with the
     * logs already in the pack. The bot is at a TABLE, planks are a 2x2 recipe, so the route is the
     * big-grid branch and the 2x2-into-3x3 slot mapping. Which of the five things below it does
     * every tick decides what to read next, and nothing currently said.
     *
     * <p>FIRST READING, and it names the component: <b>mc=1051/0/0/0/0</b>. Every single tick took
     * the "Moving item to slot..." branch and returned a MoveItemToSlotFromInventoryTask -- 1051 of
     * them, about fifty seconds of solid loop -- while mcShort, mcFromOutput, mcWait and
     * mcInvalidSlot all stayed at zero, and ciGrid stayed 0 as well, meaning nothing ever landed in
     * the grid. So the slot never becomes satisfied because the item never arrives: this task is
     * asking correctly and MoveItemToSlotFromInventoryTask is not delivering. That is the next
     * thing to read -- not this loop, and not the recipe mapping.
     */
    public static volatile int mcFilled, mcShort, mcFromOutput, mcWait, mcInvalidSlot;

    /**
     * Ticks where the ingredient this slot wants was already in the cursor, mid-move. Read as
     * mcFlight. Zero on a build without the fix below is not proof of anything; a NON-zero reading
     * is proof that the carousel condition really does occur.
     */
    public static volatile int mcInFlight;

    private final RecipeTarget target;

    public CraftGenericManuallyTask(RecipeTarget target) {
        this.target = target;
    }

    @Override
    protected void onStart() {

    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        boolean bigCrafting = StorageHelper.isBigCraftingOpen();

        if (!bigCrafting && !StorageHelper.isPlayerInventoryOpen()) {
            // Make sure we're not in another screen before we craft,
            // otherwise crafting won't work
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (!cursorStack.isEmpty()) {
                Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
                if (moveTo.isPresent()) {
                    mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
                    return null;
                }
                if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                    return null;
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                // Try throwing away cursor slot if it's garbage
                if (garbage.isPresent()) {
                    mod.getSlotHandler().clickSlot(garbage.get(), 0, SlotActionType.PICKUP);
                    return null;
                }
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            } else {
                StorageHelper.closeScreen();
            }
            // Just to be safe
        }

        Slot outputSlot = bigCrafting ? CraftingTableSlot.OUTPUT_SLOT : PlayerSlot.CRAFT_OUTPUT_SLOT;

        // Example:
        // We need 9 sticks
        // plank recipe results in 4 sticks
        // this means 3 planks per slot
        int requiredPerSlot = (int) Math.ceil((double) target.getTargetCount() / target.getRecipe().outputCount());

        // For each slot in table
        for (int craftSlot = 0; craftSlot < target.getRecipe().getSlotCount(); ++craftSlot) {
            ItemTarget toFill = target.getRecipe().getSlot(craftSlot);
            Slot currentCraftSlot;
            if (bigCrafting) {
                // Craft in table
                currentCraftSlot = CraftingTableSlot.getInputSlot(craftSlot, target.getRecipe().isBig());
            } else {
                // Craft in window
                currentCraftSlot = PlayerSlot.getCraftInputSlot(craftSlot);
            }
            ItemStack present = StorageHelper.getItemStackInSlot(currentCraftSlot);
            if (toFill == null || toFill.isEmpty()) {
                if (present.getItem() != Items.AIR) {
                    // Move this item OUT if it should be empty
                    setDebugState("Found INVALID slot");
                    mcInvalidSlot++;
                    mod.getSlotHandler().clickSlot(currentCraftSlot, 0, SlotActionType.PICKUP);
                }
            } else {
                boolean correctItem = toFill.matches(present.getItem());
                boolean isSatisfied = correctItem && present.getCount() >= requiredPerSlot;
                if (!isSatisfied) {
                    // ASK ABOUT THE INGREDIENT WE NEED, NOT ABOUT WHATEVER IS SITTING THERE.
                    // The question this guard means to ask is "have we run out, so should we take
                    // what the grid already made instead" -- and it asked it about `present`, the
                    // stack IN the craft slot. For a slot still to be filled `present` is AIR, so
                    // it asked whether the inventory contains AIR.
                    //
                    // That answer is not even stable: hasItem checks the CURSOR first
                    // (InventorySubTracker.java:76-79), and an empty cursor is an ItemStack whose
                    // item is AIR. So with an empty cursor it said "yes, we have air", the guard
                    // fell through and the slot got filled -- crafting worked by accident. The
                    // moment the bot is holding anything, the same call says no, every slot is
                    // skipped by the `continue` below, and with an empty output the tail of this
                    // method waits. Nothing fills, nothing comes out, and it never recovers,
                    // because clearing the cursor is gated on the output NOT stacking with it and
                    // an empty output stacks with everything.
                    // Measured on the playthrough: 1856 ticks inside this task with the materials
                    // in the pack, ciReceive=0, ciGrid=0 -- the grid empty because the ingredients
                    // never went in.
                    // THE STACK IN FLIGHT LIVES IN THE CURSOR, AND THIS QUESTION COULD NOT SEE IT.
                    // Filling a slot takes two ticks: the mover picks the ingredient UP into the
                    // cursor, and puts it DOWN on the next one. Between those two ticks the item is
                    // in neither the pack nor the grid. So when the stack being moved is the last of
                    // its kind, this guard reads "we have run out", takes the `continue` below, the
                    // loop ends with nothing returned -- which drops the mover mid-move -- and the
                    // tail of this method, finding a cursor that does not stack with an empty
                    // output, puts the ingredient back in the pack. Next tick it is in the pack
                    // again, the mover picks it up again, and the craft rides that carousel forever.
                    // Measured on the smelt course, from the tag on that tail:
                    //   90x CURSORBACK manualTail holding=minecraft:oak_log
                    //   30x CURSORBACK manualTail holding=minecraft:stick
                    //   30x CURSORBACK manualTail holding=minecraft:oak_planks
                    // Note what this does NOT go back to: `hasItem`, which checks the cursor FIRST
                    // and reports an empty cursor as AIR, is the bug the comment above describes.
                    // Asking whether the cursor holds THIS INGREDIENT is a different question and
                    // has no such hole -- toFill is non-empty here, so an empty cursor never matches.
                    boolean inFlight = toFill.matches(StorageHelper.getItemStackInCursorSlot().getItem());
                    if (inFlight) {
                        mcInFlight++;
                    }
                    if (!inFlight && !mod.getItemStorage().hasItemInventoryOnly(toFill.getMatches())) {
                        if (!StorageHelper.getItemStackInSlot(outputSlot).isEmpty()) {
                            setDebugState("NO MORE to fit: grabbing from output.");
                            mcFromOutput++;
                            return new ReceiveCraftingOutputSlotTask(outputSlot, target.getTargetCount());
                        } else {
                            // Move on to the NEXT slot, we can't fill this one anymore.
                            mcShort++;
                            continue;
                        }
                    }

                    setDebugState("Moving item to slot...");
                    mcFilled++;
                    return new MoveItemToSlotFromInventoryTask(new ItemTarget(toFill, requiredPerSlot), currentCraftSlot);
                }
                // We could be OVER satisfied
                boolean oversatisfies = present.getCount() > requiredPerSlot;
                if (oversatisfies) {
                    setDebugState("OVER SATISFIED slot! Right clicking slot to extract half and spread it out more.");
                    mod.getSlotHandler().clickSlot(currentCraftSlot, 0, SlotActionType.PICKUP);
                }
            }
        }

        // Ensure our cursor is empty/can receive our item
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!ItemHelper.canStackTogether(StorageHelper.getItemStackInSlot(outputSlot), cursor)) {
            // The twin of the tag in CraftInInventoryTask.onResourceStop -- see the note there.
            if (!cursor.isEmpty()) {
                adris.altoclef.Debug.logMessage("CURSORBACK manualTail holding=" + cursor.getItem());
            }
            Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false).or(() -> StorageHelper.getGarbageSlot(mod));
            if (toFit.isPresent()) {
                mod.getSlotHandler().clickSlot(toFit.get(), 0, SlotActionType.PICKUP);
            } else {
                // Eh screw it
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            }
        }

        if (!StorageHelper.getItemStackInSlot(outputSlot).isEmpty()) {
            return new ReceiveCraftingOutputSlotTask(outputSlot, target.getTargetCount());
        } else {
            // Wait
            mcWait++;
            return null;
        }
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CraftGenericManuallyTask task) {
            return task.target.equals(target);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Crafting: " + target;
    }
}
