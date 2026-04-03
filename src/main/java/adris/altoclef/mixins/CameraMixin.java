package adris.altoclef.mixins;

import adris.altoclef.AltoClef;
import adris.altoclef.ui.EpicCamera;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setPos(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);

    //#if MC >= 12111
    //$$ @Inject(at = @At("TAIL"), method = "update")
    //$$ private void onUpdate(net.minecraft.world.World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
    //#else
    @Inject(at = @At("TAIL"), method = "update")
    private void onUpdate(net.minecraft.world.BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
    //#endif
        if (thirdPerson && !inverseView
                && AltoClef.getInstance() != null
                && AltoClef.getInstance().getModSettings().isEpicCameraEnabled()) {
            var update = EpicCamera.getInstance().getUpdate(
                    focusedEntity, tickDelta,
                    AltoClef.getCameraRotationModifer(),
                    AltoClef.getCameraPositionModifer());
            if (update != null) {
                setPos(update.getPosition().x, update.getPosition().y, update.getPosition().z);
                setRotation(update.getYaw(), update.getPitch());
            }
        } else {
            EpicCamera.getInstance().reset();
        }
    }
}
