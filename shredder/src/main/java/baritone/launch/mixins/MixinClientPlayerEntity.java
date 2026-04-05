/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.SprintStateEvent;
import baritone.api.event.events.type.EventState;
import baritone.behavior.LookBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.util.PlayerInput;

/**
 * @author Brady
 * @since 8/1/2018
 */
@Mixin(ClientPlayerEntity.class)
public class MixinClientPlayerEntity {
    @Unique
    private static final MethodHandle MAY_FLY = baritone$resolveMayFly();

    @Unique
    private static MethodHandle baritone$resolveMayFly() {
        try {
            var lookup = MethodHandles.publicLookup();
            return lookup.findVirtual(ClientPlayerEntity.class, "mayFly", MethodType.methodType(boolean.class));
        } catch (NoSuchMethodException e) {
            return null;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/client/network/AbstractClientPlayerEntity.tick()V",
                    shift = At.Shift.AFTER
            )
    )
    private void onPreUpdate(CallbackInfo ci) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((ClientPlayerEntity) (Object) this);
        if (baritone != null) {
            baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.PRE));
        }
    }

    @Redirect(
            method = "tickMovement",
            at = @At(
                    value = "FIELD",
                    target = "net/minecraft/entity/player/PlayerAbilities.allowFlying:Z"
            )
    )
    @Group(name = "mayFly", min = 1, max = 1)
    private boolean isAllowFlying(PlayerAbilities capabilities) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((ClientPlayerEntity) (Object) this);
        if (baritone == null) {
            return capabilities.allowFlying;
        }
        return !baritone.getPathingBehavior().isPathing() && capabilities.allowFlying;
    }

    @Redirect(
        method = "tickMovement",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;mayFly()Z"
        )
    )
    @Group(name = "mayFly", min = 1, max = 1)
    private boolean onMayFlyNeoforge(ClientPlayerEntity instance) throws Throwable {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((ClientPlayerEntity) (Object) this);
        if (baritone == null) {
            return (boolean) MAY_FLY.invokeExact(instance);
        }
        return !baritone.getPathingBehavior().isPathing() && (boolean) MAY_FLY.invokeExact(instance);
    }

    @Redirect(
            method = "tickMovement",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/PlayerInput;sprint()Z"
            )
    )
    private boolean redirectSprintInput(final PlayerInput instance) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((ClientPlayerEntity) (Object) this);
        if (baritone == null) {
            return instance.sprint();
        }
        SprintStateEvent event = new SprintStateEvent();
        baritone.getGameEventHandler().onPlayerSprintState(event);
        if (event.getState() != null) {
            return event.getState();
        }
        if (baritone != BaritoneAPI.getProvider().getPrimaryBaritone()) {
            // hitting control shouldn't make all bots sprint
            return false;
        }
        return instance.sprint();
    }

    @Inject(
            method = "tickRiding",
            at = @At(
                    value = "HEAD"
            )
    )
    private void updateRidden(CallbackInfo cb) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((ClientPlayerEntity) (Object) this);
        if (baritone != null) {
            ((LookBehavior) baritone.getLookBehavior()).pig();
        }
    }

    @Redirect(
            method = "tickMovement",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayerEntity;checkGliding()Z"
            )
    )
    private boolean tryToStartFallFlying(final ClientPlayerEntity instance) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(instance);
        if (baritone != null && baritone.getPathingBehavior().isPathing()) {
            return false;
        }
        return instance.checkGliding();
    }

    @Inject(
            method = "getYaw",
            at = @At("RETURN"),
            cancellable = true
    )
    private void onGetYaw(float tickDelta, CallbackInfoReturnable<Float> cir) {
        if (!Baritone.settings().smoothLook.value) {
            return;
        }
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((ClientPlayerEntity) (Object) this);
        if (baritone != null) {
            LookBehavior look = (LookBehavior) baritone.getLookBehavior();
            cir.setReturnValue(look.getSmoothedYaw(cir.getReturnValue()));
        }
    }

    @Inject(
            method = "getPitch",
            at = @At("RETURN"),
            cancellable = true
    )
    private void onGetPitch(float tickDelta, CallbackInfoReturnable<Float> cir) {
        if (!Baritone.settings().smoothLook.value) {
            return;
        }
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((ClientPlayerEntity) (Object) this);
        if (baritone != null) {
            LookBehavior look = (LookBehavior) baritone.getLookBehavior();
            cir.setReturnValue(look.getSmoothedPitch(cir.getReturnValue()));
        }
    }
}
