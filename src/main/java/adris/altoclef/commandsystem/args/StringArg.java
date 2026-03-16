package adris.altoclef.commandsystem.args;

import adris.altoclef.commandsystem.StringReader;
import adris.altoclef.commandsystem.exception.CommandNotFinishedException;

import java.util.stream.Stream;

public class StringArg extends Arg<String> {

    public StringArg(String name) {
        super(name);
    }

    public StringArg(String name, String defaultValue) {
        super(name, defaultValue);
    }

    public StringArg(String name, String defaultValue, boolean showDefault) {
        super(name, defaultValue, showDefault);
    }


    @Override
    public Stream<String> getSuggestions(StringReader reader) {
        return Stream.empty();
    }

    @Override
    protected StringParser<String> getParser() {
        return reader -> {
            String value = reader.next();
            if (value.isEmpty()) throw new CommandNotFinishedException("String cannot be empty");

            return value;
        };
    }

    @Override
    public String getTypeName() {
        return "String";
    }

    @Override
    public Class<String> getType() {
        return String.class;
    }

}
