package adris.altoclef.multiversion;

import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;

public class CraftingRecipeVer {


    @Pattern
    private static ItemStack getOutput(CraftingRecipe craftingRecipe) {
        //#if MC >= 12111
        //$$ return ItemStack.EMPTY; // TODO [1.21.11] Recipe.getResult() API changed
        //#elseif MC >= 11904
        return craftingRecipe.getResult(null);
        //#else
        //$$ return craftingRecipe.getOutput();
        //#endif
    }

}
