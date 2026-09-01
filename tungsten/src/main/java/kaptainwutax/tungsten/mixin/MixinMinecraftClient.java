package kaptainwutax.tungsten.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import kaptainwutax.tungsten.TungstenModDataContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.world.ClientWorld;

@Mixin(MinecraftClient.class)
public class MixinMinecraftClient {

	@Shadow @Nullable public ClientWorld world;
	@Shadow @Nullable public GameRenderer gameRenderer;

	@Inject(at = @At("HEAD"), method = "tick")
	private void tick(CallbackInfo info) {
		if (gameRenderer != TungstenModDataContainer.gameRenderer) {
	        TungstenModDataContainer.gameRenderer = this.gameRenderer;
		}
		if (MinecraftClient.getInstance().player != TungstenModDataContainer.player) {
	        TungstenModDataContainer.player = MinecraftClient.getInstance().player;
		}
	}
	
}
