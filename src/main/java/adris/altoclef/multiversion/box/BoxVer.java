package adris.altoclef.multiversion.box;

import adris.altoclef.multiversion.Pattern;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class BoxVer {


   @Pattern
    public Box of(Vec3d center, double x, double y, double z) {
       //#if MC >= 11701
       return Box.of(center, x, y, z);
       //#else
       //$$ return adris.altoclef.multiversion.box.BoxHelper.of(center, x, y, z);
       //#endif
   }


}
