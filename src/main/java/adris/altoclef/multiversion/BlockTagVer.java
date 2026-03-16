package adris.altoclef.multiversion;

import net.minecraft.block.Block;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.Registries;

public class BlockTagVer {


    public static boolean isWool(Block block) {
        //#if MC >= 11802
        return Registries.BLOCK.getKey(block).map(e -> Registries.BLOCK.entryOf(e).streamTags().anyMatch(t -> t == BlockTags.WOOL)).orElse(false);
        //#else
        //$$ return BlockTags.WOOL.contains(block);
        //#endif
    }

}
