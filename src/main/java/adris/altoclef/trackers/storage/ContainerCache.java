package adris.altoclef.trackers.storage;

import adris.altoclef.util.Dimension;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.function.Consumer;

public class ContainerCache {

    private final BlockPos blockPos;
    private final Dimension dimension;
    private final ContainerType containerType;

    private final HashMap<Item, Integer> itemCounts = new HashMap<>();
    private int _emptySlots;

    public ContainerCache(Dimension dimension, BlockPos blockPos, ContainerType containerType) {
        this.dimension = dimension;
        this.blockPos = blockPos;
        this.containerType = containerType;
    }

    public void update(ScreenHandler screenHandler, Consumer<ItemStack> onStack) {
        itemCounts.clear();
        _emptySlots = 0;
        int start = 0;
        int end = screenHandler.slots.size() - (4 * 9); // subtract by player inventory
        // do NOT count the furnace output slot as an empty slot, it cannot be used.
        boolean isFurnace = (screenHandler instanceof FurnaceScreenHandler);

        // Iterate through all STORAGE slots
        for (int i = start; i < end; ++i) {
            ItemStack stack = screenHandler.slots.get(i).getStack().copy();

            if (stack.isEmpty()) {
                // Ignore furnace output slot
                if (!(isFurnace && i == 2)) {
                    _emptySlots++;
                }
            } else {
                Item item = stack.getItem();
                int count = stack.getCount();
                itemCounts.put(item, itemCounts.getOrDefault(item, 0) + count);
                onStack.accept(stack);
            }
        }
    }

    public int getItemCount(Item... items) {
        int result = 0;
        for (Item item : items) {
            result += itemCounts.getOrDefault(item, 0);
        }
        return result;
    }

    public boolean hasItem(Item... items) {
        for (Item item : items) {
            if (itemCounts.containsKey(item) && itemCounts.get(item) > 0)
                return true;
        }
        return false;
    }

    public int getEmptySlotCount() {
        return _emptySlots;
    }

    public boolean isFull() {
        return _emptySlots == 0;
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public ContainerType getContainerType() {
        return containerType;
    }

    public Dimension getDimension() {
        return dimension;
    }
}
