package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHudListener;
import net.minecraft.network.MessageType;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;


@Mixin(ChatHudListener.class)
public final class ChatReadMixin {

    @Shadow @Final private MinecraftClient client;

    @Inject(
            method = "onChatMessage",
            at = @At("HEAD")
    )
    private void onChatMessage(MessageType messageType, Text message, UUID senderUuid, CallbackInfo ci) {
        String msg;
        String senderName;
        if (MinecraftClient.getInstance().isInSingleplayer() || client.getNetworkHandler() == null) {
            senderName = MinecraftClient.getInstance().player.getName().asString();
        } else {
            senderName = client.getNetworkHandler().getWorld().getPlayerByUuid(senderUuid).getName().asString();
        }

        if (message instanceof TranslatableText translatable) {
            msg = "";

            for (Object obj : translatable.getArgs()) {
                if (!(obj instanceof Text text) || obj instanceof TranslatableText) continue;
                String str = text.getString();
                if (str.equals(senderName)) continue;
                msg += str;
            }
        } else {
            msg = message.getString();
        }

        ChatMessageEvent evt = new ChatMessageEvent(msg,senderName,messageType);
        EventBus.publish(evt);
    }
}