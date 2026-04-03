package kaptainwutax.tungsten.mixin;

import java.util.ArrayList;
import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.commandsystem.Command;
import kaptainwutax.tungsten.commandsystem.CommandExecutor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientConnectionState;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class MixinClientPlayNetworkHandler extends ClientCommonNetworkHandler {
	
	@Shadow
    private ClientWorld world;

    @Shadow
    public abstract void sendChatMessage(String content);

    @Unique
    private boolean ignoreChatMessage;

    @Unique
    private boolean worldNotNull;
    
    protected MixinClientPlayNetworkHandler(MinecraftClient client, ClientConnection connection, ClientConnectionState connectionState) {
        super(client, connection, connectionState);
    }
    
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void onSendChatMessage(String message, CallbackInfo ci) {
        if (ignoreChatMessage) return;
		String prefix = TungstenMod.getCommandPrefix();
        if (message.startsWith(prefix)) {
            try {
            	if (message.contains("|")) {
//            		CommandExecutor.dispatch(message.split(";")[0].substring(prefix.length()));
                	Collection<Command> commands = new ArrayList<>(TungstenMod.getCommandExecutor().allCommands());
                	commands.removeIf((command) -> !message.contains(command.getName()));
                	TungstenMod.getCommandExecutor().executeRecursive(commands.toArray(new Command[commands.size()]), message.split("|"), 0, () -> {
                    }, ex -> Debug.logWarning(ex.getMessage()));
            	} else CommandExecutor.dispatch(message.substring(prefix.length()));
            } catch (CommandSyntaxException e) {
                Debug.logWarning(e.getMessage());
            } catch (IllegalArgumentException e) {
                Debug.logWarning(e.getMessage());
            }

            client.inGameHud.getChatHud().addToMessageHistory(message);
            ci.cancel();
        }
    }

    @Inject(method = "onEntityTrackerUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/util/thread/ThreadExecutor;)V",
        shift = At.Shift.AFTER), cancellable = false)
    public void onEntityTrackerUpdate(EntityTrackerUpdateS2CPacket packet, CallbackInfo ci) {
        // Previously cancelled tracker updates for our player during execution,
        // which could desync pose/metadata. Now let vanilla handle them normally.
    }

    @Inject(method = "onPlayerPositionLook", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/util/thread/ThreadExecutor;)V",
        shift = At.Shift.AFTER), cancellable = false)
    public void onPlayerPositionLook(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        if(TungstenModDataContainer.isExecutorRunning()) {
            // Server teleported us — stop executor and let vanilla handle the teleport.
            // Previously this ci.cancel()'d the packet, which caused a deadlock:
            // server waits for TeleportConfirmC2SPacket, client never sends it,
            // server ignores all subsequent movement packets → player stuck.
            Debug.logMessage("Server teleport received during execution — stopping executor");
            TungstenModDataContainer.EXECUTOR.stop = true;
            TungstenModDataContainer.PATHFINDER.stop.set(true);
        }
    }

}
