package adris.altoclef.commandsystem.args;

import adris.altoclef.commandsystem.exception.BadCommandSyntaxException;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.StringReader;
import adris.altoclef.commandsystem.exception.CommandNotFinishedException;

import java.util.stream.Stream;

public abstract class Arg<T> {


    public final boolean hasDefault;
    public final String name;
    public final T defaultValue;
    public final boolean showDefault;

    protected Arg(String name) {
        this.name = name;
        this.hasDefault = false;
        this.defaultValue = null;
        this.showDefault = false;
    }

    protected Arg(String name, T defaultValue) {
        this(name, defaultValue, true);
    }

    protected Arg(String name, T defaultValue, boolean showDefault) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.showDefault = showDefault;
        this.hasDefault = true;
    }



    public ParseResult consumeIfSupplied(StringReader reader) {
        StringReader copy = reader.copy();
        ParseResult result = getSupplied(copy, getParser());

        if (result == ParseResult.CONSUMED) {
            reader.set(copy);
        }

        return result;
    }

    public static <T> ParseResult getSupplied(StringReader parser, StringParser<T> parseFunction) {
        try {
            parseFunction.parse(parser);
        } catch (BadCommandSyntaxException ignored) {
            return ParseResult.BAD_SYNTAX;
        } catch (CommandNotFinishedException ignored) {
            return ParseResult.NOT_FINISHED;
        } catch (CommandException e) {
            throw new IllegalStateException("Unknown exception type when parsing "+e.getClass().getSimpleName(), e);
        }
        return ParseResult.CONSUMED;
    }

    public static <T> T parseIfSupplied(StringReader parser, StringParser<T> parseFunction) {
        try {
            StringReader copy = parser.copy();
            T parsedValue = parseFunction.parse(copy);

            parser.set(copy);
            return parsedValue;
        } catch (CommandException ignored) {
            return null;
        }
    }

    public String getHelpRepresentation() {
        if (hasDefault) {
            if (showDefault) {
                return "<" + name + "=" + defaultValue + ">";
            }
            return "<" + name + ">";
        }
        return "[" + name + "]";
    }

    public abstract Class<T> getType();

    public final T parseArg(StringReader parser) throws CommandException {
        return this.getParser().parse(parser);
    }

    public abstract Stream<String> getSuggestions(StringReader reader);

    protected abstract StringParser<T> getParser();

    public abstract String getTypeName();

    @FunctionalInterface
    public interface StringParser<T> {
        T parse(StringReader reader) throws CommandException;
    }

    public enum ParseResult {
        CONSUMED, NOT_FINISHED, BAD_SYNTAX
    }

}
