package adris.altoclef.commandsystem.args;

import adris.altoclef.commandsystem.exception.BadCommandSyntaxException;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.StringReader;

import java.util.stream.Stream;

public class FloatArg extends Arg<Float> {

    public FloatArg(String name) {
        super(name);
    }

    public FloatArg(String name, Float defaultValue) {
        super(name, defaultValue);
    }

    public FloatArg(String name, Float defaultValue, boolean showDefault) {
        super(name, defaultValue, showDefault);
    }


    public static Float parse(StringReader parser) throws CommandException {
        String value = parser.next();
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            throw new BadCommandSyntaxException("Failed to parse '"+ value + "' into a Float");
        }
    }

    @Override
    public Stream<String> getSuggestions(StringReader reader) {
        return Stream.empty();
    }


    @Override
    protected StringParser<Float> getParser() {
        return FloatArg::parse;
    }

    @Override
    public String getTypeName() {
        return "Float";
    }

    @Override
    public Class<Float> getType() {
        return Float.class;
    }
}
