package adris.altoclef.multiversion.entity;

import adris.altoclef.multiversion.Pattern;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import java.util.List;

public class LivingEntityVer {

    @SuppressWarnings("unchecked")
    private static Iterable<ItemStack> reflectMethod(LivingEntity entity, String name) {
        try {
            return (Iterable<ItemStack>) LivingEntity.class.getMethod(name).invoke(entity);
        } catch (Exception e) {
            return List.of();
        }
    }

    static Iterable<ItemStack> callEquippedItems(LivingEntity e) { return reflectMethod(e, "getEquippedItems"); }
    static Iterable<ItemStack> callHandItems(LivingEntity e) { return reflectMethod(e, "getHandItems"); }
    static Iterable<ItemStack> callArmorItems(LivingEntity e) { return reflectMethod(e, "getArmorItems"); }

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
