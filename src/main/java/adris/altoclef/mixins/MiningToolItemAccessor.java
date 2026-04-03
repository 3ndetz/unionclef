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
//$$ // TODO [1.21.11] MiningToolItem deleted — this mixin is not needed
//$$ public interface MiningToolItemAccessor {}
//#endif
