package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.construction.CoverWithSandTask;

public class CoverWithSandCommand extends Command {

    public CoverWithSandCommand() throws CommandException {
        super("coverwithsand", "Cover nether lava with sand");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.runUserTask(new CoverWithSandTask(), this::finish);
    }
}
