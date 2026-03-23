package kaptainwutax.tungsten.mixin;

import kaptainwutax.tungsten.combat.CombatController;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into InGameHud.render() — called every rendered frame (~60 FPS).
 * Drives render-frequency systems: WindMouse rotation + SafetySystem visualization.
 */
@Mixin(InGameHud.class)
public class MixinInGameHud {

    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderFrame(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            WindMouseRotation.INSTANCE.applyRenderStep(mc.player);
            CombatController.safety.renderUpdate(tickCounter.getTickDelta(true));
        }
    }
}
