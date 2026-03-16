package adris.altoclef.mixins;

import adris.altoclef.util.helpers.MouseMoveHelper;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerEntity.class)
public abstract class MixinLocalPlayer extends AbstractClientPlayerEntity {

    public MixinLocalPlayer(ClientWorld world, GameProfile profile) {
        super(world, profile);
    }

    @Inject(method = "getPitch", at = @At("RETURN"), cancellable = true)
    public void getPitch(float tickDelta, CallbackInfoReturnable<Float> cir) {
        if (MouseMoveHelper.RotationEnabled)
            cir.setReturnValue(super.getPitch(tickDelta));
    }

    @Inject(method = "getYaw", at = @At("RETURN"), cancellable = true)
    public void getYaw(float tickDelta, CallbackInfoReturnable<Float> cir) {
        if (MouseMoveHelper.RotationEnabled)
            cir.setReturnValue(super.getYaw(tickDelta));
    }
}