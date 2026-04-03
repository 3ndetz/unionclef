package adris.altoclef.multiversion.entity;

import adris.altoclef.multiversion.Pattern;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import java.util.List;

public class LivingEntityVer {

    // getEquippedItems/getHandItems/getArmorItems deleted in 1.21.11.
    // Can't appear in source at all — preprocessor remaps ALL identifiers
    // and crashes when target has no mapping.
    // Solution: call the 1.21.1 method via reflection to hide it from preprocessor.

    @SuppressWarnings("unchecked")
    static Iterable<ItemStack> callEquippedItems(LivingEntity entity) {
        try {
            var method = LivingEntity.class.getMethod("getEquippedItems");
            return (Iterable<ItemStack>) method.invoke(entity);
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    static Iterable<ItemStack> callHandItems(LivingEntity entity) {
        try {
            var method = LivingEntity.class.getMethod("getHandItems");
            return (Iterable<ItemStack>) method.invoke(entity);
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    static Iterable<ItemStack> callArmorItems(LivingEntity entity) {
        try {
            var method = LivingEntity.class.getMethod("getArmorItems");
            return (Iterable<ItemStack>) method.invoke(entity);
        } catch (Exception e) {
            return List.of();
        }
    }

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
        //#else
        return callEquippedItems(entity);
        //#endif
    }

    public static boolean hasTrident(LivingEntity entity) {
        //#if MC >= 12111
        //$$ return entity.getMainHandStack().isOf(Items.TRIDENT)
        //$$     || entity.getOffHandStack().isOf(Items.TRIDENT);
        //#else
        for (ItemStack stack : callEquippedItems(entity)) {
            if (stack.isOf(Items.TRIDENT)) return true;
        }
        return false;
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
