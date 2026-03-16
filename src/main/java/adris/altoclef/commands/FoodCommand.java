package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.args.IntArg;
import adris.altoclef.tasks.resources.CollectFoodTask;

public class FoodCommand extends Command {
    public FoodCommand() {
        super("food", "Collects a certain amount of food",
                new IntArg( "count")
        );
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        mod.runUserTask(new CollectFoodTask(parser.get(Integer.class)), this::finish);
    }
}