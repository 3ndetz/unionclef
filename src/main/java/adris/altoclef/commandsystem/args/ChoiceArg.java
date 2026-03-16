package adris.altoclef.commandsystem.args;

import adris.altoclef.commandsystem.StringReader;
import adris.altoclef.commandsystem.exception.CommandNotFinishedException;

import java.util.List;
import java.util.stream.Stream;

/**
 * String argument with tab-completion from a fixed list of choices.
 * Accepts any string at execution time (not restricted to choices).
 */
public class ChoiceArg extends Arg<String> {

    private final List<String> choices;

    public ChoiceArg(String name, List<String> choices) {
        super(name);
        this.choices = choices;
    }

    public ChoiceArg(String name, String defaultValue, List<String> choices) {
        super(name, defaultValue);
        this.choices = choices;
    }

    @Override
    public Stream<String> getSuggestions(StringReader reader) {
        return choices.stream();
    }

    @Override
    protected StringParser<String> getParser() {
        return reader -> {
            String value = reader.next();
            if (value.isEmpty()) throw new CommandNotFinishedException("Choice cannot be empty");
            return value;
        };
    }

    @Override
    public String getTypeName() {
        return "Choice";
    }

    @Override
    public Class<String> getType() {
        return String.class;
    }
}
