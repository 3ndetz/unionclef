package adris.altoclef.mixins;

import adris.altoclef.control.AttackDispatchLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes vanilla's player-input attack for AttackDispatchLog; read-only, changes nothing. */
@Mixin(MinecraftClient.class)
public abstract class AttackDispatchMixin {

    @Shadow
    protected int attackCooldown;

    @Shadow
    public HitResult crosshairTarget;

    @Inject(method = "doAttack", at = @At("HEAD"))
    private void altoclef$attackDispatchBegin(CallbackInfoReturnable<Boolean> cir) {
        AttackDispatchLog.begin(attackCooldown, crosshairTarget);
    }

    @Inject(method = "doAttack", at = @At("RETURN"))
    private void altoclef$attackDispatchEnd(CallbackInfoReturnable<Boolean> cir) {
        AttackDispatchLog.end(Boolean.TRUE.equals(cir.getReturnValue()));
    }
}
