package adris.altoclef.mixins;

import adris.altoclef.util.agent.NoBreakGuard;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No break starts while the no-break policy is on (see NoBreakGuard). First of the break mixins. */
@Mixin(value = ClientPlayerInteractionManager.class, priority = 100)
public final class NoBreakGuardMixin {
    @Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
    private void altoclef$guardAttack(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> ci) {
        if (NoBreakGuard.refuse(pos)) ci.setReturnValue(false);
    }

    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void altoclef$guardProgress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> ci) {
        if (NoBreakGuard.refuse(pos)) ci.setReturnValue(false);
    }
}
