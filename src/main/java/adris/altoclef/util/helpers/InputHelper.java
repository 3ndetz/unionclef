package adris.altoclef.util.helpers;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;

public class InputHelper {

    public static boolean isKeyPressed(int code) {
        //#if MC < 12111
        return InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow().getHandle(), code);
        //#else
        //$$ return InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow(), code); // TODO [1.21.11] isKeyPressed(long, int) → isKeyPressed(Window, int)
        //#endif
    }
}
