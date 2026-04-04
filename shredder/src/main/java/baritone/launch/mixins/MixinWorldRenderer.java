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

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.RenderEvent;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.ObjectAllocator;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author Brady
 * @since 2/13/2020
 */
@Mixin(WorldRenderer.class)
public class MixinWorldRenderer {

    @Inject(
            method = "render",
            at = @At("RETURN")
    )
    private void onStartHand(final ObjectAllocator objectAllocator, final RenderTickCounter deltaTracker, final boolean bl, final Camera camera, final Matrix4f matrix4f, final Matrix4f matrix4f2, final Matrix4f matrix4f3, final GpuBufferSlice gpuBufferSlice, final Vector4f vector4f, final boolean bl2, final CallbackInfo ci) {
        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            MatrixStack poseStack = new MatrixStack();
            poseStack.multiplyPositionMatrix(matrix4f);
            ibaritone.getGameEventHandler().onRenderPass(new RenderEvent(deltaTracker.getTickDelta(false), poseStack, matrix4f2));
        }
    }
}
