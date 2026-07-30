package kaptainwutax.tungsten.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import kaptainwutax.tungsten.path.movements.Movement;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;

/**
 * Resolve a ported {@link Movement}'s direction keys in the facing that movement ASKED for, not in
 * the facing the camera happens to have reached.
 *
 * <p>Port of {@code baritone/src/main/java/baritone/launch/mixins/MixinEntity.java:43-66}
 * ({@code RotationMoveEvent.Type.MOTION_UPDATE}), which exists verbatim in
 * {@code shredder/src/main/java/baritone/launch/mixins/MixinEntity.java} and therefore already
 * compiles on this MC version — the {@code @Shadow private float yaw} on {@code Entity} is theirs.
 * Upstream routes it through an event bus and {@code LookBehavior.onPlayerRotationMove}; there is
 * one producer here, so the frame is a field on {@link Movement} instead.
 *
 * <p><b>Why this is not cosmetic.</b> Baritone can press a direction key in the same tick it asks
 * for a rotation because it snaps the yaw itself ({@code LookBehavior.onPlayerUpdate} PRE) and
 * because of this very mixin. Tungsten aims through {@code WindMouseRotation}, which steps once per
 * RENDER FRAME, so a movement that asked for a 180 turn and pressed MOVE_FORWARD was walking
 * BACKWARDS for as long as the turn took — measured on nav_bridge, three blocks of retreat at ~9 fps
 * and a fall into the void. The full trace is recorded on {@link Movement#motionYaw}.
 *
 * <p>Scope: the client player only, and only on ticks a ported movement declared a rotation
 * ({@code MovementQueue} clears the frame when it stops, {@code MixinClientPlayerEntity} clears it
 * at the end of every tick). Everything else in the game — the walker, combat, the human — moves by
 * the camera exactly as before.
 *
 * <p>NOT ported: {@code RotationMoveEvent.Type.JUMP} ({@code MixinLivingEntity.jump}) and the elytra
 * hooks. The sprint-jump boost is resolved in the camera's yaw for now; no movement in this port
 * jumps and turns in the same tick, and adding it would be a second mechanism in one change.
 */
@Mixin(Entity.class)
public class MixinEntityMotionYaw {

    @Shadow
    private float yaw;

    @Shadow
    private float pitch;

    @Unique
    private float tungsten$savedYaw;

    @Unique
    private float tungsten$savedPitch;

    @Unique
    private boolean tungsten$swapped;

    @Inject(method = "updateVelocity", at = @At("HEAD"))
    private void tungsten$motionFrameHead(CallbackInfo ci) {
        tungsten$swapped = false;
        if (!(((Object) this) instanceof ClientPlayerEntity)) {
            return;
        }
        Float wantYaw = Movement.motionYaw;
        if (wantYaw == null) {
            return;
        }
        Float wantPitch = Movement.motionPitch;
        tungsten$savedYaw = this.yaw;
        tungsten$savedPitch = this.pitch;
        this.yaw = wantYaw;
        if (wantPitch != null) {
            this.pitch = wantPitch;
        }
        tungsten$swapped = true;
    }

    @Inject(method = "updateVelocity", at = @At("RETURN"))
    private void tungsten$motionFrameReturn(CallbackInfo ci) {
        if (tungsten$swapped) {
            this.yaw = tungsten$savedYaw;
            this.pitch = tungsten$savedPitch;
            tungsten$swapped = false;
        }
    }
}
