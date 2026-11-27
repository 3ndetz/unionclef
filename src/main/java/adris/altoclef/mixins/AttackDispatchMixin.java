package adris.altoclef.mixins;

import adris.altoclef.control.AttackDispatchLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes vanilla's player-input attack for AttackDispatchLog. Changes nothing, except that a press
 *  armed by tapAttackGuarded whose target check fails at dispatch is cancelled (logged with the reason). */
@Mixin(MinecraftClient.class)
public abstract class AttackDispatchMixin {

    @Shadow
    protected int attackCooldown;

    @Shadow
    public HitResult crosshairTarget;

    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void altoclef$attackDispatchBegin(CallbackInfoReturnable<Boolean> cir) {
        // Only a press armed by tapAttackGuarded is checked; a failed check cancels this one attack.
        String refused = AttackDispatchLog.checkAtDispatch((MinecraftClient) (Object) this, attackCooldown, crosshairTarget);
        if (refused != null) {
            cir.setReturnValue(false);
            return;
        }
        AttackDispatchLog.begin(attackCooldown, crosshairTarget);
    }

    @Inject(method = "doAttack", at = @At("RETURN"))
    private void altoclef$attackDispatchEnd(CallbackInfoReturnable<Boolean> cir) {
        AttackDispatchLog.end(Boolean.TRUE.equals(cir.getReturnValue()));
    }
}
