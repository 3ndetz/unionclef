package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Playground;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.StringReader;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.exception.CommandNotFinishedException;
import adris.altoclef.commandsystem.args.Arg;

import java.util.stream.Stream;

public class TestCommand extends Command {

    public TestCommand() {
        super("test", "Generic command for testing",
                new TestNameArg("extra", "")
        );
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        Playground.TEMP_TEST_FUNCTION(mod, parser.get(String.class));
        finish();
    }

    private static class TestNameArg extends Arg<String> {
        public TestNameArg(String name, String defaultValue) {
            super(name, defaultValue);
        }

        @Override
        public Stream<String> getSuggestions(StringReader reader) {
            String current = "";
            try {
                if (reader.hasNext()) current = reader.next();
            } catch (CommandException ignored) {}
            String prefix = current;
            return Playground.TEST_NAMES.stream()
                    .filter(name -> name.startsWith(prefix));
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
        public String getTypeName() { return "String"; }

        @Override
        public Class<String> getType() { return String.class; }
    }
}
