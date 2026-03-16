package adris.altoclef.multiversion;

import net.minecraft.client.MinecraftClient;

public class MinecraftClientVer {


    @Pattern
    private static float getTickDelta(MinecraftClient client) {
        //#if MC >= 12100
        return client.getRenderTickCounter().getTickDelta(true);
        //#else
        //$$ return client.getTickDelta();
        //#endif
    }

}
