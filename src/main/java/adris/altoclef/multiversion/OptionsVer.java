package adris.altoclef.multiversion;

import net.minecraft.client.MinecraftClient;

public class OptionsVer {


    public static void setGamma(double value) {
        //#if MC >= 11904
        MinecraftClient.getInstance().options.getGamma().setValue(value);
        //#else
        //$$ MinecraftClient.getInstance().options.gamma = value;
        //#endif
    }

    public static void setAutoJump(boolean value) {
        //#if MC >= 11904
        MinecraftClient.getInstance().options.getAutoJump().setValue(value);
        //#else
        //$$ MinecraftClient.getInstance().options.autoJump = value;
        //#endif
    }

}
