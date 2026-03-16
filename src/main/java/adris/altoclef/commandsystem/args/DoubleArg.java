package adris.altoclef.commandsystem.args;

import adris.altoclef.commandsystem.exception.BadCommandSyntaxException;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.StringReader;

import java.util.stream.Stream;

public class DoubleArg extends Arg<Double> {

    public DoubleArg(String name) {
        super(name);
    }

    public DoubleArg(String name, Double defaultValue) {
        super(name, defaultValue);
    }

    public DoubleArg(String name, Double defaultValue, boolean showDefault) {
        super(name, defaultValue, showDefault);
    }


    public static Double parse(StringReader parser) throws CommandException {
        String value = parser.next();
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new BadCommandSyntaxException("Failed to parse '"+ value + "' into a Double");
        }
    }

    @Override
    public Stream<String> getSuggestions(StringReader reader) {
        return Stream.empty();
    }

    @Override
    protected StringParser<Double> getParser() {
        return DoubleArg::parse;
    }

    @Override
    public String getTypeName() {
        return "Double";
    }

    @Override
    public Class<Double> getType() {
        return Double.class;
    }

}
