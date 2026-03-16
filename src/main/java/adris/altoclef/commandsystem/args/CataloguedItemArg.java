package adris.altoclef.commandsystem.args;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.commandsystem.StringReader;
import adris.altoclef.commandsystem.exception.BadCommandSyntaxException;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.exception.CommandNotFinishedException;

import java.util.stream.Stream;

public class CataloguedItemArg extends Arg<String> {

    public CataloguedItemArg(String name) {
        super(name);
    }

    public CataloguedItemArg(String name, String defaultValue) {
        super(name, defaultValue);
    }

    public CataloguedItemArg(String name, String defaultValue, boolean showDefault) {
        super(name, defaultValue, showDefault);
    }


    public static String parse(StringReader parser) throws CommandException {
        String value = parser.next();

        if (TaskCatalogue.taskExists(value)) return value;

        boolean begins = listSuggestions().anyMatch(s -> s.startsWith(value));

        String errorMsg = "No catalogued item named '" + value+"'";
        if (begins) {
            throw new CommandNotFinishedException(errorMsg);
        } else {
            throw new BadCommandSyntaxException(errorMsg);
        }
    }

    public static Stream<String> listSuggestions() {
        return TaskCatalogue.resourceNames().stream();
    }

    @Override
    public Stream<String> getSuggestions(StringReader reader) {
        return listSuggestions();
    }

    @Override
    protected StringParser<String> getParser() {
        return CataloguedItemArg::parse;
    }

    @Override
    public String getTypeName() {
        return "Item";
    }

    @Override
    public Class<String> getType() {
        return String.class;
    }

}
