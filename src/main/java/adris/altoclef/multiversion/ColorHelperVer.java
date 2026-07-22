package adris.altoclef.multiversion;

import net.minecraft.util.math.ColorHelper;

public class ColorHelperVer {
    public static int getAlpha(int color) {
        //#if MC >= 12111
        //$$ return ColorHelper.getAlpha(color);
        //#else
        return ColorHelper.Argb.getAlpha(color);
        //#endif
    }
    public static int getRed(int color) {
        //#if MC >= 12111
        //$$ return ColorHelper.getRed(color);
        //#else
        return ColorHelper.Argb.getRed(color);
        //#endif
    }
    public static int getGreen(int color) {
        //#if MC >= 12111
        //$$ return ColorHelper.getGreen(color);
        //#else
        return ColorHelper.Argb.getGreen(color);
        //#endif
    }
    public static int getBlue(int color) {
        //#if MC >= 12111
        //$$ return ColorHelper.getBlue(color);
        //#else
        return ColorHelper.Argb.getBlue(color);
        //#endif
    }
    public static int getArgb(int a, int r, int g, int b) {
        //#if MC >= 12111
        //$$ return ColorHelper.getArgb(a, r, g, b);
        //#else
        return ColorHelper.Argb.getArgb(a, r, g, b);
        //#endif
    }
}
