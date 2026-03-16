package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.entity.SelfCareTask;

public class SelfCareCommand extends Command {
    public SelfCareCommand() {
        super("selfcare", "Care for self (get resources and gear up)");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        mod.runUserTask(new SelfCareTask(), this::finish);
    }
}
