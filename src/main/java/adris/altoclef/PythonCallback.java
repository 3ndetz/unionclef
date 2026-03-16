package adris.altoclef;

import java.util.Map;

public interface PythonCallback {
    public Boolean isStarted();
    public String onChatMessage(String s);

    public Map<String,String> onVerifedChat(Map<String,String> s);
    public Map<String,String> onUpdateServerInfo(Map<String,String> s);
    public void onDeath(String s);
    public void onKill(String s);
    public void onAutoclefEvent(String t, String s);
    public void onDamage(float s);
    public void onDamageConfirmed(String damaged, String attacker, float amount);
    public void onCaptchaSolveRequest(byte[] image_bytes);
    public void onVoiceFeed(String s, byte[] audio);
    public String agentCommandRequest(String s);
}
