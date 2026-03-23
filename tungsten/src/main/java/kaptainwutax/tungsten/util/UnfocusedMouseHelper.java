package kaptainwutax.tungsten.util;

import net.minecraft.client.MinecraftClient;

/**
 * Applies pending WindMouse rotation deltas when MC window is unfocused.
 * Mouse.updateMouse() doesn't run without focus, so we replicate the
 * sensitivity formula + changeLookDirection here.
 */
public final class UnfocusedMouseHelper {

    private UnfocusedMouseHelper() {}

    public static void applyPendingDeltas() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.currentScreen != null) return;

        double[] deltas = WindMouseRotation.INSTANCE.consumeRawPixelDeltas();
        if (deltas[0] == 0 && deltas[1] == 0) return;

        double sens = mc.options.getMouseSensitivity().getValue();
        double f = sens * 0.6 + 0.2;
        double scale = f * f * f * 8.0;

        mc.player.changeLookDirection(deltas[0] * scale, deltas[1] * scale);
    }
}
