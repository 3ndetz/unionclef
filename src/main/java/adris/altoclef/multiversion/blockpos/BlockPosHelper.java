package adris.altoclef.multiversion.blockpos;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

public class BlockPosHelper {

    //#if MC <= 11605
    //$$ public static Vec3i add(Vec3i blockPos, int x, int y, int z) {
    //$$     return x == 0 && y == 0 && z == 0 ? blockPos : new Vec3i(blockPos.getX() + x, blockPos.getY() + y, blockPos.getZ() + z);
    //$$ }
    //$$ public static BlockPos add(BlockPos blockPos, int x, int y, int z) {
    //$$     return x == 0 && y == 0 && z == 0 ? blockPos : new BlockPos(blockPos.getX() + x, blockPos.getY() + y, blockPos.getZ() + z);
    //$$ }
    //#endif


}
