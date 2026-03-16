package adris.altoclef.multiversion;

import net.minecraft.network.message.MessageType;

public class MessageTypeVer {

    //#if MC >= 11904
    public static MessageType getMessageType(MessageType.Parameters parameters) {
    //#else
    //$$ public static MessageType getMessageType(Object obj) {
    //#endif

        //#if MC >= 12005
        return parameters.type().value();
        //#elseif MC >= 11904
        //$$ return parameters.type();
        //#else
        //$$ throw new IllegalStateException("Cannot get message type from params since they do not exist in this version!");
        //#endif
    }
}
