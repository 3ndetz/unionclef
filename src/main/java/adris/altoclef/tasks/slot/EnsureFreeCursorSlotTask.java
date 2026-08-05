package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Optional;

public class EnsureFreeCursorSlotTask extends Task {

    /**
     * Clicks spent trying to put the cursor down before we stop believing the destination.
     *
     * <p>This task used to click and return null for ever, with nothing checking that the click
     * changed anything. Caught in a playthrough stall capture: the bot stood still for ninety
     * seconds inside
     * {@code <Breaking the crafting grid> Clearing the 2x2 crafting grid ->
     *  <Breaking the cursor slot> Moving cursor stack back}
     * with 511 slot clicks issued and the cursor still full. The slot handler was fine -- its
     * blacklist was empty and its drops were just the ordinary rate gate -- the clicks simply did
     * not land, because the slot offered as "can fit" would not take the stack.
     *
     * <p>Forty is a couple of seconds at the container move delay: long enough that a slow but
     * working exchange finishes normally, short enough that a hopeless one cannot own the run.
     */
    private static final int CLICKS_BEFORE_GIVING_UP = 40;

    /** Clicks spent on the CURRENT cursor stack; reset whenever the cursor actually changes. */
    private int clicksOnThisStack = 0;
    private ItemStack lastCursor = ItemStack.EMPTY;

    /** Times a cursor stack had to be thrown because no slot would accept it. Read as slotYeet. */
    public static volatile int cursorGaveUp;

    @Override
    protected void onStart() {
        clicksOnThisStack = 0;
        lastCursor = ItemStack.EMPTY;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();

        // PROGRESS IS THE CURSOR CHANGING, NOT THE CLICK BEING SENT.
        // Any change -- a different item, a different count, or an empty cursor -- means the
        // exchange is working and the budget starts again.
        if (cursor.isEmpty() || !ItemStack.areEqual(cursor, lastCursor)) {
            clicksOnThisStack = 0;
            lastCursor = cursor.copy();
        }

        if (!cursor.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
            if (moveTo.isPresent() && clicksOnThisStack < CLICKS_BEFORE_GIVING_UP) {
                setDebugState("Moving cursor stack back");
                clicksOnThisStack++;
                mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
                return null;
            }
            if (moveTo.isPresent()) {
                // The destination says it can fit and the cursor says otherwise, forty clicks
                // running. Throwing the stack loses one stack; standing here loses the run, and
                // dropped items are pickup-able, so this is the cheaper mistake of the two.
                setDebugState("Cursor will not go down — throwing it");
                cursorGaveUp++;
                clicksOnThisStack = 0;
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                return null;
            }
            if (ItemHelper.canThrowAwayStack(mod, cursor)) {
                setDebugState("Incompatible cursor stack, throwing");
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            } else {
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                if (garbage.isPresent()) {
                    // Pick up garbage so we throw it out next frame
                    setDebugState("Picking up garbage");
                    mod.getSlotHandler().clickSlot(garbage.get(), 0, SlotActionType.PICKUP);
                } else {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                }
            }
            return null;
        }
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EnsureFreeCursorSlotTask;
    }


    // And filling this in will make it look ok in the task tree
    @Override
    protected String toDebugString() {
        return "Breaking the cursor slot";
    }
}
