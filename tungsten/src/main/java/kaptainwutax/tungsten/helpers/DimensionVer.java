package kaptainwutax.tungsten.helpers;

import net.minecraft.world.dimension.DimensionType;

public class DimensionVer {
    public static boolean isUltrawarm(DimensionType dim) {
        //#if MC >= 12111
        //$$ return false; // TODO: use EnvironmentAttributes API
        //#else
        return dim.ultrawarm();
        //#endif
    }
}
