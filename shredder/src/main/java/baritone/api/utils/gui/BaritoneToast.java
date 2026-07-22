/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.utils.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class BaritoneToast implements Toast {
    private String title;
    private String subtitle;
    private long firstDrawTime;
    private boolean newDisplay;
    private long totalShowTime;
    private Visibility visibility = Visibility.SHOW;

    public BaritoneToast(Text titleComponent, Text subtitleComponent, long totalShowTime) {
        this.title = titleComponent.getString();
        this.subtitle = subtitleComponent == null ? null : subtitleComponent.getString();
        this.totalShowTime = totalShowTime;
    }

    @Override
    public void update(ToastManager toastGui, long delta) {
        if (this.newDisplay) {
            this.firstDrawTime = delta;
            this.newDisplay = false;
        }
        this.visibility = (delta - this.firstDrawTime < totalShowTime) ? Visibility.SHOW : Visibility.HIDE;
    }

    @Override
    public void draw(DrawContext gui, TextRenderer textRenderer, long delta) {
        gui.drawTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("textures/gui/toasts.png"), 0, 0, 0.0f, 32.0f, 160, 32, 256, 256);

        if (this.subtitle == null) {
            gui.drawTextWithShadow(textRenderer, this.title, 18, 12, -11534256);
        } else {
            gui.drawTextWithShadow(textRenderer, this.title, 18, 7, -11534256);
            gui.drawTextWithShadow(textRenderer, this.subtitle, 18, 18, -16777216);
        }
    }

    @Override
    public Visibility getVisibility() {
        return this.visibility;
    }

    public void setDisplayedText(Text titleComponent, Text subtitleComponent) {
        this.title = titleComponent.getString();
        this.subtitle = subtitleComponent == null ? null : subtitleComponent.getString();
        this.newDisplay = true;
    }

    public static void addOrUpdate(ToastManager toast, Text title, Text subtitle, long totalShowTime) {
        BaritoneToast baritonetoast = toast.getToast(BaritoneToast.class, new Object());

        if (baritonetoast == null) {
            toast.add(new BaritoneToast(title, subtitle, totalShowTime));
        } else {
            baritonetoast.setDisplayedText(title, subtitle);
        }
    }

    public static void addOrUpdate(Text title, Text subtitle) {
        addOrUpdate(MinecraftClient.getInstance().getToastManager(), title, subtitle, baritone.api.BaritoneAPI.getSettings().toastTimer.value);
    }
}
