package kaptainwutax.tungsten.mixin;

import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects synthetic mouse deltas into cursorDeltaX/Y before updateMouse() runs.
 *
 * When window is focused: deltas go into cursorDeltaX/Y → full vanilla pipeline.
 * When window is NOT focused: updateMouse() is never called by MC, so we
 * apply the sensitivity scaling + changeLookDirection ourselves via
 * applyPendingUnfocused(), called from MixinInGameHud.
 */
@Mixin(Mouse.class)
public class MixinMouse {

    @Shadow @Final private MinecraftClient client;
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;

    /** When focused: inject our pixel deltas into cursorDelta before updateMouse. */
    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void injectSyntheticDeltas(CallbackInfo ci) {
        if (this.client.player == null || this.client.currentScreen != null) return;

        double[] deltas = WindMouseRotation.INSTANCE.consumeRawPixelDeltas();
        if (deltas[0] != 0 || deltas[1] != 0) {
            this.cursorDeltaX += deltas[0];
            this.cursorDeltaY += deltas[1];
        }
    }

    /**
     * When window is unfocused, MC doesn't call updateMouse().
     * This method consumes pending deltas and applies them through
     * the same sensitivity formula + changeLookDirection.
     * Called from MixinInGameHud every render frame.
     */
    @Unique
    public static void applyPendingUnfocused() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.currentScreen != null) return;

        double[] deltas = WindMouseRotation.INSTANCE.consumeRawPixelDeltas();
        if (deltas[0] == 0 && deltas[1] == 0) return;

        // replicate MC sensitivity formula: f = sens * 0.6 + 0.2; scale = f³ * 8
        double sens = mc.options.getMouseSensitivity().getValue();
        double f = sens * 0.6 + 0.2;
        double scale = f * f * f * 8.0;

        double dx = deltas[0] * scale;
        double dy = deltas[1] * scale;

        mc.player.changeLookDirection(dx, dy);
    }
}
