package adris.altoclef.multiversion.item;

import adris.altoclef.mixins.AxeItemAccessor;
import adris.altoclef.mixins.MiningToolItemAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.PickaxeItem;

import java.util.Set;

public class ItemHelper {


    //#if MC <= 11605
    //$$ public static boolean isSuitableFor(Item item, BlockState state){
    //$$     if (item instanceof PickaxeItem pickaxe) {
    //$$         return pickaxe.isSuitableFor(state);
    //$$     }
    //$$
    //$$     if (item instanceof MiningToolItem) {
    //$$         boolean isInEffectiveBlocks = ((MiningToolItemAccessor)item).getEffectiveBlocks().contains(state.getBlock());
    //$$
    //$$         if (item instanceof AxeItem) {
    //$$             return isInEffectiveBlocks || ((AxeItemAccessor)item).getEffectiveMaterials().contains(state.getMaterial());
    //$$         }
    //$$         return isInEffectiveBlocks;
    //$$     }
    //$$
    //$$     return item.isSuitableFor(state);
    //$$ }
    //$$
    //#endif

}
