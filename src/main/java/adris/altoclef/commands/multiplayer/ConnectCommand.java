package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;

public class ConnectCommand extends Command {
    public ConnectCommand() {
        super("connect", "Connect to server by <ip>[:port]", new StringArg("server", null));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String server = parser.get(String.class);
        if (server == null) {
            finish();
            return;
        }
        Debug.logMessage("!!! REQUESTED SERVER RECONNECT: from " + mod.getTaskRunner().gameMenuTaskChain.getServerIp() + " to " + server);
        mod.getTaskRunner().gameMenuTaskChain.connectToServer(server);
        finish();
    }
}
