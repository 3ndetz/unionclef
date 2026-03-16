package adris.altoclef;

import adris.altoclef.util.agent.VoiceChatIntegration;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@ForgeVoicechatPlugin
public class AltoclefVoicechat implements VoicechatPlugin {

    @Nullable
    public static VoicechatClientApi CLIENT_API = null;
    @Nullable
    public static VoicechatApi voicechatApi;

    private ExecutorService executorService;

    public AltoclefVoicechat() {
        executorService = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r);
            thread.setName("AltoclefMicrophoneProcessThread");
            thread.setUncaughtExceptionHandler((t, e) ->
                System.err.println("[AltoclefVoicechat] Error in microphone process thread: " + e.getMessage())
            );
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public String getPluginId() {
        return "altoclef";
    }

    @Override
    public void initialize(VoicechatApi api) {
        voicechatApi = api;
        if (api instanceof VoicechatClientApi clientApi) {
            CLIENT_API = clientApi;
        }
        Debug.logMessage("Voicechat API initialized! AltoClef=" + (AltoClef.getInstance() != null));
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, this::onReceiveAudio);
    }

    private void onReceiveAudio(ClientReceiveSoundEvent event) {
        if (event == null) return;
        AltoClef mod = AltoClef.getInstance();
        if (mod == null) return;

        if (event instanceof ClientReceiveSoundEvent.EntitySound entitySound) {
            VoiceChatIntegration.onSound(mod, entitySound);
        }
    }
}
