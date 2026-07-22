package adris.altoclef.mixins;

import net.minecraft.block.Block;
//#if MC < 12111
import net.minecraft.item.MiningToolItem;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

//#if MC < 12111
@Mixin(MiningToolItem.class)
public interface MiningToolItemAccessor {

    //#if MC <= 11605
    //$$ @Accessor("effectiveBlocks")
    //$$ Set<Block> getEffectiveBlocks();
    //#endif

}
//#else
//$$ @Mixin(net.minecraft.item.Item.class) // no-op stub — MiningToolItem deleted in 1.21.11
//$$ public interface MiningToolItemAccessor {}
//#endif
