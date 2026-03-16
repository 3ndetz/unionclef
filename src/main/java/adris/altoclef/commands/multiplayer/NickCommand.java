package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.ui.MessagePriority;

public class NickCommand extends Command {
    public NickCommand() {
        super("nick", "Set new nickname (applies after rejoin)", new StringArg("name"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String name = parser.get(String.class);
        // AltoClef.changePlayerName is not available in this fork;
        // send the /nick command via the server instead
        mod.getMessageSender().enqueueChat("/nick " + name, MessagePriority.TIMELY);
        mod.log("Requested nick change to: " + name);
        finish();
    }
}
