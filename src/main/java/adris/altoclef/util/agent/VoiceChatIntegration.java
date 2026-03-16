package adris.altoclef.util.agent;

import adris.altoclef.AltoClef;
import adris.altoclef.Py4jEntryPoint;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

import java.util.UUID;

public class VoiceChatIntegration {

    public static void onSound(AltoClef mod, ClientReceiveSoundEvent.EntitySound event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        UUID senderId = event.getId();

        // Resolve player name via Minecraft world — no internal voicechat APIs needed
        PlayerEntity player = mc.world.getPlayerByUuid(senderId);
        if (player == null) return;

        String playerName = player.getName().getString();

        short[] audio = event.getRawAudio();
        if (audio == null || audio.length == 0) return;

        // Convert short[] (16-bit PCM) to byte[] little-endian
        byte[] bytes = new byte[audio.length * 2];
        for (int i = 0; i < audio.length; i++) {
            bytes[i * 2]     = (byte) (audio[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((audio[i] >> 8) & 0xFF);
        }

        Py4jEntryPoint.last_talking_player = playerName;
        mod.getInfoSender().onVoiceFeed(playerName, bytes);
    }
}
