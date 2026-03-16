package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.construction.CoverWithBlocksTask;

public class CoverWithBlocksCommand extends Command {

    public CoverWithBlocksCommand() throws CommandException {
        super("coverwithblocks", "Cover nether lava with throwaway blocks");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.runUserTask(new CoverWithBlocksTask(), this::finish);
    }
}
