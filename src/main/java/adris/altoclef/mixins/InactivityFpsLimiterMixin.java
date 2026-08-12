package adris.altoclef.mixins;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.InactivityFpsLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops vanilla throttling a HEADLESS bot client to 10 fps for looking idle.
 *
 * <p>⛔ WHAT THIS IS FOR, AND THE NUMBER THAT FOUND IT. The bench discards any run below a 14 fps
 * validity floor, and about 40% of every series was being discarded. The frame rate was bimodal:
 * 28-29 fps or EXACTLY 10.0, with nothing in between, over and over. A CPU-bound software
 * rasteriser gives scattered numbers like 8.3 and 11.7; the same round 10.0 run after run is a
 * limiter, not a load. options.txt sets maxFps:30, which is the healthy cluster, and 10 is what
 * {@link InactivityFpsLimiter} imposes on a client whose window is unfocused and whose input has
 * gone quiet.
 *
 * <p>pauseOnLostFocus:false switches off the PAUSE, not this limiter. And the bot's input arrives
 * through the mod rather than through the window, so from vanilla's point of view nobody has
 * touched the keyboard in minutes — which is exactly true, and exactly the wrong conclusion.
 *
 * <p>Two cheaper explanations were measured and refuted before this one: foreign load (tester1 at
 * 44% of a core and tester2 at 32%, on a 24-core host) and the render resolution (/proc shows the X
 * server holding 854x480 while BOTH clusters appear in the same series on the same container).
 *
 * <p>OFF BY DEFAULT, because a human's client should keep vanilla behaviour — an idle Minecraft
 * SHOULD stop burning a core. The bench pins {@code botFpsNoIdleThrottle} on.
 */
@Mixin(InactivityFpsLimiter.class)
public class InactivityFpsLimiterMixin {

    /**
     * Hands back the user's own maxFps instead of the idle limit.
     *
     * <p>Deliberately returns the CONFIGURED cap rather than an unbounded value: the point is to
     * stop the client being throttled below its own setting, not to let a headless container spin
     * a core producing frames nobody looks at.
     */
    @Inject(method = "update", at = @At("RETURN"), cancellable = true)
    private void unionclef$keepBotClientAwake(CallbackInfoReturnable<Integer> cir) {
        if (!kaptainwutax.tungsten.TungstenConfig.get().botFpsNoIdleThrottle) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return;
        int configured = mc.options.getMaxFps().getValue();
        if (cir.getReturnValue() < configured) {
            cir.setReturnValue(configured);
        }
    }
}
