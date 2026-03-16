package adris.altoclef.multiversion.recipemanager;

import net.minecraft.recipe.RecipeManager;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import java.util.Collection;

public class RecipeManagerWrapper {

    private final RecipeManager recipeManager;

    public static RecipeManagerWrapper of(RecipeManager recipeManager) {
        if (recipeManager == null) return null;

        return new RecipeManagerWrapper(recipeManager);
    }


    private RecipeManagerWrapper(RecipeManager recipeManager) {
        this.recipeManager = recipeManager;
    }

    //#if MC>12001
    public Collection<WrappedRecipeEntry> values() {
        return recipeManager.values().stream().map(r -> new WrappedRecipeEntry(r.id(),r.value())).collect(Collectors.toSet());
    }
    //#else
    //$$ public Collection<WrappedRecipeEntry> values() {
    //$$    List<WrappedRecipeEntry> result = new ArrayList<>();
    //$$    for (Identifier id : recipeManager.keys().toList()) {
    //$$        result.add(new WrappedRecipeEntry(id, recipeManager.get(id).get()));
    //$$    }
    //$$
    //$$    return result;
    //$$ }
    //#endif



}
