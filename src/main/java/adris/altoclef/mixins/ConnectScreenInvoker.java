package adris.altoclef.mixins;

import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ConnectScreen.class)
public interface ConnectScreenInvoker {

    //#if MC <= 11605
    //$$ @Invoker("connect")
    //$$ void invokeConnect(String address, int port);
    //#endif
}
