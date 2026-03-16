package adris.altoclef.tasks.resources;

import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.item.Item;

import java.util.function.Function;

public abstract class CraftWithMatchingWoolTask extends CraftWithMatchingMaterialsTask {

    private final Function<ItemHelper.ColorfulItems, Item> getMajorityMaterial;
    private final Function<ItemHelper.ColorfulItems, Item> getTargetItem;

    public CraftWithMatchingWoolTask(ItemTarget target, Function<ItemHelper.ColorfulItems, Item> getMajorityMaterial, Function<ItemHelper.ColorfulItems, Item> getTargetItem, CraftingRecipe recipe, boolean[] sameMask) {
        super(target, recipe, sameMask);
        this.getMajorityMaterial = getMajorityMaterial;
        this.getTargetItem = getTargetItem;
    }


    @Override
    protected Item getSpecificItemCorrespondingToMajorityResource(Item majority) {
        for (ItemHelper.ColorfulItems colorfulItem : ItemHelper.getColorfulItems()) {
            if (getMajorityMaterial.apply(colorfulItem) == majority) {
                return getTargetItem.apply(colorfulItem);
            }
        }
        return null;
    }
}
