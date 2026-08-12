package adris.altoclef.mixins;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
//#if MC >= 12111
//$$ import net.minecraft.client.option.InactivityFpsLimiter;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#endif

/**
 * Stops vanilla throttling a HEADLESS bot client to 10 fps for looking idle.
 *
 * <p>⛔ WHAT THIS IS FOR, AND THE NUMBER THAT FOUND IT. The bench discards any run under a 14 fps
 * validity floor, and about 40% of every series was being discarded. The frame rate was bimodal:
 * 28-29 fps or EXACTLY 10.0, nothing in between, run after run. A CPU-bound software rasteriser
 * gives scattered numbers like 8.3 and 11.7; the same round 10.0 repeatedly is a limiter, not a
 * load. options.txt sets maxFps:30 — that is the healthy cluster — and 10 is what vanilla's
 * inactivity limiter imposes on a client whose window is unfocused and whose input has gone quiet.
 *
 * <p>pauseOnLostFocus:false switches off the PAUSE, not this. The bot's input arrives through the
 * mod rather than through the window, so vanilla is quite right that nobody has touched the
 * keyboard — and quite wrong about what that means for a bench client.
 *
 * <p>Two cheaper explanations were measured and refuted first: foreign load (tester1 at 44% of a
 * core, tester2 at 32%, on a 24-core host) and the render resolution (/proc shows the X server
 * holding 854x480 while BOTH clusters appear in one series on one container).
 *
 * <p>⛔ WHICH BRANCH IS LIVE, because two attempts died on it. InactivityFpsLimiter arrived in
 * 1.21.11 and is absent from the 1.21 base this tree compiles against, so the BASE branch must be
 * the plain-Java one and the 1.21.11 mixin must be the {@code //$$} commented one — the
 * preprocessor swaps them for the versioned build. The annotation has to move with the class, not
 * just the method: leaving {@code @Mixin(MinecraftClient.class)} live for both would inject
 * {@code update} into the wrong class on 1.21.11. Same shape as MiningToolItemAccessor.
 *
 * <p>OFF BY DEFAULT: an idle Minecraft on a human's machine SHOULD stop burning a core. The bench
 * pins {@code botFpsNoIdleThrottle} on.
 */
//#if MC < 12111
@Mixin(MinecraftClient.class) // no-op stub — InactivityFpsLimiter does not exist before 1.21.11
public class InactivityFpsLimiterMixin {
}
//#else
//$$ @Mixin(InactivityFpsLimiter.class)
//$$ public class InactivityFpsLimiterMixin {
//$$     /**
//$$      * Hands back the user's own maxFps instead of the idle limit. Returns the CONFIGURED cap
//$$      * rather than an unbounded value on purpose: the point is to stop the client being
//$$      * throttled below its own setting, not to let a headless container spin a core producing
//$$      * frames nobody will ever look at.
//$$      */
//$$     @Inject(method = "update", at = @At("RETURN"), cancellable = true)
//$$     private void unionclef$keepBotClientAwake(CallbackInfoReturnable<Integer> cir) {
//$$         if (!kaptainwutax.tungsten.TungstenConfig.get().botFpsNoIdleThrottle) return;
//$$         MinecraftClient mc = MinecraftClient.getInstance();
//$$         if (mc == null || mc.options == null) return;
//$$         int configured = mc.options.getMaxFps().getValue();
//$$         if (cir.getReturnValue() < configured) {
//$$             cir.setReturnValue(configured);
//$$         }
//$$     }
//$$ }
//#endif
