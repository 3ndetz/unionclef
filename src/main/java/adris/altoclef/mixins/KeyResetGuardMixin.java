package adris.altoclef.mixins;

import adris.altoclef.control.AttackDispatchLog;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla throws every pending key press away here (a screen opened, a world change, focus lost);
 *  a guarded attack press goes with them, so its guard is dropped with a receipt. Changes nothing. */
@Mixin(KeyBinding.class)
public abstract class KeyResetGuardMixin {
    @Inject(method = "unpressAll", at = @At("HEAD"))
    private static void altoclef$dropGuardOnReset(CallbackInfo ci) {
        AttackDispatchLog.onKeysReset();
    }
}
