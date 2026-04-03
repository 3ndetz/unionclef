package adris.altoclef.multiversion;

import net.minecraft.block.Block;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.Registries;

public class BlockTagVer {


    public static boolean isWool(Block block) {
        //#if MC >= 12111
        //$$ return false; // TODO [1.21.11] Registries.BLOCK.entryOf() removed — use Registry.getEntry(RegistryKey)
        //#elseif MC >= 11802
        return Registries.BLOCK.getKey(block).map(e -> Registries.BLOCK.entryOf(e).streamTags().anyMatch(t -> t == BlockTags.WOOL)).orElse(false);
        //#else
        //$$ return BlockTags.WOOL.contains(block);
        //#endif
    }

}
