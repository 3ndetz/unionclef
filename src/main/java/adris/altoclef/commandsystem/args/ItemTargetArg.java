package adris.altoclef.commandsystem.args;

import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.StringReader;
import adris.altoclef.util.ItemTarget;

import java.util.stream.Stream;

public class ItemTargetArg extends Arg<ItemTarget> {

    public ItemTargetArg(String name) {
        super(name);
    }

    public ItemTargetArg(String name, ItemTarget defaultValue) {
        super(name, defaultValue);
    }

    public ItemTargetArg(String name, ItemTarget defaultValue, boolean showDefault) {
        super(name, defaultValue, showDefault);
    }


    private static ItemTarget parse(StringReader reader) throws CommandException {
        String item = CataloguedItemArg.parse(reader);
        int count = 1;

        Integer parsed = Arg.parseIfSupplied(reader, IntArg::parse);
        if (parsed != null) {
            count = parsed;
        }

        return new ItemTarget(item, count);
    }

    @Override
    public Class<ItemTarget> getType() {
        return ItemTarget.class;
    }

    @Override
    public Stream<String> getSuggestions(StringReader reader) {
        if (getSupplied(reader, CataloguedItemArg::parse) == ParseResult.NOT_FINISHED) {
            return CataloguedItemArg.listSuggestions();
        }
        return Stream.empty();
    }

    @Override
    public String getTypeName() {
        return "Item Target";
    }

    @Override
    protected StringParser<ItemTarget> getParser() {
        return ItemTargetArg::parse;
    }
}
