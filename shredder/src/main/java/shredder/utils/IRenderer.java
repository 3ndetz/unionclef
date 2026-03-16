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

package shredder.utils;

import shredder.api.BaritoneAPI;
import shredder.api.Settings;
import shredder.utils.accessor.IEntityRenderManager;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public interface IRenderer {

    Tessellator tessellator = Tessellator.getInstance();
    IEntityRenderManager renderManager = (IEntityRenderManager) MinecraftClient.getInstance().getEntityRenderDispatcher();
    TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
    Settings settings = BaritoneAPI.getSettings();

    float[] color = new float[]{1.0F, 1.0F, 1.0F, 255.0F};

    static void glColor(Color color, float alpha) {
        float[] colorComponents = color.getColorComponents(null);
        IRenderer.color[0] = colorComponents[0];
        IRenderer.color[1] = colorComponents[1];
        IRenderer.color[2] = colorComponents[2];
        IRenderer.color[3] = alpha;
    }

    static BufferBuilder startLines(Color color, float alpha, float lineWidth, boolean ignoreDepth) {
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.blendFuncSeparate(
                GlStateManager.SrcFactor.SRC_ALPHA,
                GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SrcFactor.ONE,
                GlStateManager.DstFactor.ZERO
        );
        glColor(color, alpha);
        RenderSystem.lineWidth(lineWidth);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        if (ignoreDepth) {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
        return tessellator.begin(VertexFormat.DrawMode.LINES, VertexFormats.LINES);
    }

    static BufferBuilder startLines(Color color, float lineWidth, boolean ignoreDepth) {
        return startLines(color, .4f, lineWidth, ignoreDepth);
    }

    static void endLines(BufferBuilder bufferBuilder, boolean ignoredDepth) {
        BuiltBuffer meshData = bufferBuilder.endNullable();
        if (meshData != null) {
            BufferRenderer.drawWithGlobalProgram(meshData);
        }
        
        if (ignoredDepth) {
            RenderSystem.enableDepthTest();
        }

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    static void emitLine(BufferBuilder bufferBuilder, MatrixStack stack, double x1, double y1, double z1, double x2, double y2, double z2) {
        final double dx = x2 - x1;
        final double dy = y2 - y1;
        final double dz = z2 - z1;

        final double invMag = 1.0 / Math.sqrt(dx * dx + dy * dy + dz * dz);
        final float nx = (float) (dx * invMag);
        final float ny = (float) (dy * invMag);
        final float nz = (float) (dz * invMag);

        emitLine(bufferBuilder, stack, x1, y1, z1, x2, y2, z2, nx, ny, nz);
    }

    static void emitLine(BufferBuilder bufferBuilder, MatrixStack stack,
                         double x1, double y1, double z1,
                         double x2, double y2, double z2,
                         double nx, double ny, double nz) {
        emitLine(bufferBuilder, stack,
                (float) x1, (float) y1, (float) z1,
                (float) x2, (float) y2, (float) z2,
                (float) nx, (float) ny, (float) nz
        );
    }

    static void emitLine(BufferBuilder bufferBuilder, MatrixStack stack,
                         float x1, float y1, float z1,
                         float x2, float y2, float z2,
                         float nx, float ny, float nz) {
        MatrixStack.Entry pose = stack.peek();

        bufferBuilder.vertex(pose, x1, y1, z1).color(color[0], color[1], color[2], color[3]).normal(pose, nx, ny, nz);
        bufferBuilder.vertex(pose, x2, y2, z2).color(color[0], color[1], color[2], color[3]).normal(pose, nx, ny, nz);
    }

    static void emitAABB(BufferBuilder bufferBuilder, MatrixStack stack, Box aabb) {
        Box toDraw = aabb.offset(-renderManager.renderPosX(), -renderManager.renderPosY(), -renderManager.renderPosZ());

        // bottom
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.minY, toDraw.minZ, toDraw.maxX, toDraw.minY, toDraw.minZ, 1.0, 0.0, 0.0);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.minY, toDraw.minZ, toDraw.maxX, toDraw.minY, toDraw.maxZ, 0.0, 0.0, 1.0);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.minY, toDraw.maxZ, toDraw.minX, toDraw.minY, toDraw.maxZ, -1.0, 0.0, 0.0);
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.minY, toDraw.maxZ, toDraw.minX, toDraw.minY, toDraw.minZ, 0.0, 0.0, -1.0);
        // top
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.maxY, toDraw.minZ, toDraw.maxX, toDraw.maxY, toDraw.minZ, 1.0, 0.0, 0.0);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.maxY, toDraw.minZ, toDraw.maxX, toDraw.maxY, toDraw.maxZ, 0.0, 0.0, 1.0);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.maxY, toDraw.maxZ, toDraw.minX, toDraw.maxY, toDraw.maxZ, -1.0, 0.0, 0.0);
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.maxY, toDraw.maxZ, toDraw.minX, toDraw.maxY, toDraw.minZ, 0.0, 0.0, -1.0);
        // corners
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.minY, toDraw.minZ, toDraw.minX, toDraw.maxY, toDraw.minZ, 0.0, 1.0, 0.0);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.minY, toDraw.minZ, toDraw.maxX, toDraw.maxY, toDraw.minZ, 0.0, 1.0, 0.0);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.minY, toDraw.maxZ, toDraw.maxX, toDraw.maxY, toDraw.maxZ, 0.0, 1.0, 0.0);
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.minY, toDraw.maxZ, toDraw.minX, toDraw.maxY, toDraw.maxZ, 0.0, 1.0, 0.0);
    }

    static void emitAABB(BufferBuilder bufferBuilder, MatrixStack stack, Box aabb, double expand) {
        emitAABB(bufferBuilder, stack, aabb.expand(expand, expand, expand));
    }

    static void emitLine(BufferBuilder bufferBuilder, MatrixStack stack, Vec3d start, Vec3d end) {
        double vpX = renderManager.renderPosX();
        double vpY = renderManager.renderPosY();
        double vpZ = renderManager.renderPosZ();
        emitLine(bufferBuilder, stack, start.x - vpX, start.y - vpY, start.z - vpZ, end.x - vpX, end.y - vpY, end.z - vpZ);
    }

}
