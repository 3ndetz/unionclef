package kaptainwutax.tungsten.mixin;

import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects synthetic mouse deltas into cursorDeltaX/Y before updateMouse() runs.
 * When focused: full vanilla pipeline. When unfocused: UnfocusedMouseHelper handles it.
 */
@Mixin(Mouse.class)
public class MixinMouse {

    @Shadow @Final private MinecraftClient client;
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;

    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void injectSyntheticDeltas(CallbackInfo ci) {
        if (this.client.player == null || this.client.currentScreen != null) return;

        double[] deltas = WindMouseRotation.INSTANCE.consumeRawPixelDeltas();
        if (deltas[0] != 0 || deltas[1] != 0) {
            this.cursorDeltaX += deltas[0];
            this.cursorDeltaY += deltas[1];
        }
    }
}
