package adris.altoclef.commandsystem.args;

import adris.altoclef.commandsystem.StringReader;
import adris.altoclef.commandsystem.exception.CommandNotFinishedException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.util.stream.Stream;

/**
 * String argument with tab-completion from the server player list.
 * Drop-in replacement for StringArg("playerName") in any command.
 */
public class PlayerArg extends Arg<String> {

    public PlayerArg(String name) {
        super(name);
    }

    public PlayerArg(String name, String defaultValue) {
        super(name, defaultValue);
    }

    public static Stream<String> listOnlinePlayers() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return Stream.empty();
        return mc.getNetworkHandler().getPlayerList().stream()
                .map(PlayerListEntry::getProfile)
                .map(p -> p.getName())
                .filter(name -> !name.isEmpty());
    }

    @Override
    public Stream<String> getSuggestions(StringReader reader) {
        return listOnlinePlayers();
    }

    @Override
    protected StringParser<String> getParser() {
        return reader -> {
            String value = reader.next();
            if (value.isEmpty()) throw new CommandNotFinishedException("Player name cannot be empty");
            return value;
        };
    }

    @Override
    public String getTypeName() {
        return "Player";
    }

    @Override
    public Class<String> getType() {
        return String.class;
    }
}
