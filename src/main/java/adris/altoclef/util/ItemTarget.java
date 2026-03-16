package adris.altoclef.util;

import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.item.Item;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Defines an item and a count.
 * <p>
 * Multiple Minecraft Items can meet the criteria of an "item" (ex. "wooden planks" can be satisfied by oak, acacia, spruce, jungle, etc.)
 */
public class ItemTarget {

    /**
     * Converts an array of `Item` objects into an array of `ItemTarget` objects.
     *
     * @param items the array of `Item` objects to convert
     * @return the array of `ItemTarget` objects
     */
    public static ItemTarget[] of(Item... items) {
        return Arrays.stream(items).map(ItemTarget::new).toArray(ItemTarget[]::new);
    }

    private static final int BASICALLY_INFINITY = 99999999;

    public static ItemTarget EMPTY = new ItemTarget(new Item[0], 0);
    private Item[] itemMatches;
    private final int targetCount;
    private String catalogueName = null;
    private boolean infinite = false;

    public ItemTarget(Item[] items, int targetCount) {
        itemMatches = items;
        this.targetCount = targetCount;
        infinite = false;
    }

    public ItemTarget(String catalogueName, int targetCount) {
        this.catalogueName = catalogueName;
        itemMatches = TaskCatalogue.getItemMatches(catalogueName);
        this.targetCount = targetCount;
    }

    public ItemTarget(String catalogueName) {
        this(catalogueName, 1);
    }

    public ItemTarget(Item item, int targetCount) {
        this(new Item[]{item}, targetCount);
    }

    public ItemTarget(Item... items) {
        this(items, 1);
    }

    public ItemTarget(Item item) {
        this(item, 1);
    }

    public ItemTarget(ItemTarget toCopy, int newCount) {
        if (toCopy.itemMatches != null) {
            itemMatches = new Item[toCopy.itemMatches.length];
            System.arraycopy(toCopy.itemMatches, 0, itemMatches, 0, toCopy.itemMatches.length);
        }
        catalogueName = toCopy.catalogueName;
        targetCount = newCount;
        infinite = toCopy.infinite;
    }

    public static boolean nullOrEmpty(ItemTarget target) {
        return target == null || target == EMPTY;
    }

    public static Item[] getMatches(ItemTarget... targets) {
        Set<Item> result = new HashSet<>();
        for (ItemTarget target : targets) {
            result.addAll(Arrays.asList(target.getMatches()));
        }
        return result.toArray(Item[]::new);
    }

    public ItemTarget infinite() {
        infinite = true;
        return this;
    }

    public Item[] getMatches() {
        return itemMatches != null ? itemMatches : new Item[0];
    }

    public int getTargetCount() {
        if (infinite) {
            return BASICALLY_INFINITY;
        }
        return targetCount;
    }

    public boolean matches(Item item) {
        if (itemMatches != null) {
            for (Item match : itemMatches) {
                if (match == null) continue;
                if (match.equals(item)) return true;
            }
        }
        return false;
    }

    public boolean isCatalogueItem() {
        return catalogueName != null;
    }

    public String getCatalogueName() {
        return catalogueName;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ItemTarget other) {
            if (infinite) {
                if (!other.infinite) return false;
            } else {
                // Neither are infinite
                if (targetCount != other.targetCount) return false;
            }
            if ((other.itemMatches == null) != (itemMatches == null)) return false;
            if (itemMatches != null) {
                if (itemMatches.length != other.itemMatches.length) return false;
                for (int i = 0; i < itemMatches.length; ++i) {
                    if (other.itemMatches[i] == null) {
                        if ((other.itemMatches[i] == null) != (itemMatches[i] == null)) return false;
                    } else {
                        if (!other.itemMatches[i].equals(itemMatches[i])) return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return itemMatches == null || itemMatches.length == 0;
    }

    @Override
    public String toString() {

        StringBuilder result = new StringBuilder();
        if (isEmpty()) {
            result.append("(empty)");
        } else if (isCatalogueItem()) {
            result.append(catalogueName);
        } else {
            result.append("[");
            int counter = 0;
            if (itemMatches != null) {
                for (Item item : itemMatches) {
                    if (item == null) {
                        result.append("(null??)");
                    } else {
                        result.append(ItemHelper.trimItemName(item.getTranslationKey()));
                    }
                    if (++counter != itemMatches.length) {
                        result.append(",");
                    }
                }
            }
            result.append("]");
        }
        if (!infinite && !isEmpty() && targetCount > 1) {
            result.append(" x ").append(targetCount);
        } else if (infinite) {
            result.append(" x infinity");
        }

        return result.toString();
    }


}
