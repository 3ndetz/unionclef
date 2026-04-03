package adris.altoclef.multiversion;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Recipe;
import net.minecraft.world.World;

public class RecipeVer {



    public static ItemStack getOutput(Recipe<?> recipe, World world) {
        //#if MC >= 12111
        //$$ return ItemStack.EMPTY; // TODO [1.21.11] Recipe.getResult() API changed
        //#elseif MC >= 11904
        return recipe.getResult(world.getRegistryManager());
        //#else
        //$$ return recipe.getOutput();
        //#endif
    }


}
