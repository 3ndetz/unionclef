package adris.altoclef.commandsystem.args;

import adris.altoclef.commandsystem.exception.BadCommandSyntaxException;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.StringReader;
import adris.altoclef.commandsystem.exception.CommandNotFinishedException;

import java.util.Arrays;
import java.util.stream.Stream;

public class EnumArg<T extends Enum<T>> extends Arg<T>{

    private final Class<T> enumCl;

    public EnumArg(String name, Class<T> enumCl) {
        super(name);
        this.enumCl = enumCl;
    }

    public static <T extends Enum<T>> T parse(StringReader reader, Class<T> enumCl) throws CommandException {
        String value = reader.next();

        boolean begins = false;
        for (T enumConstant : enumCl.getEnumConstants()) {
            if (enumConstant.name().equals(value)) return enumConstant;

            if (enumConstant.name().startsWith(value)) begins = true;
        }

        String errorMsg = "Value '"+value+"' does not exist";
        if (begins) {
            throw new CommandNotFinishedException(errorMsg);
        } else {
            throw new BadCommandSyntaxException(errorMsg);
        }
    }


    @Override
    public Class<T> getType() {
        return enumCl;
    }


    @Override
    protected StringParser<T> getParser() {
        return reader -> parse(reader, enumCl);
    }

    @Override
    public Stream<String> getSuggestions(StringReader reader) {
        return suggestions(enumCl);
    }

    public static Stream<String> suggestions(Class<? extends Enum<?>> enumCl) {
        return Arrays.stream(enumCl.getEnumConstants()).map(Enum::name);
    }

    @Override
    public String getTypeName() {
        return "Enum of "+enumCl.getSimpleName();
    }

}
