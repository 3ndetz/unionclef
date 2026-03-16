package adris.altoclef.multiversion.recipemanager;

import net.minecraft.recipe.Recipe;
//#if MC>12001
import net.minecraft.recipe.RecipeEntry;
//#endif
import net.minecraft.util.Identifier;

public record WrappedRecipeEntry(Identifier id, Recipe<?> value) {

    //#if MC>12001
    public RecipeEntry<?> asRecipe() {
        return new RecipeEntry<Recipe<?>>(id, value);
    }
    //#else
    //$$ public Recipe<?> asRecipe(){
    //$$     return value;
    //$$ }
    //#endif

}
