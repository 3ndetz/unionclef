package adris.altoclef.multiversion;

import net.minecraft.client.MinecraftClient;

public class InGameHudVer {

    public static boolean shouldShowDebugHud() {
        //#if MC > 12001
        return MinecraftClient.getInstance().inGameHud.getDebugHud().shouldShowDebugHud();
        //#else
        //$$ return MinecraftClient.getInstance().options.debugEnabled;
        //#endif
    }

}
