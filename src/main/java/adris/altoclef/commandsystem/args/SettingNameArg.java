package adris.altoclef.commandsystem.args;

import adris.altoclef.AltoClef;
import adris.altoclef.butler.ButlerConfig;
import adris.altoclef.commandsystem.StringReader;
import adris.altoclef.commandsystem.exception.CommandNotFinishedException;
import adris.altoclef.util.helpers.SettingsReflectionHelper;

import java.util.stream.Stream;

/**
 * String argument with tab-completion from all available settings.
 * Suggests setting names from both altoclef and butler configs.
 */
public class SettingNameArg extends Arg<String> {

    public SettingNameArg(String name) {
        super(name);
    }

    @Override
    public Stream<String> getSuggestions(StringReader reader) {
        Stream<String> mainSettings = SettingsReflectionHelper
                .getSettableFields(AltoClef.getInstance().getModSettings())
                .stream().map(s -> s.name);
        Stream<String> butlerSettings = SettingsReflectionHelper
                .getSettableFields(ButlerConfig.getInstance())
                .stream().map(s -> s.name);
        return Stream.concat(Stream.of("list"), Stream.concat(mainSettings, butlerSettings));
    }

    @Override
    protected StringParser<String> getParser() {
        return reader -> {
            String value = reader.next();
            if (value.isEmpty()) throw new CommandNotFinishedException("Setting name cannot be empty");
            return value;
        };
    }

    @Override
    public String getTypeName() {
        return "Setting";
    }

    @Override
    public Class<String> getType() {
        return String.class;
    }
}
