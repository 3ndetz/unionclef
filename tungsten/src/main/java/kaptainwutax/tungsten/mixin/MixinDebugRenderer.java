	package kaptainwutax.tungsten.mixin;

	import java.util.ArrayList;
	import java.util.Collection;
	import java.util.Collections;
	import java.util.Comparator;
	import java.util.List;

	import org.spongepowered.asm.mixin.Mixin;
	import org.spongepowered.asm.mixin.injection.At;
	import org.spongepowered.asm.mixin.injection.Inject;
	import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

	import com.mojang.blaze3d.systems.RenderSystem;
	// MC 1.21.1: VertexFormat is in net.minecraft.client.render, not blaze3d
import net.minecraft.client.render.VertexFormat.DrawMode;

	import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.render.Color;
	import kaptainwutax.tungsten.render.Cuboid;
	import kaptainwutax.tungsten.render.Renderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
	import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Tessellator;
	import net.minecraft.client.render.VertexConsumerProvider;
	import net.minecraft.client.render.VertexFormats;
	import net.minecraft.client.render.debug.DebugRenderer;
	import net.minecraft.client.util.math.MatrixStack;
	import net.minecraft.util.math.Box;
	import net.minecraft.util.math.Vec3d;
	import static org.lwjgl.opengl.GL11.*;

	@Mixin(DebugRenderer.class)
	public class MixinDebugRenderer {

		// Maximum number of renderers to draw at once to prevent performance issues
		private static final int MAX_RENDERERS_PER_CATEGORY = 500;

		@Inject(method = "render", at = @At("RETURN"))
		public void render(MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers,
				double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
			// MC 1.21: Frustum removed from render() parameters
			Frustum frustum = null;

			glDisable(GL_DEPTH_TEST);
		    glDisable(GL_BLEND);


			Tessellator tessellator = Tessellator.getInstance();
			BufferBuilder builder;

			RenderSystem.lineWidth(2.0F);

			builder = tessellator.begin(DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
			Cuboid goal = new Cuboid(TungstenMod.TARGET.subtract(0.5D, 0D, 0.5D), new Vec3d(1.0D, 2.0D, 1.0D), Color.GREEN);
			goal.render(builder);
			RenderLayer.getDebugLineStrip(2).draw(builder.end());

			// Batch render each collection with culling and limiting
			if (!TungstenModRenderContainer.RUNNING_PATH_RENDERER.isEmpty())
				renderCollection(TungstenModRenderContainer.RUNNING_PATH_RENDERER, tessellator, frustum, cameraX, cameraY, cameraZ);

			if (!TungstenModRenderContainer.BLOCK_PATH_RENDERER.isEmpty())
				renderCollection(TungstenModRenderContainer.BLOCK_PATH_RENDERER, tessellator, frustum, cameraX, cameraY, cameraZ);

			if (!TungstenModRenderContainer.RENDERERS.isEmpty())
				renderCollection(TungstenModRenderContainer.RENDERERS, tessellator, frustum, cameraX, cameraY, cameraZ);

			if (!TungstenModRenderContainer.TEST.isEmpty())
				renderCollection(TungstenModRenderContainer.TEST, tessellator, frustum, cameraX, cameraY, cameraZ);

			if (!TungstenModRenderContainer.ERROR.isEmpty())
				renderCollection(TungstenModRenderContainer.ERROR, tessellator, frustum, cameraX, cameraY, cameraZ);

			// Combat viz: force no depth test after each draw call
			if (!TungstenModRenderContainer.COMBAT_TRAJECTORY.isEmpty())
				renderCollectionNoDepth(TungstenModRenderContainer.COMBAT_TRAJECTORY, tessellator);

		    glEnable(GL_BLEND);
		    glEnable(GL_DEPTH_TEST);
		}

		private static void renderCollection(Collection<Renderer> renderers, Tessellator tessellator, Frustum frustum,
				double cameraX, double cameraY, double cameraZ) {
			int count = 0;
			List<Renderer> sortedRenderers = new ArrayList<>(renderers);
			Collections.reverse(sortedRenderers);
			try {
				for (Renderer r : sortedRenderers) {
					if (count >= MAX_RENDERERS_PER_CATEGORY) {
						break;
					}

					try {
						if (frustum != null && r.getPos() != null) {
							if (!frustum.isVisible(new Box(r.getPos().getX() - 3, r.getPos().getY() - 3, r.getPos().getZ() - 3,
									r.getPos().getX() + 3, r.getPos().getY() + 3, r.getPos().getZ() + 3))) {
								continue;
							}
						}

						BufferBuilder builder = tessellator.begin(DrawMode.DEBUG_LINES,
								VertexFormats.POSITION_COLOR);
						r.render(builder);
						RenderLayer.getDebugLineStrip(2).draw(builder.end());
						count++;
					} catch (Exception e) {
						TungstenMod.LOG.debug("Error rendering object: " + e.getMessage());
					}
				}
			} catch (Exception e) {
				TungstenMod.LOG.debug("Error rendering object: " + e.getMessage());
			}
		}

		/** Renders with depth test disabled — visible through blocks. */
		private static void renderCollectionNoDepth(Collection<Renderer> renderers, Tessellator tessellator) {
			int count = 0;
			List<Renderer> sorted = new ArrayList<>(renderers);
			Collections.reverse(sorted);
			try {
				for (Renderer r : sorted) {
					if (count >= MAX_RENDERERS_PER_CATEGORY) break;
					try {
						glDisable(GL_DEPTH_TEST);
						RenderSystem.lineWidth(3.0F);
						BufferBuilder builder = tessellator.begin(DrawMode.DEBUG_LINES,
								VertexFormats.POSITION_COLOR);
						r.render(builder);
						RenderLayer.getDebugLineStrip(2).draw(builder.end());
						// RenderLayer re-enables depth test internally, disable again
						glDisable(GL_DEPTH_TEST);
						count++;
					} catch (Exception e) {
						TungstenMod.LOG.debug("Error rendering combat viz: " + e.getMessage());
					}
				}
			} catch (Exception e) {
				TungstenMod.LOG.debug("Error rendering combat viz: " + e.getMessage());
			}
			RenderSystem.lineWidth(2.0F);
		}

		private static void render(Renderer r, Tessellator tessellator) {
			try {
				BufferBuilder builder = tessellator.begin(DrawMode.DEBUG_LINES,
						VertexFormats.POSITION_COLOR);
				r.render(builder);
				RenderLayer.getDebugLineStrip(2).draw(builder.end());
			} catch (Exception e) {
				// Ignored
			}
		}

	}
