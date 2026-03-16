package adris.altoclef.mixins;

import adris.altoclef.util.agent.AgentInputBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {
    @Shadow @Final private MinecraftClient client;
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;
    @Shadow private double x;
    @Shadow private double y;

    @Unique private double virtualX = 0;
    @Unique private double virtualY = 0;

    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void onUpdateMouseHead(CallbackInfo ci) {
        if (AgentInputBridge.isAgentInputActive) {
            double[] deltas = AgentInputBridge.consumeDeltas();
            double dx = deltas[0];
            double dy = deltas[1];

            virtualX += dx;
            virtualY += dy;

            Window window = this.client.getWindow();
            virtualX = Math.max(0, Math.min(window.getWidth(), virtualX));
            virtualY = Math.max(0, Math.min(window.getHeight(), virtualY));

            this.x = virtualX;
            this.y = virtualY;

            if (this.client.player != null && this.client.currentScreen == null) {
                this.client.player.changeLookDirection(dx, dy);
            }

            this.cursorDeltaX = 0;
            this.cursorDeltaY = 0;
            AgentInputBridge.afterTick();
        }
    }
}
