package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ClientTickEvent;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
//#if MC < 12111
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Changed this from player to client, I hope this doesn't break anything.
@Mixin(MinecraftClient.class)
public final class ClientTickMixin {

    @Shadow
    public ClientWorld world;

    @Inject(
            method = "tick",
            at = @At("HEAD")
    )
    private void clientTick(CallbackInfo ci) {
        EventBus.publish(new ClientTickEvent());
    }

    // --- shredder joinWorld world-event bridge (preprocessed for cross-version) ---

    @Inject(method = "joinWorld", at = @At("HEAD"))
    //#if MC < 12111
    private void shredderPreLoadWorld(ClientWorld world, DownloadingTerrainScreen.WorldEntryReason arg2, CallbackInfo ci) {
    //#else
    //$$ private void shredderPreLoadWorld(ClientWorld world, CallbackInfo ci) {
    //#endif
        fireShredderWorldEvent(world, EventState.PRE);
    }

    @Inject(method = "joinWorld", at = @At("RETURN"))
    //#if MC < 12111
    private void shredderPostLoadWorld(ClientWorld world, DownloadingTerrainScreen.WorldEntryReason arg2, CallbackInfo ci) {
    //#else
    //$$ private void shredderPostLoadWorld(ClientWorld world, CallbackInfo ci) {
    //#endif
        fireShredderWorldEvent(world, EventState.POST);
    }

    @Unique
    private void fireShredderWorldEvent(ClientWorld world, EventState state) {
        if (this.world == null && world == null) {
            return;
        }
        BaritoneAPI.getProvider().getPrimaryBaritone().getGameEventHandler().onWorldEvent(
                new WorldEvent(world, state)
        );
    }
}
