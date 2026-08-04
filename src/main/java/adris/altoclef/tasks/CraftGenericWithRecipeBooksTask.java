package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.multiversion.recipemanager.WrappedRecipeEntry;
import adris.altoclef.tasks.slot.EnsureFreePlayerCraftingGridTask;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.JankCraftingRecipeMapping;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Optional;

public class CraftGenericWithRecipeBooksTask extends Task implements ITaskUsesCraftingGrid {

    /** States this task passes through per tick; read over py4j in placeStats(). */
    public static volatile int cgTick, cgBigOpen, cgInvOpen, cgNoScreen, cgSent, cgOutputReady, cgCraftable, cgNotCraftable, cgBookCraftable, cgBookNone;
    /** Item of the last recipe actually sent; read over py4j. */
    public static volatile String cgLastSent = "-";

    private final RecipeTarget target;

    public CraftGenericWithRecipeBooksTask(RecipeTarget target) {
        this.target = target;
    }

    /**
     * This method is called when the mod starts.
     */
    @Override
    protected void onStart() {

    }

    /**
     * This method handles the logic for the onTick event.
     * It checks various conditions and performs actions accordingly.
     *
     * @return The next task to execute.
     */
    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // Check if the big crafting UI or player inventory UI is open
        boolean isBigCraftingOpen = StorageHelper.isBigCraftingOpen();
        boolean isPlayerInventoryOpen = StorageHelper.isPlayerInventoryOpen();
        // WHERE DOES A CRAFT DIE? Measured: twelve minutes with twelve wood items in the pack --
        // a table needs four planks -- and the crafting rung never arrives, while the chain sits
        // in this very task. Count the states it passes through before touching any of them.
        cgTick++;
        if (isBigCraftingOpen) cgBigOpen++;
        else if (isPlayerInventoryOpen) cgInvOpen++;
        else cgNoScreen++;

        // Get the item stack in the cursor slot
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();

        // Declare variables for the slots to move to and the garbage slot
        Optional<Slot> moveTo;
        Optional<Slot> garbage;

        // Check if neither the big crafting UI nor the player inventory UI is open
        if (!isBigCraftingOpen && !isPlayerInventoryOpen) {
            // Check if the cursor stack is not empty
            if (!cursorStack.isEmpty()) {
                // Find a slot in the player's inventory to move the item to
                moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
                if (moveTo.isPresent()) {
                    // Click the slot to move the item to the player's inventory
                    mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
                    return null;
                }
                // Check if the item can be thrown away
                if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                    // Click an undefined slot to throw away the item
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                    return null;
                }
                // Find the garbage slot and click it to move the item there
                garbage = StorageHelper.getGarbageSlot(mod);
                if (garbage.isPresent()) {
                    mod.getSlotHandler().clickSlot(garbage.get(), 0, SlotActionType.PICKUP);
                }
                // Click an undefined slot to clear the cursor stack
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            } else {
                // Close the screen
                StorageHelper.closeScreen();
            }
        }

        // Determine the output slot based on whether the big crafting UI is open
        Slot outputSlot = isBigCraftingOpen ? CraftingTableSlot.OUTPUT_SLOT : PlayerSlot.CRAFT_OUTPUT_SLOT;
        // Get the item stack in the output slot
        ItemStack output = StorageHelper.getItemStackInSlot(outputSlot);

        // Check if the output item matches the target item and the target count has not been reached
        // DOES THE CLICK PRODUCE ANYTHING? A recipe going out (cgSent 3/2/1) is not the same as
        // an item appearing in the output slot, and those two need opposite fixes.
        if (target.getOutputItem() == output.getItem()) cgOutputReady++;
        if (target.getOutputItem() == output.getItem() && mod.getItemStorage().getItemCount(target.getOutputItem()) < target.getTargetCount()) {
            // Return a task to receive the crafting output slot
            return new ReceiveCraftingOutputSlotTask(outputSlot, target.getTargetCount());
        }

        // Check if the cursor stack is not empty
        if (!cursorStack.isEmpty()) {
            // Find a slot in the player's inventory to move the item to
            moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            if (moveTo.isPresent()) {
                // Click the slot to move the item to the player's inventory
                mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
                return null;
            }
            // Check if the item can be thrown away
            if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                // Click an undefined slot to throw away the item
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                return null;
            }
            // Find the garbage slot and click it to move the item there
            garbage = StorageHelper.getGarbageSlot(mod);
            garbage.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP));
            // Click an undefined slot to clear the cursor stack
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            return null;
        }

        // Check if neither the big crafting UI nor the player inventory UI is open
        if (!isBigCraftingOpen) {
            PlayerSlot[] playerInputSlots = PlayerSlot.CRAFT_INPUT_SLOTS;
            for (PlayerSlot playerInputSlot : playerInputSlots) {
                ItemStack playerInput = StorageHelper.getItemStackInSlot(playerInputSlot);
                if (!playerInput.isEmpty()) {
                    // Return a task to ensure a free player crafting grid
                    return new EnsureFreePlayerCraftingGridTask();
                }
            }
        }

        //#if MC < 12111
        Optional<WrappedRecipeEntry> recipeToSend = JankCraftingRecipeMapping.getMinecraftMappedRecipe(target.getRecipe(), target.getOutputItem());
        if (recipeToSend.isPresent()) {
            if (mod.getSlotHandler().canDoSlotAction()) {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                assert player != null;
                // Click the recipe to send it
                mod.getController().clickRecipe(player.currentScreenHandler.syncId, recipeToSend.get().asRecipe(), true);
                mod.getSlotHandler().registerSlotAction();
            }
        }
        //#else
        //$$ // 1.21.11 moved recipe-book crafting onto NetworkRecipeId, and the port had been left
        //$$ // switched off. That is what blocked the playthrough's second rung: this task held the
        //$$ // inventory screen for a whole run -- measured 9295 ticks of 9295, no table screen, no
        //$$ // close -- because it reached here and did nothing, with wood to spare in the pack.
        //$$ //
        //$$ // The ids are handed out at RUNTIME by the server during its recipe sync, so the
        //$$ // client's own book is the only place they exist; the recipe registry cannot help.
        //$$ // Every type below came from the yarn mappings or from the compiler naming it, never
        //$$ // from a guess: clickRecipe(int, NetworkRecipeId, boolean), RecipeDisplayEntry.id(),
        //$$ // ClientRecipeBook.getOrderedResults(), RecipeResultCollection.getAllRecipes(),
        //$$ // RecipeDisplayEntry.getStacks(ContextParameterMap), and the context itself from
        //$$ // SlotDisplayContexts.createParameters(World) rather than the null the compiler would
        //$$ // have accepted and the runtime would have thrown on.
        //$$ ClientPlayerEntity player = MinecraftClient.getInstance().player;
        //$$ if (player != null && mod.getWorld() != null && mod.getSlotHandler().canDoSlotAction()) {
        //$$     net.minecraft.util.context.ContextParameterMap ctx =
        //$$             net.minecraft.recipe.display.SlotDisplayContexts.createParameters(mod.getWorld());
        //$$     for (net.minecraft.client.gui.screen.recipebook.RecipeResultCollection col
        //$$             : player.getRecipeBook().getOrderedResults()) {
        //$$         // IS THE CRAFTABLE SET EVER FILLED? isCraftable was false on every send while
        //$$         // the pack held logs, so either the book cannot see the inventory or nothing
        //$$         // recomputes it. Counting the whole book tells those apart in one run.
        //$$         if (col.hasCraftableRecipes()) cgBookCraftable++; else cgBookNone++;
        //$$         for (net.minecraft.recipe.RecipeDisplayEntry entry : col.getAllRecipes()) {
        //$$             for (ItemStack shown : entry.getStacks(ctx)) {
        //$$                 if (shown.getItem() == target.getOutputItem()) {
        //$$                     // IS THE SERVER EVEN WILLING? A click on a recipe the player has
        //$$                     // not unlocked is dropped silently, which looks exactly like what
        //$$                     // was measured: the right recipe sent, cgOutReady=0. One counter
        //$$                     // settles it before any theory about screens or arguments.
        //$$                     if (col.isCraftable(entry.id())) cgCraftable++; else cgNotCraftable++;
        //$$                     cgSent++;   // "the hang is gone" is not "a recipe was sent"
        //$$                     cgLastSent = String.valueOf(
        //$$                             net.minecraft.registry.Registries.ITEM.getId(target.getOutputItem()));
        //$$                     mod.getController().clickRecipe(
        //$$                             player.currentScreenHandler.syncId, entry.id(), true);
        //$$                     mod.getSlotHandler().registerSlotAction();
        //$$                     return null;
        //$$                 }
        //$$             }
        //$$         }
        //$$     }
        //$$ }
        //#endif

        return null;
    }

    /**
     * This method is called when the task is interrupted.
     *
     * @param interruptTask The task that interrupted the current task.
     */
    @Override
    protected void onStop(Task interruptTask) {

    }

    /**
     * Checks if a given Task object is equal to this CraftGenericWithRecipeBooksTask object.
     *
     * @param other The Task object to compare with.
     * @return True if the given Task is equal to this CraftGenericWithRecipeBooksTask, false otherwise.
     */
    @Override
    protected boolean isEqual(Task other) {
        // Check if the other Task is an instance of CraftGenericWithRecipeBooksTask
        if (other instanceof CraftGenericWithRecipeBooksTask) {
            CraftGenericWithRecipeBooksTask task = (CraftGenericWithRecipeBooksTask) other;

            // Check if the target of the other task is equal to the target of this task
            boolean isEqual = task.target.equals(target);

            // Log a message if the targets are not equal
            if (!isEqual) {
                Debug.logInternal("Task targets are not equal");
            }

            // Return the result of the equality check
            return isEqual;
        }

        // Log a message if the other Task is not an instance of CraftGenericWithRecipeBooksTask
        Debug.logInternal("Task is not an instance of CraftGenericWithRecipeBooksTask");

        // Return false if the other Task is not an instance of CraftGenericWithRecipeBooksTask
        return false;
    }

    /**
     * Returns a debug string representation of the object.
     *
     * @return The debug string representation.
     */
    @Override
    protected String toDebugString() {
        // Return the debug string.
        return getClass().getSimpleName() + " (w/ RECIPE): " + target;
    }
}
