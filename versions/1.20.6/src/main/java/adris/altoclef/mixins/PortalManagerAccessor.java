package adris.altoclef.mixins;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MinecraftClient.class)
public interface PortalManagerAccessor {


    /**
     * used in 1.21 and later to access portal manager (doesn't exist in earlier versions)
     */

}
