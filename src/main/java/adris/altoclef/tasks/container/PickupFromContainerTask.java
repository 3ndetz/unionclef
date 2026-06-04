package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.FurnaceSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public class PickupFromContainerTask extends AbstractDoToStorageContainerTask {

    private final BlockPos _targetContainer;
    private final ItemTarget[] _targets;

    private final EnsureFreeInventorySlotTask _freeInventoryTask = new EnsureFreeInventorySlotTask();

    public PickupFromContainerTask(BlockPos targetContainer, ItemTarget... targets) {
        _targets = targets;
        _targetContainer = targetContainer;
    }

    /**
     * Returns the tier rank of a tool/armor item (0 = best, higher = worse).
     * Items not in any tier list get Integer.MAX_VALUE (lowest priority).
     */
    private static int getItemTierRank(Item item) {
        Item[][] tierLists = {
            ItemHelper.SwordsTopPriority,
            ItemHelper.AxesTopPriority,
            ItemHelper.PickaxesTopPriority,
            ItemHelper.ShovelsTopPriority,
            ItemHelper.HoesTopPriority,
            ItemHelper.HelmetsTopPriority,
            ItemHelper.ChestplatesTopPriority,
            ItemHelper.LeggingsTopPriority,
            ItemHelper.BootsTopPriority
        };
        for (Item[] tierList : tierLists) {
            for (int i = 0; i < tierList.length; i++) {
                if (tierList[i] == item) return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Compare two slots by stack quantity first, then by tool/armor tier.
     * Returns true if {@code candidate} is better than {@code currentBest}.
     */
    private static boolean isBetterSlot(ItemStack candidate, ItemStack currentBest, int leftNeeded, Function<ItemStack, Boolean> canStackFit) {
        int overshoot = candidate.getCount() - leftNeeded;
        int currBestOvershoot = currentBest.getCount() - leftNeeded;
        boolean canFit = canStackFit.apply(candidate);
        boolean currBestCanFit = canStackFit.apply(currentBest);

        // Priority 1: inventory fit
        if (canFit != currBestCanFit) {
            return canFit;
        }

        // Priority 2: quantity (closest to needed, non-negative preferred)
        if (overshoot < 0 && currBestOvershoot < 0) {
            // Both undershoot: pick the larger (closer to fulfilling)
            if (overshoot != currBestOvershoot) return overshoot > currBestOvershoot;
        } else if (overshoot >= 0 && currBestOvershoot >= 0) {
            // Both overshoot or exact: pick the smaller overshoot
            if (overshoot != currBestOvershoot) return overshoot < currBestOvershoot;
        } else {
            // One undershoots, one overshoots: prefer the one that meets the need (>= 0)
            if (overshoot >= 0) return true;
            if (currBestOvershoot >= 0) return false;
        }

        // Priority 3: tool/armor tier (lower rank = better tier)
        int candidateTier = getItemTierRank(candidate.getItem());
        int bestTier = getItemTierRank(currentBest.getItem());
        return candidateTier < bestTier;
    }

    public static Optional<Slot> getBestSlotToTransfer(AltoClef mod, ItemTarget itemToMove, int currentItemQuantity, List<Slot> grabPotentials, Function<ItemStack, Boolean> canStackFit) {
        Slot bestPotential = null;
        int leftNeeded = itemToMove.getTargetCount() - currentItemQuantity;
        for (Slot slot : grabPotentials) {
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (itemToMove.matches(stack.getItem())) {
                if (bestPotential == null) {
                    bestPotential = slot;
                    continue;
                }
                ItemStack currBest = StorageHelper.getItemStackInSlot(bestPotential);
                if (isBetterSlot(stack, currBest, leftNeeded, canStackFit)) {
                    bestPotential = slot;
                }
            }
        }
        return Optional.ofNullable(bestPotential);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof PickupFromContainerTask task) {
            return Objects.equals(_targetContainer, task._targetContainer) && Arrays.equals(_targets, task._targets);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Picking up from container at (" + _targetContainer.toShortString() + "): " + Arrays.toString(_targets);
    }

    @Override
    protected Optional<BlockPos> getContainerTarget() {
        return Optional.of(_targetContainer);
    }

    @Override
    protected Task onTick() {
        // Free inventory while we're doing it.
        if (_freeInventoryTask.isActive() && !_freeInventoryTask.isFinished() && !AltoClef.getInstance().getItemStorage().hasEmptyInventorySlot()) {
            setDebugState("Freeing inventory.");
            return _freeInventoryTask;
        }
        return super.onTick();
    }

    @Override
    public boolean isFinished() {
        return Arrays.stream(_targets).allMatch(target -> AltoClef.getInstance().getItemStorage().getItemCountInventoryOnly(target.getMatches()) >= target.getTargetCount());
    }

    @Override
    protected Task onContainerOpenSubtask(AltoClef mod, ContainerCache containerCache) {
        for (ItemTarget target : _targets) {
            // Go through each item
            int count = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
            if (target.matches(StorageHelper.getItemStackInCursorSlot().getItem()))
                count -= StorageHelper.getItemStackInCursorSlot().getCount();
            if (count < target.getTargetCount()) {
                setDebugState("Collecting " + target);
                // Grab the item from the current chest that most closely matches our requirements
                List<Slot> potentials = mod.getItemStorage().getSlotsWithItemContainer(target.getMatches());

                // Pick the best slot to grab from.
                Optional<Slot> bestPotential = getBestSlotToTransfer(mod, target, count, potentials, stack -> mod.getItemStorage().getSlotThatCanFitInPlayerInventory(stack, false).isPresent());
                ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
                if (!cursorStack.isEmpty()) {
                    Optional<Slot> toPlace = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false).or(() -> StorageHelper.getGarbageSlot(mod));
                    if (toPlace.isPresent() && target.matches(cursorStack.getItem())) {
                        mod.getSlotHandler().clickSlot(toPlace.get(), 0, SlotActionType.PICKUP);
                        return null;
                    }
                    if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                        mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                        return null;
                    }
                    if (toPlace.isPresent()) {
                        mod.getSlotHandler().clickSlot(toPlace.get(), 0, SlotActionType.PICKUP);
                        return null;
                    }
                }
                if (bestPotential.isPresent()) {
                    // Just pick it up, it's now ours.
                    mod.getSlotHandler().clickSlot(bestPotential.get(), 0, SlotActionType.PICKUP);
                    return null;
                }
                setDebugState("SHOULD NOT HAPPEN! No valid items detected.");
            }
        }

        // We're done.
        setDebugState("Done");
        if (mod.getPlayer().currentScreenHandler instanceof SmokerScreenHandler || mod.getPlayer().currentScreenHandler
                instanceof FurnaceScreenHandler) {
            mod.getSlotHandler().clickSlot(FurnaceSlot.INPUT_SLOT_MATERIALS, 0, SlotActionType.PICKUP);
            return null;
        }
        return null;
    }
}
