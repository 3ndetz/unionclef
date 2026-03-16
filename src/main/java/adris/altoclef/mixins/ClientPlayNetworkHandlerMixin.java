package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.AnimEvent;
import adris.altoclef.eventbus.events.DamageEvent;
import adris.altoclef.eventbus.events.multiplayer.ItemUseEvent;
import adris.altoclef.eventbus.events.multiplayer.ProjectileEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    @Shadow
    private ClientWorld world;

    @Inject(method = "onEntityTrackerUpdate", at = @At("TAIL"))
    public void onEntityTrackerUpdate(EntityTrackerUpdateS2CPacket packet, CallbackInfo ci) {
        try {
            Entity entity = world != null ? world.getEntityById(packet.id()) : null;
            if (entity != null) {
                if ((entity instanceof PlayerEntity player && player.getName() != null) ||
                        (entity instanceof ProjectileEntity)) {
                    if (packet.trackedValues() != null &&
                            packet.trackedValues().size() == 1 &&
                            packet.trackedValues().getFirst() != null &&
                            packet.trackedValues().getFirst().id() == 8 &&
                            packet.trackedValues().getFirst().value() instanceof Byte byteVal) {
                        int value = (byte) byteVal & 1;
                        boolean released = value <= 0;
                        if (entity instanceof PlayerEntity)
                            EventBus.publish(new ItemUseEvent(entity, released));
                        if (entity instanceof ProjectileEntity projectile && projectile.getPos() != null)
                            EventBus.publish(new ProjectileEvent(projectile, released));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Inject(method = "onEntityDamage", at = @At("TAIL"))
    private void onEntityDamage(EntityDamageS2CPacket packet, CallbackInfo ci) {
        Entity entity = world != null ? world.getEntityById(packet.entityId()) : null;
        if (entity != null) {
            EventBus.publish(new DamageEvent(entity));
        }
    }

    @Inject(method = "onEntityAnimation", at = @At("TAIL"))
    public void onEntityAnimation(EntityAnimationS2CPacket packet, CallbackInfo ci) {
        Entity entity = world != null ? world.getEntityById(packet.getEntityId()) : null;
        if (entity != null) {
            EventBus.publish(new AnimEvent(entity, packet.getAnimationId()));
        }
    }
}
