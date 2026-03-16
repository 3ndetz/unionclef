package adris.altoclef.util;

import net.minecraft.item.Item;

import java.util.Objects;

public class RecipeTarget {

    private final CraftingRecipe recipe;
    private final Item item;
    private final int targetCount;

    public RecipeTarget(Item item, int targetCount, CraftingRecipe recipe) {
        this.item = item;
        this.targetCount = targetCount;
        this.recipe = recipe;
    }

    public CraftingRecipe getRecipe() {
        return recipe;
    }

    public Item getOutputItem() {
        return item;
    }

    public int getTargetCount() {
        return targetCount;
    }

    @Override
    public String toString() {
        if (targetCount == 1)
            return "Recipe{"+item+"}";

        return "Recipe{" +
                item + " x " + targetCount +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecipeTarget that = (RecipeTarget) o;
        return targetCount == that.targetCount && recipe.equals(that.recipe) && Objects.equals(item, that.item);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recipe, item);
    }
}
