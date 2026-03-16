package adris.altoclef.multiversion.box;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class BoxHelper {

    //#if MC <= 11605
    //$$ public static Box of(Vec3d center, double dx, double dy, double dz) {
    //$$     return new Box(center.x - dx / 2.0, center.y - dy / 2.0, center.z - dz / 2.0, center.x + dx / 2.0, center.y + dy / 2.0, center.z + dz / 2.0);
    //$$ }
    //#endif

}
