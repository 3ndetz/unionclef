package adris.altoclef.util.agent;

import adris.altoclef.tasks.multiplayer.GestureTask.Gesture;

public class AgentState {

    public String name;
    public String focusPlayerName = "";
    public String emotionalState = "neutral";
    public String pythonPartState = "Disabled"; // TODO implement into enum

    public AgentState() {
        name = "NetTyan"; // AltoClef.getSelfName(); // need to update, because first name from IDE is like "Player42"
    }

    public Gesture getGesture() {
        return emotionToGesture(emotionalState);
    }

    public Gesture emotionToGesture(String emotion) {
        if (emotion == null) {
            return Gesture.Hey;
        }
        return switch (emotion) {
            // case "neutral" -> Gesture.Hey;  // dublicate of default for now
            case "happy" -> Gesture.Cheer;
            case "sad" -> Gesture.Sad;
            case "angry" -> Gesture.Fight;
            case "disgusted" -> Gesture.Disrespect;
            case "yandere" -> Gesture.Crazy;
            default -> Gesture.Hey;
        };
    }

    @Override
    public String toString() {
        return "AgentState{" +
                "name='" + name + '\'' +
                ", focusPlayerName='" + focusPlayerName + '\'' +
                ", emotionalState='" + emotionalState + '\'' +
                ", pythonPartState='" + pythonPartState + '\'' +
                '}';
    }
}
