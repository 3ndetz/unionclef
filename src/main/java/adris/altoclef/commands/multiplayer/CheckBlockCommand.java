package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.ItemTargetArg;
import adris.altoclef.commandsystem.args.ListArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.exception.RuntimeCommandException;

// TODO
public class CheckBlockCommand extends Command {
    public CheckBlockCommand() throws CommandException {
        super("check_block", "Checks if some block is present in render view", new ListArg<>(new ItemTargetArg("block"), "blocks"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        // check if blocks is here

        // track and scan the blocks and return one if found
        throw new RuntimeCommandException("Check block command not implemented yet =(");
    }
}
