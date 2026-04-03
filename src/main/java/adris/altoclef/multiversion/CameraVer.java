package adris.altoclef.multiversion;

import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;

public class CameraVer {
    @Pattern
    private static Vec3d getPos(Camera camera) {
        //#if MC >= 12111
        //$$ return camera.getCameraPos();
        //#else
        return camera.getPos();
        //#endif
    }
}
