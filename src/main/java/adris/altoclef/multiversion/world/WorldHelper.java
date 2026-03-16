package adris.altoclef.multiversion.world;

import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;

public class WorldHelper {

    //#if MC <= 11605
    //$$ public static int getTopY(World world) {
    //$$     return 255;
    //$$ }
    //$$ public static int getBottomY(World world) {
    //$$     return 0;
    //$$ }
    //$$
    //$$ public static boolean isOutOfHeightLimit(World world,BlockPos pos) {
    //$$      return isOutOfHeightLimit(pos.getY());
    //$$   }
    //$$
    //$$ private static boolean isOutOfHeightLimit(int y) {
    //$$      return y < getBottomY(null) || y >= getTopY(null);
    //$$   }
    //#endif

}
