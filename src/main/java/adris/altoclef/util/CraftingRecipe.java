package adris.altoclef.util;

import adris.altoclef.Debug;
import net.minecraft.item.Item;

import java.util.Arrays;

public class CraftingRecipe {

    private ItemTarget[] slots;

    private int width, height;

    private boolean shapeless;

    private String shortName;

    private int outputCount;

    // Every item in this list MUST match.
    // Used for beds where the wood can be anything
    // but the wool MUST be the same color.
    //private final Set<Integer> _mustMatch = new HashSet<>();

    private CraftingRecipe() {
    }

    public static CraftingRecipe newShapedRecipe(Item[][] items, int outputCount) {
        return newShapedRecipe(null, items, outputCount);
    }

    public static CraftingRecipe newShapedRecipe(ItemTarget[] slots, int outputCount) {
        return newShapedRecipe(null, slots, outputCount);
    }

    public static CraftingRecipe newShapedRecipe(String shortName, Item[][] items, int outputCount) {
        return newShapedRecipe(shortName, createSlots(items), outputCount);
    }

    public static CraftingRecipe newShapedRecipe(String shortName, ItemTarget[] slots, int outputCount) {
        if (slots.length != 4 && slots.length != 9) {
            Debug.logError("Invalid shaped crafting recipe, must be either size 4 or 9. Size given: " + slots.length);
            return null;
        }

        CraftingRecipe result = new CraftingRecipe();
        result.shortName = shortName;
        // Remove null
        result.slots = Arrays.stream(slots).map(target -> target == null ? ItemTarget.EMPTY : target).toArray(ItemTarget[]::new);
        result.outputCount = outputCount;
        if (slots.length == 4) {
            result.width = 2;
            result.height = 2;
        } else {
            result.width = 3;
            result.height = 3;
        }
        result.shapeless = false;

        return result;
    }

    private static ItemTarget[] createSlots(ItemTarget[] slots) {
        ItemTarget[] result = new ItemTarget[slots.length];
        System.arraycopy(slots, 0, result, 0, slots.length);
        return result;
    }

    private static ItemTarget[] createSlots(Item[][] slots) {
        ItemTarget[] result = new ItemTarget[slots.length];
        for (int i = 0; i < slots.length; ++i) {
            if (slots[i] == null) {
                result[i] = ItemTarget.EMPTY;
            } else {
                result[i] = new ItemTarget(slots[i]);
            }
        }
        return result;
    }

    public ItemTarget getSlot(int index) {
        ItemTarget result = slots[index];
        return result != null ? result : ItemTarget.EMPTY;
    }

    public int getSlotCount() {
        return slots.length;
    }

    public ItemTarget[] getSlots() {
        return slots;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isShapeless() {
        return shapeless;
    }

    public boolean isBig() {
        return slots.length > 4;
    }

    public int outputCount() {
        return outputCount;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof CraftingRecipe other) {
            if (other.shapeless != shapeless) return false;
            if (other.outputCount != outputCount) return false;
            if (other.height != height) return false;
            if (other.width != width) return false;
            //if (other._mustMatch.size() != _mustMatch.size()) return false;
            if (other.slots.length != slots.length) return false;
            for (int i = 0; i < slots.length; ++i) {
                if ((other.slots[i] == null) != (slots[i] == null)) return false;
                if (other.slots[i] != null && !other.slots[i].equals(slots[i])) return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        String name = "CraftingRecipe{";
        if (shortName != null) {
            name += "craft " + shortName;
        } else {
            name += "_slots=" + Arrays.toString(slots) +
                    ", _width=" + width +
                    ", _height=" + height +
                    ", _shapeless=" + shapeless;
        }
        name += "}";
        return name;
    }
}
