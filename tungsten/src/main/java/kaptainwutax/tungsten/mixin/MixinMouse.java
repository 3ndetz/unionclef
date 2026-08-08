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
// NOTE, and a warning about how this was nearly "fixed": both this injection and
        // UnfocusedMouseHelper call consumeRawPixelDeltas(), which ZEROES the buffer, so they can
        // race. I added a !isWindowFocused() guard here on the strength of a trace showing the yaw
        // moving on 1 tick in 100 — and that trace was INVALID, sampled before the fight started.
        //
        // Sampled properly, while punkStats reported combat=82:
        //     ticks where yaw moved : 26 of 99 (26%)
        //     mean |dyaw| per tick  : 13.56 deg
        //     max  |dyaw| per tick  : 175.35 deg
        // The head is not frozen. It WHIPS — 175 degrees in a tick — which is the shake the aim
        // low-pass in SafetySystem was added for ("прицел трясёт как не в себя"). Missing a 40 deg
        // window while swinging past the target is a different fault from never turning.

        double[] deltas = WindMouseRotation.INSTANCE.consumeRawPixelDeltas();
        if (deltas[0] != 0 || deltas[1] != 0) {
            this.cursorDeltaX += deltas[0];
            this.cursorDeltaY += deltas[1];
        }
    }
}
