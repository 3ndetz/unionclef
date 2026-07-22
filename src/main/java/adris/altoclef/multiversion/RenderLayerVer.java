package adris.altoclef.multiversion;

import net.minecraft.client.render.RenderLayer;

public class RenderLayerVer {


    public static RenderLayer getGuiOverlay() {
        //#if MC >= 12111
        //$$ return null; // RenderLayer.getGuiOverlay() removed in 1.21.11
        //#elseif MC >= 12001
        return RenderLayer.getGuiOverlay();
        //#else
        //$$ return null;
        //#endif
    }

}
