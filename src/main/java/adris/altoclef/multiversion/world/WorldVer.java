package adris.altoclef.multiversion.world;

import adris.altoclef.multiversion.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

//#if MC >= 11802
import net.minecraft.registry.entry.RegistryEntry;
//#endif

public class WorldVer {



    public static boolean isBiomeAtPos(World world, RegistryKey<Biome> biome, BlockPos pos) {
        //#if MC >= 11802
        RegistryEntry<Biome> b = world.getBiome(pos);
        return b.matchesKey(biome);
        //#else
        //$$ Biome b = world.getBiome(pos);
        //$$ return world.getRegistryManager().get(Registry.BIOME_KEY).get(biome) == b;
        //#endif
    }


    //#if MC >= 11802
    public static boolean isBiome(RegistryEntry<Biome> biome1, RegistryKey<Biome> biome2) {
        return biome1.matchesKey(biome2);
    }
    //#else
    //$$ public static boolean isBiome(Biome biome1, RegistryKey<Biome> biome2) {
    //$$     World world = MinecraftClient.getInstance().world;
    //$$     return world.getRegistryManager().get(Registry.BIOME_KEY).get(biome2) == biome1;
    //$$ }
    //#endif


    @Pattern
    public static int getBottomY(World world) {
        //#if MC >= 11701
        return world.getBottomY();
        //#else
        //$$ return adris.altoclef.multiversion.world.WorldHelper.getBottomY(world);
        //#endif
    }

    @Pattern
    public static int getTopY(World world) {
        //#if MC >= 11701
        return world.getTopY();
        //#else
        //$$ return adris.altoclef.multiversion.world.WorldHelper.getTopY(world);
        //#endif
    }

    @Pattern
    private static boolean isOutOfHeightLimit(World world,BlockPos pos) {
        //#if MC >= 11701
        return world.isOutOfHeightLimit(pos);
        //#else
        //$$ return adris.altoclef.multiversion.world.WorldHelper.isOutOfHeightLimit(world,pos);
        //#endif
    }

}
