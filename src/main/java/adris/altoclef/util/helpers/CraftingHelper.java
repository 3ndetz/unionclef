package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.*;

public class CraftingHelper {

    private CraftingHelper() {
    }


    /**
     * checks if the bot can acquire item just from stuff in his inventory through crafting (crafting can include multiple steps, like turning a log into sticks)
     */
    public static boolean canCraftItemNow(AltoClef mod, Item item) {
        List<ItemStack> inventoryItems = new ArrayList<>();
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            inventoryItems.add(new ItemStack(stack.getItem(), stack.getCount()));
        }

        for (CraftingRecipe recipe : mod.getCraftingRecipeTracker().getRecipeForItem(item)) {
            if (canCraftItemNow(mod, new ArrayList<>(inventoryItems), recipe, new HashSet<>())) {
                return true;
            }
        }

        return false;
    }

    private static boolean canCraftItemNow(AltoClef mod, List<ItemStack> inventoryStacks, CraftingRecipe recipe, HashSet<Item> alreadyChecked) {
        Item recipeResult = mod.getCraftingRecipeTracker().getRecipeResult(recipe).getItem();

        if (alreadyChecked.contains(recipeResult)) return false;
        alreadyChecked.add(recipeResult);

        ItemTarget[] targets = recipe.getSlots();

        itemTargetLoop:
        for (ItemTarget itemTarget : targets) {
            if (itemTarget == ItemTarget.EMPTY) {
                continue;
            }

            for (Item item : itemTarget.getMatches()) {

                for (ItemStack inventoryStack : inventoryStacks) {

                    // we can use something from inventory to craft it!
                    // reduce the amount of items available, continue the loop
                    if (inventoryStack.getItem() == item && inventoryStack.getCount() >= itemTarget.getTargetCount()) {
                        inventoryStack.setCount(inventoryStack.getCount() - itemTarget.getTargetCount());
                        continue itemTargetLoop;
                    }
                }

            }

            // we didn't find and item in the inventory that we could use right away
            // now try to recursively call for recipes of the items we need if we find something

            // FIXME this doesnt take counts into consideration, but I dont even think there is a recipe that needs more then one item on a specific slot, so we should be fine
            for (Item item : itemTarget.getMatches()) {
                if (!mod.getCraftingRecipeTracker().hasRecipeForItem(item)) continue;

                for (CraftingRecipe newRecipe : mod.getCraftingRecipeTracker().getRecipeForItem(item)) {
                    List<ItemStack> inventoryStacksCopy = new ArrayList<>(inventoryStacks);
                    if (canCraftItemNow(mod, inventoryStacksCopy, newRecipe, new HashSet<>(alreadyChecked))) {

                        // this is the inventory we are now left with
                        inventoryStacks = inventoryStacksCopy;

                        // we crafted something, add it to the available items minus the one we used
                        ItemStack result = mod.getCraftingRecipeTracker().getRecipeResult(newRecipe);
                        result.setCount(result.getCount() - 1);
                        inventoryStacks.add(result);

                        continue itemTargetLoop;
                    }
                }
            }

            // we cannot get the item
            return false;
        }

        return true;
    }

}
