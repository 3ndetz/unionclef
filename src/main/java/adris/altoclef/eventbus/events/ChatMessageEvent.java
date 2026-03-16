package adris.altoclef.eventbus.events;

import net.minecraft.network.message.MessageType;

/**
 * Whenever chat appears
 */
public class ChatMessageEvent {
    private final String message;
    private final String senderName;
    private final MessageType messageType;

    public ChatMessageEvent(String message, String senderName, MessageType messageType) {
        this.message = message;
        this.senderName = senderName;
        this.messageType = messageType;
    }
    public String messageContent() {
        return message;
    }

    public String senderName() {
        return senderName;
    }

    public MessageType messageType() {
        return messageType;
    }
}
