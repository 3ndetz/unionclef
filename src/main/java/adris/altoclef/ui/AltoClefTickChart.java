package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import adris.altoclef.multiversion.InGameHudVer;
import adris.altoclef.multiversion.DrawContextWrapper;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * displays a chart indicating how much of the tick time is taken up by altoclef
 * (very similar to Minecrafts `TickChart`)
 */
public class AltoClefTickChart {


    protected final TextRenderer textRenderer;
    protected final List<Long> list = new ArrayList<>();

    public AltoClefTickChart(TextRenderer textRenderer) {
        this.textRenderer = textRenderer;
    }


    public void pushTickNanos(long nanoTime) {
        list.add(nanoTime);
    }

    public void render(AltoClef mod, DrawContextWrapper context, int x, int width) {
        if (InGameHudVer.shouldShowDebugHud() || !mod.getTaskRunner().isActive()) return;

        int height = context.getScaledWindowHeight();
        context.fill(x, height - 37, x + width, height, 0x90505050);

        long m = Integer.MAX_VALUE;
        long n = Integer.MIN_VALUE;


        while (list.size() >= width-1) {
            list.remove(0);
        }

        for (int i = 0; i < list.size(); ++i) {
            int p = x + i + 1;

            long r = this.get(i);
            m = Math.min(m, r);
            n = Math.max(n, r);

            this.drawTotalBar(context, p, height, i);
        }

        context.drawHorizontalLine(x, x + width - 1, height - 37, 0xFFDDDDDD);
        context.drawHorizontalLine(x, x + width - 1, height - 1, 0xFFDDDDDD);
        context.drawVerticalLine(x, height - 37, height, 0xFFDDDDDD);
        context.drawVerticalLine(x + width - 1, height - 37, height, 0xFFDDDDDD);


        this.drawBorderedText(context, "50 ms", x + 1, height - 37 + 1);
    }


    protected void drawTotalBar(DrawContextWrapper context, int x, int y, int index) {
        long l = list.get(index);
        int i = this.getHeight(l);
        int j = this.getColor(l);
        context.fill(x, y - i, x + 1, y, j);
    }

    protected long get(int index) {
        return list.get(index);
    }


    protected void drawBorderedText(DrawContextWrapper context, String string, int x, int y) {
        MatrixStack matrixStack = context.getMatrices();
        matrixStack.push();
        matrixStack.scale(0.5f,0.5f,1);

        context.fill(x*2, y*2, x*2 + this.textRenderer.getWidth(string) + 2, y*2 + this.textRenderer.fontHeight+1, 0x90505050);
        context.drawText(this.textRenderer, string, (x + 1)*2, (y + 1)*2, 0xE9E9E9, false);

        matrixStack.pop();
    }



    protected int getHeight(double value) {
        return (int)Math.round(nanosToMillis(value) * 37 / 50d);
    }


    protected int getColor(long value) {
        float maxMs = 50f;
        double ms = nanosToMillis(value);

        if (ms > maxMs) {
            return 0xFFFFFFFF;
        }

        return getColor(ms/maxMs, 0xFF00FF00, 0xFFFFC800, 0xFFFF0000);
    }

    protected int getColor(double value, int minColor, int medianColor, int maxColor) {
        if (value < 0.5) {
            return lerp((float)((value) / (0.5)), minColor, medianColor);
        }
        return lerp((float)((value - 0.5) / 0.5), medianColor, maxColor);
    }

    private static int lerp(float delta, int start, int end) {
        int i = (int) MathHelper.lerp(delta, ColorHelper.Argb.getAlpha(start), ColorHelper.Argb.getAlpha(end));
        int j = (int) MathHelper.lerp(delta, ColorHelper.Argb.getRed(start), ColorHelper.Argb.getRed(end));
        int k = (int) MathHelper.lerp(delta, ColorHelper.Argb.getGreen(start), ColorHelper.Argb.getGreen(end));
        int l = (int) MathHelper.lerp(delta, ColorHelper.Argb.getBlue(start), ColorHelper.Argb.getBlue(end));
        return ColorHelper.Argb.getArgb(i, j, k, l);
    }

    private static double nanosToMillis(double nanos) {
        return nanos / 1_000_000.0;
    }

}
