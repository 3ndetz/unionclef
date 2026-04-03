	package kaptainwutax.tungsten.mixin;

	import java.util.ArrayList;
	import java.util.Collection;
	import java.util.Collections;
	import java.util.List;

	import org.spongepowered.asm.mixin.Mixin;
	import org.spongepowered.asm.mixin.injection.At;
	import org.spongepowered.asm.mixin.injection.Inject;
	import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//#if MC >= 12111
//$$ import com.mojang.blaze3d.vertex.VertexFormat.DrawMode;
//$$ import net.minecraft.client.render.DrawStyle;
//#else
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.VertexFormat.DrawMode;
import net.minecraft.client.render.RenderLayer;
//#endif

	import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.render.Color;
	import kaptainwutax.tungsten.render.Cuboid;
	import kaptainwutax.tungsten.render.Renderer;
import net.minecraft.client.render.BufferBuilder;
	import net.minecraft.client.render.Frustum;
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

		private static final int MAX_RENDERERS_PER_CATEGORY = 500;

		@Inject(method = "render", at = @At("RETURN"))
		//#if MC >= 12111
		//$$ public void render(Frustum frustum, double cameraX, double cameraY, double cameraZ, float tickProgress, CallbackInfo ci) {
		//#else
		public void render(MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers,
				double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
			Frustum frustum = null;
		//#endif

			glDisable(GL_DEPTH_TEST);
		    glDisable(GL_BLEND);

			Tessellator tessellator = Tessellator.getInstance();
			BufferBuilder builder;

			//#if MC >= 12111
			//$$ DrawStyle drawStyle = new DrawStyle(-1, 2.0F, 0);
			//#else
			RenderSystem.lineWidth(2.0F);
			//#endif

			builder = tessellator.begin(DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
			Cuboid goal = new Cuboid(TungstenMod.TARGET.subtract(0.5D, 0D, 0.5D), new Vec3d(1.0D, 2.0D, 1.0D), Color.GREEN);
			goal.render(builder);
			//#if MC < 12111
			RenderLayer.getDebugLineStrip(2).draw(builder.end());
			//#endif

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

			if (!TungstenModRenderContainer.COMBAT_TRAJECTORY.isEmpty())
				renderCollectionNoDepth(TungstenModRenderContainer.COMBAT_TRAJECTORY, tessellator, frustum, cameraX, cameraY, cameraZ);

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
					if (count >= MAX_RENDERERS_PER_CATEGORY) break;
					try {
						if (frustum != null && r.getPos() != null) {
							if (!frustum.isVisible(new Box(r.getPos().getX() - 3, r.getPos().getY() - 3, r.getPos().getZ() - 3,
									r.getPos().getX() + 3, r.getPos().getY() + 3, r.getPos().getZ() + 3))) {
								continue;
							}
						}
						BufferBuilder builder = tessellator.begin(DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
						r.render(builder);
						//#if MC < 12111
						RenderLayer.getDebugLineStrip(2).draw(builder.end());
						//#endif
						count++;
					} catch (Exception e) {
						TungstenMod.LOG.debug("Error rendering object: " + e.getMessage());
					}
				}
			} catch (Exception e) {
				TungstenMod.LOG.debug("Error rendering object: " + e.getMessage());
			}
		}

		private static void renderCollectionNoDepth(Collection<Renderer> renderers, Tessellator tessellator, Frustum frustum,
				double cameraX, double cameraY, double cameraZ) {
			int count = 0;
			List<Renderer> sorted = new ArrayList<>(renderers);
			Collections.reverse(sorted);
			try {
				for (Renderer r : sorted) {
					if (count >= MAX_RENDERERS_PER_CATEGORY) break;
					try {
						glDisable(GL_DEPTH_TEST);
						BufferBuilder builder = tessellator.begin(DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
						r.render(builder);
						//#if MC < 12111
						RenderLayer.getDebugLineStrip(2).draw(builder.end());
						//#endif
						glDisable(GL_DEPTH_TEST);
						count++;
					} catch (Exception e) {
						TungstenMod.LOG.debug("Error rendering combat viz: " + e.getMessage());
					}
				}
			} catch (Exception e) {
				TungstenMod.LOG.debug("Error rendering combat viz: " + e.getMessage());
			}
		}
	}
