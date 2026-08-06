package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.resources.CollectRecipeCataloguedResourcesTask;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasks.slot.EnsureFreePlayerCraftingGridTask;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;
import java.util.Optional;

/**
 * Crafts an item within the 2x2 inventory crafting grid.
 */
public class CraftInInventoryTask extends ResourceTask {

    /** Ticks, "no materials" exits, and output collections; read over py4j in placeStats(). */
    public static volatile int ciTick, ciCollect, ciReceive;
    /** Ticks the craft found its own ingredients stranded in the 2x2 grid. Read as ciGrid. */
    public static volatile int ciGridStranded;

    private final RecipeTarget _target;
    private final boolean _collect;
    private final boolean _ignoreUncataloguedSlots;
    private boolean _fullCheckFailed = false;

    public CraftInInventoryTask(RecipeTarget target, boolean collect, boolean ignoreUncataloguedSlots) {
        super(new ItemTarget(target.getOutputItem(), target.getTargetCount()));
        _target = target;
        _collect = collect;
        _ignoreUncataloguedSlots = ignoreUncataloguedSlots;
    }

    public CraftInInventoryTask(RecipeTarget target) {
        this(target, true, false);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        _fullCheckFailed = false;
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty() && !StorageHelper.isBigCraftingOpen()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveTo.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP));
            if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            garbage.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP));
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
        } else {
            StorageHelper.closeScreen();
        } // Just to be safe I guess
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // A LEVEL UP FROM THE PLACEMENT. Both crafting routes -- recipe book and manual -- behave
        // identically: wood gathered, crafting rung never reached. So the question is no longer
        // how ingredients are laid out but whether control gets that far, and with what.
        ciTick++;
        // Grab from output FIRST
        if (StorageHelper.isPlayerInventoryOpen()) {
            if (StorageHelper.getItemStackInCursorSlot().isEmpty()) {
                Item outputItem = StorageHelper.getItemStackInSlot(PlayerSlot.CRAFT_OUTPUT_SLOT).getItem();
                if (itemTargets != null) {
                    for (ItemTarget target : itemTargets) {
                        if (target.matches(outputItem)) {
                            ciReceive++;
                            return new ReceiveCraftingOutputSlotTask(PlayerSlot.CRAFT_OUTPUT_SLOT, target.getTargetCount());
                        }
                    }
                }
            }
        }

        ItemTarget toGet = itemTargets[0];
        Item toGetItem = toGet.getMatches()[0];

        // MATERIALS SITTING IN THE GRID ARE NOT MISSING MATERIALS.
        // hasRecipeMaterialsOrTarget looks at the INVENTORY. Manual crafting -- now the default,
        // because the recipe book is disabled on plenty of servers -- puts the ingredients INTO
        // the 2x2 grid, and anything that interrupts the craft leaves them stranded there. The
        // inventory check then says "no materials", the collect branch runs, and it runs again,
        // and again: measured on the playthrough, ciCollect=8051 out of ciTick=8051 -- EVERY
        // tick -- with ciReceive=0 and 1008 slot clicks, the bot standing at a table "Getting
        // stick x 2" while its planks sat in the grid in front of it.
        // Clearing the grid puts them back where the check can see them. The task already exists;
        // nothing was calling it from here.
        // BUT THE INGREDIENTS OF THE CRAFT IN PROGRESS ARE NOT STRANDED EITHER.
        // As written this fired on ANY occupied grid slot, and manual crafting fills those slots on
        // purpose -- so the two tasks undid each other every tick. Read straight off a failing
        // @gamer run's chain:
        //     <Craft 2x2 Task {dark_oak_planks x 2}> Crafting in inventory...
        //     <Crafting: {dark_oak_planks x 2}> Moving item to slot...
        //     <Breaking the crafting grid> Clearing the 2x2 crafting grid
        // place, clear, place, clear, and no planks ever come out.
        // Stranded means FOREIGN: an item this recipe has no use for. An item the recipe does want
        // is the craft working, and it is left alone.
        if (StorageHelper.isPlayerInventoryOpen()) {
            for (Slot gridSlot : PlayerSlot.CRAFT_INPUT_SLOTS) {
                ItemStack inGrid = StorageHelper.getItemStackInSlot(gridSlot);
                if (!inGrid.isEmpty() && !isRecipeIngredient(inGrid.getItem())) {
                    ciGridStranded++;
                    setDebugState("Something foreign in the crafting grid — taking it back");
                    return new EnsureFreePlayerCraftingGridTask();
                }
            }
        }

        if (_collect && !StorageHelper.hasRecipeMaterialsOrTarget(mod, _target)) {
            // PRIME SUSPECT: a bot that believes it lacks materials never reaches crafting at
            // all, however well placement works -- and it holds up to twelve wood items a run.
            ciCollect++;
            // Collect recipe materials
            setDebugState("Collecting materials");
            return collectRecipeSubTask(mod);
        }

        // No need to free inventory, output gets picked up.

        setDebugState("Crafting in inventory... for " + toGet);
        // MANUAL PLACEMENT IS THE ONE THAT WORKS EVERYWHERE.
        // The recipe book is off by default (see Settings.useCraftingBookToCraft): plenty of
        // servers disable it, and this is the exact path a stall capture caught looping -- the
        // book answering "no such recipe" 655 times for a 3x3 item while the 2x2 player grid was
        // open. Placing the items needs no book and no server feature.
        return mod.getModSettings().shouldUseCraftingBookToCraft()
                ? new CraftGenericWithRecipeBooksTask(_target)
                : new CraftGenericManuallyTask(_target);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            List<Slot> moveTo = mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(cursorStack, false);
            if (!moveTo.isEmpty()) {
                for (Slot MoveTo : moveTo) {
                    mod.getSlotHandler().clickSlot(MoveTo, 0, SlotActionType.PICKUP);
                }
            } else {
                Optional<Slot> garbageSlot = StorageHelper.getGarbageSlot(mod);
                if (garbageSlot.isPresent()) {
                    mod.getSlotHandler().clickSlot(garbageSlot.get(), 0, SlotActionType.PICKUP);
                } else {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                }
            }
        }
    }

    // TODO check if this doesnt break something... but generally this shouldnt pickup items
    @Override
    protected double getPickupRange(AltoClef mod) {
        return 0;
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CraftInInventoryTask task) {
            if (!task._target.equals(_target)) return false;
            return isCraftingEqual(task);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return toCraftingDebugStringName() + " " + _target;
    }

    // virtual. By default assumes subtasks are CATALOGUED (in TaskCatalogue.java)
    /** Does the recipe being made use this item in any of its slots? */
    private boolean isRecipeIngredient(net.minecraft.item.Item item) {
        if (_target == null || _target.getRecipe() == null) {
            return false;
        }
        for (int i = 0; i < _target.getRecipe().getSlotCount(); ++i) {
            ItemTarget slot = _target.getRecipe().getSlot(i);
            if (slot != null && !slot.isEmpty() && slot.matches(item)) {
                return true;
            }
        }
        return false;
    }

    protected Task collectRecipeSubTask(AltoClef mod) {
        return new CollectRecipeCataloguedResourcesTask(_ignoreUncataloguedSlots, _target);
    }

    protected String toCraftingDebugStringName() {
        return "Craft 2x2 Task";
    }

    protected boolean isCraftingEqual(CraftInInventoryTask other) {
        return true;
    }

    public RecipeTarget getRecipeTarget() {
        return _target;
    }
}
