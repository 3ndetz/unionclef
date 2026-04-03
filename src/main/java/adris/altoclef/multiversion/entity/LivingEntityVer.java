package adris.altoclef.multiversion.entity;

import adris.altoclef.multiversion.Pattern;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public class LivingEntityVer {


    // FIXME this should be possible with mappings, right?
    @Pattern
    private static Iterable<ItemStack> getItemsEquipped(LivingEntity entity) {
        //#if MC >= 12111
        //$$ return java.util.List.of(
        //$$     entity.getMainHandStack(), entity.getOffHandStack(),
        //$$     entity.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD),
        //$$     entity.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST),
        //$$     entity.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS),
        //$$     entity.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET)
        //$$ );
        //#elseif MC >= 12005
        return entity.getEquippedItems();
        //#else
        //$$ return entity.getItemsEquipped();
        //#endif
    }

    @Pattern
    private static boolean isSuitableFor(Item item, BlockState state) {
        //#if MC >= 12005
        return item.getDefaultStack().isSuitableFor(state);
        //#else
        //$$ return item.isSuitableFor(state);
        //#endif
    }

}
